import 'dart:async';
import 'package:flutter/material.dart';
import 'rc_state.dart';
import 'rc_monitor_service.dart';
import 'widgets/stick_widget.dart';
import 'widgets/button_indicator.dart';

void main() {
  runApp(const DjiRcApp());
}

class DjiRcApp extends StatelessWidget {
  const DjiRcApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DJI RC to vJoy',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.green,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const RcMonitorPage(),
    );
  }
}

class RcMonitorPage extends StatefulWidget {
  const RcMonitorPage({super.key});

  @override
  State<RcMonitorPage> createState() => _RcMonitorPageState();
}

class _RcMonitorPageState extends State<RcMonitorPage> {
  final _service = RcMonitorService();

  RcState _state = const RcState();
  bool _connected = false;
  bool _starting = false;
  String _statusMessage = 'Not started';
  StreamSubscription<RcState>? _subscription;
  int _packetCount = 0;

  @override
  void dispose() {
    _subscription?.cancel();
    _service.stop();
    super.dispose();
  }

  Future<void> _startMonitoring() async {
    if (_starting) return; // debounce rapid presses
    _starting = true;
    setState(() => _statusMessage = 'Starting...');

    final status = await _service.start();
    _starting = false;
    if (status == 'started' || status == 'already_running') {
      _subscription?.cancel();
      _subscription = _service.stateStream.listen(
        (state) {
          setState(() {
            _state = state;
            _packetCount++;
          });
        },
        onError: (error) {
          setState(() {
            _connected = false;
            _statusMessage = 'Stream error: $error';
          });
        },
      );
      setState(() {
        _connected = true;
        _statusMessage = 'Running — reading USB data';
      });
    } else {
      setState(() {
        _connected = false;
        _statusMessage = 'Failed: $status';
      });
    }
  }

  Future<void> _stopMonitoring() async {
    _subscription?.cancel();
    _subscription = null;
    await _service.stop();
    _service.resetStream();
    setState(() {
      _connected = false;
      _statusMessage = 'Stopped';
      _packetCount = 0;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('DJI RC Monitor'),
        actions: [
          if (_connected)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Center(
                child: Text(
                  '$_packetCount pkts',
                  style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                ),
              ),
            ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status bar
            _buildStatusBar(),
            const SizedBox(height: 16),

            // Sticks
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                StickWidget(
                  label: 'Left Stick',
                  horizontal: _state.stickLeftH,
                  vertical: _state.stickLeftV,
                ),
                StickWidget(
                  label: 'Right Stick',
                  horizontal: _state.stickRightH,
                  vertical: _state.stickRightV,
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Buttons section
            _buildSectionTitle('Buttons'),
            Wrap(
              alignment: WrapAlignment.center,
              children: [
                ButtonIndicator(label: 'PAUSE', pressed: _state.pause),
                ButtonIndicator(label: 'HOME', pressed: _state.gohome),
                ButtonIndicator(label: 'SHUTTER', pressed: _state.shutter),
                ButtonIndicator(label: 'RECORD', pressed: _state.record),
                ButtonIndicator(
                  label: 'C1',
                  pressed: _state.custom1,
                  activeColor: Colors.orangeAccent,
                ),
                ButtonIndicator(
                  label: 'C2',
                  pressed: _state.custom2,
                  activeColor: Colors.orangeAccent,
                ),
                ButtonIndicator(
                  label: 'C3',
                  pressed: _state.custom3,
                  activeColor: Colors.orangeAccent,
                ),
              ],
            ),
            const SizedBox(height: 12),

            // 5D Joystick
            _buildSectionTitle('5D Joystick'),
            _buildFiveDIndicator(),
            const SizedBox(height: 12),

            // Mode switch
            _buildSectionTitle('Flight Mode Switch'),
            _buildModeSwitch(),
            const SizedBox(height: 12),

            // Wheels
            _buildSectionTitle('Wheels'),
            _buildWheels(),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBar() {
    return Card(
      color: _connected ? Colors.green.shade900 : Colors.grey.shade900,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(
              _connected ? Icons.usb : Icons.usb_off,
              color: _connected ? Colors.greenAccent : Colors.grey,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                _statusMessage,
                style: TextStyle(
                  color: _connected ? Colors.greenAccent : Colors.grey.shade400,
                ),
              ),
            ),
            ElevatedButton.icon(
              onPressed: _connected ? _stopMonitoring : _startMonitoring,
              icon: Icon(_connected ? Icons.stop : Icons.play_arrow),
              label: Text(_connected ? 'Stop' : 'Start'),
              style: ElevatedButton.styleFrom(
                backgroundColor: _connected
                    ? Colors.red.shade700
                    : Colors.green.shade700,
                foregroundColor: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.bold,
          color: Colors.grey.shade400,
        ),
      ),
    );
  }

  Widget _buildFiveDIndicator() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Column(
          children: [
            ButtonIndicator(
              label: 'UP',
              pressed: _state.fiveDUp,
              activeColor: Colors.cyanAccent,
            ),
            Row(
              children: [
                ButtonIndicator(
                  label: 'L',
                  pressed: _state.fiveDLeft,
                  activeColor: Colors.cyanAccent,
                ),
                ButtonIndicator(
                  label: 'OK',
                  pressed: _state.fiveDCenter,
                  activeColor: Colors.cyanAccent,
                ),
                ButtonIndicator(
                  label: 'R',
                  pressed: _state.fiveDRight,
                  activeColor: Colors.cyanAccent,
                ),
              ],
            ),
            ButtonIndicator(
              label: 'DN',
              pressed: _state.fiveDDown,
              activeColor: Colors.cyanAccent,
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildModeSwitch() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        _buildModeChip('Sport', 0),
        const SizedBox(width: 8),
        _buildModeChip('Normal', 1),
        const SizedBox(width: 8),
        _buildModeChip('Tripod', 2),
      ],
    );
  }

  Widget _buildModeChip(String label, int mode) {
    final active = _state.flightMode == mode;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: active ? Colors.amber.shade700 : Colors.grey.shade800,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontWeight: FontWeight.bold,
          fontSize: 12,
          color: active ? Colors.black : Colors.grey.shade500,
        ),
      ),
    );
  }

  Widget _buildWheels() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        _buildWheelValue('Left Wheel', _state.leftWheel),
        _buildWheelValue('Right Wheel', _state.rightWheel),
        _buildWheelValue('R.Wheel Δ', _state.rightWheelDelta),
      ],
    );
  }

  Widget _buildWheelValue(String label, int value) {
    return Column(
      children: [
        Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
        const SizedBox(height: 2),
        Text(
          '$value',
          style: const TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            fontFamily: 'monospace',
          ),
        ),
      ],
    );
  }
}
