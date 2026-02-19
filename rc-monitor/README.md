# rc-monitor

Lightweight C library for monitoring DJI remote controller inputs on Android without the full DJI Mobile SDK. Parses raw USB data from RM510-series controllers (RC-N1, RC-N2, DJI RC) into structured button, stick, and wheel state.

The protocol format was reverse-engineered from `libdjisdk_jni.so` in DJI Mobile SDK V5 5.17.0 using Ghidra. See `RC_MONITORING_SPEC.md` in the parent directory for the full reverse-engineering notes.

## What it parses

All data comes from a single DJI DUML protocol push packet (`cmd_set=0x06`, `cmd_id=0x05`). The library decodes the 17-byte payload into:

| Input                | Type    | Range / Values                     |
|----------------------|---------|------------------------------------|
| Pause button         | bool    | pressed / released                 |
| GoHome button        | bool    | pressed / released                 |
| Shutter button       | bool    | pressed / released                 |
| Record button        | bool    | pressed / released                 |
| Custom buttons C1-C3 | bool    | pressed / released                 |
| 5D joystick          | 5x bool| up, down, left, right, center      |
| Flight mode switch   | enum    | Sport (0), Normal (1), Tripod (2)  |
| Right stick          | 2x int  | H/V, centered at 0, ~-660 to +660 |
| Left stick           | 2x int  | H/V, centered at 0, ~-660 to +660 |
| Left wheel/dial      | int     | centered at 0                      |
| Right wheel/dial     | int     | centered at 0                      |
| Right wheel delta    | int     | signed incremental, -31 to +31     |

## Project structure

```
rc-monitor/
  CMakeLists.txt                 NDK and desktop build
  include/
    rc_monitor.h                 Public C API
  src/
    rc_monitor.c                 DUML frame parser + payload decoder
    rc_monitor_jni.c             Android JNI bridge
  java/com/dji/rcmonitor/
    RcMonitor.java               Java wrapper with RcState class
    UsbRcReader.java             Android USB Host API integration
  test/
    test_rc_monitor.c            Unit tests
```

## Integration into an Android app

### 1. Add the native library to your build

Copy or symlink the `rc-monitor` directory into your project, then reference it from your app module's `build.gradle`:

```groovy
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }
    externalNativeBuild {
        cmake {
            path file('../rc-monitor/CMakeLists.txt')
            version '3.18.1+'
        }
    }
}
```

### 2. Add the Java sources

Copy the contents of `java/` into your app's `src/main/java/` directory so that the package `com.dji.rcmonitor` is available:

```
app/src/main/java/com/dji/rcmonitor/
  RcMonitor.java
  UsbRcReader.java
```

### 3. Declare USB permissions in AndroidManifest.xml

```xml
<manifest ...>
    <uses-feature android:name="android.hardware.usb.host" />

    <application ...>
        <activity ...>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_device_filter" />
        </activity>
    </application>
</manifest>
```

Create `res/xml/usb_device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="11427" />  <!-- 0x2CA3 = DJI -->
</resources>
```

### 4. Use it

#### Option A: UsbRcReader (handles USB automatically)

```java
UsbRcReader reader = new UsbRcReader(context);

reader.start(new RcMonitor.SimpleListener() {
    @Override
    public void onState(RcMonitor.RcState s) {
        Log.d("RC", String.format(
            "Mode:%s Shutter:%b Record:%b Sticks:[%d,%d,%d,%d]",
            s.flightModeString(), s.shutter, s.record,
            s.stickRightH, s.stickRightV,
            s.stickLeftH, s.stickLeftV));
    }
});

// When done:
reader.stop();
```

The listener callback fires on the USB reader thread. Post to a `Handler` or use `runOnUiThread()` if you need to update UI.

#### Option B: Manual USB reads with RcMonitor

If you need more control over the USB connection (permissions, device selection, error handling):

```java
RcMonitor monitor = new RcMonitor();
monitor.init(new RcMonitor.SimpleListener() {
    @Override
    public void onState(RcMonitor.RcState s) {
        // handle state
    }
});

// Your own USB read loop:
byte[] buf = new byte[1024];
while (running) {
    int n = usbConnection.bulkTransfer(bulkInEndpoint, buf, buf.length, 100);
    if (n > 0) {
        monitor.feed(buf, n);
    }
}

monitor.destroy();
```

#### Option C: Direct payload parsing (with DJI SDK)

If you already use the DJI SDK and want this library only for payload parsing, bypass the DUML framing entirely. Pass the raw push data bytes from a native hook or intercepted callback:

```java
// payload is a 17-byte array from the DJI SDK's internal push data
monitor.feedDirect(payload, payload.length);
```

## C API

The core C library has no Android dependencies and can be used standalone:

```c
#include "rc_monitor.h"

void on_rc(const rc_state_t *s, void *userdata) {
    printf("Shutter=%d RightH=%d RightV=%d Mode=%s\n",
           s->shutter,
           s->stick_right.horizontal,
           s->stick_right.vertical,
           rcm_flight_mode_str(s->flight_mode));
}

// With DUML framing (raw USB data):
rcm_parser_t *p = rcm_create(on_rc, NULL);
rcm_feed(p, usb_bulk_data, n_bytes);  // callback fires for each RC packet
rcm_destroy(p);

// Without framing (raw 17-byte payload):
rc_state_t state;
rcm_parse_payload(payload_17_bytes, 17, &state);
```

## Running the tests

```sh
cd rc-monitor
mkdir build && cd build
cmake .. && make
./test_rc_monitor
```

## Payload format reference

The 17-byte `rc_button_physical_status_push` payload:

```
Bytes 0-4: Button/switch bitfield (little-endian)
  byte0[4]    Pause button
  byte0[5]    GoHome button
  byte0[6]    Shutter button
  byte1[0]    Record button
  byte1[3]    5D Right
  byte1[4]    5D Up
  byte1[5]    5D Down
  byte1[6]    5D Left
  byte1[7]    5D Center
  byte2[1:0]  Flight mode switch (2 bits)
  byte2[2]    Custom button C1
  byte2[3]    Custom button C2
  byte2[4]    Custom button C3
  byte4[5:1]  Right wheel delta magnitude (5 bits)
  byte4[6]    Right wheel delta sign

Bytes 5-16: Analog values (uint16 LE, subtract 0x400 to center)
  5-6     Right stick horizontal
  7-8     Right stick vertical
  9-10    Left stick vertical
  11-12   Left stick horizontal
  13-14   Left wheel
  15-16   Right wheel
```

## Limitations

- The raw USB approach bypasses DJI's authentication handshake. The RM510 may require a DUML session to be established before it streams push data. If no data arrives, you may need to send an initial handshake sequence or use the DJI SDK for connection setup while using this library just for parsing.
- The DUML CRC seeds and frame layout are based on DUML v1. The V5 SDK may use a slightly different framing version; the parser tries multiple header offsets to account for this.
- Tested against the RM510 payload format. Other RC models (RC701/DJI RC Pro, RM700/RC-Plus) use the same push packet layout based on the shared `OnButtonPhysicalStatusPush` handler, but hardware-specific fields (e.g. which custom buttons physically exist) vary.
