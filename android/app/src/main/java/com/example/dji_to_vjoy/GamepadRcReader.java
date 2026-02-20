package com.example.dji_to_vjoy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import space.yasha.rcmonitor.RcMonitor;
import space.yasha.rcmonitor.RcReader;

/**
 * Reads RC data using up to three data sources (priority order):
 *
 * <h3>Source 1 (preferred): Raw USB reading of DJI Virtual Joystick</h3>
 * Opens the USB device (VID 0x2CA3, PID 0x1501) directly via
 * {@link UsbManager}, detaches the xpad kernel driver, and reads
 * raw interrupt IN packets. These packets contain ALL controls:
 * sticks, wheels, buttons (including C1, C2, C3, pause, go home,
 * shutter, record), and flight mode switch.
 *
 * <h3>Source 2 (fallback): /dev/input via Shizuku</h3>
 * If raw USB is not available (no permission, device not found),
 * falls back to reading {@code /dev/input/eventN} via Shizuku.
 * This provides sticks, wheels, HAT (5D joystick), but NOT
 * DJI-intercepted buttons like C1, C2, pause, etc.
 *
 * <h3>Source 3 (last resort): Activity event forwarding</h3>
 * Receives events from Activity's {@code dispatchKeyEvent}/
 * {@code dispatchGenericMotionEvent}. Very limited.
 */
public class GamepadRcReader implements RcReader {
    private static final String TAG = "GamepadRcReader";
    private static final String DJI_JOYSTICK_NAME = "DJI embedded joystick";
    private static final String DEFAULT_DEVICE = "/dev/input/event8";
    private static final int CENTER = 0x400; // 1024

    /* struct input_event on arm64: 8+8+2+2+4 = 24 bytes */
    private static final int INPUT_EVENT_SIZE = 24;

    /* linux/input-event-codes.h */
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;

    /* Absolute axis codes */
    private static final int ABS_X     = 0x00;
    private static final int ABS_Y     = 0x01;
    private static final int ABS_Z     = 0x02;
    private static final int ABS_RX    = 0x03;
    private static final int ABS_RY    = 0x04;
    private static final int ABS_RZ    = 0x05;
    private static final int ABS_HAT0X = 0x10;
    private static final int ABS_HAT0Y = 0x11;

    /* Linux BTN key codes for gamepad buttons */
    private static final int BTN_A      = 0x130; // 304 - BTN_SOUTH
    private static final int BTN_B      = 0x131; // 305 - BTN_EAST
    private static final int BTN_X      = 0x133; // 307 - BTN_NORTH
    private static final int BTN_Y      = 0x134; // 308 - BTN_WEST
    private static final int BTN_TL     = 0x136; // 310
    private static final int BTN_TR     = 0x137; // 311
    private static final int BTN_TL2    = 0x138; // 312
    private static final int BTN_TR2    = 0x139; // 313
    private static final int BTN_SELECT = 0x13a; // 314
    private static final int BTN_START  = 0x13b; // 315
    private static final int BTN_MODE   = 0x13c; // 316
    private static final int BTN_THUMBL = 0x13d; // 317
    private static final int BTN_THUMBR = 0x13e; // 318
    private static final int KEY_RECORD = 167;

    /* USB device IDs for DJI Virtual Joystick */
    private static final int DJI_VID = 0x2CA3;
    private static final int DJI_JOYSTICK_PID = 0x1501;
    private static final int USB_READ_TIMEOUT_MS = 100;
    private static final int RAW_USB_PACKET_SIZE = 64;

    /* Scale: ±32768 raw → ±660 rc-monitor range */
    private static final double STICK_SCALE = 660.0 / 32768.0;
    /* Wheels: 0-255 raw, center=127 → ±660 */
    private static final double WHEEL_SCALE = 660.0 / 127.0;

    /* DJI Protocol Binder constants */
    private static final String SERVICE_NAME = "protocol";
    private static final int RC_CMD_SET = 0x06;
    private static final int RC_CMD_ID  = 0x05;
    private static final int TX_ADD_PACK_LISTENER   = 2;
    private static final int TX_REMOVE_PACK_LISTENER = 5;
    private static final int TX_ON_SUCCESS = 1;
    private static final int TX_ON_FAILURE = 2;
    private static final String DESC_PROTOCOL_MGR  = "com.dji.protocol.IProtocolManager";
    private static final String DESC_PACK_LISTENER = "com.dji.protocol.IPackListener";

    private final Context context;
    private final RcMonitor monitor;

    private volatile boolean running;
    private boolean shizukuMode;
    private boolean rawUsbMode;
    private int djiDeviceId = -1;
    private Process shizukuProcess;
    private Thread inputThread;

    /* Raw USB reading */
    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint usbEndpointIn;
    private Thread usbThread;
    private int rawUsbPacketCount;
    private int lastBtnState = -1; // for transition logging
    private byte[] lastPacket = null; // for full-diff logging

    /* Binder protocol subscription for DJI-intercepted buttons */
    private IBinder protocolBinder;
    private IBinder listenerBinder;
    private boolean binderActive;

    /* Stick/wheel state (rc-monitor ±660 range) */
    private volatile int stickLeftH, stickLeftV;
    private volatile int stickRightH, stickRightV;
    private volatile int leftWheel, rightWheel;

    /* Button state */
    private volatile boolean pause, goHome, shutter, record;
    private volatile boolean custom1, custom2, custom3;
    private volatile boolean fiveDUp, fiveDDown, fiveDLeft, fiveDRight, fiveDCenter;
    private volatile int flightMode = 1; // Normal

    public GamepadRcReader(Context context) {
        this.context = context;
        this.monitor = new RcMonitor();
    }

    // ─── Static Shizuku helpers (called by RcMonitorPlugin) ───────────

    /** @return true if Shizuku service is running and reachable */
    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /** @return true if Shizuku permission is already granted */
    public static boolean isShizukuPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() { return "Gamepad"; }

    @Override
    public boolean isAvailable() {
        return findDjiJoystick() != -1 || findDjiJoystickUsb(context) != null;
    }

    @Override
    public boolean start(RcMonitor.RcStateListener listener) {
        if (running) return false;
        djiDeviceId = findDjiJoystick();  // may be -1 if xpad was detached
        if (djiDeviceId == -1 && findDjiJoystickUsb(context) == null) {
            Log.e(TAG, "DJI joystick not found (neither InputDevice nor USB)");
            return false;
        }
        if (djiDeviceId != -1) {
            InputDevice dev = InputDevice.getDevice(djiDeviceId);
            Log.i(TAG, "Found InputDevice: id=" + djiDeviceId + " name=" +
                    (dev != null ? dev.getName() : "?"));
        } else {
            Log.i(TAG, "InputDevice not available (xpad detached?), trying raw USB only");
        }
        if (!monitor.init(listener)) {
            Log.e(TAG, "Failed to init native parser");
            return false;
        }
        running = true;

        // Try raw USB first — gives us ALL controls including DJI-intercepted buttons
        if (tryStartRawUsb()) {
            rawUsbMode = true;
            shizukuMode = false;
            Log.i(TAG, "Gamepad RC reader started via RAW USB (full access incl. all buttons)");
            return true;
        }

        // Fall back to Shizuku /dev/input (sticks + wheels + HAT, limited buttons)
        if (tryStartShizuku()) {
            shizukuMode = true;
            rawUsbMode = false;
            Log.i(TAG, "Gamepad RC reader started via Shizuku (sticks/wheels only, limited buttons)");
        } else {
            shizukuMode = false;
            rawUsbMode = false;
            Log.w(TAG, "No Shizuku — buttons only via Activity forwarding.");
        }

        return true;
    }

    @Override
    public void stop() {
        running = false;
        stopBinderProtocol();
        stopRawUsb();
        if (shizukuProcess != null) {
            shizukuProcess.destroy();
            shizukuProcess = null;
        }
        if (inputThread != null) {
            try { inputThread.join(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inputThread = null;
        }
        monitor.destroy();
        djiDeviceId = -1;
        shizukuMode = false;
    }

    @Override
    public boolean isRunning() { return running; }

    /** @return true if Shizuku mode is active (full sticks + buttons) */
    public boolean isShizukuMode() { return shizukuMode; }

    /** @return true if raw USB mode is active (ALL controls) */
    public boolean isRawUsbMode() { return rawUsbMode; }

    // ─── Raw USB reading (preferred) ──────────────────────────────────

    /**
     * Find and open the DJI Virtual Joystick USB device, detach the xpad
     * kernel driver, and start reading raw interrupt IN packets.
     * @return true if raw USB reading started successfully
     */
    private boolean tryStartRawUsb() {
        try {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                Log.w(TAG, "UsbManager not available");
                return false;
            }

            UsbDevice joystickDevice = null;
            for (UsbDevice dev : usbManager.getDeviceList().values()) {
                if (dev.getVendorId() == DJI_VID && dev.getProductId() == DJI_JOYSTICK_PID) {
                    joystickDevice = dev;
                    break;
                }
            }
            if (joystickDevice == null) {
                Log.w(TAG, "DJI Virtual Joystick USB device not found in UsbManager");
                return false;
            }

            if (!usbManager.hasPermission(joystickDevice)) {
                Log.w(TAG, "No USB permission for DJI Virtual Joystick (VID:PID " +
                        String.format("%04X:%04X", DJI_VID, DJI_JOYSTICK_PID) + ")");
                return false;
            }

            UsbDeviceConnection conn = usbManager.openDevice(joystickDevice);
            if (conn == null) {
                Log.e(TAG, "Failed to open USB device");
                return false;
            }

            // The joystick has one interface (0) with interrupt IN and OUT endpoints
            if (joystickDevice.getInterfaceCount() < 1) {
                Log.e(TAG, "No USB interfaces on joystick device");
                conn.close();
                return false;
            }

            UsbInterface intf = joystickDevice.getInterface(0);
            Log.d(TAG, "USB interface: class=" + intf.getInterfaceClass() +
                    " subclass=" + intf.getInterfaceSubclass() +
                    " protocol=" + intf.getInterfaceProtocol() +
                    " endpoints=" + intf.getEndpointCount());

            // Force-claim detaches the xpad kernel driver
            if (!conn.claimInterface(intf, true)) {
                Log.e(TAG, "Failed to claim USB interface (force=true)");
                conn.close();
                return false;
            }
            Log.i(TAG, "Claimed USB interface (xpad driver detached)");

            // Find the interrupt IN endpoint
            UsbEndpoint epIn = null;
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint ep = intf.getEndpoint(i);
                Log.d(TAG, "  EP" + i + ": addr=0x" + Integer.toHexString(ep.getAddress()) +
                        " type=" + ep.getType() + " dir=" + ep.getDirection() +
                        " maxPkt=" + ep.getMaxPacketSize());
                if (ep.getDirection() == UsbConstants.USB_DIR_IN &&
                    (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ||
                     ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    epIn = ep;
                }
            }

            if (epIn == null) {
                Log.e(TAG, "No IN endpoint found on joystick interface");
                conn.releaseInterface(intf);
                conn.close();
                return false;
            }

            this.usbConnection = conn;
            this.usbInterface = intf;
            this.usbEndpointIn = epIn;
            this.rawUsbPacketCount = 0;

            // Run comprehensive USB probe before starting the read loop
            // This tries control transfers, DUML frames, XInput init, and sysfs
            try {
                UsbProbe.probeAll(conn, intf, epIn, joystickDevice);
            } catch (Exception e) {
                Log.e(TAG, "USB probe failed (non-fatal): " + e.getMessage(), e);
            }

            usbThread = new Thread(this::readRawUsbLoop, "rc-rawusb-reader");
            usbThread.setDaemon(true);
            usbThread.start();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Raw USB init failed: " + e.getMessage(), e);
            return false;
        }
    }

    private void readRawUsbLoop() {
        Log.d(TAG, "Raw USB read loop started (EP 0x" +
                Integer.toHexString(usbEndpointIn.getAddress()) +
                " maxPkt=" + usbEndpointIn.getMaxPacketSize() + ")");

        byte[] buf = new byte[RAW_USB_PACKET_SIZE];

        while (running) {
            int len = usbConnection.bulkTransfer(usbEndpointIn, buf, buf.length, USB_READ_TIMEOUT_MS);
            if (len < 0) continue; // timeout or error

            // Log ANY byte change (excluding bytes 2-3 seq counter and bytes 4-15 analog)
            // This detects button changes AND changes in bytes 0,1 (header) and 16,17 (buttons)
            boolean anyChange = false;
            if (lastPacket == null) {
                anyChange = true;
            } else if (len >= 18 && lastPacket.length >= 18) {
                // Check bytes 0,1 (header) and 16,17 (buttons) for changes
                if (buf[0] != lastPacket[0] || buf[1] != lastPacket[1] ||
                    buf[16] != lastPacket[16] || buf[17] != lastPacket[17]) {
                    anyChange = true;
                }
            }
            if (anyChange) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    hex.append(String.format("%02X ", buf[i] & 0xFF));
                }
                // Show which non-analog bytes changed
                StringBuilder diff = new StringBuilder("DIFF:");
                if (lastPacket != null && lastPacket.length >= len) {
                    for (int i = 0; i < len; i++) {
                        if (i >= 2 && i <= 15) continue; // skip seq + analog
                        if (buf[i] != lastPacket[i]) {
                            diff.append(String.format(" b%d:%02X->%02X", i, lastPacket[i] & 0xFF, buf[i] & 0xFF));
                        }
                    }
                }
                Log.w(TAG, "PKT_CHANGE[" + rawUsbPacketCount + "|" + diff + "]: " + hex.toString().trim());
                // Save copy
                lastPacket = new byte[len];
                System.arraycopy(buf, 0, lastPacket, 0, len);
            } else if (lastPacket != null && len >= 18) {
                // Also check for changes in ANY byte position beyond analog values
                // This catches bytes 0-1 and 16-17 which are already handled above
                // But ALSO detect any extended bytes or unexpected changes
                boolean extChange = false;
                if (lastPacket.length != len) extChange = true;
                if (!extChange) {
                    for (int i = 0; i < len; i++) {
                        if (i >= 2 && i <= 15) continue; // skip seq + analog
                        if (buf[i] != lastPacket[i]) { extChange = true; break; }
                    }
                }
                if (extChange) {
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < len; i++) hex.append(String.format("%02X ", buf[i] & 0xFF));
                    Log.w(TAG, "EXT_CHANGE[" + rawUsbPacketCount + "]: " + hex.toString().trim());
                    lastPacket = new byte[len];
                    System.arraycopy(buf, 0, lastPacket, 0, len);
                }
            }
            rawUsbPacketCount++;

            parseRawUsbPacket(buf, len);
        }
    }

    /**
     * Parse a raw USB packet from the DJI Virtual Joystick.
     * DJI 18-byte USB HID report format (empirically determined):
     *
     * Byte  0:    Report ID (always 0x02)
     * Byte  1:    Constant (always 0x0E)
     * Bytes 2-3:  Sequence counter (uint16 LE, incrementing)
     * Bytes 4-5:  Right stick horizontal (uint16 LE, center=0x0400)
     * Bytes 6-7:  Right stick vertical   (uint16 LE, center=0x0400)
     * Bytes 8-9:  Left stick vertical    (uint16 LE, center=0x0400)
     * Bytes 10-11: Left stick horizontal (uint16 LE, center=0x0400)
     * Bytes 12-13: Left wheel            (uint16 LE, center=0x0400)
     * Bytes 14-15: Right wheel           (uint16 LE, center=0x0400)
     * Bytes 16-17: Button flags          (uint16 LE bitfield)
     *
     * Button bitfield (CONFIRMED via user testing on RM510B):
     *   byte16 bit 2 (0x04) = Record button
     *   byte16 bit 3 (0x08) = Shutter (full press / 2nd stage)
     *   byte17 bit 0 (0x01) = 5D Up
     *   byte17 bit 1 (0x02) = 5D Down
     *   byte17 bit 2 (0x04) = 5D Left
     *   byte17 bit 3 (0x08) = 5D Right
     *   byte17 bit 4 (0x10) = 5D Center (press)
     *
     * NOT exposed via USB virtual joystick (DJI-intercepted):
     *   C1, C2, Pause, Go Home/RTH, Flight Mode, Shutter 1st stage
     */
    private void parseRawUsbPacket(byte[] buf, int len) {
        if (len < 18) return;

        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);

        // Six uint16 LE analog values centered at 0x0400 (1024)
        int rawRightH = bb.getShort(4) & 0xFFFF;
        int rawRightV = bb.getShort(6) & 0xFFFF;
        int rawLeftV  = bb.getShort(8) & 0xFFFF;
        int rawLeftH  = bb.getShort(10) & 0xFFFF;
        int rawLWheel = bb.getShort(12) & 0xFFFF;
        int rawRWheel = bb.getShort(14) & 0xFFFF;

        stickRightH = rawRightH - CENTER;
        stickRightV = rawRightV - CENTER;
        stickLeftV  = rawLeftV  - CENTER;
        stickLeftH  = rawLeftH  - CENTER;
        leftWheel   = rawLWheel - CENTER;
        rightWheel  = rawRWheel - CENTER;

        // Button flags (bytes 16-17)
        int b16 = buf[16] & 0xFF;
        int b17 = buf[17] & 0xFF;

        // byte 16 (CONFIRMED by user testing):
        shutter = (b16 & 0x08) != 0;  // bit 3 = Shutter full/2nd stage
        record  = (b16 & 0x04) != 0;  // bit 2 = Record button

        // byte 17: 5D joystick (CONFIRMED)
        fiveDUp     = (b17 & 0x01) != 0;  // bit 0
        fiveDDown   = (b17 & 0x02) != 0;  // bit 1
        fiveDLeft   = (b17 & 0x04) != 0;  // bit 2
        fiveDRight  = (b17 & 0x08) != 0;  // bit 3
        fiveDCenter = (b17 & 0x10) != 0;  // bit 4

        // NOT exposed via USB — DJI intercepts at proprietary layer:
        // C1, C2, pause, goHome, flightMode, shutter 1st stage
        // These remain at their default (false / 1=Normal)

        emitState();
    }

    /**
     * Parse DJI vendor-specific extension bytes beyond the standard
     * report. Called when packets are longer than 18 bytes.
     */
    private void parseRawUsbDjiExtension(byte[] buf, int len) {
        // Log extension bytes for first 100 packets
        if (rawUsbPacketCount <= 100 && len > 18) {
            StringBuilder ext = new StringBuilder();
            for (int i = 18; i < len; i++) {
                ext.append(String.format("%02X ", buf[i] & 0xFF));
            }
            Log.d(TAG, "DJI extension[" + (len - 18) + "B]: " + ext.toString().trim());
        }
    }

    private void stopRawUsb() {
        if (usbThread != null) {
            try { usbThread.join(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            usbThread = null;
        }
        if (usbConnection != null) {
            if (usbInterface != null) {
                usbConnection.releaseInterface(usbInterface);
            }
            usbConnection.close();
            usbConnection = null;
        }
        usbInterface = null;
        usbEndpointIn = null;
        rawUsbMode = false;
    }

    /**
     * Find the DJI Virtual Joystick USB device via UsbManager.
     * @return the UsbDevice, or null if not found
     */
    public static UsbDevice findDjiJoystickUsb(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return null;
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == DJI_VID && dev.getProductId() == DJI_JOYSTICK_PID) {
                return dev;
            }
        }
        return null;
    }

    // ─── Shizuku input reading (fallback) ─────────────────────────────

    private boolean tryStartShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku service is not running");
                return false;
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted");
                return false;
            }

            // Find the input device path dynamically
            String devicePath = findDevicePath();
            Log.i(TAG, "Opening input device via Shizuku: " + devicePath);

            // Shizuku.newProcess is private in v13 — use reflection
            Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            newProcess.setAccessible(true);

            shizukuProcess = (Process) newProcess.invoke(null,
                    new String[]{"cat", devicePath}, null, null);

            // Start reading thread
            inputThread = new Thread(this::readInputLoop, "rc-shizuku-reader");
            inputThread.setDaemon(true);
            inputThread.start();

            return true;
        } catch (Exception e) {
            Log.w(TAG, "Shizuku init failed: " + e.getMessage(), e);
            return false;
        }
    }

    private void readInputLoop() {
        Log.d(TAG, "Shizuku input read loop started");
        try (InputStream is = shizukuProcess.getInputStream()) {
            byte[] buf = new byte[INPUT_EVENT_SIZE];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

            while (running) {
                // Read exactly 24 bytes (one struct input_event)
                int total = 0;
                while (total < INPUT_EVENT_SIZE) {
                    int n = is.read(buf, total, INPUT_EVENT_SIZE - total);
                    if (n < 0) {
                        Log.w(TAG, "Shizuku process EOF");
                        running = false;
                        break;
                    }
                    total += n;
                }
                if (!running) break;

                bb.rewind();
                bb.getLong(); // tv_sec
                bb.getLong(); // tv_usec
                int type = bb.getShort() & 0xFFFF;
                int code = bb.getShort() & 0xFFFF;
                int value = bb.getInt();

                if (type == EV_ABS) {
                    handleAbsEvent(code, value);
                } else if (type == EV_KEY) {
                    handleRawKeyEvent(code, value);
                } else if (type == EV_SYN) {
                    emitState();
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "Shizuku read error: " + e.getMessage());
            }
        } finally {
            Log.d(TAG, "Shizuku input read loop stopped");
        }
    }

    private void handleAbsEvent(int code, int value) {
        switch (code) {
            case ABS_X:     stickLeftH  = (int)(value * STICK_SCALE); break;
            case ABS_Y:     stickLeftV  = (int)(value * STICK_SCALE); break;
            case ABS_RX:    stickRightH = (int)(value * STICK_SCALE); break;
            case ABS_RY:    stickRightV = (int)(value * STICK_SCALE); break;
            case ABS_Z:     leftWheel   = (int)((value - 127) * WHEEL_SCALE); break;
            case ABS_RZ:    rightWheel  = (int)((value - 127) * WHEEL_SCALE); break;
            case ABS_HAT0X:
                fiveDLeft  = value < 0;
                fiveDRight = value > 0;
                break;
            case ABS_HAT0Y:
                fiveDUp   = value < 0;
                fiveDDown = value > 0;
                break;
        }
    }

    private void handleRawKeyEvent(int code, int value) {
        boolean pressed = (value != 0); // 1=down, 0=up, 2=repeat → treat repeat as down
        switch (code) {
            case BTN_START:  pause   = pressed; break;
            case BTN_SELECT: goHome  = pressed; break;
            case BTN_MODE:   goHome  = pressed; break;
            case BTN_A:      shutter = pressed; break;
            case BTN_TL:     shutter = pressed; break;
            case BTN_TR:     record  = pressed; break;
            case KEY_RECORD: record  = pressed; break;
            case BTN_B:      custom1 = pressed; break;
            case BTN_X:      custom2 = pressed; break;
            case BTN_Y:      custom3 = pressed; break;
            case BTN_THUMBL:
            case BTN_THUMBR: fiveDCenter = pressed; break;
            default:
                Log.d(TAG, "Unmapped raw key: 0x" + Integer.toHexString(code) +
                        " (" + code + ") value=" + value);
                break;
        }
    }

    // ─── Activity forwarding fallback (buttons only) ──────────────────

    /**
     * Called by the Activity's dispatchGenericMotionEvent.
     * Only used in fallback mode (no Shizuku).
     */
    public boolean onMotionEvent(MotionEvent event) {
        if (!running || shizukuMode) return false;
        if (djiDeviceId != -1 && event.getDeviceId() != djiDeviceId) return false;

        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_RX);
        float ry = event.getAxisValue(MotionEvent.AXIS_RY);
        float lz = event.getAxisValue(MotionEvent.AXIS_Z);
        float rz = event.getAxisValue(MotionEvent.AXIS_RZ);
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        stickLeftH  = (int)(lx * 660);
        stickLeftV  = (int)(ly * 660);
        stickRightH = (int)(rx * 660);
        stickRightV = (int)(ry * 660);

        if (lz >= 0 && lz <= 1.0f && rz >= 0 && rz <= 1.0f) {
            leftWheel  = (int)((lz * 2 - 1) * 660);
            rightWheel = (int)((rz * 2 - 1) * 660);
        } else {
            leftWheel  = (int)(lz * 660);
            rightWheel = (int)(rz * 660);
        }

        fiveDLeft  = hatX < -0.5f;
        fiveDRight = hatX > 0.5f;
        fiveDUp    = hatY < -0.5f;
        fiveDDown  = hatY > 0.5f;

        emitState();
        return true;
    }

    /**
     * Called by the Activity's dispatchKeyEvent.
     * Used in both modes: fallback for buttons, and as backup if Shizuku misses keys.
     */
    public boolean onKeyEvent(KeyEvent event) {
        if (!running) return false;
        // In Shizuku mode, raw keys come from /dev/input — skip Activity keys to avoid doubles
        if (shizukuMode) return false;
        if (djiDeviceId != -1 && event.getDeviceId() != djiDeviceId) return false;

        boolean pressed = (event.getAction() == KeyEvent.ACTION_DOWN);

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_START:  pause   = pressed; break;
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BUTTON_SELECT: goHome  = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_A:      shutter = pressed; break;
            case KeyEvent.KEYCODE_MEDIA_RECORD:  record  = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_L1:     shutter = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_R1:     record  = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_B:      custom1 = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_X:      custom2 = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_Y:      custom3 = pressed; break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR: fiveDCenter = pressed; break;
            case KeyEvent.KEYCODE_DPAD_UP:       fiveDUp     = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:     fiveDDown   = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:     fiveDLeft   = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:    fiveDRight  = pressed; break;
            default:
                return false;
        }
        if (pressed) emitState();
        return true;
    }

    // ─── Payload emission ─────────────────────────────────────────────

    private void emitState() {
        byte[] payload = buildPayload();
        monitor.feedDirect(payload, payload.length);
    }

    private byte[] buildPayload() {
        byte[] p = new byte[17];

        int b0 = 0;
        if (pause)   b0 |= (1 << 4);
        if (goHome)  b0 |= (1 << 5);
        if (shutter) b0 |= (1 << 6);
        p[0] = (byte) b0;

        int b1 = 0;
        if (record)      b1 |= (1 << 0);
        if (fiveDRight)  b1 |= (1 << 3);
        if (fiveDUp)     b1 |= (1 << 4);
        if (fiveDDown)   b1 |= (1 << 5);
        if (fiveDLeft)   b1 |= (1 << 6);
        if (fiveDCenter) b1 |= (1 << 7);
        p[1] = (byte) b1;

        int b2 = flightMode & 0x03;
        if (custom1) b2 |= (1 << 2);
        if (custom2) b2 |= (1 << 3);
        if (custom3) b2 |= (1 << 4);
        p[2] = (byte) b2;

        putLE16(p, 5,  CENTER + stickRightH);
        putLE16(p, 7,  CENTER + stickRightV);
        putLE16(p, 9,  CENTER + stickLeftV);
        putLE16(p, 11, CENTER + stickLeftH);
        putLE16(p, 13, CENTER + leftWheel);
        putLE16(p, 15, CENTER + rightWheel);
        return p;
    }

    private static void putLE16(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    // ─── Device discovery ─────────────────────────────────────────────

    private int findDjiJoystick() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && DJI_JOYSTICK_NAME.equals(dev.getName())) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Find /dev/input/eventN path for the DJI joystick by parsing
     * /proc/bus/input/devices (world-readable, no special permissions).
     */
    private String findDevicePath() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line;
            boolean foundDji = false;
            while ((line = br.readLine()) != null) {
                if (line.contains(DJI_JOYSTICK_NAME)) {
                    foundDji = true;
                }
                if (foundDji && line.startsWith("H: Handlers=")) {
                    String handlers = line.substring("H: Handlers=".length());
                    for (String token : handlers.split("\\s+")) {
                        if (token.startsWith("event")) {
                            String path = "/dev/input/" + token;
                            Log.d(TAG, "Found device path: " + path);
                            return path;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse /proc/bus/input/devices: " + e.getMessage());
        }
        Log.w(TAG, "Falling back to default device path: " + DEFAULT_DEVICE);
        return DEFAULT_DEVICE;
    }

    // ─── DJI Protocol Binder subscription (intercepted buttons) ───────

    /**
     * Subscribe to the DJI protocol service for RC push packets (0x06, 0x05).
     * These carry the buttons DJI intercepts before they reach /dev/input:
     * C1, C2, C3, pause, go home, shutter, record, flight mode switch.
     *
     * The 17-byte payload is decoded inline to update only the button/mode
     * fields — stick/wheel data continues to come from /dev/input.
     */
    private void tryStartBinderProtocol() {
        try {
            IBinder rawBinder = getServiceBinder(SERVICE_NAME);
            if (rawBinder == null) {
                Log.w(TAG, "Protocol Binder service not found — DJI buttons unavailable");
                return;
            }

            // Wrap through Shizuku so transact() runs as shell (uid 2000)
            // which bypasses the dji.permission.PROTOCOL check in services.jar
            boolean useShizuku = false;
            try {
                if (Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    protocolBinder = new ShizukuBinderWrapper(rawBinder);
                    useShizuku = true;
                    Log.d(TAG, "Using ShizukuBinderWrapper for protocol service");
                }
            } catch (Exception e) {
                Log.w(TAG, "Shizuku wrapper failed, trying direct: " + e.getMessage());
            }
            if (!useShizuku) {
                protocolBinder = rawBinder;
                Log.d(TAG, "Using direct binder for protocol service (may need permission)");
            }

            listenerBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data,
                                             Parcel reply, int flags)
                        throws RemoteException {
                    if (code == TX_ON_SUCCESS) {
                        data.enforceInterface(DESC_PACK_LISTENER);
                        handleProtocolPack(data);
                        if (reply != null) reply.writeNoException();
                        return true;
                    } else if (code == TX_ON_FAILURE) {
                        data.enforceInterface(DESC_PACK_LISTENER);
                        Log.w(TAG, "Protocol onFailure callback");
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };

            callAddPackListener(protocolBinder, listenerBinder);
            binderActive = true;
            Log.i(TAG, "Protocol Binder subscribed for RC push (0x06,0x05) — DJI buttons active");
        } catch (Throwable e) {
            Log.w(TAG, "Protocol Binder subscription failed: " + e.getMessage());
            binderActive = false;
        }
    }

    private void stopBinderProtocol() {
        if (binderActive && protocolBinder != null && listenerBinder != null) {
            try {
                callRemovePackListener(protocolBinder, listenerBinder);
                Log.d(TAG, "Protocol Binder listener removed");
            } catch (Throwable e) {
                Log.w(TAG, "Failed to remove protocol listener: " + e.getMessage());
            }
        }
        protocolBinder = null;
        listenerBinder = null;
        binderActive = false;
    }

    /**
     * Decode a Pack from the Binder parcel and extract button/mode data
     * from the 17-byte RC push payload. Only updates button and mode fields —
     * sticks and wheels are owned by the /dev/input source.
     */
    private void handleProtocolPack(Parcel parcel) {
        try {
            int nonNull = parcel.readInt();
            if (nonNull == 0) return;

            Class<?> packCls = Class.forName("com.dji.protocol.Pack");
            Field creatorField = packCls.getField("CREATOR");
            Object creator = creatorField.get(null);
            Method create = creator.getClass().getMethod("createFromParcel", Parcel.class);
            Object pack = create.invoke(creator, parcel);

            int cmdSet  = packCls.getField("cmdSet").getInt(pack);
            int cmdId   = packCls.getField("cmdId").getInt(pack);
            byte[] data = (byte[]) packCls.getField("data").get(pack);

            if (cmdSet != RC_CMD_SET || cmdId != RC_CMD_ID || data == null || data.length < 17) {
                return;
            }

            // Decode button fields from 17-byte payload (see RC_MONITORING_SPEC.md)
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            int b2 = data[2] & 0xFF;

            pause       = (b0 & (1 << 4)) != 0;
            goHome      = (b0 & (1 << 5)) != 0;
            shutter     = (b0 & (1 << 6)) != 0;
            record      = (b1 & (1 << 0)) != 0;
            fiveDRight  = (b1 & (1 << 3)) != 0;
            fiveDUp     = (b1 & (1 << 4)) != 0;
            fiveDDown   = (b1 & (1 << 5)) != 0;
            fiveDLeft   = (b1 & (1 << 6)) != 0;
            fiveDCenter = (b1 & (1 << 7)) != 0;
            flightMode  = b2 & 0x03;
            custom1     = (b2 & (1 << 2)) != 0;
            custom2     = (b2 & (1 << 3)) != 0;
            custom3     = (b2 & (1 << 4)) != 0;

            Log.d(TAG, String.format("Binder RC push: P:%d GH:%d SH:%d REC:%d C1:%d C2:%d C3:%d FM:%d",
                    pause?1:0, goHome?1:0, shutter?1:0, record?1:0,
                    custom1?1:0, custom2?1:0, custom3?1:0, flightMode));

            emitState();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse protocol Pack: " + e.getMessage());
        }
    }

    /* ---- Raw Binder transact wrappers ------------------------------------ */

    private void callAddPackListener(IBinder binder, IBinder listener)
            throws Throwable {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_PROTOCOL_MGR);

            Class<?> filterCls = Class.forName("com.dji.protocol.PackFilter");
            Class<?> ruleCls   = Class.forName("com.dji.protocol.PackRule");
            Object filter = filterCls.newInstance();
            Constructor<?> ruleCtor = ruleCls.getConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            Object rule = ruleCtor.newInstance(0, 0, 0, 0, RC_CMD_SET, RC_CMD_ID);
            Method addRule = filterCls.getMethod("addRule", ruleCls);
            addRule.invoke(filter, rule);

            d.writeInt(1); // non-null marker
            Method writeToParcel = filterCls.getMethod("writeToParcel",
                    Parcel.class, int.class);
            writeToParcel.invoke(filter, d, 0);
            d.writeStrongBinder(listener);

            binder.transact(TX_ADD_PACK_LISTENER, d, r, 0);
            r.readException();
        } finally {
            d.recycle();
            r.recycle();
        }
    }

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
