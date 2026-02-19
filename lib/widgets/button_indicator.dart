import 'package:flutter/material.dart';

/// A simple button indicator that lights up when pressed.
class ButtonIndicator extends StatelessWidget {
  final String label;
  final bool pressed;
  final Color activeColor;

  const ButtonIndicator({
    super.key,
    required this.label,
    required this.pressed,
    this.activeColor = Colors.greenAccent,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.all(3),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: pressed ? activeColor : Colors.grey.shade800,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: pressed
              ? activeColor.withValues(alpha: 0.7)
              : Colors.grey.shade600,
          width: 1,
        ),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.bold,
          color: pressed ? Colors.black : Colors.grey.shade400,
        ),
      ),
    );
  }
}
