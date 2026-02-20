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
import space.yasha.rcmonitor.InputEventReader
import space.yasha.rcmonitor.RcMonitor
import space.yasha.rcmonitor.RcReaderChain
import space.yasha.rcmonitor.UsbRcReader
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Native Android bridge between the rc-monitor library and Flutter.
 *
 * Uses RcReaderChain to try readers in priority order:
 *   1. DussStreamReader — USB Interface 7, no root, no handshake
 *   2. UsbRcReader — full CDC ACM + DUML handshake
 *   3. InputEventReader — /dev/input sticks only (no buttons)
 *
 * Provides:
 * - MethodChannel "com.dji.rc/control" for start/stop commands
 * - EventChannel  "com.dji.rc/state"   for streaming RC state to Dart
 */
class RcMonitorPlugin(
    private val context: Context
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "RcMonitorPlugin"
        private const val LOG_TAG = "RC_OUTPUT"
        private const val ACTION_USB_PERMISSION = "com.example.dji_to_vjoy.USB_PERMISSION"
    }

    private var readerChain: RcReaderChain? = null
    private var eventSink: EventChannel.EventSink? = null
    private var isRunning = false
    private var pendingResult: MethodChannel.Result? = null
    private var lastLogLine: String = ""

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted device=${device?.deviceName}")

                if (granted && device != null) {
                    val started = doStartReaderChain()
                    pendingResult?.let { result ->
                        if (started) {
                            result.success(mapOf("status" to "started"))
                        } else {
                            result.error("START_FAILED", "Failed to start RC reader after permission grant", null)
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
        // Bypass hidden API restrictions for accessing DJI's framework classes
        bypassHiddenApiRestrictions()
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            // "Meta-reflection" trick: obtain getDeclaredMethod *through* reflection
            // so the hidden-API check sees java.lang.reflect.Method as the caller
            // (boot classpath) rather than our app code.  Works on Android 10.
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")

            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", emptyArray<Class<*>>()
            ) as java.lang.reflect.Method

            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)
            ) as java.lang.reflect.Method

            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, arrayOf("L") as Any)
            Log.d(TAG, "Hidden API restrictions bypassed (meta-reflection)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bypass hidden API restrictions: ${e.message}")
        }
    }

    private val rcListener = object : RcMonitor.SimpleListener() {
        override fun onState(s: RcMonitor.RcState) {
            val stateMap = HashMap<String, Any>().apply {
                put("pause", s.pause)
                put("gohome", s.gohome)
                put("shutter", s.shutter)
                put("record", s.record)
                put("custom1", s.custom1)
                put("custom2", s.custom2)
                put("custom3", s.custom3)
                put("fiveDUp", s.fiveDUp)
                put("fiveDDown", s.fiveDDown)
                put("fiveDLeft", s.fiveDLeft)
                put("fiveDRight", s.fiveDRight)
                put("fiveDCenter", s.fiveDCenter)
                put("flightMode", s.flightMode)
                put("flightModeStr", s.flightModeString())
                put("stickRightH", s.stickRightH)
                put("stickRightV", s.stickRightV)
                put("stickLeftH", s.stickLeftH)
                put("stickLeftV", s.stickLeftV)
                put("leftWheel", s.leftWheel)
                put("rightWheel", s.rightWheel)
                put("rightWheelDelta", s.rightWheelDelta)
            }

            eventSink?.let { sink ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    sink.success(stateMap)
                }
            }

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

            if (logLine != lastLogLine) {
                lastLogLine = logLine
                Log.d(LOG_TAG, logLine)
            }
        }

        private fun b(v: Boolean): Int = if (v) 1 else 0
    }

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
            "status" -> {
                val statusList = readerChain?.status() ?: emptyList()
                result.success(mapOf(
                    "running" to isRunning,
                    "activeReader" to (readerChain?.active?.name ?: "none"),
                    "readers" to statusList
                ))
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        Log.d(TAG, "Flutter event listener attached")
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        Log.d(TAG, "Flutter event listener detached")
    }

    private fun startMonitoring(result: MethodChannel.Result) {
        Log.d(TAG, "Starting RC monitoring...")

        // Find a DJI USB device to check permission
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            result.error("NO_USB_MANAGER", "USB manager unavailable", null)
            return
        }

        val djiDevice = findDjiDevice(usbManager)
        if (djiDevice == null) {
            Log.w(TAG, "No DJI USB device found, will try non-USB readers")
            // Still try — InputEventReader doesn't need USB
            val started = doStartReaderChain()
            if (started) {
                result.success(mapOf("status" to "started"))
            } else {
                result.error("NO_DEVICE", "No DJI USB device and no other readers available", null)
            }
            return
        }

        Log.d(TAG, "Found DJI device: ${djiDevice.deviceName} PID=0x${djiDevice.productId.toString(16)}")

        if (!usbManager.hasPermission(djiDevice)) {
            Log.d(TAG, "Requesting USB permission — please tap Allow on the device")
            pendingResult = result
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(djiDevice, permissionIntent)
            return
        }

        val started = doStartReaderChain()
        if (started) {
            result.success(mapOf("status" to "started"))
        } else {
            result.error("START_FAILED", "Failed to start RC reader", null)
        }
    }

    private fun doStartReaderChain(): Boolean {
        readerChain?.stop()
        readerChain = null
        isRunning = false

        val chain = RcReaderChain(
            GamepadRcReader(context),    // Priority 1: /dev/input direct read (no root on RM510B)
            BinderRcReader(context),     // Priority 2: DJI protocol Binder IPC (needs platform sign)
            DussStreamReader(context),   // Priority 3: DUSS stream (no root, no handshake)
            UsbRcReader(context),        // Priority 4: Full USB CDC ACM + DUML
            InputEventReader(context)    // Priority 5: /dev/input sticks only (needs root)
        )

        val active = chain.start(rcListener)
        if (active != null) {
            readerChain = chain
            isRunning = true
            Log.i(TAG, "RC monitoring started via ${active.name} reader")
            return true
        }

        Log.e(TAG, "No reader could start. Chain status:")
        for (status in chain.status()) {
            Log.e(TAG, "  ${status["name"]}: available=${status["available"]}")
        }
        return false
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping RC monitoring...")
        readerChain?.stop()
        readerChain = null
        isRunning = false
        lastLogLine = ""
        Log.d(TAG, "RC monitoring stopped")
    }

    private fun findDjiDevice(usbManager: UsbManager): UsbDevice? {
        var fallback: UsbDevice? = null
        for (device in usbManager.deviceList.values) {
            if (device.vendorId == RcMonitor.DJI_USB_VID) {
                val pid = device.productId
                if (pid == RcMonitor.DJI_USB_PID_INTERNAL) return device
                if (pid == RcMonitor.DJI_USB_PID_ACTIVE || pid == RcMonitor.DJI_USB_PID_INIT) {
                    fallback = device
                }
            }
        }
        return fallback
    }
}
