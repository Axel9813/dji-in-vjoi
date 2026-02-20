package com.example.dji_to_vjoy

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var rcPlugin: RcMonitorPlugin? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val plugin = RcMonitorPlugin(this)
        rcPlugin = plugin

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.dji.rc/control"
        ).setMethodCallHandler(plugin)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.dji.rc/state"
        ).setStreamHandler(plugin)
    }
}
