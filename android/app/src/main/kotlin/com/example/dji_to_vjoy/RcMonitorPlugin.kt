package com.example.dji_to_vjoy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import space.yasha.rcmonitor.DussStreamReader
import space.yasha.rcmonitor.RcMonitor
import space.yasha.rcmonitor.RcReaderChain
import space.yasha.rcmonitor.UsbRcReader
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Native Android bridge between the rc-monitor C library and Flutter.
 *
 * Provides:
 * - MethodChannel "com.dji.rc/control" for start/stop commands
 * - EventChannel  "com.dji.rc/state"   for streaming RC state to Dart
 *
 * All RC state changes are also logged to logcat with tag "RC_OUTPUT"
 * so they can be captured by the Python script via:
 *   adb logcat -s RC_OUTPUT:D
 */
class RcMonitorPlugin(
    private val context: Context
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "RcMonitorPlugin"
        private const val LOG_TAG = "RC_OUTPUT"  // Tag for Python/ADB capture
        private const val ACTION_USB_PERMISSION = "com.example.dji_to_vjoy.USB_PERMISSION"
    }

    private var readerChain: RcReaderChain? = null
    private var eventSink: EventChannel.EventSink? = null
    private var isRunning = false
    private var pendingResult: MethodChannel.Result? = null

    // Last state for change detection (avoid flooding logcat)
    private var lastLogLine: String = ""

    /** BroadcastReceiver for USB permission result */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted device=${device?.deviceName}")

                if (granted && device != null) {
                    // Permission granted — now start the reader
                    val started = doStartReader()
                    pendingResult?.let { result ->
                        if (started) {
                            result.success(mapOf("status" to "started"))
                        } else {
                            result.error("START_FAILED", "Failed to start USB RC reader after permission grant", null)
                        }
                        pendingResult = null
                    }
                } else {
                    Log.e(TAG, "USB permission denied by user")
                    pendingResult?.let { result ->
                        result.error("PERMISSION_DENIED", "USB permission denied", null)
                        pendingResult = null
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter)
    }

    /**
     * The RC state listener that bridges native callbacks to Flutter EventChannel
     * and also outputs to logcat for ADB/Python capture.
     */
    private val rcListener = object : RcMonitor.SimpleListener() {
        override fun onState(s: RcMonitor.RcState) {
            // Build a map for Flutter
            val stateMap = HashMap<String, Any>().apply {
                // Buttons
                put("pause", s.pause)
                put("gohome", s.gohome)
                put("shutter", s.shutter)
                put("record", s.record)
                put("custom1", s.custom1)
                put("custom2", s.custom2)
                put("custom3", s.custom3)

                // 5D joystick
                put("fiveDUp", s.fiveDUp)
                put("fiveDDown", s.fiveDDown)
                put("fiveDLeft", s.fiveDLeft)
                put("fiveDRight", s.fiveDRight)
                put("fiveDCenter", s.fiveDCenter)

                // Mode switch
                put("flightMode", s.flightMode)
                put("flightModeStr", s.flightModeString())

                // Sticks
                put("stickRightH", s.stickRightH)
                put("stickRightV", s.stickRightV)
                put("stickLeftH", s.stickLeftH)
                put("stickLeftV", s.stickLeftV)

                // Wheels
                put("leftWheel", s.leftWheel)
                put("rightWheel", s.rightWheel)
                put("rightWheelDelta", s.rightWheelDelta)
            }

            // Send to Flutter via EventChannel (must be on main thread)
            eventSink?.let { sink ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    sink.success(stateMap)
                }
            }

            // Output to logcat for Python/ADB capture
            // Format: key:value pairs separated by |
            val logLine = buildString {
                append("P:${b(s.pause)}")
                append("|GH:${b(s.gohome)}")
                append("|SH:${b(s.shutter)}")
                append("|REC:${b(s.record)}")
                append("|C1:${b(s.custom1)}")
                append("|C2:${b(s.custom2)}")
                append("|C3:${b(s.custom3)}")
                append("|5U:${b(s.fiveDUp)}")
                append("|5D:${b(s.fiveDDown)}")
                append("|5L:${b(s.fiveDLeft)}")
                append("|5R:${b(s.fiveDRight)}")
                append("|5C:${b(s.fiveDCenter)}")
                append("|FM:${s.flightMode}")
                append("|RH:${s.stickRightH}")
                append("|RV:${s.stickRightV}")
                append("|LH:${s.stickLeftH}")
                append("|LV:${s.stickLeftV}")
                append("|LW:${s.leftWheel}")
                append("|RW:${s.rightWheel}")
                append("|RWD:${s.rightWheelDelta}")
            }

            // Only log if state changed (reduces logcat spam)
            if (logLine != lastLogLine) {
                lastLogLine = logLine
                Log.d(LOG_TAG, logLine)
            }
        }

        private fun b(v: Boolean): Int = if (v) 1 else 0
    }

    // --- MethodChannel handler ---

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                if (isRunning) {
                    result.success(mapOf("status" to "already_running"))
                    return
                }
                startMonitoring(result)
            }
            "stop" -> {
                stopMonitoring()
                result.success(mapOf("status" to "stopped"))
            }
            "isRunning" -> {
                result.success(mapOf("running" to isRunning))
            }
            else -> result.notImplemented()
        }
    }

    // --- EventChannel handler ---

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        Log.d(TAG, "Flutter event listener attached")
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        Log.d(TAG, "Flutter event listener detached")
    }

    // --- RC monitoring lifecycle ---

    private fun startMonitoring(result: MethodChannel.Result) {
        Log.d(TAG, "Starting RC monitoring...")

        val reader = UsbRcReader(context)
        val device = reader.findDjiDevice()
        if (device == null) {
            Log.e(TAG, "No DJI USB device found. Is the RC connected?")
            result.error("NO_DEVICE", "No DJI USB device found", null)
            return
        }

        Log.d(TAG, "Found DJI device: ${device.deviceName} (VID=${device.vendorId}, PID=0x${device.productId.toString(16)})")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            result.error("NO_USB_MANAGER", "USB manager unavailable", null)
            return
        }

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission...")
            pendingResult = result
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            // Result will be delivered via usbPermissionReceiver
            return
        }

        // Already have permission — start immediately
        val started = doStartReader()
        if (started) {
            result.success(mapOf("status" to "started"))
        } else {
            result.error("START_FAILED", "Failed to start USB RC reader", null)
        }
    }

    private fun doStartReader(): Boolean {
        // Clean up any previous chain first
        readerChain?.let {
            Log.d(TAG, "Cleaning up previous reader chain before starting new one")
            it.stop()
        }
        readerChain = null
        isRunning = false

        val chain = RcReaderChain(
            DussStreamReader(context),
            UsbRcReader(context)
        )
        val active = chain.start(rcListener)
        if (active != null) {
            readerChain = chain
            isRunning = true
            Log.d(TAG, "RC monitoring started via ${active.name}")
        } else {
            Log.e(TAG, "No reader could start")
        }
        return active != null
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping RC monitoring...")
        readerChain?.stop()
        readerChain = null
        isRunning = false
        lastLogLine = ""
        Log.d(TAG, "RC monitoring stopped")
    }
}
