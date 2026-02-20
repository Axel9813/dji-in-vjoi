package com.example.dji_to_vjoy;

import android.content.Context;
import android.util.Log;
import android.view.InputDevice;

import space.yasha.rcmonitor.RcMonitor;
import space.yasha.rcmonitor.RcReader;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads RC data from the DJI embedded joystick via direct {@code /dev/input/event*}
 * access.  Handles both analog sticks AND buttons (unlike InputEventReader which
 * only reads sticks).
 *
 * The DJI RM510B exposes "DJI embedded joystick" at /dev/input/event8 with:
 * - 4 analog stick axes (ABS_X, ABS_Y, ABS_RX, ABS_RY: ±32768)
 * - 2 wheel/trigger axes (ABS_Z, ABS_RZ: 0-255, center 127)
 * - HAT switch (ABS_HAT0X, ABS_HAT0Y: -1..1)
 * - Buttons: BTN_GAMEPAD, BTN_EAST/NORTH/WEST, BTN_TL/TR, BTN_SELECT,
 *            BTN_START, BTN_THUMBL/THUMBR, BTN_DPAD_*, KEY_RECORD, etc.
 *
 * No root required on the RM510B (the app UID has read access).
 * Android's File.canRead() incorrectly returns false, so we skip that check.
 */
public class GamepadRcReader implements RcReader {
    private static final String TAG = "GamepadRcReader";
    private static final String DJI_JOYSTICK_NAME = "DJI embedded joystick";
    private static final String DEFAULT_DEVICE = "/dev/input/event8";

    /* struct input_event on arm64: 8+8+2+2+4 = 24 bytes */
    private static final int INPUT_EVENT_SIZE = 24;

    /* linux/input-event-codes.h */
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;

    /* ABS axis codes */
    private static final int ABS_X     = 0x00;
    private static final int ABS_Y     = 0x01;
    private static final int ABS_Z     = 0x02;
    private static final int ABS_RX    = 0x03;
    private static final int ABS_RY    = 0x04;
    private static final int ABS_RZ    = 0x05;
    private static final int ABS_HAT0X = 0x10;
    private static final int ABS_HAT0Y = 0x11;

    /* KEY/BTN codes */
    private static final int KEY_RECORD       = 0xA7;
    private static final int KEY_HOMEPAGE      = 0xAC;
    private static final int BTN_SOUTH         = 0x130; // BTN_GAMEPAD / BTN_A
    private static final int BTN_EAST          = 0x131; // BTN_B
    private static final int BTN_NORTH         = 0x133; // BTN_X
    private static final int BTN_WEST          = 0x134; // BTN_Y
    private static final int BTN_TL            = 0x136;
    private static final int BTN_TR            = 0x137;
    private static final int BTN_SELECT        = 0x13A;
    private static final int BTN_START         = 0x13B;
    private static final int BTN_THUMBL        = 0x13D;
    private static final int BTN_THUMBR        = 0x13E;
    private static final int BTN_DPAD_UP       = 0x220;
    private static final int BTN_DPAD_DOWN     = 0x221;
    private static final int BTN_DPAD_LEFT     = 0x222;
    private static final int BTN_DPAD_RIGHT    = 0x223;

    /* Scale: raw ±32768 → rc-monitor ±660 */
    private static final double STICK_SCALE = 660.0 / 32768.0;
    /* Scale: raw ±127 (centered 0-255) → rc-monitor ±660 */
    private static final double WHEEL_SCALE = 660.0 / 127.0;
    private static final int CENTER = 0x400; // 1024

    private final Context context;
    private final RcMonitor monitor;
    private final String devicePath;

    private volatile boolean running;
    private Thread readThread;
    private volatile InputStream activeStream;
    private volatile Process helperProcess;

    /* Stick/wheel state */
    private int stickLeftH, stickLeftV;
    private int stickRightH, stickRightV;
    private int leftWheel, rightWheel;

    /* Button state */
    private boolean pause, goHome, shutter, record;
    private boolean custom1, custom2, custom3;
    private boolean fiveDUp, fiveDDown, fiveDLeft, fiveDRight, fiveDCenter;
    private int flightMode = 1; // Normal

    public GamepadRcReader(Context context) {
        this(context, DEFAULT_DEVICE);
    }

    public GamepadRcReader(Context context, String devicePath) {
        this.context = context;
        this.devicePath = devicePath;
        this.monitor = new RcMonitor();
    }

    @Override
    public String getName() {
        return "Gamepad";
    }

    @Override
    public boolean isAvailable() {
        /* Check InputDevice API first — confirms the DJI joystick exists */
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && DJI_JOYSTICK_NAME.equals(dev.getName())) {
                return true;
            }
        }
        /* Fallback: just check the device node exists (skip canRead — it lies) */
        return new java.io.File(devicePath).exists();
    }

    @Override
    public boolean start(RcMonitor.RcStateListener listener) {
        if (running) return false;

        if (!monitor.init(listener)) {
            Log.e(TAG, "Failed to init native parser");
            return false;
        }

        running = true;
        readThread = new Thread(() -> {
            InputStream is = null;
            try {
                is = openInputDevice();
                if (is == null) {
                    Log.e(TAG, "Could not open input device");
                    running = false;
                    return;
                }
                activeStream = is;
                Log.d(TAG, "Input read loop started: " + devicePath);

                byte[] buf = new byte[INPUT_EVENT_SIZE];
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

                while (running) {
                    int total = 0;
                    while (total < INPUT_EVENT_SIZE) {
                        int n = is.read(buf, total, INPUT_EVENT_SIZE - total);
                        if (n < 0) {
                            Log.w(TAG, "Device EOF");
                            running = false;
                            break;
                        }
                        total += n;
                    }
                    if (!running) break;

                    bb.rewind();
                    bb.getLong(); /* tv_sec */
                    bb.getLong(); /* tv_usec */
                    int type  = bb.getShort() & 0xFFFF;
                    int code  = bb.getShort() & 0xFFFF;
                    int value = bb.getInt();

                    if (type == EV_ABS) {
                        handleAxis(code, value);
                    } else if (type == EV_KEY) {
                        handleKey(code, value != 0);
                    } else if (type == EV_SYN) {
                        emitState();
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Read error: " + e.getMessage());
                }
            } finally {
                if (is != null) try { is.close(); } catch (Exception ignored) {}
                Process hp = helperProcess;
                if (hp != null) { hp.destroy(); helperProcess = null; }
                activeStream = null;
                monitor.destroy();
                running = false;
                Log.d(TAG, "Input read loop stopped");
            }
        }, "rc-gamepad-reader");
        readThread.setDaemon(true);
        readThread.start();

        /* Give the thread a moment to confirm the device is readable */
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        if (!running) {
            Log.e(TAG, "Reader thread exited immediately — device not readable");
            return false;
        }

        Log.i(TAG, "Gamepad RC reader started on " + devicePath);
        return true;
    }

    /**
     * Try to open /dev/input/event8:
     * 1. Direct FileInputStream (works if we have input group)
     * 2. Via run-as helper (run-as grants supplementary groups including input)
     */
    private InputStream openInputDevice() {
        /* Attempt 1: direct open */
        try {
            FileInputStream fis = new FileInputStream(devicePath);
            Log.d(TAG, "Opened " + devicePath + " directly");
            return fis;
        } catch (Exception e) {
            Log.d(TAG, "Direct open failed: " + e.getMessage());
        }

        /* Attempt 2: via run-as (which adds input group to supplementary groups) */
        try {
            String pkg = context.getPackageName();
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/run-as", pkg, "cat", devicePath);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            helperProcess = proc;
            Log.d(TAG, "Opened " + devicePath + " via run-as helper");
            return proc.getInputStream();
        } catch (Exception e) {
            Log.e(TAG, "run-as helper failed: " + e.getMessage());
        }

        return null;
    }

    @Override
    public void stop() {
        running = false;
        InputStream stream = activeStream;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
        Process hp = helperProcess;
        if (hp != null) {
            hp.destroy();
            helperProcess = null;
        }
        if (readThread != null) {
            try { readThread.join(2000); } catch (InterruptedException ignored) {}
            readThread = null;
        }
        Log.d(TAG, "Gamepad RC reader stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handleAxis(int code, int value) {
        switch (code) {
            case ABS_X:     stickLeftH  = (int)(value * STICK_SCALE); break;
            case ABS_Y:     stickLeftV  = (int)(value * STICK_SCALE); break;
            case ABS_RX:    stickRightH = (int)(value * STICK_SCALE); break;
            case ABS_RY:    stickRightV = (int)(value * STICK_SCALE); break;
            case ABS_Z:     leftWheel   = (int)((value - 127) * WHEEL_SCALE); break;
            case ABS_RZ:    rightWheel  = (int)((value - 127) * WHEEL_SCALE); break;
            case ABS_HAT0X:
                fiveDLeft  = (value < 0);
                fiveDRight = (value > 0);
                break;
            case ABS_HAT0Y:
                fiveDUp   = (value < 0);
                fiveDDown = (value > 0);
                break;
        }
    }

    private void handleKey(int code, boolean pressed) {
        switch (code) {
            case BTN_START:      pause   = pressed; break;
            case KEY_HOMEPAGE:
            case BTN_SELECT:     goHome  = pressed; break;
            case BTN_SOUTH:      shutter = pressed; break;
            case KEY_RECORD:     record  = pressed; break;
            case BTN_TL:         shutter = pressed; break;  // L1 → shutter alt
            case BTN_TR:         record  = pressed; break;  // R1 → record alt
            case BTN_EAST:       custom1 = pressed; break;
            case BTN_NORTH:      custom2 = pressed; break;
            case BTN_WEST:       custom3 = pressed; break;
            case BTN_THUMBL:
            case BTN_THUMBR:     fiveDCenter = pressed; break;
            case BTN_DPAD_UP:    fiveDUp     = pressed; break;
            case BTN_DPAD_DOWN:  fiveDDown   = pressed; break;
            case BTN_DPAD_LEFT:  fiveDLeft   = pressed; break;
            case BTN_DPAD_RIGHT: fiveDRight  = pressed; break;
            default:
                Log.d(TAG, "Unmapped key: code=0x" + Integer.toHexString(code) +
                        " pressed=" + pressed);
                break;
        }
    }

    private void emitState() {
        byte[] payload = buildPayload();
        monitor.feedDirect(payload, payload.length);
    }

    /**
     * Build 17-byte RC push payload matching rcm_parse_payload format.
     */
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
}
