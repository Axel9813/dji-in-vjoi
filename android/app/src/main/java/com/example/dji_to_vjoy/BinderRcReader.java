package com.example.dji_to_vjoy;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import space.yasha.rcmonitor.RcMonitor;
import space.yasha.rcmonitor.RcReader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads RC data via the DJI protocol Binder service using pure reflection.
 *
 * All DJI framework classes (IProtocolManager, Pack, PackFilter, etc.) live in
 * the bootclasspath and are hidden APIs.  We access them exclusively through
 * reflection (after the meta-reflection hidden-API bypass) and raw
 * {@link android.os.Binder#transact} calls so there are no compile-time
 * dependencies on those classes.
 *
 * Requires {@code dji.permission.PROTOCOL} declared in the manifest.
 * Does NOT require root.
 */
public class BinderRcReader implements RcReader {
    private static final String TAG = "BinderRcReader";
    private static final String SERVICE_NAME = "protocol";
    private static final int RC_CMD_SET = 0x06;
    private static final int RC_CMD_ID  = 0x05;

    /* IProtocolManager transaction codes (from framework dexdump) */
    private static final int TX_IS_ENABLE           = 1;
    private static final int TX_ADD_PACK_LISTENER   = 2;
    private static final int TX_REMOVE_PACK_LISTENER = 5;

    /* IPackListener transaction codes */
    private static final int TX_ON_SUCCESS = 1;
    private static final int TX_ON_FAILURE = 2;

    /* AIDL interface descriptors */
    private static final String DESC_PROTOCOL_MGR  = "com.dji.protocol.IProtocolManager";
    private static final String DESC_PACK_LISTENER = "com.dji.protocol.IPackListener";

    private final Context context;
    private final RcMonitor monitor;

    private volatile boolean running;
    private IBinder protocolBinder;
    private IBinder listenerBinder;

    public BinderRcReader(Context context) {
        this.context = context;
        this.monitor = new RcMonitor();
    }

    /* ---- RcReader interface ------------------------------------------------ */

    @Override
    public String getName() {
        return "Binder";
    }

    @Override
    public boolean isAvailable() {
        try {
            IBinder binder = getServiceBinder(SERVICE_NAME);
            return binder != null;
        } catch (Throwable e) {
            Log.d(TAG, "isAvailable check failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean start(RcMonitor.RcStateListener listener) {
        if (running) return false;

        try {
            protocolBinder = getServiceBinder(SERVICE_NAME);
            if (protocolBinder == null) {
                Log.e(TAG, "Protocol service not found");
                return false;
            }

            /* Optional: check isEnable() — continue either way */
            boolean enabled = callIsEnable(protocolBinder);
            Log.d(TAG, "Protocol service enabled=" + enabled);

            if (!monitor.init(listener)) {
                Log.e(TAG, "Failed to init native parser");
                return false;
            }

            /* Build an IPackListener implemented purely via onTransact() */
            listenerBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data,
                                             Parcel reply, int flags)
                        throws RemoteException {
                    switch (code) {
                        case TX_ON_SUCCESS: {
                            data.enforceInterface(DESC_PACK_LISTENER);
                            handlePackFromParcel(data);
                            if (reply != null) reply.writeNoException();
                            return true;
                        }
                        case TX_ON_FAILURE: {
                            data.enforceInterface(DESC_PACK_LISTENER);
                            Log.w(TAG, "onFailure callback received");
                            if (reply != null) reply.writeNoException();
                            return true;
                        }
                        default:
                            return super.onTransact(code, data, reply, flags);
                    }
                }
            };

            /* Register for RC push: cmd_set=0x06, cmd_id=0x05 */
            callAddPackListener(protocolBinder, listenerBinder);

            running = true;
            Log.i(TAG, "Binder RC reader started — listening for RC push packets");
            return true;

        } catch (Throwable e) {
            Log.e(TAG, "Failed to start: " + e.getMessage(), e);
            cleanup();
            return false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (protocolBinder != null && listenerBinder != null) {
            try {
                callRemovePackListener(protocolBinder, listenerBinder);
                Log.d(TAG, "Pack listener removed");
            } catch (Throwable e) {
                Log.w(TAG, "Failed to remove listener: " + e.getMessage());
            }
        }
        cleanup();
        Log.d(TAG, "Binder RC reader stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /* ---- Internal helpers -------------------------------------------------- */

    private void cleanup() {
        monitor.destroy();
        protocolBinder = null;
        listenerBinder = null;
        running = false;
    }

    /**
     * Deserialise a {@code com.dji.protocol.Pack} from the parcel using
     * {@code Pack.CREATOR.createFromParcel()} via reflection, then extract
     * {@code cmdSet}, {@code cmdId} and {@code data}.
     */
    private void handlePackFromParcel(Parcel parcel) {
        try {
            /* Read the non-null marker that AIDL writes before a Parcelable */
            int nonNull = parcel.readInt();
            if (nonNull == 0) {
                Log.d(TAG, "Pack is null in parcel");
                return;
            }

            Class<?> packCls = Class.forName("com.dji.protocol.Pack");
            Field creatorField = packCls.getField("CREATOR");
            Object creator = creatorField.get(null);
            Method create = creator.getClass().getMethod("createFromParcel", Parcel.class);
            Object pack = create.invoke(creator, parcel);

            int cmdSet  = packCls.getField("cmdSet").getInt(pack);
            int cmdId   = packCls.getField("cmdId").getInt(pack);
            byte[] data = (byte[]) packCls.getField("data").get(pack);

            Log.d(TAG, String.format("Pack: cmdSet=0x%02X cmdId=0x%02X dataLen=%d",
                    cmdSet, cmdId, data != null ? data.length : 0));

            if (cmdSet == RC_CMD_SET && cmdId == RC_CMD_ID
                    && data != null && data.length >= 17) {
                monitor.feedDirect(data, data.length);
            } else if (data != null && data.length > 0) {
                monitor.feed(data, data.length);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse Pack: " + e.getMessage());
        }
    }

    /* ---- Raw Binder transact wrappers ------------------------------------- */

    /** IProtocolManager.isEnable() → transaction 1 */
    private boolean callIsEnable(IBinder binder) {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_PROTOCOL_MGR);
            binder.transact(TX_IS_ENABLE, d, r, 0);
            r.readException();
            return r.readInt() != 0;
        } catch (Throwable e) {
            Log.w(TAG, "isEnable() failed: " + e.getMessage());
            return true; // assume enabled
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /**
     * IProtocolManager.addPackListener(PackFilter, IPackListener) → transaction 2.
     *
     * We build a {@code PackFilter} containing one {@code PackRule} for
     * (0,0,0,0, 0x06, 0x05) using reflection, serialise it via
     * {@code writeToParcel()}, then append the listener binder.
     */
    private void callAddPackListener(IBinder binder, IBinder listener)
            throws Throwable {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_PROTOCOL_MGR);

            /* Construct PackFilter via reflection */
            Class<?> filterCls = Class.forName("com.dji.protocol.PackFilter");
            Class<?> ruleCls   = Class.forName("com.dji.protocol.PackRule");

            Object filter = filterCls.newInstance();

            Constructor<?> ruleCtor = ruleCls.getConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            Object rule = ruleCtor.newInstance(0, 0, 0, 0, RC_CMD_SET, RC_CMD_ID);

            Method addRule = filterCls.getMethod("addRule", ruleCls);
            addRule.invoke(filter, rule);

            /* Write as Parcelable (non-null marker + writeToParcel) */
            d.writeInt(1);
            Method writeToParcel = filterCls.getMethod("writeToParcel",
                    Parcel.class, int.class);
            writeToParcel.invoke(filter, d, 0);

            /* Write the listener binder */
            d.writeStrongBinder(listener);

            binder.transact(TX_ADD_PACK_LISTENER, d, r, 0);
            r.readException();
            Log.d(TAG, "addPackListener succeeded");
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** IProtocolManager.removePackListener(IPackListener) → transaction 5 */
    private void callRemovePackListener(IBinder binder, IBinder listener)
            throws RemoteException {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_PROTOCOL_MGR);
            d.writeStrongBinder(listener);
            binder.transact(TX_REMOVE_PACK_LISTENER, d, r, 0);
            r.readException();
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /* ---- ServiceManager access -------------------------------------------- */

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static IBinder getServiceBinder(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, name);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to get service '" + name + "': " + e.getMessage());
            return null;
        }
    }
}
