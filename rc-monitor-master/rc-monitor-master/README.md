# rc-monitor

Self-contained C library for monitoring DJI remote controller inputs on Android. Handles USB enumeration, CDC ACM setup, DUML handshake, and parses raw USB data from RM510-series controllers (RC-N1, RC-N2, DJI RC) into structured button, stick, and wheel state.

The protocol format was reverse-engineered from `libdjisdk_jni.so` in DJI Mobile SDK V5 5.17.0 using Ghidra. See `RC_MONITORING_SPEC.md` in the parent directory for the full reverse-engineering notes.

## What it parses

The RC streams DJI DUML protocol push packets (`cmd_set=0x06`, `cmd_id=0x05`) once the handshake is complete. The library decodes the 17-byte payload into:

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

## Handshake

The RM510-series RC requires a DUML enable command before it starts streaming push data over USB. The sequence is:

1. The RC enumerates as a CDC ACM USB device (VID `0x2CA3`, PID `0x1020`).
2. The host configures the serial line: 115200 baud, 8N1, DTR+RTS asserted.
3. An enable command is sent: `cmd_set=0x06`, `cmd_id=0x24`, `payload=[0x01]`.
4. The RC begins streaming push data (`cmd_set=0x06`, `cmd_id=0x05`).

`UsbRcReader` handles this automatically. For manual integration, build and send the enable command yourself:

```java
byte[] cmd = RcMonitor.buildEnableCommand(sequenceNumber);
usbConnection.bulkTransfer(bulkOutEndpoint, cmd, cmd.length, 1000);
```

If no push data arrives, stick data can be polled with `RcMonitor.buildChannelRequest(seq)` sent periodically via bulk OUT.

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
    test_rc_monitor.c            Unit tests (32 tests)
    fuzz_feed.c                  libFuzzer harness for rcm_feed()
    fuzz_payload.c               libFuzzer harness for rcm_parse_payload()
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

#### Option C: Direct payload parsing

If you already have the raw 17-byte RC push payload from another source, bypass the DUML framing entirely:

```java
// payload is a 17-byte array extracted from a DUML frame
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

### With sanitizers (ASan + UBSan)

Requires clang:

```sh
CC=clang cmake .. -DENABLE_SANITIZERS=ON && make
./test_rc_monitor
```

### Fuzz testing

Requires a compiler with libFuzzer support (e.g. clang):

```sh
CC=clang cmake .. -DENABLE_FUZZING=ON && make
./fuzz_feed -max_total_time=60
./fuzz_payload -max_total_time=60
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

- Tested against the RM510 payload format. Other RC models (RC701/DJI RC Pro, RM700/RC-Plus) use the same push packet layout based on the shared `OnButtonPhysicalStatusPush` handler, but hardware-specific fields (e.g. which custom buttons physically exist) vary.
- The RC enumerates with PID `0x0040` initially, then re-enumerates as PID `0x1020` once active. Your USB device filter should match both, or at minimum the DJI vendor ID `0x2CA3`.
