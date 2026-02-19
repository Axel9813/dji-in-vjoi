import 'package:flutter/material.dart';

/// Visual representation of an analog stick position.
/// Shows a crosshair with a dot indicating current stick deflection.
class StickWidget extends StatelessWidget {
  final String label;
  final int horizontal; // -660..+660
  final int vertical; // -660..+660
  final double size;

  const StickWidget({
    super.key,
    required this.label,
    required this.horizontal,
    required this.vertical,
    this.size = 120,
  });

  @override
  Widget build(BuildContext context) {
    // Normalize to -1..+1
    final nx = (horizontal / 660).clamp(-1.0, 1.0);
    final ny = (-vertical / 660).clamp(-1.0, 1.0); // invert Y for screen coords

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
        ),
        const SizedBox(height: 4),
        CustomPaint(size: Size(size, size), painter: _StickPainter(nx, ny)),
        const SizedBox(height: 2),
        Text(
          'H:$horizontal V:$vertical',
          style: const TextStyle(fontSize: 10, fontFamily: 'monospace'),
        ),
      ],
    );
  }
}

class _StickPainter extends CustomPainter {
  final double nx; // -1..+1
  final double ny; // -1..+1

  _StickPainter(this.nx, this.ny);

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2 - 4;

    // Background circle
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = Colors.grey.shade800
        ..style = PaintingStyle.fill,
    );

    // Crosshair
    final crossPaint = Paint()
      ..color = Colors.grey.shade600
      ..strokeWidth = 0.5;
    canvas.drawLine(
      Offset(center.dx - radius, center.dy),
      Offset(center.dx + radius, center.dy),
      crossPaint,
    );
    canvas.drawLine(
      Offset(center.dx, center.dy - radius),
      Offset(center.dx, center.dy + radius),
      crossPaint,
    );

    // Border
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = Colors.grey.shade500
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    // Stick position dot
    final dotX = center.dx + nx * (radius - 6);
    final dotY = center.dy + ny * (radius - 6);
    canvas.drawCircle(
      Offset(dotX, dotY),
      6,
      Paint()..color = Colors.greenAccent,
    );
  }

  @override
  bool shouldRepaint(_StickPainter old) => old.nx != nx || old.ny != ny;
}
