import 'package:flutter/services.dart';
import 'rc_state.dart';

/// Service that communicates with the native RcMonitorPlugin via platform channels.
class RcMonitorService {
  static const _controlChannel = MethodChannel('com.dji.rc/control');
  static const _stateChannel = EventChannel('com.dji.rc/state');

  Stream<RcState>? _cachedStream;

  /// Stream of RC state updates from the native USB reader.
  /// Caches the broadcast stream to avoid re-subscribing on each access.
  Stream<RcState> get stateStream {
    return _cachedStream ??= _stateChannel.receiveBroadcastStream().map(
      (event) => RcState.fromMap(event as Map<dynamic, dynamic>),
    );
  }

  /// Invalidate the cached stream (call after stop to allow fresh re-subscribe).
  void resetStream() {
    _cachedStream = null;
  }

  /// Start reading from the DJI USB device.
  /// Returns the status string from native side.
  Future<String> start() async {
    try {
      final result = await _controlChannel.invokeMethod<Map>('start');
      return result?['status'] as String? ?? 'unknown';
    } on PlatformException catch (e) {
      return 'error: ${e.message}';
    }
  }

  /// Stop the USB reader.
  Future<String> stop() async {
    try {
      final result = await _controlChannel.invokeMethod<Map>('stop');
      return result?['status'] as String? ?? 'unknown';
    } on PlatformException catch (e) {
      return 'error: ${e.message}';
    }
  }

  /// Check if the USB reader is currently running.
  Future<bool> isRunning() async {
    try {
      final result = await _controlChannel.invokeMethod<Map>('isRunning');
      return result?['running'] as bool? ?? false;
    } on PlatformException {
      return false;
    }
  }
}
