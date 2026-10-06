/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Small shared app log used by the UI, foreground controller, and Shizuku-side effect monitor. */
final class EchoAppLog {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 512 * 1024;

    private EchoAppLog() {}

    static void line(Context context, String message) {
        if (context == null) return;
        String line = System.currentTimeMillis() + " " + message + "\n";
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), "echoroute_app.log");
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                trimIfNeeded(file);
            } catch (Throwable ignored) {}
        }
    }

    static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try { new File(context.getFilesDir(), "echoroute_app.log").delete(); } catch (Throwable ignored) {}
        }
    }

    static String read(Context context) {
        if (context == null) return "No app log.";
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), "echoroute_app.log");
            if (!file.exists()) return "No app log yet.";
            try (FileInputStream in = new FileInputStream(file)) {
                long length = file.length();
                long skip = Math.max(0L, length - MAX_BYTES);
                if (skip > 0) in.skip(skip);
                byte[] data = new byte[(int) Math.min(length, MAX_BYTES)];
                int total = 0;
                while (total < data.length) {
                    int n = in.read(data, total, data.length - total);
                    if (n < 0) break;
                    total += n;
                }
                return total == 0 ? "No app log yet."
                        : new String(data, 0, total, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                return "Cannot read app log: " + t;
            }
        }
    }

    private static void trimIfNeeded(File file) {
        try {
            if (file.length() <= MAX_BYTES) return;
            byte[] data;
            try (FileInputStream in = new FileInputStream(file)) {
                long skip = Math.max(0L, file.length() - MAX_BYTES / 2L);
                if (skip > 0) in.skip(skip);
                data = new byte[(int) Math.min(file.length(), MAX_BYTES / 2L)];
                int total = 0;
                while (total < data.length) {
                    int n = in.read(data, total, data.length - total);
                    if (n < 0) break;
                    total += n;
                }
                if (total != data.length) {
                    byte[] exact = new byte[total];
                    System.arraycopy(data, 0, exact, 0, total);
                    data = exact;
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write("=== EchoRoute app log trimmed ===\n".getBytes(StandardCharsets.UTF_8));
                out.write(data);
                out.flush();
            }
        } catch (Throwable ignored) {}
    }
}
