# DJI RC Monitoring Library Specification

## Reverse-Engineered from DJI Mobile SDK V5 5.17.0 (libdjisdk_jni.so)

---

## 1. Overview

All RC (Remote Controller) monitoring data in the DJI V5 SDK flows through a single
push data type: `rc_button_physical_status_push`. This push packet carries **all** button
states, stick positions, wheel values, mode switch, and 5D button status in a packed
binary format.

### Key Architecture

```
USB/WiFi Transport (libdjibase.so)
  -> DUML Protocol Framing
    -> PackObserver routing (libDJICSDKCommon.so)
      -> rc_button_physical_status_push handler (libdjisdk_jni.so)
        -> Key-Value notifications to Java layer
```

### Native Library Dependencies

| Library                | Size  | Role                           |
|------------------------|-------|--------------------------------|
| libdjisdk_jni.so       | 65 MB | All SDK logic, RC handlers     |
| libDJICSDKCommon.so    | 175KB | PackObserver routing, KV store |
| libdjibase.so          | 25 MB | USB transport, DUML framing    |
| libDJIRegister.so      | ~2 MB | Authentication/activation      |
| libc++_shared.so       | ~1 MB | C++ standard library           |

---

## 2. DUML Protocol

### Command Identification

- **cmd_set = 0x06** (Remote Controller)
- **cmd_id = 0x05** (RC Push Parameter - classic DUML v1)

The V5 SDK registers observers using `PackRegisterInfo`:

```c
struct PackRegisterInfo {
    uint8_t  flag;       // offset 0 - upper 2 bits of packed key
    uint8_t  cmd_set;    // offset 1 - command set (0x06 for RC)
    uint16_t cmd_id;     // offset 2 - command ID
    uint16_t extra;      // offset 4 - additional routing field
};
```

Packed key formula: `(flag << 30) | (cmd_set << 16) | cmd_id`

### dji_cmd_rsp Structure

```c
struct dji_cmd_rsp {
    // ... header fields ...
    uint8_t* payload;    // offset 0x28 - pointer to raw payload data
    // ... more fields ...
};
```

---

## 3. rc_button_physical_status_push Payload Format (17 bytes)

### Bit-Level Layout

The handler `RM510RCAbstraction::OnButtonPhysicalStatusPush` (at 0x028576a8) reads
the payload starting from `dji_cmd_rsp->payload` (offset 0x28 of the response struct).

#### Bytes 0-4: Button/Switch Bitfield (40 bits, little-endian)

```
Byte 0 (bits 0-7):
  [3:0]  Reserved
  [4]    Pause button pressed      (1 = pressed)
  [5]    GoHome button pressed     (1 = pressed)
  [6]    Shutter button pressed    (1 = pressed)
  [7]    Reserved

Byte 1 (bits 8-15):
  [8]    Record button pressed     (1 = pressed)
  [10:9] Reserved
  [11]   5D-button Right           (1 = pressed)
  [12]   5D-button Up              (1 = pressed)
  [13]   5D-button Down            (1 = pressed)
  [14]   5D-button Left            (1 = pressed)
  [15]   5D-button Center/Press    (1 = pressed)

Byte 2 (bits 16-23):
  [17:16] Flight Mode Switch       (2-bit: 0=Sport, 1=Normal, 2=Tripod/CinSmooth)
  [18]    Custom Button 1 pressed  (1 = pressed)
  [19]    Custom Button 2 pressed  (1 = pressed)
  [20]    Custom Button 3 pressed  (1 = pressed)
  [23:21] Reserved

Byte 3 (bits 24-31):
  Reserved

Byte 4 (bits 32-39):
  [32]     Reserved
  [37:33]  Right New Wheel delta   (5-bit unsigned magnitude)
  [38]     Right New Wheel sign    (1 = positive, 0 = negative)
  [39]     Reserved
```

#### Bytes 5-16: Analog Stick and Wheel Values (uint16 LE each)

All analog values are unsigned 16-bit integers. Subtract `0x400` (1024) to get
the signed centered value. Range: approximately -660 to +660 (sticks), wheels vary.

```
Offset  Size    Field                    SDK Key Name
------  ----    -----                    ------------
5-6     uint16  Right Stick Horizontal   RCStickRightHorizontal
7-8     uint16  Right Stick Vertical     RCStickRightVertical
9-10    uint16  Left Stick Vertical      RCStickLeftVertical
11-12   uint16  Left Stick Horizontal    RCStickLeftHorizontal
13-14   uint16  Left Wheel               RCLeftWheel
15-16   uint16  Right Wheel              RCRightWheel
```

### C Structure Definition

```c
#pragma pack(push, 1)
typedef struct {
    // Bytes 0-4: Button bitfield (little-endian)
    uint8_t  byte0;          // bits: [7:rsvd][6:shutter][5:gohome][4:pause][3:0:rsvd]
    uint8_t  byte1;          // bits: [7:5d_center][6:5d_left][5:5d_down][4:5d_up]
                             //       [3:5d_right][2:1:rsvd][0:record]
    uint8_t  byte2;          // bits: [7:5:rsvd][4:custom3][3:custom2][2:custom1]
                             //       [1:0:flight_mode_switch]
    uint8_t  byte3;          // reserved
    uint8_t  byte4;          // bits: [7:rsvd][6:wheel_sign][5:1:wheel_mag(5bit)][0:rsvd]

    // Bytes 5-16: Analog values (uint16 LE, subtract 0x400 for centered)
    uint16_t stick_right_h;  // Right stick horizontal (-660..+660 centered)
    uint16_t stick_right_v;  // Right stick vertical
    uint16_t stick_left_v;   // Left stick vertical (throttle)
    uint16_t stick_left_h;   // Left stick horizontal (rudder)
    uint16_t left_wheel;     // Left wheel/dial
    uint16_t right_wheel;    // Right wheel/dial
} rc_button_physical_status_push_t;  // 17 bytes total
#pragma pack(pop)

// Helper macros for parsing
#define RC_PAUSE_BTN(p)         (((p)->byte0 >> 4) & 1)
#define RC_GOHOME_BTN(p)        (((p)->byte0 >> 5) & 1)
#define RC_SHUTTER_BTN(p)       (((p)->byte0 >> 6) & 1)
#define RC_RECORD_BTN(p)        (((p)->byte1 >> 0) & 1)
#define RC_5D_RIGHT(p)          (((p)->byte1 >> 3) & 1)
#define RC_5D_UP(p)             (((p)->byte1 >> 4) & 1)
#define RC_5D_DOWN(p)           (((p)->byte1 >> 5) & 1)
#define RC_5D_LEFT(p)           (((p)->byte1 >> 6) & 1)
#define RC_5D_CENTER(p)         (((p)->byte1 >> 7) & 1)
#define RC_FLIGHT_MODE(p)       (((p)->byte2 >> 0) & 3)
#define RC_CUSTOM1_BTN(p)       (((p)->byte2 >> 2) & 1)
#define RC_CUSTOM2_BTN(p)       (((p)->byte2 >> 3) & 1)
#define RC_CUSTOM3_BTN(p)       (((p)->byte2 >> 4) & 1)
#define RC_NEW_WHEEL_MAG(p)     (((p)->byte4 >> 1) & 0x1F)
#define RC_NEW_WHEEL_SIGN(p)    (((p)->byte4 >> 6) & 1)
#define RC_NEW_WHEEL_VAL(p)     (RC_NEW_WHEEL_SIGN(p) ? RC_NEW_WHEEL_MAG(p) : -RC_NEW_WHEEL_MAG(p))
#define RC_STICK_CENTERED(raw)  ((int16_t)(raw) - 0x400)
```

---

## 4. SDK Key Name Mapping

These are the internal key names used by the DJI V5 SDK to dispatch data to the
Java/Kotlin layer via the Key-Value system:

| Key Name                        | Type     | Source Bits      | Description                       |
|---------------------------------|----------|------------------|-----------------------------------|
| RCPauseButtonDown               | bool     | byte0[4]         | Pause/Stop button                 |
| RCGohomeButtonDown              | bool     | byte0[5]         | Return-to-Home button             |
| RCShutterButtonDown             | bool     | byte0[6]         | Photo shutter button              |
| RCRecordButtonDown              | bool     | byte1[0]         | Video record button               |
| RCFlightModeSwitchState         | enum(2b) | byte2[1:0]       | Flight mode 3-way switch          |
| RCCustomButton1Down             | bool     | byte2[2]         | C1 custom button                  |
| RCCustomButton2Down             | bool     | byte2[3]         | C2 custom button                  |
| RCCustomButton3Down             | bool     | byte2[4]         | C3 custom button                  |
| RCStickRightHorizontal          | int      | bytes 5-6        | Aileron (right stick X)           |
| RCStickRightVertical            | int      | bytes 7-8        | Elevator (right stick Y)          |
| RCStickLeftVertical             | int      | bytes 9-10       | Throttle (left stick Y)           |
| RCStickLeftHorizontal           | int      | bytes 11-12      | Rudder (left stick X)             |
| RCLeftWheel                     | int      | bytes 13-14      | Left dial/wheel                   |
| RCRightWheel                    | int      | bytes 15-16      | Right dial/wheel                  |
| RCRightNewWheel                 | int      | byte4[6,5:1]     | New right wheel (signed 5-bit)    |
| RcFiveDimensionPressedStatus    | struct   | byte1[7:3]       | 5D button composite (5 booleans)  |
| MockRcHardwareState             | struct   | composite        | Full RC state snapshot             |

---

## 5. RC Abstraction Class Hierarchy

The SDK supports multiple RC hardware types. Each has its own `OnButtonPhysicalStatusPush`
handler, but they all parse the same payload format:

```
dji::sdk::RemoteControllerAbstraction       (base class)
  +-- dji::sdk::AG405RcAbstraction          (intermediate)
  |     +-- dji::sdk::AG50XRemoteControllerAbstraction
  +-- dji::sdk::RM510RCAbstraction          (RC-N1, RC-N2, DJI RC)
  +-- dji::sdk::RC701RemoteControllerAbstraction  (DJI RC Pro)
  +-- dji::sdk::RM700RemoteControllerAbstraction  (DJI RC-Plus)
  +-- dji::sdk::EA110RemoteControllerAbstraction
  +-- dji::sdk::EA210RemoteControllerAbstraction
  +-- dji::sdk::EA220RemoteControllerAbstraction
  +-- dji::sdk::EA230RemoteControllerAbstraction
  +-- dji::sdk::PM430RemoteControllerAbstraction
```

Functions at these addresses (in libdjisdk_jni.so):
- OnButtonPhysicalStatusPush @ 0x023866e4 (FlightController variant)
- OnButtonPhysicalStatusPush @ 0x028021c0 (RC701)
- OnButtonPhysicalStatusPush @ 0x028576a8 (RM510)
- OnButtonPhysicalStatusPush @ 0x028624a0 (RM700)

---

## 6. JNI Architecture

### Initialization Chain (JNI_OnLoad @ 0x01984a04)

```
JNI_OnLoad
  -> JNI_LoadSdk
  -> JNI_LoadVideoCallback
  -> JNI_LoadMedia
  -> JNI_LoadFile
  -> JNI_LoadMission
  -> JNI_LoadWaypointV2
  -> JNI_LoadUpgrade
  -> JNI_LoadActivate
  -> JNI_LoadFlysafe
  -> JNI_LoadSystemInfo
  -> JNI_LoadHandler
  -> JNI_LoadFlightRecord
  -> JNI_LoadProvider        <-- registers JNIProviderManager
  -> JNI_LoadUtmiss
  -> dji::sdk::jni::JNI_Load
  -> djinni::jniInit
```

### Key JNI Classes

| Java Class                                           | Role                        |
|------------------------------------------------------|-----------------------------|
| dji/sdk/provider/jni/JNIProviderManager              | Native init + provider      |
| dji/sdk/provider/jni/JNIProviderManagerForCPP        | C++ -> Java callbacks       |
| dji/jni/callback/JNIProductConnectionCallback        | Connection state callbacks  |
| dji/sdk/datalink/bridge/jni/JNIDataLinkBridgeServer  | Raw data bridge             |

### JNI Registration Pattern

All native methods use dynamic registration via `RegisterNatives` (JNIEnv vtable
offset 0x6b8), not static `Java_` prefixed functions. The JNINativeMethod tables
are arrays of `{name_ptr, signature_ptr, fn_ptr}` stored in .rodata.

---

## 7. Implementation Approaches

### Approach A: Minimal Java SDK Wrapper (Recommended)

Use the official DJI V5 SDK but only initialize the RemoteController module:

```kotlin
// Minimal initialization
KeyManager.getInstance().listen(
    RemoteControllerKey.KeyStickLeftHorizontal,
    this
) { _, newValue -> onStickChanged("leftH", newValue) }

KeyManager.getInstance().listen(
    RemoteControllerKey.KeyShutterButtonDown,
    this
) { _, newValue -> onButtonChanged("shutter", newValue) }
```

**Pros:** Handles USB transport, authentication, session management, device detection
**Cons:** Requires full SDK AAR dependencies (~100MB)

### Approach B: Native Hook (Advanced)

Load only the required native libraries and hook into the push data callback:

1. Load `libc++_shared.so`, `libdjibase.so`, `libDJICSDKCommon.so`, `libdjisdk_jni.so`
2. Implement the minimal JNI provider classes that the native code expects
3. Register as a PackObserver for `rc_button_physical_status_push`
4. Parse raw payload using the format in Section 3

```c
// Pseudocode for native hook approach
void on_rc_push(uint64_t device_id, const char* key,
                uint16_t extra, dji_cmd_rsp* rsp) {
    rc_button_physical_status_push_t* data =
        (rc_button_physical_status_push_t*)rsp->payload;

    bool shutter = RC_SHUTTER_BTN(data);
    bool record  = RC_RECORD_BTN(data);
    int  stick_r_h = RC_STICK_CENTERED(data->stick_right_h);
    int  stick_r_v = RC_STICK_CENTERED(data->stick_right_v);
    int  stick_l_v = RC_STICK_CENTERED(data->stick_left_v);
    int  stick_l_h = RC_STICK_CENTERED(data->stick_left_h);
    int  mode = RC_FLIGHT_MODE(data);
    // ... dispatch to your callback
}
```

**Pros:** Minimal footprint, no Java SDK needed at runtime
**Cons:** Must reverse-engineer the full initialization sequence, authentication
is still required via `libDJIRegister.so`, USB transport setup is complex

### Approach C: Raw USB/DUML (Extreme)

Bypass all DJI libraries entirely:

1. Open USB device (DJI uses USB bulk transfer, VID: 0x2CA3)
2. Implement DUML v1 protocol framing
3. Handle handshake and session establishment
4. Filter for cmd_set=0x06 push packets
5. Parse payload per Section 3

**Pros:** Zero DJI library dependency
**Cons:** Must implement full DUML protocol stack, authentication/encryption,
device detection. Extremely complex. Refer to:
- https://github.com/o-gs/dji-firmware-tools (DUML dissectors)

---

## 8. DUML v1 Frame Format (for Approach C reference)

```
Offset  Size  Field
------  ----  -----
0       1     SOF (0x55)
1-2     2     Length (including header, 10-bit) + version (6-bit)
3       1     CRC8 of bytes 0-2
4       1     Sender/Receiver IDs (packed)
5-6     2     Sequence Number
7       1     Command Type + ACK flag
8       1     Encryption + Padding
9       1     cmd_set
10      1     cmd_id
11..N-2        Payload
N-1..N  2     CRC16 of entire frame
```

For RC push data: cmd_set=0x06, cmd_id=0x05, payload = 17 bytes per Section 3.

---

## Sources

- DJI DUML protocol documentation: https://github.com/o-gs/dji-firmware-tools
- Wireshark dissector: https://github.com/o-gs/dji-firmware-tools/blob/master/comm_dissector/wireshark/dji-dumlv1-proto.lua
- Known DUML commands reference from DJI reverse engineering community
