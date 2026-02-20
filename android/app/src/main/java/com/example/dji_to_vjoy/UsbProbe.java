package com.example.dji_to_vjoy;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Comprehensive USB probe for the DJI Virtual Joystick device (VID 0x2CA3, PID 0x1501).
 *
 * Probes:
 * 1. Vendor-specific USB control transfers (GET/SET)
 * 2. DUML frame construction and sending via EP OUT
 * 3. XInput-style init sequences via EP OUT
 * 4. Sysfs/procfs exploration for DJI-specific nodes
 *
 * All results are logged at WARN level with tag "UsbProbe" for easy filtering.
 */
public class UsbProbe {
    private static final String TAG = "UsbProbe";

    // ─── DUML CRC8 table (seed 0x77) ───────────────────────────────
    private static final int[] CRC8_TABLE = {
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

    // ─── DUML CRC16 table (seed 0x3692) ─────────────────────────────
    private static final int[] CRC16_TABLE = {
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

    private static int dumlCrc8(byte[] data, int offset, int len) {
        int crc = 0x77;
        for (int i = 0; i < len; i++)
            crc = CRC8_TABLE[(crc ^ (data[offset + i] & 0xFF)) & 0xFF];
        return crc & 0xFF;
    }

    private static int dumlCrc16(byte[] data, int offset, int len) {
        int crc = 0x3692;
        for (int i = 0; i < len; i++)
            crc = CRC16_TABLE[(crc ^ (data[offset + i] & 0xFF)) & 0xFF] ^ (crc >> 8);
        return crc & 0xFFFF;
    }

    /**
     * Build a DUML v1 frame.
     *
     * Frame format (from RC_MONITORING_SPEC.md):
     *   [0]     SOF = 0x55
     *   [1-2]   Length(10-bit) | Version(6-bit) = len | (version << 10)
     *   [3]     CRC8 of bytes 0-2
     *   [4]     Sender: type(5-bit) | index(3-bit)
     *   [5]     Receiver: type(5-bit) | index(3-bit)
     *   [6-7]   Sequence Number (LE)
     *   [8]     pack_type(1-bit) | ack_type(2-bit) | encrypt(3-bit) | pad(2-bit)
     *   [9]     cmd_set
     *   [10]    cmd_id
     *   [11..N-2]  Payload
     *   [N-1..N]   CRC16 of entire frame
     */
    private static byte[] buildDumlFrame(int senderType, int senderIdx,
                                          int recvType, int recvIdx,
                                          int seqNum, int packType, int ackType,
                                          int cmdSet, int cmdId, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        int frameLen = 11 + payloadLen + 2; // header(11) + payload + crc16(2)

        byte[] frame = new byte[frameLen];

        // SOF
        frame[0] = 0x55;

        // Length (10-bit) + Version (6-bit, typically 1)
        int version = 1;
        int lenVer = (frameLen & 0x3FF) | ((version & 0x3F) << 10);
        frame[1] = (byte) (lenVer & 0xFF);
        frame[2] = (byte) ((lenVer >> 8) & 0xFF);

        // CRC8 of bytes 0-2
        frame[3] = (byte) dumlCrc8(frame, 0, 3);

        // Sender: type(5) | index(3)
        frame[4] = (byte) (((senderType & 0x1F) << 3) | (senderIdx & 0x07));

        // Receiver: type(5) | index(3)
        frame[5] = (byte) (((recvType & 0x1F) << 3) | (recvIdx & 0x07));

        // Sequence number (LE)
        frame[6] = (byte) (seqNum & 0xFF);
        frame[7] = (byte) ((seqNum >> 8) & 0xFF);

        // pack_type(1) | ack_type(2) | encrypt(3) | pad(2)
        // For a request: pack_type=0 (request), ack_type=1 (ack requested),
        // encrypt=0 (none), pad=0
        frame[8] = (byte) (((packType & 0x01) << 7) | ((ackType & 0x03) << 5));

        // cmd_set and cmd_id
        frame[9] = (byte) (cmdSet & 0xFF);
        frame[10] = (byte) (cmdId & 0xFF);

        // Payload
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 11, payloadLen);
        }

        // CRC16 of entire frame (excluding last 2 bytes)
        int crc16 = dumlCrc16(frame, 0, frameLen - 2);
        frame[frameLen - 2] = (byte) (crc16 & 0xFF);
        frame[frameLen - 1] = (byte) ((crc16 >> 8) & 0xFF);

        return frame;
    }

    private static String hexDump(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * Run all USB probes on the given open connection.
     * This should be called from a background thread after the interface is claimed.
     */
    public static void probeAll(UsbDeviceConnection conn, UsbInterface intf,
                                 UsbEndpoint epIn, UsbDevice device) {
        Log.w(TAG, "═══════════════════════════════════════════════════════");
        Log.w(TAG, "Starting comprehensive USB probe on " +
                String.format("VID:%04X PID:%04X", device.getVendorId(), device.getProductId()));
        Log.w(TAG, "═══════════════════════════════════════════════════════");

        // Find the OUT endpoint
        UsbEndpoint epOut = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                epOut = ep;
                Log.w(TAG, "Found OUT endpoint: addr=0x" +
                        Integer.toHexString(ep.getAddress()) +
                        " type=" + ep.getType() + " maxPkt=" + ep.getMaxPacketSize());
                break;
            }
        }

        // Phase 1: USB control transfers
        probeControlTransfers(conn, device);

        // Phase 2: DUML frames over OUT endpoint
        if (epOut != null) {
            probeDumlSubscribe(conn, epOut, epIn);
        } else {
            Log.w(TAG, "No OUT endpoint found — skipping DUML and XInput probes");
        }

        // Phase 3: XInput-style init via OUT endpoint
        if (epOut != null) {
            probeXInputInit(conn, epOut, epIn);
        }

        // Phase 4: Sysfs/procfs exploration
        probeSysfs();

        Log.w(TAG, "═══════════════════════════════════════════════════════");
        Log.w(TAG, "USB probe complete");
        Log.w(TAG, "═══════════════════════════════════════════════════════");
    }

    // ─── Phase 1: USB Control Transfers ────────────────────────────

    private static void probeControlTransfers(UsbDeviceConnection conn, UsbDevice device) {
        Log.w(TAG, "── Phase 1: USB Control Transfers ──");

        // Try standard GET_DESCRIPTOR requests
        byte[] buf = new byte[256];

        // 1a. Get device descriptor (should always work)
        int ret = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD | 0x00, // device
                0x06, // GET_DESCRIPTOR
                0x0100, // Device descriptor, index 0
                0, buf, buf.length, 1000);
        Log.w(TAG, "GET_DESCRIPTOR(Device): ret=" + ret +
                (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 18)) : ""));

        // 1b. Get HID report descriptor (bmRequestType = interface)
        ret = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD | 0x01, // interface
                0x06, // GET_DESCRIPTOR
                0x2200, // HID Report descriptor
                0, buf, buf.length, 1000);
        Log.w(TAG, "GET_DESCRIPTOR(HID Report): ret=" + ret +
                (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 64)) : ""));

        // 1c. HID GET_REPORT (class-specific, input report type=1, report ID=0)
        for (int reportId = 0; reportId <= 5; reportId++) {
            ret = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01, // interface
                    0x01, // HID GET_REPORT
                    (0x01 << 8) | reportId, // Input report, report ID
                    0, buf, buf.length, 500);
            Log.w(TAG, "HID GET_REPORT(Input, ID=" + reportId + "): ret=" + ret +
                    (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 64)) : ""));
        }

        // 1d. HID GET_REPORT (feature report type=3)
        for (int reportId = 0; reportId <= 5; reportId++) {
            ret = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01, // HID GET_REPORT
                    (0x03 << 8) | reportId, // Feature report, report ID
                    0, buf, buf.length, 500);
            Log.w(TAG, "HID GET_REPORT(Feature, ID=" + reportId + "): ret=" + ret +
                    (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 64)) : ""));
        }

        // 1e. Vendor-specific control transfer (IN, device)
        for (int request = 0; request <= 3; request++) {
            ret = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR | 0x00, // device
                    request, 0, 0, buf, buf.length, 500);
            Log.w(TAG, "VENDOR_IN(device, req=" + request + "): ret=" + ret +
                    (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 64)) : ""));
        }

        // 1f. Vendor-specific control transfer (IN, interface)
        for (int request = 0; request <= 3; request++) {
            ret = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR | 0x01, // interface
                    request, 0, 0, buf, buf.length, 500);
            Log.w(TAG, "VENDOR_IN(iface, req=" + request + "): ret=" + ret +
                    (ret > 0 ? " data=" + hexDump(buf, Math.min(ret, 64)) : ""));
        }

        // 1g. Class-specific GET request (might trigger mode-switch)
        ret = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x03, // HID GET_PROTOCOL
                0, 0, buf, 1, 500);
        Log.w(TAG, "HID_GET_PROTOCOL: ret=" + ret +
                (ret > 0 ? " proto=" + (buf[0] & 0xFF) : ""));

        // 1h. Try SET_IDLE (HID class, interface)
        ret = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x0A, // SET_IDLE
                0, 0, null, 0, 500);
        Log.w(TAG, "HID_SET_IDLE: ret=" + ret);

        // 1i. Check string descriptors for clues
        String mfg = device.getManufacturerName();
        String prod = device.getProductName();
        String serial = device.getSerialNumber();
        Log.w(TAG, "Device strings: mfg=" + mfg + " prod=" + prod + " serial=" + serial);

        // 1j. Read raw string descriptors
        for (int idx = 1; idx <= 5; idx++) {
            ret = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD | 0x00,
                    0x06, // GET_DESCRIPTOR
                    0x0300 | idx, // String descriptor, index
                    0x0409, // English
                    buf, buf.length, 500);
            if (ret > 2) {
                String str = new String(buf, 2, ret - 2, java.nio.charset.StandardCharsets.UTF_16LE);
                Log.w(TAG, "String desc[" + idx + "]: \"" + str + "\"");
            } else {
                Log.w(TAG, "String desc[" + idx + "]: ret=" + ret);
            }
        }
    }

    // ─── Phase 2: DUML Subscription via OUT Endpoint ───────────────

    private static void probeDumlSubscribe(UsbDeviceConnection conn,
                                            UsbEndpoint epOut, UsbEndpoint epIn) {
        Log.w(TAG, "── Phase 2: DUML Protocol Probing ──");

        byte[] readBuf = new byte[64];

        // Read and log 3 baseline packets first
        Log.w(TAG, "Reading 3 baseline packets...");
        for (int i = 0; i < 3; i++) {
            int len = conn.bulkTransfer(epIn, readBuf, readBuf.length, 200);
            if (len > 0) {
                Log.w(TAG, "  Baseline[" + i + "]: " + hexDump(readBuf, len));
            } else {
                Log.w(TAG, "  Baseline[" + i + "]: timeout/error (ret=" + len + ")");
            }
        }

        // 2a. Send DUML ping (cmd_set=0x00, cmd_id=0x00, no payload)
        // Sender: type=10 (App), index=0; Receiver: type=6 (RC), index=0
        sendDumlAndRead(conn, epOut, epIn, "Ping(App->RC)",
                10, 0, 6, 0, 1, 0x00, 0x00, null);

        // 2b. Send DUML request for RC push data (cmd_set=0x06, cmd_id=0x05)
        sendDumlAndRead(conn, epOut, epIn, "RC Push Request(App->RC)",
                10, 0, 6, 0, 2, 0x06, 0x05, null);

        // 2c. Try cmd_set=0x06 with various cmd_ids that might be related
        // cmd_id=0x01 might be "get RC info"
        sendDumlAndRead(conn, epOut, epIn, "RC GetInfo(06,01)",
                10, 0, 6, 0, 3, 0x06, 0x01, null);

        // 2d. cmd_set=0x06, cmd_id=0x50 — RC gimbal control or similar
        sendDumlAndRead(conn, epOut, epIn, "RC(06,50)",
                10, 0, 6, 0, 4, 0x06, 0x50, null);

        // 2e. Try sending to different receiver types
        // Receiver type 0 = "Any", type 3 = "Camera", type 9 = "DJI_Internal"
        sendDumlAndRead(conn, epOut, epIn, "RC Push(App->Any)",
                10, 0, 0, 0, 5, 0x06, 0x05, null);

        // 2f. General ping to type 0
        sendDumlAndRead(conn, epOut, epIn, "Ping(App->Any)",
                10, 0, 0, 0, 6, 0x00, 0x00, null);

        // 2g. Try a device version query (cmd_set=0x00, cmd_id=0x01)
        sendDumlAndRead(conn, epOut, epIn, "GetVersion(App->Any)",
                10, 0, 0, 0, 7, 0x00, 0x01, null);

        // 2h. Heartbeat/keepalive (cmd_set=0x00, cmd_id=0x00, pack_type=1=ack)
        // Build manually with pack_type=1
        byte[] ackPing = buildDumlFrame(10, 0, 0, 0, 8, 1, 0, 0x00, 0x00, null);
        Log.w(TAG, "Sending AckPing: " + hexDump(ackPing, ackPing.length));
        int sent = conn.bulkTransfer(epOut, ackPing, ackPing.length, 500);
        Log.w(TAG, "  AckPing sent: ret=" + sent);
        readMultiple(conn, epIn, "AckPing response", 5);

        // Read several packets to see if anything changed
        Log.w(TAG, "Reading 5 post-DUML packets...");
        readMultiple(conn, epIn, "Post-DUML", 5);
    }

    private static void sendDumlAndRead(UsbDeviceConnection conn,
                                         UsbEndpoint epOut, UsbEndpoint epIn,
                                         String label, int sndType, int sndIdx,
                                         int rcvType, int rcvIdx, int seq,
                                         int cmdSet, int cmdId, byte[] payload) {
        byte[] frame = buildDumlFrame(sndType, sndIdx, rcvType, rcvIdx,
                seq, 0, 1, cmdSet, cmdId, payload);
        Log.w(TAG, "Sending " + label + ": " + hexDump(frame, frame.length));

        int sent = conn.bulkTransfer(epOut, frame, frame.length, 500);
        Log.w(TAG, "  " + label + " sent: ret=" + sent);

        // Read responses
        readMultiple(conn, epIn, label, 3);
    }

    private static void readMultiple(UsbDeviceConnection conn, UsbEndpoint epIn,
                                      String label, int count) {
        byte[] buf = new byte[64];
        for (int i = 0; i < count; i++) {
            int len = conn.bulkTransfer(epIn, buf, buf.length, 200);
            if (len > 0) {
                // Check if this looks like a DUML frame (starts with 0x55)
                boolean isDuml = (buf[0] & 0xFF) == 0x55;
                Log.w(TAG, "  " + label + " read[" + i + "]: len=" + len +
                        (isDuml ? " [DUML!]" : "") + " " + hexDump(buf, len));
            }
        }
    }

    // ─── Phase 3: XInput-style Init ────────────────────────────────

    private static void probeXInputInit(UsbDeviceConnection conn,
                                         UsbEndpoint epOut, UsbEndpoint epIn) {
        Log.w(TAG, "── Phase 3: XInput-Style Init ──");

        // The xpad Linux driver sends these init packets for Xbox 360 controllers.
        // Since DJI's device is detected by xpad, it might respond to these.

        // 3a. Xbox 360 LED command (sets LED pattern)
        // xpad format: [0x01, 0x03, led_value]
        byte[] ledCmd = new byte[] { 0x01, 0x03, 0x06 }; // LED pattern 6
        Log.w(TAG, "Sending Xbox LED cmd: " + hexDump(ledCmd, ledCmd.length));
        int sent = conn.bulkTransfer(epOut, ledCmd, ledCmd.length, 500);
        Log.w(TAG, "  Xbox LED sent: ret=" + sent);
        readMultiple(conn, epIn, "Xbox LED response", 3);

        // 3b. Xbox rumble packet (to see if device responds)
        // Format: [0x00, 0x08, 0x00, left_trigger, right_trigger, left_motor, right_motor, 0x00]
        byte[] rumble = new byte[] { 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        Log.w(TAG, "Sending Xbox rumble: " + hexDump(rumble, rumble.length));
        sent = conn.bulkTransfer(epOut, rumble, rumble.length, 500);
        Log.w(TAG, "  Xbox rumble sent: ret=" + sent);
        readMultiple(conn, epIn, "Xbox rumble response", 3);

        // 3c. Xbox 360 info request
        byte[] infoReq = new byte[] { 0x08, 0x00 };
        Log.w(TAG, "Sending Xbox info: " + hexDump(infoReq, infoReq.length));
        sent = conn.bulkTransfer(epOut, infoReq, infoReq.length, 500);
        Log.w(TAG, "  Xbox info sent: ret=" + sent);
        readMultiple(conn, epIn, "Xbox info response", 3);

        // 3d. Xbox One init sequence (first packet)
        byte[] xoneInit = new byte[] { 0x05, 0x20, 0x00, 0x01, 0x00 };
        Log.w(TAG, "Sending XOne init: " + hexDump(xoneInit, xoneInit.length));
        sent = conn.bulkTransfer(epOut, xoneInit, xoneInit.length, 500);
        Log.w(TAG, "  XOne init sent: ret=" + sent);
        readMultiple(conn, epIn, "XOne init response", 3);

        // 3e. Try sending just the report ID bytes that we see in the IN data
        // Our packets start with 0x02 0x0E — maybe sending 0x02 requests more data
        byte[] reqMore = new byte[] { 0x02, 0x01 }; // request mode 1?
        Log.w(TAG, "Sending mode req (0x02,0x01): " + hexDump(reqMore, reqMore.length));
        sent = conn.bulkTransfer(epOut, reqMore, reqMore.length, 500);
        Log.w(TAG, "  Mode req sent: ret=" + sent);
        readMultiple(conn, epIn, "Mode req response", 3);

        // 3f. Try requesting a different report type
        byte[] reqType2 = new byte[64];
        reqType2[0] = 0x01; // Different report ID
        reqType2[1] = 0x00;
        Log.w(TAG, "Sending report type 1 request");
        sent = conn.bulkTransfer(epOut, reqType2, 2, 500);
        Log.w(TAG, "  Report type 1 sent: ret=" + sent);
        readMultiple(conn, epIn, "Report type 1", 3);

        // 3g. Try sending full 64-byte zero packet (some devices need full-size packets)
        byte[] zeroPacket = new byte[64];
        Log.w(TAG, "Sending 64B zero packet");
        sent = conn.bulkTransfer(epOut, zeroPacket, zeroPacket.length, 500);
        Log.w(TAG, "  Zero packet sent: ret=" + sent);
        readMultiple(conn, epIn, "Zero packet response", 3);
    }

    // ─── Phase 4: Sysfs/Procfs Exploration ─────────────────────────

    private static void probeSysfs() {
        Log.w(TAG, "── Phase 4: Sysfs/Procfs Exploration ──");

        // Look for DJI-specific sysfs/procfs nodes
        String[] paths = {
            "/proc/dji",
            "/proc/dji_link",
            "/proc/dji_fpv",
            "/proc/driver/dji",
            "/sys/class/dji",
            "/sys/devices/platform/dji",
            "/sys/bus/usb/devices/1-1.3",
            "/dev/dji",
            "/dev/socket",
            "/proc/bus/input/devices",
        };

        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) {
                Log.w(TAG, "EXISTS: " + path + " (dir=" + f.isDirectory() + ")");
                if (f.isDirectory()) {
                    File[] children = f.listFiles();
                    if (children != null) {
                        StringBuilder sb = new StringBuilder();
                        for (File c : children) {
                            sb.append(c.getName());
                            if (c.isDirectory()) sb.append("/");
                            sb.append(", ");
                        }
                        Log.w(TAG, "  Contents: " + sb.toString());
                    } else {
                        Log.w(TAG, "  listFiles() returned null (permission denied?)");
                    }
                } else if (f.isFile() && f.canRead()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        int lineCount = 0;
                        while ((line = br.readLine()) != null && lineCount < 50) {
                            sb.append(line).append("\n");
                            lineCount++;
                        }
                        Log.w(TAG, "  Content:\n" + sb.toString().trim());
                    } catch (Exception e) {
                        Log.w(TAG, "  Read error: " + e.getMessage());
                    }
                }
            }
        }

        // Check for the USB device sysfs attributes
        String usbDir = "/sys/bus/usb/devices/1-1.3";
        String[] attrs = {"manufacturer", "product", "serial", "bNumInterfaces",
                          "bConfigurationValue", "bmAttributes", "idVendor", "idProduct"};
        for (String attr : attrs) {
            File f = new File(usbDir, attr);
            if (f.exists() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String val = br.readLine();
                    Log.w(TAG, "sysfs " + attr + " = " + val);
                } catch (Exception e) {
                    Log.w(TAG, "sysfs " + attr + ": " + e.getMessage());
                }
            }
        }

        // Scan /dev/socket for any DJI-related sockets
        File devSocket = new File("/dev/socket");
        if (devSocket.exists() && devSocket.isDirectory()) {
            File[] sockets = devSocket.listFiles();
            if (sockets != null) {
                for (File s : sockets) {
                    String name = s.getName().toLowerCase();
                    if (name.contains("dji") || name.contains("fpv") || name.contains("rc") ||
                        name.contains("joystick") || name.contains("gamepad") ||
                        name.contains("input") || name.contains("hid")) {
                        Log.w(TAG, "Interesting socket: /dev/socket/" + s.getName() +
                                " (readable=" + s.canRead() + " writable=" + s.canWrite() + ")");
                    }
                }
            }
        }

        // Check /proc/bus/input/devices for any DJI-related input devices
        File inputDevices = new File("/proc/bus/input/devices");
        if (inputDevices.exists() && inputDevices.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(inputDevices))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                Log.w(TAG, "/proc/bus/input/devices:\n" + sb.toString().trim());
            } catch (Exception e) {
                Log.w(TAG, "/proc/bus/input/devices: " + e.getMessage());
            }
        }
    }
}
