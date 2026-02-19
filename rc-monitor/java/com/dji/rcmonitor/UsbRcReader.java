package com.dji.rcmonitor;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * Reads raw USB bulk data from a DJI RM510 remote controller and feeds it
 * to RcMonitor for DUML parsing.
 *
 * Usage:
 * <pre>
 *   UsbRcReader reader = new UsbRcReader(context);
 *   reader.start(new RcMonitor.SimpleListener() {
 *       public void onState(RcMonitor.RcState state) {
 *           Log.d("RC", "Shutter: " + state.shutter +
 *                       " RightH: " + state.stickRightH);
 *       }
 *   });
 *   // ...
 *   reader.stop();
 * </pre>
 */
public class UsbRcReader {
    private static final String TAG = "UsbRcReader";
    private static final int READ_TIMEOUT_MS = 100;
    private static final int BUFFER_SIZE = 1024;

    private final Context context;
    private final RcMonitor monitor;

    private volatile boolean running;
    private Thread readThread;

    public UsbRcReader(Context context) {
        this.context = context;
        this.monitor = new RcMonitor();
    }

    /**
     * Find the first connected DJI USB device.
     * @return UsbDevice or null if not found
     */
    public UsbDevice findDjiDevice() {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return null;

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == RcMonitor.DJI_USB_VID) {
                return device;
            }
        }
        return null;
    }

    /**
     * Start reading from the DJI USB device on a background thread.
     * @param listener Callback for RC state updates (called on the read thread)
     * @return true if started successfully
     */
    public boolean start(RcMonitor.RcStateListener listener) {
        if (running) return false;

        UsbDevice device = findDjiDevice();
        if (device == null) {
            Log.e(TAG, "No DJI USB device found");
            return false;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || !usbManager.hasPermission(device)) {
            Log.e(TAG, "No USB permission for device");
            return false;
        }

        /* Find bulk IN endpoint */
        UsbInterface iface = null;
        UsbEndpoint bulkIn = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    iface = candidate;
                    bulkIn = ep;
                    break;
                }
            }
            if (bulkIn != null) break;
        }

        if (bulkIn == null) {
            Log.e(TAG, "No bulk IN endpoint found");
            return false;
        }

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        if (!conn.claimInterface(iface, true)) {
            Log.e(TAG, "Failed to claim interface");
            conn.close();
            return false;
        }

        if (!monitor.init(listener)) {
            Log.e(TAG, "Failed to init native parser");
            conn.releaseInterface(iface);
            conn.close();
            return false;
        }

        running = true;
        final UsbDeviceConnection fConn = conn;
        final UsbEndpoint fEndpoint = bulkIn;
        final UsbInterface fIface = iface;

        readThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            Log.d(TAG, "USB read loop started");

            while (running) {
                int n = fConn.bulkTransfer(fEndpoint, buf, buf.length, READ_TIMEOUT_MS);
                if (n > 0) {
                    monitor.feed(buf, n);
                }
            }

            monitor.destroy();
            fConn.releaseInterface(fIface);
            fConn.close();
            Log.d(TAG, "USB read loop stopped");
        }, "rc-usb-reader");
        readThread.setDaemon(true);
        readThread.start();

        return true;
    }

    /**
     * Stop reading and release USB resources.
     */
    public void stop() {
        running = false;
        if (readThread != null) {
            try {
                readThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
