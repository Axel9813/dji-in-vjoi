package com.example.dji_to_vjoy;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.InputStream;

/**
 * Probe DJI Unix domain sockets to discover additional button data
 * that isn't exposed via the USB virtual joystick.
 *
 * Known sockets (all world-readable / srw-rw-rw-):
 *   /dev/socket/dji_fpv     (root:root)
 *   /dev/socket/fpv_sock    (root:system)
 *   /dev/socket/fpv_sock1   (root:system)
 *   /dev/socket/fpv_sock2   (root:system)
 */
public class DjiSocketProbe {

    private static final String TAG = "DjiSocketProbe";

    private static final String[] SOCKET_PATHS = {
        "/dev/socket/dji_fpv",
        "/dev/socket/fpv_sock",
        "/dev/socket/fpv_sock1",
        "/dev/socket/fpv_sock2",
    };

    /**
     * Try connecting to each known DJI socket and log what we receive.
     * Run this in a background thread — it blocks for up to 3 seconds per socket.
     */
    public static void probeAll() {
        new Thread(() -> {
            for (String path : SOCKET_PATHS) {
                probeSocket(path);
            }
            Log.i(TAG, "Socket probe complete");
        }, "dji-socket-probe").start();
    }

    private static void probeSocket(String path) {
        Log.i(TAG, "Probing socket: " + path);
        LocalSocket sock = new LocalSocket();
        try {
            // Try filesystem namespace (for /dev/socket/* paths)
            LocalSocketAddress addr = new LocalSocketAddress(path,
                    LocalSocketAddress.Namespace.FILESYSTEM);
            sock.connect(addr);
            sock.setSoTimeout(3000);

            InputStream in = sock.getInputStream();
            byte[] buf = new byte[1024];

            // Try to read up to 3 chunks
            for (int attempt = 0; attempt < 3; attempt++) {
                int len = in.read(buf);
                if (len <= 0) {
                    Log.i(TAG, "  " + path + ": read returned " + len + " (attempt " + attempt + ")");
                    break;
                }
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(len, 128); i++) {
                    hex.append(String.format("%02X ", buf[i] & 0xFF));
                }
                // Also try to show as ASCII
                StringBuilder ascii = new StringBuilder();
                for (int i = 0; i < Math.min(len, 128); i++) {
                    int b = buf[i] & 0xFF;
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                }
                Log.w(TAG, "  " + path + " [" + len + "B]: " + hex.toString().trim());
                Log.w(TAG, "  " + path + " [ASCII]: " + ascii.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "  " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }
}
