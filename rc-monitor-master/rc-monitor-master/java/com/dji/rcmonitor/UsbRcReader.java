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
 * Handles the full CDC ACM handshake sequence:
 * 1. Finds the Protocol interface (with both bulk IN and OUT endpoints)
 * 2. Configures CDC ACM line coding (115200 baud, 8N1) and control lines (DTR+RTS)
 * 3. Sends the DUML enable command to start push data streaming
 * 4. Reads incoming DUML frames and feeds them to the parser
 * 5. Falls back to polling with channel requests if no push data arrives
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

    /* CDC ACM control request types and codes */
    private static final int USB_RT_ACM = 0x21; /* host-to-device, class, interface */
    private static final int SET_LINE_CODING = 0x20;
    private static final int SET_CONTROL_LINE_STATE = 0x22;

    /* Timeout for push data before falling back to polling (ms) */
    private static final int PUSH_TIMEOUT_MS = 2000;
    /* Interval between poll requests when in polling mode (ms) */
    private static final int POLL_INTERVAL_MS = 50;

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
     * Checks for init-mode (PID 0x0040), active-mode (PID 0x1020),
     * and internal "pigeon" (PID 0x001F) PIDs.
     * Prefers the internal device if present.
     * @return UsbDevice or null if not found
     */
    public UsbDevice findDjiDevice() {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return null;

        UsbDevice fallback = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            Log.d(TAG, "USB device: " + device.getDeviceName() +
                  " VID=0x" + String.format("%04X", device.getVendorId()) +
                  " PID=0x" + String.format("%04X", device.getProductId()) +
                  " name=" + device.getProductName() +
                  " ifaces=" + device.getInterfaceCount());

            if (device.getVendorId() == RcMonitor.DJI_USB_VID) {
                int pid = device.getProductId();
                if (pid == RcMonitor.DJI_USB_PID_INTERNAL) {
                    Log.d(TAG, "Found internal DJI device (pigeon)");
                    return device; // prefer internal device
                }
                if (pid == RcMonitor.DJI_USB_PID_ACTIVE ||
                    pid == RcMonitor.DJI_USB_PID_INIT) {
                    fallback = device;
                }
            }
        }
        return fallback;
    }

    /**
     * Find a vendor-specific interface with both bulk IN and OUT endpoints.
     * For the internal "pigeon" device, prefers vendor-specific (0xFF) interfaces.
     * Falls back to any interface with both bulk directions.
     */
    private static UsbInterface findProtocolInterface(UsbDevice device) {
        UsbInterface fallback = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            boolean hasBulkIn = false;
            boolean hasBulkOut = false;

            StringBuilder epInfo = new StringBuilder();
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                String typeStr = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "BULK" :
                                 ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ? "INT" :
                                 ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC ? "ISOC" : "CTRL";
                String dirStr = ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT";
                epInfo.append(String.format(" ep_%02X(%s/%s)", ep.getAddress(), typeStr, dirStr));

                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) hasBulkIn = true;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) hasBulkOut = true;
                }
            }

            Log.d(TAG, String.format("  Interface %d: class=0x%02X sub=0x%02X proto=0x%02X eps=[%s] bulkIn=%b bulkOut=%b",
                i, candidate.getInterfaceClass(), candidate.getInterfaceSubclass(),
                candidate.getInterfaceProtocol(), epInfo.toString(), hasBulkIn, hasBulkOut));

            if (hasBulkIn && hasBulkOut) {
                // Prefer vendor-specific interfaces (class 0xFF)
                if (candidate.getInterfaceClass() == 0xFF) {
                    Log.d(TAG, "  -> Selected vendor-specific interface " + i);
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            }
        }

        if (fallback != null) {
            Log.d(TAG, "  -> Using fallback interface " + fallback.getId());
        }
        return fallback;
    }

    /**
     * Get the bulk endpoint in the given direction from an interface.
     */
    private static UsbEndpoint findBulkEndpoint(UsbInterface iface, int direction) {
        for (int j = 0; j < iface.getEndpointCount(); j++) {
            UsbEndpoint ep = iface.getEndpoint(j);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.getDirection() == direction) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Send CDC ACM SET_LINE_CODING control transfer.
     * Configures 115200 baud, 8 data bits, 1 stop bit, no parity.
     */
    private static boolean setLineCoding(UsbDeviceConnection conn, int interfaceNum) {
        /* Line coding structure (7 bytes):
         *   dwDTERate   (4 bytes LE) = 115200 = 0x0001C200
         *   bCharFormat (1 byte)     = 0 (1 stop bit)
         *   bParityType (1 byte)     = 0 (none)
         *   bDataBits   (1 byte)     = 8
         */
        byte[] lineCoding = {
            (byte) 0x00, (byte) 0xC2, (byte) 0x01, (byte) 0x00, /* 115200 LE */
            0x00,  /* 1 stop bit */
            0x00,  /* no parity */
            0x08   /* 8 data bits */
        };

        int ret = conn.controlTransfer(
            USB_RT_ACM, SET_LINE_CODING, 0, interfaceNum,
            lineCoding, lineCoding.length, 1000);
        return ret >= 0;
    }

    /**
     * Send CDC ACM SET_CONTROL_LINE_STATE to assert DTR and RTS.
     */
    private static boolean setControlLineState(UsbDeviceConnection conn, int interfaceNum) {
        /* wValue: bit 0 = DTR, bit 1 = RTS -> 0x0003 */
        int ret = conn.controlTransfer(
            USB_RT_ACM, SET_CONTROL_LINE_STATE, 0x0003, interfaceNum,
            null, 0, 1000);
        return ret >= 0;
    }

    /**
     * Start reading from the DJI USB device on a background thread.
     * Probes all interfaces with bulk endpoints to find one that yields data.
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
        if (usbManager == null) {
            Log.e(TAG, "UsbManager is null");
            return false;
        }
        boolean hasPerm = usbManager.hasPermission(device);
        Log.d(TAG, "USB permission for " + device.getDeviceName() + ": " + hasPerm);
        if (!hasPerm) {
            Log.e(TAG, "No USB permission for device");
            return false;
        }

        /* Log all interfaces */
        findProtocolInterface(device);

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        /* Probe all interfaces with bulk endpoints to find one that yields data.
         * Priority: interface that returns data > first vendor-specific claimable */
        UsbInterface dataIface = null;    // interface that actually returned data
        UsbEndpoint dataBulkIn = null;
        UsbEndpoint dataBulkOut = null;
        UsbInterface fallbackIface = null; // first claimable vendor-specific
        UsbEndpoint fallbackBulkIn = null;
        UsbEndpoint fallbackBulkOut = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            UsbEndpoint candidateIn = findBulkEndpoint(candidate, UsbConstants.USB_DIR_IN);
            UsbEndpoint candidateOut = findBulkEndpoint(candidate, UsbConstants.USB_DIR_OUT);

            if (candidateIn == null) continue; // need at least a bulk IN

            boolean claimed = conn.claimInterface(candidate, true);
            Log.d(TAG, "Interface " + i + " claim(force=true): " + claimed);

            if (claimed) {
                /* Try a quick read to see if this interface has data */
                byte[] probeBuf = new byte[BUFFER_SIZE];
                int probeN = conn.bulkTransfer(candidateIn, probeBuf, probeBuf.length, 200);
                Log.d(TAG, "Interface " + i + " probe read: " + probeN + " bytes");

                if (probeN > 0) {
                    StringBuilder hex = new StringBuilder();
                    int show = Math.min(probeN, 64);
                    for (int j = 0; j < show; j++) {
                        hex.append(String.format("%02X ", probeBuf[j] & 0xFF));
                    }
                    if (probeN > 64) hex.append("...");
                    Log.d(TAG, "Interface " + i + " probe data (" + probeN + "b): " + hex.toString());

                    /* Prefer vendor-specific interfaces (0xFF) with data over others */
                    boolean isVendor = candidate.getInterfaceClass() == 0xFF;
                    boolean dataIsVendor = dataIface != null && dataIface.getInterfaceClass() == 0xFF;

                    if (dataIface == null || (isVendor && !dataIsVendor)) {
                        /* Release previous data interface if we're replacing it */
                        if (dataIface != null) {
                            conn.releaseInterface(dataIface);
                        }
                        dataIface = candidate;
                        dataBulkIn = candidateIn;
                        dataBulkOut = candidateOut;
                    }
                }

                /* Track first claimable vendor-specific as fallback */
                if (fallbackIface == null && candidate.getInterfaceClass() == 0xFF) {
                    fallbackIface = candidate;
                    fallbackBulkIn = candidateIn;
                    fallbackBulkOut = candidateOut;
                }

                /* Release interfaces we won't use */
                if (dataIface != null && dataIface.getId() != candidate.getId()) {
                    conn.releaseInterface(candidate);
                }
            }
        }

        /* Choose: data-producing interface > fallback */
        UsbInterface bestIface = dataIface != null ? dataIface : fallbackIface;
        UsbEndpoint bestBulkIn = dataIface != null ? dataBulkIn : fallbackBulkIn;
        UsbEndpoint bestBulkOut = dataIface != null ? dataBulkOut : fallbackBulkOut;

        /* Release any extra claimed interfaces */
        if (dataIface != null && fallbackIface != null && dataIface.getId() != fallbackIface.getId()) {
            conn.releaseInterface(fallbackIface);
        }

        if (bestIface == null || bestBulkIn == null) {
            Log.e(TAG, "No usable interface found after probing all");
            conn.close();
            return false;
        }

        Log.d(TAG, "Using interface " + bestIface.getId() +
              " (class=0x" + String.format("%02X", bestIface.getInterfaceClass()) + ")" +
              " bulkOut=" + (bestBulkOut != null ? "ep_" + String.format("%02X", bestBulkOut.getAddress()) : "none"));

        UsbInterface iface = bestIface;
        UsbEndpoint bulkIn = bestBulkIn;
        UsbEndpoint bulkOut = bestBulkOut;

        /* Skip CDC ACM for vendor-specific interfaces */
        if (iface.getInterfaceClass() != 0xFF) {
            int ifaceNum = iface.getId();
            if (!setLineCoding(conn, ifaceNum)) {
                Log.w(TAG, "SET_LINE_CODING failed (may be non-fatal)");
            }
            if (!setControlLineState(conn, ifaceNum)) {
                Log.w(TAG, "SET_CONTROL_LINE_STATE failed (may be non-fatal)");
            }
        }

        if (!monitor.init(listener)) {
            Log.e(TAG, "Failed to init native parser");
            conn.releaseInterface(iface);
            conn.close();
            return false;
        }

        running = true;
        final UsbDeviceConnection fConn = conn;
        final UsbEndpoint fBulkIn = bulkIn;
        final UsbEndpoint fBulkOut = bulkOut;
        final UsbInterface fIface = iface;

        readThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            int seq = 1;
            long totalBytesRead = 0;
            long readCount = 0;
            Log.d(TAG, "USB read loop started on interface " + fIface.getId() +
                  " bulkIn=ep_" + String.format("%02X", fBulkIn.getAddress()) +
                  " bulkOut=ep_" + String.format("%02X", fBulkOut.getAddress()));

            /* Send enable command to start push data streaming */
            if (fBulkOut != null) {
                byte[] enableCmd = RcMonitor.buildEnableCommand(seq++);
                if (enableCmd != null) {
                    int sent = fConn.bulkTransfer(fBulkOut, enableCmd, enableCmd.length, 1000);
                    if (sent >= 0) {
                        Log.d(TAG, "Enable command sent (" + sent + " bytes)");
                    } else {
                        Log.w(TAG, "Failed to send enable command (ret=" + sent + "), will try reading anyway");
                    }
                }
            } else {
                Log.d(TAG, "No bulk OUT endpoint, skipping enable command");
            }

            boolean pushMode = true;
            long lastDataTime = System.currentTimeMillis();
            long lastPollTime = 0;
            boolean loggedFirstData = false;

            while (running) {
                int n = fConn.bulkTransfer(fBulkIn, buf, buf.length, READ_TIMEOUT_MS);
                if (n > 0) {
                    readCount++;
                    totalBytesRead += n;

                    /* Log first few reads so we can see what data arrives */
                    if (!loggedFirstData || readCount <= 5) {
                        StringBuilder hex = new StringBuilder();
                        int show = Math.min(n, 32);
                        for (int i = 0; i < show; i++) {
                            hex.append(String.format("%02X ", buf[i] & 0xFF));
                        }
                        if (n > 32) hex.append("...");
                        Log.d(TAG, "Read #" + readCount + ": " + n + " bytes: " + hex.toString());
                        loggedFirstData = true;
                    }

                    if (readCount % 100 == 0) {
                        Log.d(TAG, "Stats: " + readCount + " reads, " + totalBytesRead + " bytes total");
                    }

                    monitor.feed(buf, n);
                    lastDataTime = System.currentTimeMillis();
                    pushMode = true;
                } else {
                    /* No data received - check if we should switch to polling */
                    long now = System.currentTimeMillis();
                    if (now - lastDataTime > PUSH_TIMEOUT_MS) {
                        if (pushMode) {
                            Log.d(TAG, "No data for " + PUSH_TIMEOUT_MS + "ms, switching to poll mode" +
                                  " (total reads so far: " + readCount + ")");
                        }
                        pushMode = false;
                    }

                    if (!pushMode && fBulkOut != null && now - lastPollTime > POLL_INTERVAL_MS) {
                        /* Send channel request to poll for data */
                        byte[] pollCmd = RcMonitor.buildChannelRequest(seq++);
                        if (pollCmd != null) {
                            int sent = fConn.bulkTransfer(fBulkOut, pollCmd, pollCmd.length, 100);
                            if (sent >= 0 && readCount == 0 && seq <= 3) {
                                Log.d(TAG, "Poll cmd sent (" + sent + " bytes), seq=" + (seq-1));
                            }
                        }
                        lastPollTime = now;
                    }
                }
            }

            Log.d(TAG, "USB read loop stopped. Total: " + readCount + " reads, " + totalBytesRead + " bytes");
            monitor.destroy();
            fConn.releaseInterface(fIface);
            fConn.close();
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
