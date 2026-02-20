package com.example.dji_to_vjoy

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterView
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val flutterView = findFlutterView(window.decorView)
        android.util.Log.d("MainActivity", "FlutterView found: ${flutterView != null}")
        flutterView?.setOnGenericMotionListener { _, event ->
            android.util.Log.d("MainActivity", "MotionListener: src=0x${event.source.toString(16)} devId=${event.deviceId}")
            val reader = rcPlugin?.gamepadReader
            reader != null && reader.onMotionEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        android.util.Log.d("MainActivity", "dispatchMotion: src=0x${event.source.toString(16)} devId=${event.deviceId}")
        val reader = rcPlugin?.gamepadReader
        if (reader != null && reader.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.w("MainActivity", "KEY: code=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) action=${event.action} devId=${event.deviceId} src=0x${event.source.toString(16)}")
        val reader = rcPlugin?.gamepadReader
        if (reader != null && reader.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun findFlutterView(view: View): FlutterView? {
        if (view is FlutterView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFlutterView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
