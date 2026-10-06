/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Remembers the last state of EchoRoute (running / stopped / waiting for Shizuku / cleanup pending)
 * so the app can resume or finish cleanup after Shizuku dies, the app is killed, or the phone reboots.
 */
final class EchoState {
    private static final String PREFS = "echoroute";
    private static final String KEY_DIRTY = "effects_may_be_active";
    private static final String KEY_STATE = "last_state";
    private static final String KEY_WHY = "last_state_why";
    private static final String KEY_TIME = "last_state_time";

    private EchoState() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True while EchoRoute registrations may still exist in the audio server and must be removed. */
    static boolean isDirty(Context c) {
        return p(c).getBoolean(KEY_DIRTY, false);
    }

    static void setDirty(Context c, boolean dirty) {
        p(c).edit().putBoolean(KEY_DIRTY, dirty).apply();
    }

    static void save(Context c, String state, String why) {
        p(c).edit().putString(KEY_STATE, state).putString(KEY_WHY, why)
                .putLong(KEY_TIME, System.currentTimeMillis()).apply();
        EchoAppLog.line(c, "STATE_SAVED state=" + state + " why=" + why);
    }

    static String describe(Context c) {
        SharedPreferences sp = p(c);
        String state = sp.getString(KEY_STATE, "none");
        long t = sp.getLong(KEY_TIME, 0L);
        String when = t == 0L ? "never" : new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date(t));
        return state + " (" + sp.getString("control_mode", "automatic") + " mode, "
                + (sp.getBoolean("enabled", false) ? "enabled" : "disabled")
                + (isDirty(c) ? ", cleanup pending" : "") + ") saved " + when;
    }
}
