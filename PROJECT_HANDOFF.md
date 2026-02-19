# DJI RC to vJoy — Project Handoff Summary

## Goal

Turn a **DJI RM510B remote controller** (an Android device) into a **PC gaming controller and stream deck**. The RM510B runs Android, and some of its physical buttons (C1, C2, shutter first-stage, pause, "A" button, mode switcher) are **not captured by `adb getevent`** because they are intercepted by DJI's proprietary software layer before reaching the Linux kernel input subsystem. The project aims to capture ALL inputs and relay them to a Python script on the PC that feeds them into **vJoy** (virtual joystick driver).

## User Profile

- Self-described Android development beginner
- Has a working Python + ADB + vJoy pipeline for the buttons that `getevent` already captures
- Prefers **Flutter/Dart** for the app
- Willing to **root the device** if needed (currently attempting it)
- Does **not** need to keep drone control functionality — gaming controller only

## Key Technical Discovery: rc-monitor Library

A reverse-engineered C library was added to the project at `rc-monitor/`. It was built by analyzing `libdjisdk_jni.so` from DJI Mobile SDK V5 5.17.0 using Ghidra. This is the most important asset in the project:

### What it does

- Parses raw USB bulk transfer data from DJI remotes
- Implements full **DUML v1 protocol** framing (SOF detection, CRC8/CRC16)
- Decodes 17-byte `rc_button_physical_status_push` payloads (cmd_set=0x06, cmd_id=0x05)
- Outputs a clean `rc_state_t` struct with ALL inputs: pause, gohome, shutter, record, C1-C3, 5D joystick, flight mode switch, both sticks (analog), both wheels, wheel delta

### Important caveat

The `UsbRcReader.java` wrapper assumes the RC is an **external USB device** connected to a phone/tablet. But the RM510B **IS** the Android device — its controls are internal hardware. The USB Host API approach may not work as-is for reading the built-in controls. The **payload parser** (`rcm_parse_payload`) is still directly usable once a data source is identified.

### Key files in rc-monitor/

| File                        | Purpose                                                                    |
| --------------------------- | -------------------------------------------------------------------------- |
| `RC_MONITORING_SPEC.md`     | Full reverse-engineering notes, bit-level payload format, SDK architecture |
| `README.md`                 | Integration guide, API usage examples                                      |
| `include/rc_monitor.h`      | Public C API — parser creation, feed, parse_payload                        |
| `src/rc_monitor.c`          | DUML frame parser + payload decoder with CRC tables                        |
| `src/rc_monitor_jni.c`      | JNI bridge for Android (C → Java callbacks)                                |
| `java/.../RcMonitor.java`   | Java wrapper with RcStateListener interface                                |
| `java/.../UsbRcReader.java` | Android USB Host API reader (may need adaptation)                          |
| `test/test_rc_monitor.c`    | 453 lines of unit tests                                                    |
| `CMakeLists.txt`            | NDK + desktop build config                                                 |

## Approaches Discussed

### Approach 1: DJI SDK (safe, reliable)

Use official DJI Mobile SDK V5 Key-Value listeners to receive RC state. Requires DJI developer account + API key. ~100MB dependency. Guaranteed to capture all inputs.

### Approach 2: strace + direct device node (if rooted)

Install DJI sample app, run `strace` on its process while pressing buttons, identify the device node or IPC mechanism that carries DUML data. Then read from it directly — no SDK needed. The rc-monitor parser handles the rest.

### Approach 3: Raw USB read (current implementation attempt)

Use Android USB Host API to read DUML frames from the internal USB bus. This is what rc-monitor's `UsbRcReader` does. **May not work** because the controller hardware is internal, not an external USB device. Needs testing on the actual device.

### Recommended strategy

1. Try rooting → `strace` DJI process → find data source
2. If that reveals a device node or socket → feed it to `rcm_feed()` directly
3. If not → fall back to DJI SDK Key-Value listeners
4. The Flutter app + Kotlin platform channel architecture works for all approaches

## What Has Been Built

### Flutter App (Dart)

| File                                | Description                                                                                                                             |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `lib/main.dart`                     | Main app with dark theme, RC monitoring page with start/stop, stick visualization, button indicators, mode switch display, wheel values |
| `lib/rc_state.dart`                 | `RcState` data class matching native `rc_state_t` — all buttons, sticks, wheels, mode switch                                            |
| `lib/rc_monitor_service.dart`       | Platform channel service — `MethodChannel` for start/stop, `EventChannel` for streaming RC state                                        |
| `lib/widgets/stick_widget.dart`     | Custom-painted analog stick visualizer (crosshair + dot)                                                                                |
| `lib/widgets/button_indicator.dart` | Button indicator widget that lights up when pressed                                                                                     |

### Android Native (Kotlin)

| File                             | Description                                                                                                                                                                         |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `android/.../MainActivity.kt`    | Registers `MethodChannel` and `EventChannel` with Flutter engine                                                                                                                    |
| `android/.../RcMonitorPlugin.kt` | Bridge between rc-monitor and Flutter. Handles start/stop via MethodChannel, streams state via EventChannel, **also outputs to logcat** with tag `RC_OUTPUT` for Python/ADB capture |

### Android Configuration

| File                                        | Change                                                                                                                                                         |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `android/app/build.gradle.kts`              | Added NDK `abiFilters` (arm64-v8a, armeabi-v7a), `externalNativeBuild` pointing to rc-monitor's CMakeLists.txt, `sourceSets` including rc-monitor Java sources |
| `android/.../AndroidManifest.xml`           | Added `uses-feature android.hardware.usb.host`, USB device attached intent-filter, `usb_device_filter.xml` meta-data                                           |
| `android/.../res/xml/usb_device_filter.xml` | DJI USB vendor ID filter (0x2CA3 = 11427)                                                                                                                      |

### Logcat Output Format (for Python ADB capture)

Tag: `RC_OUTPUT`, only logged on state change. Format:

```
P:0|GH:0|SH:1|REC:0|C1:0|C2:1|C3:0|5U:0|5D:0|5L:0|5R:0|5C:0|FM:1|RH:320|RV:-100|LH:0|LV:500|LW:0|RW:0|RWD:0
```

Python capture command: `adb logcat -s RC_OUTPUT:D`

## What Remains To Do

### Immediate (build issues)

1. **NDK license not accepted** — build fails with `LicenceNotAcceptedException` for NDK 28.2.13676358. The Android SDK at `C:\Apps\platform-tools-latest-windows` is minimal (only platform-tools, no cmdline-tools/sdkmanager). Need to either:
   - Install Android Studio and accept licenses through it
   - Install cmdline-tools and run `sdkmanager --licenses`
   - Or manually create the correct license file at `<sdk>/licenses/android-sdk-license`
2. **minSdk was reverted** — the user (or formatter) may have changed `minSdk` back to `flutter.minSdkVersion` from the hardcoded `21`. Check and set it appropriately (minimum API 21 for USB Host).

### After build works

3. **Test on actual RM510B** — determine if USB Host API can see the internal controller hardware
4. **If USB doesn't work (likely)** — adapt data source:
   - With root: find internal DUML data source via `strace`, create a native reader for that source
   - Without root: integrate DJI SDK V5 and use KeyManager listeners instead
5. **Python script update** — modify existing Python vJoy script to read from `adb logcat -s RC_OUTPUT:D` instead of `adb getevent`
6. **Background service** — make the app run as a persistent foreground service so it survives screen off / app switching
7. **Auto-start on boot** (if rooted) — add boot receiver to start the service automatically

### Nice to have

8. Button mapping configuration UI
9. Deadzone / sensitivity settings for sticks
10. Stream deck functionality (on-screen programmable buttons)

## Development Environment

- **OS**: Windows (Cyrillic locale — Russian)
- **Flutter**: 3.41.1 (stable)
- **Kotlin**: 2.2.20
- **Android Gradle Plugin**: 8.11.1
- **Android SDK**: `C:\Apps\platform-tools-latest-windows` (minimal installation)
- **Target device**: DJI RC Pro (RM510B), connected via ADB as `5YSZLCG00354N2`, Android 10 (API 29), arm64
- **Project path**: `C:\projects\dji_android_app_flutter\dji_to_vjoy`

## Architecture Diagram

```
┌─────────────────────────────────────────────────────┐
│                  DJI RM510B Device                   │
│                                                       │
│  ┌──────────────┐    ┌─────────────────────────────┐ │
│  │ RC Hardware   │───▶│ Data Source (TBD)           │ │
│  │ sticks/buttons│    │ USB? device node? socket?   │ │
│  └──────────────┘    └──────────┬──────────────────┘ │
│                                  │ raw DUML bytes     │
│                     ┌────────────▼────────────────┐  │
│                     │ rc-monitor C library         │  │
│                     │ (DUML parser + payload       │  │
│                     │  decoder → rc_state_t)       │  │
│                     └────────────┬────────────────┘  │
│                          JNI     │                    │
│                     ┌────────────▼────────────────┐  │
│                     │ RcMonitorPlugin.kt           │  │
│                     │ (Kotlin platform channel)    │  │
│                     └───┬────────────────────┬───┘  │
│                         │ EventChannel       │logcat │
│                    ┌────▼─────┐         ┌────▼────┐ │
│                    │ Flutter  │         │RC_OUTPUT │ │
│                    │ UI       │         │ tag      │ │
│                    └──────────┘         └────┬────┘ │
└─────────────────────────────────────────────┼───────┘
                                               │ ADB
                                    ┌──────────▼──────┐
                                    │ Python script    │
                                    │ (adb logcat)     │
                                    │ → vJoy output    │
                                    └─────────────────┘
```
