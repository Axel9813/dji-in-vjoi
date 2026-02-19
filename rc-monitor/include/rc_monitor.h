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
#define DUML_MIN_FRAME_LEN    13    /* SOF(1)+LenVer(2)+CRC8(1)+Route(3)+Cmd(2)+Type(1)+CRC16(2)+payload(1+) */
#define DUML_MAX_FRAME_LEN    1400

#define RC_PUSH_PAYLOAD_LEN   17

/* DJI USB Vendor/Product IDs */
#define DJI_USB_VID           0x2CA3

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

#ifdef __cplusplus
}
#endif

#endif /* RC_MONITOR_H */
