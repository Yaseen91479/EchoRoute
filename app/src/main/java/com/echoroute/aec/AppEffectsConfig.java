/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Per-app effect selection and "ignore in automatic" list, stored as plain strings in prefs. */
final class AppEffectsConfig {
    static final String KEY_PER_APP = "per_app_effects";      // "pkg=AEC+NS;pkg2=AGC"
    static final String KEY_IGNORED = "ignored_auto_apps";    // "pkg;pkg2"

    private AppEffectsConfig() {}

    static Map<String, Set<String>> loadPerApp(SharedPreferences p) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        String raw = p.getString(KEY_PER_APP, "");
        for (String item : raw.split("[;\\n]+")) {
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String pkg = item.substring(0, eq).trim();
            Set<String> set = new TreeSet<>();
            for (String e : item.substring(eq + 1).split("[+,]")) {
                String n = e.trim().toUpperCase(java.util.Locale.ROOT);
                if (n.equals("AGC2")) n = "AGC";
                if (n.equals("AEC") || n.equals("NS") || n.equals("AGC")) set.add(n);
            }
            if (!pkg.isEmpty() && !set.isEmpty()) map.put(pkg, set);
        }
        return map;
    }

    static void savePerApp(SharedPreferences p, Map<String, Set<String>> map) {
        p.edit().putString(KEY_PER_APP, serialize(map)).apply();
    }

    static Set<String> loadIgnored(SharedPreferences p) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : p.getString(KEY_IGNORED, "").split("[;,\\n]+")) {
            if (!s.trim().isEmpty()) set.add(s.trim());
        }
        return set;
    }

    static void saveIgnored(SharedPreferences p, Set<String> set) {
        p.edit().putString(KEY_IGNORED, ignoredString(set)).apply();
    }

    static String ignoredString(SharedPreferences p) {
        return ignoredString(loadIgnored(p));
    }

    private static String ignoredString(Set<String> set) {
        return String.join(";", set);
    }

    static String serialize(Map<String, Set<String>> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : map.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(String.join("+", e.getValue()));
        }
        return sb.toString();
    }

    /** Config sent to the engine: exactly the saved per-app selection. */
    static String effectiveConfig(SharedPreferences p) {
        return serialize(loadPerApp(p));
    }
}
