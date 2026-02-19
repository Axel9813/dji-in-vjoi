/*
 * rc_monitor.h - DJI RM510 RC Monitor Library
 *
 * Parses DJI DUML protocol frames from raw USB bulk transfers and decodes
 * rc_button_physical_status_push payloads into structured RC state.
 *
 * Reverse-engineered from DJI Mobile SDK V5 5.17.0 (libdjisdk_jni.so)
 * Target hardware: RM510 (RC-N1, RC-N2, DJI RC)
 */

#ifndef RC_MONITOR_H
#define RC_MONITOR_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* --- DJI DUML Protocol Constants --- */

#define DUML_SOF              0x55
#define DUML_CMD_SET_RC       0x06
#define DUML_CMD_RC_PUSH      0x05
#define DUML_VERSION          1
#define DUML_HEADER_LEN       11
#define DUML_FOOTER_LEN       2
#define DUML_MIN_FRAME_LEN    13    /* SOF(1)+LenVer(2)+CRC8(1)+Route(3)+Cmd(2)+Type(1)+CRC16(2)+payload(1+) */
#define DUML_MAX_FRAME_LEN    1400

#define RC_PUSH_PAYLOAD_LEN   17

/* Device types */
#define DUML_DEV_ANY          0
#define DUML_DEV_CAMERA       1
#define DUML_DEV_APP          2
#define DUML_DEV_FC           3
#define DUML_DEV_GIMBAL       4
#define DUML_DEV_RC           6
#define DUML_DEV_PC           10

/* RC command IDs */
#define DUML_CMD_RC_CHANNEL   0x01
#define DUML_CMD_RC_ENABLE    0x24

/* Pack/ack types */
#define DUML_PACK_REQUEST     0
#define DUML_PACK_RESPONSE    1
#define DUML_ACK_NO_ACK       0
#define DUML_ACK_AFTER_EXEC   2

/* DJI USB Vendor/Product IDs */
#define DJI_USB_VID           0x2CA3
#define DJI_USB_PID_INIT      0x0040
#define DJI_USB_PID_ACTIVE    0x1020

/* --- RC State Structures --- */

/* Flight mode switch positions */
typedef enum {
    RC_MODE_SPORT    = 0,
    RC_MODE_NORMAL   = 1,
    RC_MODE_TRIPOD   = 2,  /* or CineSmooth depending on aircraft */
    RC_MODE_UNKNOWN  = 3
} rc_flight_mode_t;

/* 5-direction joystick button state */
typedef struct {
    bool up;
    bool down;
    bool left;
    bool right;
    bool center;
} rc_five_d_t;

/* Stick axis value: signed, centered at 0, range approx -660..+660 */
typedef struct {
    int16_t horizontal;
    int16_t vertical;
} rc_stick_t;

/* Complete RC state from a single push packet */
typedef struct {
    /* Buttons (true = pressed) */
    bool pause;
    bool gohome;
    bool shutter;
    bool record;
    bool custom1;
    bool custom2;
    bool custom3;

    /* 5-direction joystick */
    rc_five_d_t five_d;

    /* Flight mode switch */
    rc_flight_mode_t flight_mode;

    /* Analog sticks (centered at 0) */
    rc_stick_t stick_right;    /* aileron (H) / elevator (V) */
    rc_stick_t stick_left;     /* rudder (H) / throttle (V) */

    /* Wheels/dials (centered at 0) */
    int16_t left_wheel;
    int16_t right_wheel;
    int8_t  right_wheel_delta; /* incremental, signed, from 5-bit field */
} rc_state_t;

/* --- Callback --- */

/*
 * Called when a valid RC push packet is decoded.
 * @param state   Parsed RC state
 * @param userdata Opaque pointer passed to rcm_create()
 */
typedef void (*rcm_callback_t)(const rc_state_t *state, void *userdata);

/* --- DUML Frame Parser --- */

/*
 * Opaque parser context. Handles reassembly of DUML frames from
 * partial USB bulk reads and filters for RC push packets.
 */
typedef struct rcm_parser rcm_parser_t;

/*
 * Create a new parser instance.
 * @param cb       Callback invoked on each decoded RC push packet
 * @param userdata Passed through to callback
 * @return Parser handle, or NULL on allocation failure
 */
rcm_parser_t *rcm_create(rcm_callback_t cb, void *userdata);

/*
 * Destroy a parser instance and free all resources.
 */
void rcm_destroy(rcm_parser_t *p);

/*
 * Feed raw bytes from a USB bulk read into the parser.
 * May invoke the callback zero or more times synchronously.
 * Thread-safe: can be called from the USB read thread.
 *
 * @param p    Parser handle
 * @param data Raw bytes from USB bulk transfer
 * @param len  Number of bytes
 * @return Number of RC push packets decoded in this call
 */
int rcm_feed(rcm_parser_t *p, const uint8_t *data, size_t len);

/*
 * Reset parser state (e.g. after USB disconnect/reconnect).
 */
void rcm_reset(rcm_parser_t *p);

/* --- Direct Payload Parsing (no DUML framing) --- */

/*
 * Parse a raw 17-byte rc_button_physical_status_push payload directly.
 * Use this if you already have the extracted DUML payload.
 *
 * @param payload  Pointer to 17-byte payload
 * @param len      Length of payload (must be >= 17)
 * @param out      Output RC state
 * @return 0 on success, -1 if len < 17 or payload is NULL
 */
int rcm_parse_payload(const uint8_t *payload, size_t len, rc_state_t *out);

/* --- Utility --- */

/*
 * Return a human-readable name for a flight mode value.
 */
const char *rcm_flight_mode_str(rc_flight_mode_t mode);

/* --- Packet Builder --- */

/*
 * Build a DUML v1 packet.
 * @param out         Output buffer
 * @param out_size    Size of output buffer
 * @param sender_type   Device type of sender (e.g. DUML_DEV_PC)
 * @param sender_index  Sender index (usually 0)
 * @param receiver_type Device type of receiver (e.g. DUML_DEV_RC)
 * @param receiver_index Receiver index (usually 0)
 * @param seq_num     Sequence number
 * @param pack_type   Pack type (DUML_PACK_REQUEST or DUML_PACK_RESPONSE)
 * @param ack_type    Ack type (DUML_ACK_NO_ACK or DUML_ACK_AFTER_EXEC)
 * @param encrypt_type Encryption type (usually 0)
 * @param cmd_set     Command set
 * @param cmd_id      Command ID
 * @param payload     Payload bytes (may be NULL if payload_len is 0)
 * @param payload_len Payload length
 * @return Total packet size, or -1 on error
 */
int rcm_build_packet(uint8_t *out, size_t out_size,
                     uint8_t sender_type, uint8_t sender_index,
                     uint8_t receiver_type, uint8_t receiver_index,
                     uint16_t seq_num,
                     uint8_t pack_type, uint8_t ack_type, uint8_t encrypt_type,
                     uint8_t cmd_set, uint8_t cmd_id,
                     const uint8_t *payload, size_t payload_len);

/*
 * Build the RC enable/handshake command (cmd_set=0x06, cmd_id=0x24).
 * Sender=PC/0, Receiver=RC/0, ack=ACK_AFTER_EXEC, payload=[0x01].
 * @param out       Output buffer (must be >= 14 bytes)
 * @param out_size  Size of output buffer
 * @param seq       Sequence number
 * @return Total packet size (14), or -1 on error
 */
int rcm_build_enable_cmd(uint8_t *out, size_t out_size, uint16_t seq);

/*
 * Build a channel data request (cmd_set=0x06, cmd_id=0x01).
 * Sender=PC/0, Receiver=RC/0, ack=ACK_AFTER_EXEC, no payload.
 * @param out       Output buffer (must be >= 13 bytes)
 * @param out_size  Size of output buffer
 * @param seq       Sequence number
 * @return Total packet size (13), or -1 on error
 */
int rcm_build_channel_request(uint8_t *out, size_t out_size, uint16_t seq);

#ifdef __cplusplus
}
#endif

#endif /* RC_MONITOR_H */
