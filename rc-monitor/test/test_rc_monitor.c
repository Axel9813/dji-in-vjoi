/*
 * test_rc_monitor.c - Unit tests for the RC monitor payload parser
 *
 * Build and run:
 *   cd rc-monitor && mkdir build && cd build
 *   cmake .. && make
 *   ./test_rc_monitor
 */

#include <stdio.h>
#include <string.h>
#include <assert.h>
#include "rc_monitor.h"

#define TEST(name) static void name(void)
#define RUN(name) do { printf("  %-40s", #name); name(); printf("OK\n"); } while(0)

/* ---- Payload parser tests ---- */

TEST(test_all_zeros) {
    uint8_t payload[17] = {0};
    rc_state_t s;
    assert(rcm_parse_payload(payload, 17, &s) == 0);

    /* All buttons off */
    assert(!s.pause && !s.gohome && !s.shutter && !s.record);
    assert(!s.custom1 && !s.custom2 && !s.custom3);
    assert(!s.five_d.up && !s.five_d.down && !s.five_d.left &&
           !s.five_d.right && !s.five_d.center);

    /* Flight mode 0 = Sport */
    assert(s.flight_mode == RC_MODE_SPORT);

    /* Sticks at 0x0000 => -0x400 = -1024 */
    assert(s.stick_right.horizontal == -1024);
    assert(s.stick_right.vertical == -1024);
    assert(s.stick_left.vertical == -1024);
    assert(s.stick_left.horizontal == -1024);
    assert(s.left_wheel == -1024);
    assert(s.right_wheel == -1024);
    assert(s.right_wheel_delta == 0);
}

TEST(test_sticks_centered) {
    /* Sticks at center value: 0x0400 = 1024 */
    uint8_t payload[17] = {0};
    /* Right H at bytes 5-6 */
    payload[5] = 0x00; payload[6] = 0x04;
    /* Right V at bytes 7-8 */
    payload[7] = 0x00; payload[8] = 0x04;
    /* Left V at bytes 9-10 */
    payload[9] = 0x00; payload[10] = 0x04;
    /* Left H at bytes 11-12 */
    payload[11] = 0x00; payload[12] = 0x04;
    /* Left wheel at bytes 13-14 */
    payload[13] = 0x00; payload[14] = 0x04;
    /* Right wheel at bytes 15-16 */
    payload[15] = 0x00; payload[16] = 0x04;

    rc_state_t s;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.stick_right.horizontal == 0);
    assert(s.stick_right.vertical == 0);
    assert(s.stick_left.vertical == 0);
    assert(s.stick_left.horizontal == 0);
    assert(s.left_wheel == 0);
    assert(s.right_wheel == 0);
}

TEST(test_sticks_full_deflection) {
    uint8_t payload[17] = {0};
    /* Max value: 0x0694 = 1684 => centered: 1684 - 1024 = 660 */
    payload[5] = 0x94; payload[6] = 0x06;   /* right H = +660 */
    /* Min value: 0x016C = 364 => centered: 364 - 1024 = -660 */
    payload[7] = 0x6C; payload[8] = 0x01;   /* right V = -660 */
    /* Center values for the rest */
    payload[9] = 0x00; payload[10] = 0x04;
    payload[11] = 0x00; payload[12] = 0x04;
    payload[13] = 0x00; payload[14] = 0x04;
    payload[15] = 0x00; payload[16] = 0x04;

    rc_state_t s;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.stick_right.horizontal == 660);
    assert(s.stick_right.vertical == -660);
}

TEST(test_buttons_individual) {
    rc_state_t s;
    uint8_t payload[17] = {0};
    /* Center sticks to avoid noise */
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }

    /* Pause: byte0 bit4 */
    payload[0] = 0x10;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.pause && !s.gohome && !s.shutter);
    payload[0] = 0;

    /* GoHome: byte0 bit5 */
    payload[0] = 0x20;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(!s.pause && s.gohome && !s.shutter);
    payload[0] = 0;

    /* Shutter: byte0 bit6 */
    payload[0] = 0x40;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.shutter && !s.record);
    payload[0] = 0;

    /* Record: byte1 bit0 */
    payload[1] = 0x01;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.record && !s.shutter);
    payload[1] = 0;

    /* Custom1: byte2 bit2 */
    payload[2] = 0x04;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.custom1 && !s.custom2 && !s.custom3);
    payload[2] = 0;

    /* Custom2: byte2 bit3 */
    payload[2] = 0x08;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(!s.custom1 && s.custom2 && !s.custom3);
    payload[2] = 0;

    /* Custom3: byte2 bit4 */
    payload[2] = 0x10;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(!s.custom1 && !s.custom2 && s.custom3);
    payload[2] = 0;
}

TEST(test_five_d_joystick) {
    rc_state_t s;
    uint8_t payload[17] = {0};
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }

    /* 5D Right: byte1 bit3 */
    payload[1] = 0x08;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.right && !s.five_d.up && !s.five_d.down &&
           !s.five_d.left && !s.five_d.center);

    /* 5D Up: byte1 bit4 */
    payload[1] = 0x10;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.up && !s.five_d.right);

    /* 5D Down: byte1 bit5 */
    payload[1] = 0x20;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.down);

    /* 5D Left: byte1 bit6 */
    payload[1] = 0x40;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.left);

    /* 5D Center: byte1 bit7 */
    payload[1] = 0x80;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.center);

    /* All 5D pressed */
    payload[1] = 0xF8;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.five_d.up && s.five_d.down && s.five_d.left &&
           s.five_d.right && s.five_d.center);
}

TEST(test_flight_mode_switch) {
    rc_state_t s;
    uint8_t payload[17] = {0};
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }

    /* Mode 0 = Sport */
    payload[2] = 0x00;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.flight_mode == RC_MODE_SPORT);

    /* Mode 1 = Normal */
    payload[2] = 0x01;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.flight_mode == RC_MODE_NORMAL);

    /* Mode 2 = Tripod */
    payload[2] = 0x02;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.flight_mode == RC_MODE_TRIPOD);

    /* Mode with custom button bits set too: byte2 = 0x07 => mode=3, custom1=1 */
    payload[2] = 0x07;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.flight_mode == RC_MODE_UNKNOWN);
    assert(s.custom1);
}

TEST(test_right_new_wheel) {
    rc_state_t s;
    uint8_t payload[17] = {0};
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }

    /* Magnitude 10, direction positive (bit6 of byte4 = 1) */
    /* byte4: bits[5:1] = 10 = 0b01010, bit6 = 1 => byte4 = 0b0_1_01010_0 = 0x54 */
    payload[4] = (10 << 1) | (1 << 6);  /* 0x14 | 0x40 = 0x54 */
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.right_wheel_delta == 10);

    /* Magnitude 10, direction negative (bit6 = 0) */
    payload[4] = (10 << 1);  /* 0x14 */
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.right_wheel_delta == -10);

    /* Magnitude 31 (max), positive */
    payload[4] = (31 << 1) | (1 << 6);  /* 0x3E | 0x40 = 0x7E */
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.right_wheel_delta == 31);

    /* Zero magnitude */
    payload[4] = 0;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.right_wheel_delta == 0);
}

TEST(test_all_buttons_pressed) {
    uint8_t payload[17] = {0};
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }

    /* Set all button bits */
    payload[0] = 0x70;  /* pause + gohome + shutter */
    payload[1] = 0xF9;  /* record + all 5D */
    payload[2] = 0x1D;  /* mode=1(Normal) + custom1 + custom2 + custom3 */

    rc_state_t s;
    assert(rcm_parse_payload(payload, 17, &s) == 0);
    assert(s.pause && s.gohome && s.shutter && s.record);
    assert(s.custom1 && s.custom2 && s.custom3);
    assert(s.five_d.up && s.five_d.down && s.five_d.left &&
           s.five_d.right && s.five_d.center);
    assert(s.flight_mode == RC_MODE_NORMAL);
}

TEST(test_payload_too_short) {
    uint8_t payload[16] = {0};
    rc_state_t s;
    assert(rcm_parse_payload(payload, 16, &s) == -1);
    assert(rcm_parse_payload(payload, 0, &s) == -1);
    assert(rcm_parse_payload(NULL, 17, &s) == -1);
}

TEST(test_payload_longer_ok) {
    /* Payload longer than 17 bytes should still work */
    uint8_t payload[32] = {0};
    payload[0] = 0x40; /* shutter */
    for (int i = 5; i < 17; i += 2) { payload[i] = 0x00; payload[i+1] = 0x04; }
    rc_state_t s;
    assert(rcm_parse_payload(payload, 32, &s) == 0);
    assert(s.shutter);
}

/* ---- DUML parser tests ---- */

static int g_callback_count;
static rc_state_t g_last_state;

static void test_callback(const rc_state_t *state, void *userdata) {
    g_callback_count++;
    g_last_state = *state;
    (void)userdata;
}

TEST(test_parser_create_destroy) {
    rcm_parser_t *p = rcm_create(test_callback, NULL);
    assert(p != NULL);
    rcm_destroy(p);

    /* NULL callback should fail */
    assert(rcm_create(NULL, NULL) == NULL);
}

TEST(test_parser_valid_frame) {
    /*
     * Build a minimal DUML v1 frame:
     *   [0]    = 0x55 (SOF)
     *   [1-2]  = length(10 bits) | version(6 bits)
     *   [3]    = CRC8
     *   [4-8]  = routing/seq/type (filler)
     *   [9]    = cmd_set = 0x06
     *   [10]   = cmd_id  = 0x05
     *   [11-27] = 17 bytes payload
     *   [28-29] = CRC16
     *
     * Total length = 30
     */
    uint8_t frame[30];
    memset(frame, 0, sizeof(frame));

    uint16_t frame_len = 30;
    frame[0] = 0x55;
    /* LenVer: len in low 10 bits, version=1 in upper 6 bits */
    uint16_t len_ver = (frame_len & 0x03FF) | (1 << 10);
    frame[1] = len_ver & 0xFF;
    frame[2] = (len_ver >> 8) & 0xFF;

    /* CRC8 of first 3 bytes - we need to compute this correctly */
    /* Using the same CRC8 function (seed 0x77) */
    {
        uint8_t crc = 0x77;
        uint8_t table[256] = {
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
        for (int i = 0; i < 3; i++)
            crc = table[(crc ^ frame[i]) & 0xFF];
        frame[3] = crc;
    }

    /* cmd_set and cmd_id */
    frame[9]  = 0x06;  /* RC */
    frame[10] = 0x05;  /* Push */

    /* Payload: shutter pressed + sticks centered */
    frame[11] = 0x40;  /* byte0: shutter bit6 */
    /* Stick values at center (0x0400) */
    frame[16] = 0x00; frame[17] = 0x04; /* right H */
    frame[18] = 0x00; frame[19] = 0x04; /* right V */
    frame[20] = 0x00; frame[21] = 0x04; /* left V */
    frame[22] = 0x00; frame[23] = 0x04; /* left H */
    frame[24] = 0x00; frame[25] = 0x04; /* left wheel */
    frame[26] = 0x00; frame[27] = 0x04; /* right wheel */

    /* CRC16 of bytes 0..27 */
    {
        uint16_t crc = 0x3692;
        uint16_t table[256] = {
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
        for (int i = 0; i < 28; i++)
            crc = table[(crc ^ frame[i]) & 0xFF] ^ (crc >> 8);
        frame[28] = crc & 0xFF;
        frame[29] = (crc >> 8) & 0xFF;
    }

    g_callback_count = 0;
    memset(&g_last_state, 0, sizeof(g_last_state));

    rcm_parser_t *p = rcm_create(test_callback, NULL);
    int n = rcm_feed(p, frame, sizeof(frame));
    assert(n == 1);
    assert(g_callback_count == 1);
    assert(g_last_state.shutter == true);
    assert(g_last_state.stick_right.horizontal == 0);
    assert(g_last_state.stick_right.vertical == 0);
    rcm_destroy(p);
}

TEST(test_parser_garbage_prefix) {
    /* Parser should skip non-0x55 bytes */
    rcm_parser_t *p = rcm_create(test_callback, NULL);
    g_callback_count = 0;

    uint8_t garbage[] = {0x00, 0xFF, 0x12, 0x34, 0xAB};
    rcm_feed(p, garbage, sizeof(garbage));
    assert(g_callback_count == 0);

    rcm_destroy(p);
}

TEST(test_flight_mode_str) {
    assert(strcmp(rcm_flight_mode_str(RC_MODE_SPORT), "Sport") == 0);
    assert(strcmp(rcm_flight_mode_str(RC_MODE_NORMAL), "Normal") == 0);
    assert(strcmp(rcm_flight_mode_str(RC_MODE_TRIPOD), "Tripod") == 0);
    assert(strcmp(rcm_flight_mode_str(RC_MODE_UNKNOWN), "Unknown") == 0);
}

/* ---- Main ---- */

int main(void) {
    printf("RC Monitor Tests:\n");

    /* Payload parser */
    RUN(test_all_zeros);
    RUN(test_sticks_centered);
    RUN(test_sticks_full_deflection);
    RUN(test_buttons_individual);
    RUN(test_five_d_joystick);
    RUN(test_flight_mode_switch);
    RUN(test_right_new_wheel);
    RUN(test_all_buttons_pressed);
    RUN(test_payload_too_short);
    RUN(test_payload_longer_ok);

    /* DUML parser */
    RUN(test_parser_create_destroy);
    RUN(test_parser_valid_frame);
    RUN(test_parser_garbage_prefix);

    /* Utility */
    RUN(test_flight_mode_str);

    printf("\nAll tests passed.\n");
    return 0;
}
