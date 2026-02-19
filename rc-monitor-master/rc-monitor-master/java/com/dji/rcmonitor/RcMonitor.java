package com.dji.rcmonitor;

/**
 * DJI RM510 RC Monitor - reads raw USB data from DJI remote controller
 * and parses button/stick state from DUML protocol frames.
 *
 * Usage:
 * <pre>
 *   RcMonitor monitor = new RcMonitor();
 *   monitor.init(new RcMonitor.RcStateListener() {
 *       public void onRcState(
 *           boolean pause, boolean gohome, boolean shutter, boolean record,
 *           boolean custom1, boolean custom2, boolean custom3,
 *           boolean fiveDUp, boolean fiveDDown, boolean fiveDLeft,
 *           boolean fiveDRight, boolean fiveDCenter,
 *           int flightMode,
 *           int stickRightH, int stickRightV,
 *           int stickLeftH, int stickLeftV,
 *           int leftWheel, int rightWheel, int rightWheelDelta) {
 *           // Handle RC state
 *       }
 *   });
 *
 *   // In your USB read loop:
 *   byte[] buf = new byte[1024];
 *   int n = usbConnection.bulkTransfer(endpoint, buf, buf.length, 100);
 *   if (n > 0) monitor.feed(buf, n);
 *
 *   // Cleanup:
 *   monitor.destroy();
 * </pre>
 */
public class RcMonitor {

    static {
        System.loadLibrary("rc_monitor");
    }

    /** Callback interface for RC state updates. */
    public interface RcStateListener {
        /**
         * Called when a valid RC push packet is decoded.
         *
         * @param pause       Pause/stop button pressed
         * @param gohome      Return-to-home button pressed
         * @param shutter     Photo shutter button pressed
         * @param record      Video record button pressed
         * @param custom1     C1 custom button pressed
         * @param custom2     C2 custom button pressed
         * @param custom3     C3 custom button pressed
         * @param fiveDUp     5D joystick up
         * @param fiveDDown   5D joystick down
         * @param fiveDLeft   5D joystick left
         * @param fiveDRight  5D joystick right
         * @param fiveDCenter 5D joystick center press
         * @param flightMode  Flight mode switch (0=Sport, 1=Normal, 2=Tripod)
         * @param stickRightH Right stick horizontal (aileron), centered ~-660..+660
         * @param stickRightV Right stick vertical (elevator), centered ~-660..+660
         * @param stickLeftH  Left stick horizontal (rudder), centered ~-660..+660
         * @param stickLeftV  Left stick vertical (throttle), centered ~-660..+660
         * @param leftWheel   Left wheel/dial value
         * @param rightWheel  Right wheel/dial value
         * @param rightWheelDelta Right wheel incremental delta (signed)
         */
        void onRcState(
            boolean pause, boolean gohome, boolean shutter, boolean record,
            boolean custom1, boolean custom2, boolean custom3,
            boolean fiveDUp, boolean fiveDDown, boolean fiveDLeft,
            boolean fiveDRight, boolean fiveDCenter,
            int flightMode,
            int stickRightH, int stickRightV,
            int stickLeftH, int stickLeftV,
            int leftWheel, int rightWheel, int rightWheelDelta
        );
    }

    /** Convenience wrapper with named fields. */
    public static class RcState {
        public boolean pause, gohome, shutter, record;
        public boolean custom1, custom2, custom3;
        public boolean fiveDUp, fiveDDown, fiveDLeft, fiveDRight, fiveDCenter;
        public int flightMode;
        public int stickRightH, stickRightV;
        public int stickLeftH, stickLeftV;
        public int leftWheel, rightWheel, rightWheelDelta;

        public String flightModeString() {
            switch (flightMode) {
                case 0: return "Sport";
                case 1: return "Normal";
                case 2: return "Tripod";
                default: return "Unknown";
            }
        }
    }

    /** Listener adapter that delivers an RcState object. */
    public static abstract class SimpleListener implements RcStateListener {
        public abstract void onState(RcState state);

        @Override
        public void onRcState(
                boolean pause, boolean gohome, boolean shutter, boolean record,
                boolean custom1, boolean custom2, boolean custom3,
                boolean fiveDUp, boolean fiveDDown, boolean fiveDLeft,
                boolean fiveDRight, boolean fiveDCenter,
                int flightMode,
                int stickRightH, int stickRightV,
                int stickLeftH, int stickLeftV,
                int leftWheel, int rightWheel, int rightWheelDelta) {
            RcState s = new RcState();
            s.pause = pause;      s.gohome = gohome;
            s.shutter = shutter;  s.record = record;
            s.custom1 = custom1;  s.custom2 = custom2;  s.custom3 = custom3;
            s.fiveDUp = fiveDUp;  s.fiveDDown = fiveDDown;
            s.fiveDLeft = fiveDLeft; s.fiveDRight = fiveDRight;
            s.fiveDCenter = fiveDCenter;
            s.flightMode = flightMode;
            s.stickRightH = stickRightH; s.stickRightV = stickRightV;
            s.stickLeftH = stickLeftH;   s.stickLeftV = stickLeftV;
            s.leftWheel = leftWheel;     s.rightWheel = rightWheel;
            s.rightWheelDelta = rightWheelDelta;
            onState(s);
        }
    }

    private boolean initialized = false;

    /**
     * Initialize the native parser with a listener.
     * @return true on success
     */
    public boolean init(RcStateListener listener) {
        if (initialized) return false;
        initialized = nativeInit(listener);
        return initialized;
    }

    /**
     * Feed raw bytes from USB bulk transfer.
     * May invoke the listener callback zero or more times.
     * @param data Raw USB data
     * @param length Number of valid bytes in data
     * @return Number of RC packets decoded
     */
    public int feed(byte[] data, int length) {
        if (!initialized) return 0;
        return nativeFeed(data, length);
    }

    /**
     * Feed a raw 17-byte RC push payload directly (no DUML framing).
     * Use this if you extract the payload from the DJI SDK's push data callback.
     * @param payload Raw 17-byte payload
     * @param length Must be >= 17
     * @return 1 if decoded, 0 otherwise
     */
    public int feedDirect(byte[] payload, int length) {
        if (!initialized) return 0;
        return nativeFeedDirect(payload, length);
    }

    /**
     * Reset parser state. Call after USB disconnect/reconnect.
     */
    public void reset() {
        if (initialized) nativeReset();
    }

    /**
     * Release all native resources. Must be called when done.
     */
    public void destroy() {
        if (initialized) {
            nativeDestroy();
            initialized = false;
        }
    }

    /* --- Packet builders (static, no init required) --- */

    /**
     * Build the DUML enable/handshake command.
     * Send this to the RC via bulk OUT to start push data streaming.
     * @param seq Sequence number
     * @return Ready-to-send byte array, or null on error
     */
    public static byte[] buildEnableCommand(int seq) {
        return nativeBuildEnableCmd(seq);
    }

    /**
     * Build a channel data request command.
     * Send this to the RC via bulk OUT to poll for stick/button state.
     * @param seq Sequence number
     * @return Ready-to-send byte array, or null on error
     */
    public static byte[] buildChannelRequest(int seq) {
        return nativeBuildChannelRequest(seq);
    }

    /* --- Native methods --- */
    private native boolean nativeInit(RcStateListener listener);
    private native int nativeFeed(byte[] data, int length);
    private native int nativeFeedDirect(byte[] payload, int length);
    private native void nativeReset();
    private native void nativeDestroy();
    private static native byte[] nativeBuildEnableCmd(int seq);
    private static native byte[] nativeBuildChannelRequest(int seq);

    /* --- DJI USB constants --- */
    public static final int DJI_USB_VID = 0x2CA3;
    public static final int DJI_USB_PID_INIT = 0x0040;
    public static final int DJI_USB_PID_ACTIVE = 0x1020;
    /** Internal "pigeon" controller on RM510B — the RC hardware on the device itself */
    public static final int DJI_USB_PID_INTERNAL = 0x001F;
}
