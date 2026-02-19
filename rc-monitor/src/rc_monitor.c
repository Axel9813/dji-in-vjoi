/*
 * rc_monitor.c - DJI RM510 RC Monitor Library
 *
 * DUML frame parser + rc_button_physical_status_push payload decoder.
 * Reverse-engineered from libdjisdk_jni.so (DJI Mobile SDK V5 5.17.0).
 */

#include "rc_monitor.h"
#include <stdlib.h>
#include <string.h>

/* ---------- CRC tables for DUML protocol ---------- */

static const uint8_t crc8_table[256] = {
    0x00,0x5e,0xbc,0xe2,0x61,0x3f,0xdd,0x83,0xc2,0x9c,0x7e,0x20,0xa3,0xfd,0x1f,0x41,
    0x9d,0xc3,0x21,0x7f,0xfc,0xa2,0x40,0x1e,0x5f,0x01,0xe3,0xbd,0x3e,0x60,0x82,0xdc,
    0x23,0x7d,0x9f,0xc1,0x42,0x1c,0xfe,0xa0,0xe1,0xbf,0x5d,0x03,0x80,0xde,0x3c,0x62,
    0xbe,0xe0,0x02,0x5c,0xdf,0x81,0x63,0x3d,0x7c,0x22,0xc0,0x9e,0x1d,0x43,0xa1,0xff,
    0x46,0x18,0xfa,0xa4,0x27,0x79,0x9b,0xc5,0x84,0xda,0x38,0x66,0xe5,0xbb,0x59,0x07,
    0xdb,0x85,0x67,0x39,0xba,0xe4,0x06,0x58,0x19,0x47,0xa5,0xfb,0x78,0x26,0xc4,0x9a,
    0x65,0x3b,0xd9,0x87,0x04,0x5a,0xb8,0xe6,0xa7,0xf9,0x1b,0x45,0xc6,0x98,0x7a,0x24,
    0xf8,0xa6,0x44,0x1a,0x99,0xc7,0x25,0x7b,0x3a,0x64,0x86,0xd8,0x5b,0x05,0xe7,0xb9,
    0x8c,0xd2,0x30,0x6e,0xed,0xb3,0x51,0x0f,0x4e,0x10,0xf2,0xac,0x2f,0x71,0x93,0xcd,
    0x11,0x4f,0xad,0xf3,0x70,0x2e,0xcc,0x92,0xd3,0x8d,0x6f,0x31,0xb2,0xec,0x0e,0x50,
    0xaf,0xf1,0x13,0x4d,0xce,0x90,0x72,0x2c,0x6d,0x33,0xd1,0x8f,0x0c,0x52,0xb0,0xee,
    0x32,0x6c,0x8e,0xd0,0x53,0x0d,0xef,0xb1,0xf0,0xae,0x4c,0x12,0x91,0xcf,0x2d,0x73,
    0xca,0x94,0x76,0x28,0xab,0xf5,0x17,0x49,0x08,0x56,0xb4,0xea,0x69,0x37,0xd5,0x8b,
    0x57,0x09,0xeb,0xb5,0x36,0x68,0x8a,0xd4,0x95,0xcb,0x29,0x77,0xf4,0xaa,0x48,0x16,
    0xe9,0xb7,0x55,0x0b,0x88,0xd6,0x34,0x6a,0x2b,0x75,0x97,0xc9,0x4a,0x14,0xf6,0xa8,
    0x74,0x2a,0xc8,0x96,0x15,0x4b,0xa9,0xf7,0xb6,0xe8,0x0a,0x54,0xd7,0x89,0x6b,0x35
};

static const uint16_t crc16_table[256] = {
    0x0000,0x1189,0x2312,0x329b,0x4624,0x57ad,0x6536,0x74bf,
    0x8c48,0x9dc1,0xaf5a,0xbed3,0xca6c,0xdbe5,0xe97e,0xf8f7,
    0x1081,0x0108,0x3393,0x221a,0x56a5,0x472c,0x75b7,0x643e,
    0x9cc9,0x8d40,0xbfdb,0xae52,0xdaed,0xcb64,0xf9ff,0xe876,
    0x2102,0x308b,0x0210,0x1399,0x6726,0x76af,0x4434,0x55bd,
    0xad4a,0xbcc3,0x8e58,0x9fd1,0xeb6e,0xfae7,0xc87c,0xd9f5,
    0x3183,0x200a,0x1291,0x0318,0x77a7,0x662e,0x54b5,0x453c,
    0xbdcb,0xac42,0x9ed9,0x8f50,0xfbef,0xea66,0xd8fd,0xc974,
    0x4204,0x538d,0x6116,0x709f,0x0420,0x15a9,0x2732,0x36bb,
    0xce4c,0xdfc5,0xed5e,0xfcd7,0x8868,0x99e1,0xab7a,0xbaf3,
    0x5285,0x430c,0x7197,0x601e,0x14a1,0x0528,0x37b3,0x263a,
    0xdecd,0xcf44,0xfddf,0xec56,0x98e9,0x8960,0xbbfb,0xaa72,
    0x6306,0x728f,0x4014,0x519d,0x2522,0x34ab,0x0630,0x17b9,
    0xef4e,0xfec7,0xcc5c,0xddd5,0xa96a,0xb8e3,0x8a78,0x9bf1,
    0x7387,0x620e,0x5095,0x411c,0x35a3,0x242a,0x16b1,0x0738,
    0xffcf,0xee46,0xdcdd,0xcd54,0xb9eb,0xa862,0x9af9,0x8b70,
    0x8408,0x9581,0xa71a,0xb693,0xc22c,0xd3a5,0xe13e,0xf0b7,
    0x0840,0x19c9,0x2b52,0x3adb,0x4e64,0x5fed,0x6d76,0x7cff,
    0x9489,0x8500,0xb79b,0xa612,0xd2ad,0xc324,0xf1bf,0xe036,
    0x18c1,0x0948,0x3bd3,0x2a5a,0x5ee5,0x4f6c,0x7df7,0x6c7e,
    0xa50a,0xb483,0x8618,0x9791,0xe32e,0xf2a7,0xc03c,0xd1b5,
    0x2942,0x38cb,0x0a50,0x1bd9,0x6f66,0x7eef,0x4c74,0x5dfd,
    0xb58b,0xa402,0x9699,0x8710,0xf3af,0xe226,0xd0bd,0xc134,
    0x39c3,0x284a,0x1ad1,0x0b58,0x7fe7,0x6e6e,0x5cf5,0x4d7c,
    0xc60c,0xd785,0xe51e,0xf497,0x8028,0x91a1,0xa33a,0xb2b3,
    0x4a44,0x5bcd,0x6956,0x78df,0x0c60,0x1de9,0x2f72,0x3efb,
    0xd68d,0xc704,0xf59f,0xe416,0x90a9,0x8120,0xb3bb,0xa232,
    0x5ac5,0x4b4c,0x79d7,0x685e,0x1ce1,0x0d68,0x3ff3,0x2e7a,
    0xe70e,0xf687,0xc41c,0xd595,0xa12a,0xb0a3,0x8238,0x93b1,
    0x6b46,0x7acf,0x4854,0x59dd,0x2d62,0x3ceb,0x0e70,0x1ff9,
    0xf78f,0xe606,0xd49d,0xc514,0xb1ab,0xa022,0x92b9,0x8330,
    0x7bc7,0x6a4e,0x58d5,0x495c,0x3de3,0x2c6a,0x1ef1,0x0f78
};

static uint8_t duml_crc8(const uint8_t *data, size_t len) {
    uint8_t crc = 0x77; /* DUML CRC8 seed */
    for (size_t i = 0; i < len; i++)
        crc = crc8_table[(crc ^ data[i]) & 0xFF];
    return crc;
}

static uint16_t duml_crc16(const uint8_t *data, size_t len) {
    uint16_t crc = 0x3692; /* DUML CRC16 seed */
    for (size_t i = 0; i < len; i++)
        crc = crc16_table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc;
}

/* ---------- Payload parser ---------- */

static inline uint16_t read_u16_le(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

int rcm_parse_payload(const uint8_t *payload, size_t len, rc_state_t *out) {
    if (!payload || !out || len < RC_PUSH_PAYLOAD_LEN)
        return -1;

    memset(out, 0, sizeof(*out));

    uint8_t b0 = payload[0];
    uint8_t b1 = payload[1];
    uint8_t b2 = payload[2];
    uint8_t b4 = payload[4];

    /* Buttons */
    out->pause   = (b0 >> 4) & 1;
    out->gohome  = (b0 >> 5) & 1;
    out->shutter = (b0 >> 6) & 1;
    out->record  = (b1 >> 0) & 1;
    out->custom1 = (b2 >> 2) & 1;
    out->custom2 = (b2 >> 3) & 1;
    out->custom3 = (b2 >> 4) & 1;

    /* 5-direction joystick */
    out->five_d.right  = (b1 >> 3) & 1;
    out->five_d.up     = (b1 >> 4) & 1;
    out->five_d.down   = (b1 >> 5) & 1;
    out->five_d.left   = (b1 >> 6) & 1;
    out->five_d.center = (b1 >> 7) & 1;

    /* Flight mode switch (2 bits) */
    out->flight_mode = (rc_flight_mode_t)(b2 & 0x03);

    /* Analog sticks: uint16 LE, subtract 0x400 to center at 0 */
    out->stick_right.horizontal = (int16_t)(read_u16_le(payload + 5)  - 0x400);
    out->stick_right.vertical   = (int16_t)(read_u16_le(payload + 7)  - 0x400);
    out->stick_left.vertical    = (int16_t)(read_u16_le(payload + 9)  - 0x400);
    out->stick_left.horizontal  = (int16_t)(read_u16_le(payload + 11) - 0x400);

    /* Wheels */
    out->left_wheel  = (int16_t)(read_u16_le(payload + 13) - 0x400);
    out->right_wheel = (int16_t)(read_u16_le(payload + 15) - 0x400);

    /* Right new wheel: 5-bit magnitude with sign bit */
    int mag  = (b4 >> 1) & 0x1F;
    int sign = (b4 >> 6) & 1;
    out->right_wheel_delta = (int8_t)(sign ? mag : -mag);

    return 0;
}

/* ---------- DUML frame parser with ring buffer ---------- */

#define RING_SIZE 4096

struct rcm_parser {
    rcm_callback_t  callback;
    void           *userdata;

    uint8_t  ring[RING_SIZE];
    size_t   head;   /* write position */
    size_t   count;  /* bytes in buffer */

    /* Frame assembly state */
    enum { SCAN_SOF, READ_FRAME } state;
    uint16_t frame_len;  /* expected frame length once header decoded */
};

rcm_parser_t *rcm_create(rcm_callback_t cb, void *userdata) {
    if (!cb) return NULL;
    rcm_parser_t *p = (rcm_parser_t *)calloc(1, sizeof(rcm_parser_t));
    if (!p) return NULL;
    p->callback = cb;
    p->userdata = userdata;
    p->state = SCAN_SOF;
    return p;
}

void rcm_destroy(rcm_parser_t *p) {
    free(p);
}

void rcm_reset(rcm_parser_t *p) {
    if (!p) return;
    p->head = 0;
    p->count = 0;
    p->state = SCAN_SOF;
    p->frame_len = 0;
}

/* Read byte at logical position `idx` (0 = oldest byte in buffer) */
static inline uint8_t ring_peek(const rcm_parser_t *p, size_t idx) {
    size_t tail = (p->head + RING_SIZE - p->count) % RING_SIZE;
    return p->ring[(tail + idx) % RING_SIZE];
}

/* Copy `n` bytes starting at logical position `idx` into `dst` */
static void ring_copy(const rcm_parser_t *p, size_t idx, uint8_t *dst, size_t n) {
    for (size_t i = 0; i < n; i++)
        dst[i] = ring_peek(p, idx + i);
}

/* Discard `n` bytes from the front of the buffer */
static void ring_consume(rcm_parser_t *p, size_t n) {
    if (n > p->count) n = p->count;
    p->count -= n;
}

/* Push one byte into the ring buffer */
static void ring_push(rcm_parser_t *p, uint8_t b) {
    p->ring[p->head] = b;
    p->head = (p->head + 1) % RING_SIZE;
    if (p->count < RING_SIZE)
        p->count++;
    /* If overflow, oldest byte is silently lost */
}

/*
 * Try to decode one DUML frame from the ring buffer.
 * Returns 1 if an RC push packet was decoded and callback invoked,
 * 0 if a non-RC frame was consumed, -1 if more data needed.
 */
static int try_decode_frame(rcm_parser_t *p) {
    while (p->count > 0) {
        if (p->state == SCAN_SOF) {
            /* Scan for SOF byte 0x55 */
            if (ring_peek(p, 0) != DUML_SOF) {
                ring_consume(p, 1);
                continue;
            }
            /* Need at least 4 bytes to read header (SOF + LenVer + CRC8) */
            if (p->count < 4)
                return -1;

            /* Verify CRC8 of first 3 bytes */
            uint8_t hdr[3];
            ring_copy(p, 0, hdr, 3);
            uint8_t expected_crc8 = ring_peek(p, 3);
            if (duml_crc8(hdr, 3) != expected_crc8) {
                /* Not a valid frame start, skip this 0x55 */
                ring_consume(p, 1);
                continue;
            }

            /* Extract frame length (10 bits from bytes 1-2) */
            uint16_t len_ver = (uint16_t)hdr[1] | ((uint16_t)hdr[2] << 8);
            p->frame_len = len_ver & 0x03FF;

            if (p->frame_len < DUML_MIN_FRAME_LEN ||
                p->frame_len > DUML_MAX_FRAME_LEN) {
                ring_consume(p, 1);
                continue;
            }

            p->state = READ_FRAME;
        }

        if (p->state == READ_FRAME) {
            if (p->count < p->frame_len)
                return -1; /* Need more data */

            /* Copy full frame out */
            uint8_t frame[DUML_MAX_FRAME_LEN];
            ring_copy(p, 0, frame, p->frame_len);

            /* Verify CRC16 over entire frame except last 2 bytes */
            uint16_t expected_crc16 = (uint16_t)frame[p->frame_len - 2] |
                                      ((uint16_t)frame[p->frame_len - 1] << 8);
            uint16_t actual_crc16 = duml_crc16(frame, p->frame_len - 2);

            ring_consume(p, p->frame_len);
            p->state = SCAN_SOF;

            if (actual_crc16 != expected_crc16)
                continue; /* Bad CRC, skip frame */

            /*
             * DUML v1 frame layout:
             *   [0]     SOF
             *   [1-2]   length(10) + version(6)
             *   [3]     CRC8
             *   [4]     sender(3) + receiver(5)   -- varies by version
             *   [5-6]   sequence number
             *   [7]     cmd type + ack
             *   [8]     encryption + padding
             *   [9]     cmd_set
             *   [10]    cmd_id
             *   [11..]  payload
             *   [-2,-1] CRC16
             *
             * Note: the exact header layout can vary between DUML versions.
             * We search for cmd_set=0x06 at multiple possible offsets.
             */
            int rc_decoded = 0;

            /* Try standard DUML v1 offsets */
            if (p->frame_len >= 13) {
                uint8_t cmd_set = frame[9];
                uint8_t cmd_id  = frame[10];

                if (cmd_set == DUML_CMD_SET_RC && cmd_id == DUML_CMD_RC_PUSH) {
                    size_t payload_len = p->frame_len - 13; /* 11 header + 2 CRC16 */
                    if (payload_len >= RC_PUSH_PAYLOAD_LEN) {
                        rc_state_t state;
                        if (rcm_parse_payload(frame + 11, payload_len, &state) == 0) {
                            p->callback(&state, p->userdata);
                            return 1;
                        }
                    }
                }
            }

            /*
             * DUML v2/v3 may have a slightly different header.
             * Try scanning for the RC cmd_set/cmd_id pair in bytes 8-12.
             */
            if (!rc_decoded && p->frame_len >= 14) {
                for (int off = 8; off <= 12 && off + 2 + RC_PUSH_PAYLOAD_LEN <= (int)p->frame_len - 2; off++) {
                    if (frame[off] == DUML_CMD_SET_RC && frame[off + 1] == DUML_CMD_RC_PUSH) {
                        rc_state_t state;
                        size_t payload_off = off + 2;
                        size_t payload_len = p->frame_len - 2 - payload_off;
                        if (payload_len >= RC_PUSH_PAYLOAD_LEN &&
                            rcm_parse_payload(frame + payload_off, payload_len, &state) == 0) {
                            p->callback(&state, p->userdata);
                            return 1;
                        }
                    }
                }
            }

            return 0; /* Valid frame but not RC push */
        }
    }
    return -1;
}

int rcm_feed(rcm_parser_t *p, const uint8_t *data, size_t len) {
    if (!p || !data) return 0;

    int decoded = 0;
    for (size_t i = 0; i < len; i++) {
        ring_push(p, data[i]);

        int ret;
        while ((ret = try_decode_frame(p)) >= 0) {
            decoded += ret;
        }
    }
    return decoded;
}

/* ---------- Utility ---------- */

const char *rcm_flight_mode_str(rc_flight_mode_t mode) {
    switch (mode) {
        case RC_MODE_SPORT:   return "Sport";
        case RC_MODE_NORMAL:  return "Normal";
        case RC_MODE_TRIPOD:  return "Tripod";
        default:              return "Unknown";
    }
}
