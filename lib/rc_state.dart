/// RC state model matching the native rc_state_t structure.
class RcState {
  // Buttons
  final bool pause;
  final bool gohome;
  final bool shutter;
  final bool record;
  final bool custom1;
  final bool custom2;
  final bool custom3;

  // 5D joystick
  final bool fiveDUp;
  final bool fiveDDown;
  final bool fiveDLeft;
  final bool fiveDRight;
  final bool fiveDCenter;

  // Mode switch
  final int flightMode;
  final String flightModeStr;

  // Sticks (centered at 0, range ~-660 to +660)
  final int stickRightH;
  final int stickRightV;
  final int stickLeftH;
  final int stickLeftV;

  // Wheels
  final int leftWheel;
  final int rightWheel;
  final int rightWheelDelta;

  const RcState({
    this.pause = false,
    this.gohome = false,
    this.shutter = false,
    this.record = false,
    this.custom1 = false,
    this.custom2 = false,
    this.custom3 = false,
    this.fiveDUp = false,
    this.fiveDDown = false,
    this.fiveDLeft = false,
    this.fiveDRight = false,
    this.fiveDCenter = false,
    this.flightMode = 1,
    this.flightModeStr = 'Normal',
    this.stickRightH = 0,
    this.stickRightV = 0,
    this.stickLeftH = 0,
    this.stickLeftV = 0,
    this.leftWheel = 0,
    this.rightWheel = 0,
    this.rightWheelDelta = 0,
  });

  factory RcState.fromMap(Map<dynamic, dynamic> map) {
    return RcState(
      pause: map['pause'] as bool? ?? false,
      gohome: map['gohome'] as bool? ?? false,
      shutter: map['shutter'] as bool? ?? false,
      record: map['record'] as bool? ?? false,
      custom1: map['custom1'] as bool? ?? false,
      custom2: map['custom2'] as bool? ?? false,
      custom3: map['custom3'] as bool? ?? false,
      fiveDUp: map['fiveDUp'] as bool? ?? false,
      fiveDDown: map['fiveDDown'] as bool? ?? false,
      fiveDLeft: map['fiveDLeft'] as bool? ?? false,
      fiveDRight: map['fiveDRight'] as bool? ?? false,
      fiveDCenter: map['fiveDCenter'] as bool? ?? false,
      flightMode: map['flightMode'] as int? ?? 1,
      flightModeStr: map['flightModeStr'] as String? ?? 'Normal',
      stickRightH: map['stickRightH'] as int? ?? 0,
      stickRightV: map['stickRightV'] as int? ?? 0,
      stickLeftH: map['stickLeftH'] as int? ?? 0,
      stickLeftV: map['stickLeftV'] as int? ?? 0,
      leftWheel: map['leftWheel'] as int? ?? 0,
      rightWheel: map['rightWheel'] as int? ?? 0,
      rightWheelDelta: map['rightWheelDelta'] as int? ?? 0,
    );
  }

  /// Returns true if any button is currently pressed.
  bool get anyButtonPressed =>
      pause ||
      gohome ||
      shutter ||
      record ||
      custom1 ||
      custom2 ||
      custom3 ||
      fiveDUp ||
      fiveDDown ||
      fiveDLeft ||
      fiveDRight ||
      fiveDCenter;
}
