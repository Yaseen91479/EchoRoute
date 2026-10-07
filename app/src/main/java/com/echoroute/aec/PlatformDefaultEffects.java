/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.audiofx.AudioEffect;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku-side global microphone-effect controller.
 *
 * EchoRoute does NOT open the microphone and does NOT attach AudioEffect directly
 * to another app's session. Instead it installs Android source-default input effects
 * for the normal microphone source classes. Android AudioPolicy then creates those
 * effects when a new recording input/session is opened for the matching source.
 *
 * The active-recording monitor never owns the target session. It tracks the current
 * Session ID and rotates our source-default registration IDs when the previous
 * microphone session has ended and a new one is detected. No raw PCM is logged.
 */
final class PlatformDefaultEffects {
    private static final String TAG = "EchoRouteAEC";
    private static final String PREFS = "echoroute_platform_effects";
    private static final String PREF_IDS = "active_effect_ids";
    private static final String MAIN_PREFS = "echoroute";
    private static final String PREF_REMOVE_FORCE_CLOSE_OVERRIDE = "remove_force_close_override";

    // Android effect type UUID for Automatic Gain Control V2.
    private static final UUID AGC2_TYPE = UUID.fromString(
            "ae3c653b-be18-4ab8-8938-418f0a7f06ac");

    private static final UUID AEC_TYPE = AudioEffect.EFFECT_TYPE_AEC;
    private static final UUID NS_TYPE = AudioEffect.EFFECT_TYPE_NS;
    private static final String AEC_TYPE_TEXT = "7b491460-8d4d-11e0-bd61-0002a5d5c51b";
    private static final String NS_TYPE_TEXT = "58b4b260-8e06-11e0-aa8e-0002a5d5c51b";
    private static final Pattern EFFECT_ID_PATTERN = Pattern.compile("Effect ID: (\\d+):");
    private static final Pattern TYPE_PATTERN = Pattern.compile("TYPE: ([0-9a-fA-F-]{36})");

    /** Normal app microphone source classes. DEFAULT is normalized to MIC by AudioPolicy. */
    private static final SourceSpec[] NORMAL_MIC_SOURCES = new SourceSpec[]{
            new SourceSpec(1, "MIC"),
            new SourceSpec(5, "CAMCORDER"),
            new SourceSpec(6, "VOICE_RECOGNITION"),
            new SourceSpec(7, "VOICE_COMMUNICATION"),
            new SourceSpec(9, "UNPROCESSED")
    };

    private final Context context;
    private final AudioManager audioManager;
    private final Object lock = new Object();

    private ScheduledExecutorService executor;
    private StateSink sink;
    private boolean running;
    private boolean wantAec;
    private boolean wantNs;
    private boolean wantAgc;
    private boolean automaticMode = true;
    private boolean forceCloseTarget = true;
    private String targetPackage = "";
    // Per-app policy: package -> wanted effects ("AEC","NS","AGC"). Ignored apps get nothing in automatic mode.
    private final Map<String, Set<String>> perApp = new HashMap<>();
    private final Set<String> ignoredAuto = new HashSet<>();
    private final Map<Integer, String> enforcedSessions = new HashMap<>();
    private final Map<Integer, Integer> enforceAttempts = new HashMap<>();
    private final Map<Integer, Set<Integer>> sessionSuspended = new HashMap<>();
    private final List<Integer> installedIds = new ArrayList<>();
    private final Set<String> installedKeys = new HashSet<>();
    // key ("AEC@1") -> source-default registration ID, so a registration can be removed by ID.
    private final Map<String, Integer> keyIds = new HashMap<>();
    // Session ledger: for every live mic session we remember package/uid and the exact effect
    // instance IDs found in it, so effects can be closed (suspended) by ID and verified later.
    private final Map<Integer, SessionRecord> ledger = new HashMap<>();
    private String lastConfigSignature = "";
    private String lastActiveSignature = "";
    // Session bookkeeping is diagnostic/control state only; effect registration IDs are
    // source-default registration IDs, not the app's AudioRecord session ID.
    private int currentSessionId = -1;
    private int currentUid = -1;
    private String currentPackage = "";
    private long lastScanErrorAt;
    private boolean initialSessionPrepared;
    private final Set<Integer> suspendedEffectIds = new HashSet<>();
    private int suspendedSessionId = -1;

    PlatformDefaultEffects(Context context) {
        this.context = context;
        this.audioManager = context.getSystemService(AudioManager.class);
    }

    synchronized void start(boolean aec, boolean ns, boolean agc, boolean automatic, String manualTarget, boolean forceCloseTarget,
                          String perAppCfg, String ignoredCfg, StateSink stateSink) {
        stopLocked("restart");
        this.sink = stateSink;
        cleanupStaleRegistrations();
        this.wantAec = aec;
        this.wantNs = ns;
        this.wantAgc = agc;
        this.automaticMode = automatic;
        this.forceCloseTarget = forceCloseTarget;
        this.targetPackage = manualTarget == null ? "" : manualTarget;
        applyConfig(perAppCfg, ignoredCfg);
        this.running = true;
        this.initialSessionPrepared = false;

        state("ACTIVE", automaticMode
                ? "Automatic mode started: follows the active microphone session."
                : "Manual mode started: target=" + targetPackage);
        log("SOURCE_DEFAULT_START AEC=" + aec + " NS=" + ns + " AGC2=" + agc
                + " controlMode=" + (automaticMode ? "automatic" : "manual")
                + " target=" + targetPackage
                + " sources=MIC,CAMCORDER,VOICE_RECOGNITION,VOICE_COMMUNICATION,UNPROCESSED");

        int installed = 0;
        int requested = 0;
        // Install the UNION of every app's wanted effects; per-app separation is done per
        // session afterwards by suspending the effects each app does not want.
        boolean[] u = unionWanted();
        if (u[0]) { requested++; installed += installForAllSources(AudioEffect.EFFECT_TYPE_AEC, "AEC"); }
        if (u[1]) { requested++; installed += installForAllSources(AudioEffect.EFFECT_TYPE_NS, "NS"); }
        if (u[2]) { requested++; installed += installForAllSources(AGC2_TYPE, "AGC2"); }
        log("PER_APP_CONFIG apps=" + perApp + " ignoredInAutomatic=" + ignoredAuto);

        persistState();
        if (requested > 0 && installed == 0) {
            running = false;
            throw new IllegalStateException("No requested source-default effect could be installed");
        }

        state("ACTIVE", "Installed " + installed + " source-default registration(s)."
                + " New microphone sessions will receive matching selected effects automatically.");
        log("EFFECT_CONTROL_MODE=global-source-default; forceCloseTarget=" + forceCloseTarget);
        log("SOURCE_DEFAULT_READY requestedTypes=" + requested + " installedRegistrations=" + installed
                + " ids=" + installedIds);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EchoRoute-MicSessionMonitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safeScan, 0, 300, TimeUnit.MILLISECONDS);
        executor.execute(() -> restoreSuspendedEffectsIfPossible());
    }

    synchronized void stop() {
        stopLocked("controller-stop");
    }

    synchronized boolean isRunning() {
        return running;
    }

    private static Set<String> globalSet(boolean aec, boolean ns, boolean agc) {
        Set<String> set = new HashSet<>();
        if (aec) set.add("AEC");
        if (ns) set.add("NS");
        if (agc) set.add("AGC");
        return set;
    }

    private void applyConfig(String perAppCfg, String ignoredCfg) {
        synchronized (lock) {
            perApp.clear();
            ignoredAuto.clear();
            enforcedSessions.clear();
            enforceAttempts.clear();
            sessionSuspended.clear();
            if (perAppCfg != null) {
                for (String item : perAppCfg.split("[;\\n]+")) {
                    int eq = item.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = item.substring(0, eq).trim();
                    Set<String> set = new HashSet<>();
                    for (String e : item.substring(eq + 1).split("[+,]")) {
                        String n = e.trim().toUpperCase(java.util.Locale.ROOT);
                        if (n.equals("AGC2")) n = "AGC";
                        if (n.equals("AEC") || n.equals("NS") || n.equals("AGC")) set.add(n);
                    }
                    if (!pkg.isEmpty() && !set.isEmpty()) perApp.put(pkg, set);
                }
            }
            if (ignoredCfg != null) {
                for (String p : ignoredCfg.split("[;,\\n]+")) {
                    if (!p.trim().isEmpty()) ignoredAuto.add(p.trim());
                }
            }
        }
    }

    synchronized void updateConfig(String perAppCfg, String ignoredCfg) {
        applyConfig(perAppCfg, ignoredCfg);
        if (!running) return;

        boolean allowForceClose = forceCloseTarget;
        try {
            android.content.SharedPreferences main = context.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
            if (main.contains(PREF_REMOVE_FORCE_CLOSE_OVERRIDE)) {
                allowForceClose = main.getBoolean(PREF_REMOVE_FORCE_CLOSE_OVERRIDE, forceCloseTarget);
                main.edit().remove(PREF_REMOVE_FORCE_CLOSE_OVERRIDE).apply();
            }
        } catch (Throwable ignored) {}

        boolean[] u = unionWanted();
        int added = 0;
        int removed = 0;
        synchronized (lock) {
            if (u[0]) added += installForAllSources(AudioEffect.EFFECT_TYPE_AEC, "AEC");
            if (u[1]) added += installForAllSources(AudioEffect.EFFECT_TYPE_NS, "NS");
            if (u[2]) added += installForAllSources(AGC2_TYPE, "AGC2");
            // Effect types nobody wants any more: remove their registrations by ID.
            removed = pruneUnusedRegistrations(u);
            if (added > 0 || removed > 0) persistState();
        }
        state("CONFIG", "Per-app effects updated for " + perApp.size() + " app(s).");
        log("CONFIG_UPDATE apps=" + perApp + " ignoredInAutomatic=" + ignoredAuto
                + " newRegistrations=" + added + " removedRegistrations=" + removed);
        scheduleCloseForRemovedEffects("config-change", allowForceClose);
    }

    /** Removes source-default registrations (by ID) for effect types that no app wants any more. */
    private int pruneUnusedRegistrations(boolean[] union) {
        int removed = 0;
        java.util.Iterator<Map.Entry<String, Integer>> it = keyIds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            String key = e.getKey();
            String label = key.substring(0, key.indexOf('@'));
            boolean wanted = label.equals("AEC") ? union[0] : label.equals("NS") ? union[1] : union[2];
            if (wanted) continue;
            int id = e.getValue();
            removeEffectById(id);
            installedIds.remove(Integer.valueOf(id));
            installedKeys.remove(key);
            it.remove();
            removed++;
            log("REGISTRATION_REMOVED_BY_ID key=" + key + " id=" + id);
        }
        return removed;
    }

    /** Looks at the session ledger and closes, by effect ID, every effect an app no longer wants. */
    private void scheduleCloseForRemovedEffects(String reason, boolean allowForceClose) {
        final List<CloseJob> jobs = new ArrayList<>();
        synchronized (lock) {
            for (SessionRecord r : ledger.values()) {
                Set<String> want = effectsFor(r.pkg);
                Set<String> drop = new java.util.TreeSet<>();
                for (Map.Entry<Integer, String> e : r.effects.entrySet()) {
                    if (r.suspended.contains(e.getKey())) continue;
                    if (!want.contains(e.getValue())) drop.add(e.getValue());
                }
                if (!drop.isEmpty()) jobs.add(new CloseJob(r.pkg, r.uid, r.session, drop));
            }
        }
        if (jobs.isEmpty()) return;
        final ScheduledExecutorService ex = executor;
        if (ex == null) return;
        try {
            ex.execute(() -> { for (CloseJob j : jobs) closeEffectsById(j, reason, allowForceClose); });
        } catch (Throwable t) {
            log("CLOSE_JOB_SCHEDULE_FAILED " + t);
        }
    }

    /**
     * Closes the given effects in one live session by their saved effect IDs, verifies the result,
     * and (when Force Close is on) releases only that app's microphone so the chain is really gone.
     */
    private void closeEffectsById(CloseJob job, String reason, boolean allowForceClose) {
        SessionRecord rec;
        synchronized (lock) { rec = ledger.get(job.session); }
        if (rec == null) return;
        try {
            if (!containsSession(audioManager.getActiveRecordingConfigurations(), rec.session, rec.uid, rec.pkg)) {
                log("CLOSE_SKIP_SESSION_GONE package=" + rec.pkg + " session=" + rec.session);
                return;
            }
        } catch (Throwable ignored) {}

        List<Integer> ids = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<Integer, String> e : rec.effects.entrySet()) {
                if (job.names.contains(e.getValue()) && !rec.suspended.contains(e.getKey())) ids.add(e.getKey());
            }
        }
        if (ids.isEmpty()) return;

        boolean allOk = true;
        for (int id : ids) {
            boolean ok = invokeSetEffectSuspended(id, rec.session, true);
            allOk &= ok;
            if (ok) {
                synchronized (lock) {
                    rec.suspended.add(id);
                    Set<Integer> s2 = sessionSuspended.get(rec.session);
                    if (s2 == null) { s2 = new HashSet<>(); sessionSuspended.put(rec.session, s2); }
                    s2.add(id);
                }
            }
        }

        // Verify: query the session again and report which of those IDs are still listed.
        List<Integer> stillListed = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (EffectInstance e : querySessionEffects(rec.session)) present.add(e.id);
        for (int id : ids) if (present.contains(id)) stillListed.add(id);
        log("CLOSE_EFFECTS_BY_ID package=" + rec.pkg + " session=" + rec.session
                + " names=" + job.names + " ids=" + ids + " suspendCallsOk=" + allOk
                + " stillListedInSession=" + stillListed + " reason=" + reason);

        if (allOk) {
            state("APPLIED", "Closed " + String.join("+", job.names) + " for " + rec.pkg
                    + " by effect ID " + ids + " (session " + rec.session + ").");
        } else {
            state("WARNING", "Could not close " + String.join("+", job.names) + " for " + rec.pkg
                    + " by effect ID " + ids + ".");
        }

        if (allowForceClose) {
            // Normal config changes follow the global Force Close setting. X-removal supplies a
            // one-shot false override and an explicit force-stop when the user chooses YES.
            closeMicForPackage(rec.pkg, rec.uid, rec.session, true, "effects-removed", true);
        } else if (!allOk) {
            state("WARNING", "Effects of " + rec.pkg + " stay active until its microphone is closed."
                    + " Turn on Force Close or close the app's microphone.");
        }
    }

    /** STOP: suspend every effect saved in the ledger by ID (live sessions only) and verify. */
    private void suspendLedgerById(List<SessionRecord> recs, String reason) {
        List<AudioRecordingConfiguration> configs = null;
        try { configs = audioManager == null ? null : audioManager.getActiveRecordingConfigurations(); }
        catch (Throwable ignored) {}
        for (SessionRecord r : recs) {
            if (!containsSession(configs, r.session, r.uid, r.pkg)) continue;
            List<Integer> ids = new ArrayList<>();
            for (Integer id : r.effects.keySet()) if (!r.suspended.contains(id)) ids.add(id);
            if (ids.isEmpty()) continue;
            boolean ok = true;
            for (int id : ids) ok &= invokeSetEffectSuspended(id, r.session, true);
            log("LEDGER_CLOSE_BY_ID package=" + r.pkg + " session=" + r.session
                    + " ids=" + ids + " ok=" + ok + " reason=" + reason);
        }
    }

    /** True while the package (or its uid) still has an input registered with AudioPolicy. */
    private boolean packageStillRecording(String pkg, int uid) {
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return false;
            for (AudioRecordingConfiguration c : configs) {
                String cp = clientPackage(c);
                int cu = clientUid(c);
                if (pkg.equals(cp) || (uid > 0 && cu == uid)) return true;
            }
        } catch (Throwable t) {
            log("PACKAGE_RECORDING_CHECK_FAILED package=" + pkg + " error=" + t);
        }
        return false;
    }

    private Candidate findRecordingForPackage(String pkg, int uid, int session) {
        Candidate c = findCandidateByIdentity(pkg, uid, session);
        if (c != null) return c;
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return null;
            for (AudioRecordingConfiguration cfg : configs) {
                String cp = clientPackage(cfg);
                int cu = clientUid(cfg);
                if (!pkg.equals(cp) && !(uid > 0 && cu == uid)) continue;
                int sess = cfg.getClientAudioSessionId();
                if (sess <= 0) continue;
                return new Candidate(cfg, sess, cu, cp, cfg.getClientAudioSource());
            }
        } catch (Throwable t) {
            log("FIND_RECORDING_FAILED package=" + pkg + " error=" + t);
        }
        return null;
    }

    /** Packages that currently record (never EchoRoute itself); optionally only the managed ones. */
    private List<String> livePackages(boolean managedOnly) {
        List<String> out = new ArrayList<>();
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return out;
            for (AudioRecordingConfiguration c : configs) {
                if (c.getClientAudioSessionId() <= 0) continue;
                String pkg = clientPackage(c);
                if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;
                if (managedOnly && !isManaged(pkg)) continue;
                if (!out.contains(pkg)) out.add(pkg);
            }
        } catch (Throwable t) {
            log("LIVE_PACKAGES_FAILED " + t);
        }
        return out;
    }

    /**
     * Closes ONLY the microphone of one app (AudioPolicy stopInput/releaseInput + idle state), which
     * releases its effect chain. The app keeps running. If the mic is still open afterwards and
     * allowKill is true, the app is force-stopped as a last resort. Verifies the result.
     */
    private boolean closeMicForPackage(String pkg, int uid, int session, boolean allowKill,
                                       String reason, boolean resetNow) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return true;
        Candidate c = findRecordingForPackage(pkg, uid, session);
        if (c == null) {
            log("MIC_CLOSE_NOT_NEEDED package=" + pkg + " reason=" + reason + " (no open microphone input)");
            return true;
        }
        int cu = c.uid;
        int sess = c.sessionId;
        state("MIC_CLOSE", "Closing only the microphone of " + pkg + " (the app keeps running).");
        releaseMicInput(c, reason, 1000);
        boolean gone = !packageStillRecording(pkg, cu);
        if (!gone && allowKill) {
            state("MIC_CLOSE", "Microphone of " + pkg + " is still open; force-stopping the app as a last resort.");
            if (forceStopPackage(pkg)) waitForPackageToDisappear(pkg, cu, sess, 5000);
            gone = !packageStillRecording(pkg, cu);
        }
        log("MIC_CLOSE_VERIFY package=" + pkg + " uid=" + cu + " session=" + sess
                + " closed=" + gone + " reason=" + reason);
        if (gone) state("APPLIED", "Microphone of " + pkg + " closed and verified; its effect chain was released.");
        else state("WARNING", "Microphone of " + pkg + " is still open after force close.");
        if (resetNow) resetUidAudioPolicyState(pkg);
        return gone;
    }

    /** Removes stale registrations left behind by an earlier (dead) session; used when STOP could not run. */
    void cleanupStale() {
        cleanupStaleRegistrations();
    }

    /** Force-stops exactly the selected package after an Activity-tab removal confirmation. */
    String forceClosePackage(String pkg) {
        if (!isForceClosable(pkg)) {
            state("WARNING", "Cannot force close " + String.valueOf(pkg) + ".");
            return "Cannot close.";
        }
        boolean requested = false;
        try {
            requested = forceStopPackage(pkg);
        } catch (Throwable t) {
            log("FORCE_CLOSE_PACKAGE_FAILED package=" + pkg + " error=" + t);
        }
        if (requested) {
            waitForPackageToDisappear(pkg, -1, -1, 5000);
            resetUidAudioPolicyState(pkg);
            state("APPLIED", "Force closed " + pkg + ".");
            return pkg + ": closed";
        }
        state("WARNING", "Could not force close " + pkg + ".");
        return pkg + ": failed";
    }

    /**
     * Notification "Force close": force-stops ONLY the app that is running on screen right now.
     * It never touches EchoRoute itself, the launcher, system UI, Shizuku, other apps that use the
     * microphone, or apps in the background.
     */
    String forceCloseMic() {
        String fg = foregroundPackage();
        log("FORCE_CLOSE_TARGET foreground=" + fg);
        if (fg == null || !isForceClosable(fg)) {
            state("INFO", "Force close: no running app on screen to close.");
            return "Nothing to close.";
        }
        boolean hadMic = livePackages(false).contains(fg);
        state("MIC_CLOSE", "Force closing the running app " + fg + ".");
        boolean requested = forceStopPackage(fg);
        if (requested) waitForPackageToDisappear(fg, -1, -1, 4000);
        boolean micStillOpen = hadMic && packageStillRecording(fg, -1);
        if (hadMic) resetUidAudioPolicyState(fg);
        boolean ok = requested && !micStillOpen;
        if (ok) state("APPLIED", "Force closed " + fg + ".");
        else state("WARNING", "Could not fully close " + fg + (micStillOpen ? " (microphone still open)." : "."));
        String result = fg + (ok ? ": closed" : ": failed");
        log("FORCE_CLOSE_DONE " + result);
        return result;
    }

    private boolean isForceClosable(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith("uid:")) return false;
        if (pkg.equals(context.getPackageName())) return false;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.shell") || pkg.equals("moe.shizuku.privileged.api")) return false;
        return !homePackages().contains(pkg);
    }

    private Set<String> homePackages() {
        Set<String> out = new HashSet<>();
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : context.getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Throwable t) {
            log("HOME_PACKAGES_FAILED " + t);
        }
        return out;
    }

    /** Package of the activity that is resumed (on screen) right now, or null. */
    private String foregroundPackage() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
            Pattern pat = Pattern.compile("u\\d+\\s+([A-Za-z0-9_.]+)/");
            String found = null;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean top = line.contains("topResumedActivity=");
                    if (!top && !line.contains("mResumedActivity:") && !line.contains("ResumedActivity:")) continue;
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        found = m.group(1);
                        if (top) break;
                    }
                }
            }
            log("FOREGROUND_PACKAGE " + found);
            return found;
        } catch (Throwable t) {
            log("FOREGROUND_PACKAGE_FAILED " + t);
            return null;
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Effects this package should have right now. */
    private Set<String> effectsFor(String pkg) {
        synchronized (lock) {
            Set<String> own = perApp.get(pkg);
            if (own != null) return own;
            if (!automaticMode || ignoredAuto.contains(pkg)) return Collections.emptySet();
            return globalSet(wantAec, wantNs, wantAgc);
        }
    }

    /** Whether EchoRoute should track/touch this package at all. */
    private boolean isManaged(String pkg) {
        synchronized (lock) {
            if (perApp.containsKey(pkg)) return true;
            return automaticMode && !ignoredAuto.contains(pkg);
        }
    }

    private boolean[] unionWanted() {
        synchronized (lock) {
            boolean a = automaticMode && wantAec, n = automaticMode && wantNs, g = automaticMode && wantAgc;
            for (Set<String> s : perApp.values()) {
                a |= s.contains("AEC");
                n |= s.contains("NS");
                g |= s.contains("AGC");
            }
            return new boolean[]{a, n, g};
        }
    }

    private void enforceAllSessions(List<AudioRecordingConfiguration> configs) {
        if (configs == null) return;
        Set<Integer> live = new HashSet<>();
        for (AudioRecordingConfiguration c : configs) {
            int session = c.getClientAudioSessionId();
            if (session <= 0 || c.isClientSilenced()) continue;
            String pkg = clientPackage(c);
            if (pkg == null || pkg.isEmpty()) pkg = "uid:" + clientUid(c);
            if (pkg.equals(context.getPackageName())) continue;
            live.add(session);
            String sig = pkg + "|" + new java.util.TreeSet<>(effectsFor(pkg));
            String done;
            int attempts;
            synchronized (lock) {
                done = enforcedSessions.get(session);
                Integer a = enforceAttempts.get(session);
                attempts = a == null ? 0 : a;
            }
            if (sig.equals(done)) continue;
            boolean ok = enforceSessionPolicy(session, pkg, clientUid(c));
            synchronized (lock) {
                if (ok || attempts >= 5) {
                    enforcedSessions.put(session, sig);
                    if (!ok) log("POLICY_NO_EFFECT_INSTANCES package=" + pkg + " session=" + session
                            + " (mic was already open before EchoRoute registered; close the app mic or use Force Close)");
                } else {
                    enforceAttempts.put(session, attempts + 1);
                }
            }
        }
        synchronized (lock) {
            enforcedSessions.keySet().retainAll(live);
            enforceAttempts.keySet().retainAll(live);
            sessionSuspended.keySet().retainAll(live);
            ledger.keySet().retainAll(live);
        }
    }

    /** Suspends, on this session only, every managed effect this package does not want. */
    private boolean enforceSessionPolicy(int sessionId, String pkg, int uid) {
        Set<String> want = effectsFor(pkg);
        List<EffectInstance> effects = querySessionEffects(sessionId);
        if (effects.isEmpty()) return false;
        Set<Integer> suspended = new HashSet<>();
        Set<String> active = new java.util.TreeSet<>();
        Map<Integer, String> managed = new LinkedHashMap<>();
        for (EffectInstance e : effects) {
            if (!isManagedType(e.type)) continue;
            String name = isAecType(e.type) ? "AEC" : isNsType(e.type) ? "NS" : "AGC";
            boolean wanted = want.contains(name);
            boolean callOk = invokeSetEffectSuspended(e.id, sessionId, !wanted);
            managed.put(e.id, name);
            if (wanted) active.add(name); else if (callOk) suspended.add(e.id);
        }
        synchronized (lock) {
            sessionSuspended.put(sessionId, suspended);
            // Save this session's effect IDs so they can be closed by ID later.
            if (!managed.isEmpty()) {
                SessionRecord r = new SessionRecord(sessionId, uid, pkg);
                r.effects.putAll(managed);
                r.suspended.addAll(suspended);
                ledger.put(sessionId, r);
            }
        }
        log("LEDGER_SAVE package=" + pkg + " session=" + sessionId + " effects=" + managed
                + " suspended=" + suspended);
        log("POLICY_APPLIED package=" + pkg + " session=" + sessionId
                + " wanted=" + want + " active=" + active + " suspendedIds=" + suspended);
        state("APPLIED", active.isEmpty()
                ? "No EchoRoute effects for " + pkg + " (not selected / ignored)."
                : "Applied " + String.join("+", active) + " to " + pkg + " (session " + sessionId + ").");
        return true;
    }

    /** Lines "package|session|AEC+NS" for live mic sessions that really have managed effects active. */
    String queryAppliedEffects() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return "";
            for (AudioRecordingConfiguration c : configs) {
                int session = c.getClientAudioSessionId();
                if (session <= 0) continue;
                String pkg = clientPackage(c);
                if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;
                Set<Integer> susp;
                synchronized (lock) {
                    Set<Integer> s = sessionSuspended.get(session);
                    susp = s == null ? new HashSet<>() : new HashSet<>(s);
                }
                Set<String> names = new java.util.TreeSet<>();
                for (EffectInstance e : querySessionEffects(session)) {
                    if (!isManagedType(e.type) || susp.contains(e.id)) continue;
                    names.add(isAecType(e.type) ? "AEC" : isNsType(e.type) ? "NS" : "AGC");
                }
                if (!names.isEmpty()) sb.append(pkg).append('|').append(session).append('|')
                        .append(String.join("+", names)).append('\n');
            }
        } catch (Throwable t) {
            log("QUERY_APPLIED_FAILED " + t);
        }
        return sb.toString();
    }

    private int installForAllSources(UUID type, String label) {
        int installed = 0;
        for (SourceSpec source : NORMAL_MIC_SOURCES) {
            String key = label + "@" + source.value;
            if (installedKeys.contains(key)) continue;
            try {
                int regId = installDefaultEffect(type, label + "-" + source.name, source.value);
                installedKeys.add(key);
                keyIds.put(key, regId);
                installed++;
            } catch (Throwable t) {
                String message = label + " source=" + source.name + "(" + source.value + ") failed: "
                        + rootMessage(t);
                Log.w(TAG, message, t);
                state("WARNING", message);
                log("SOURCE_DEFAULT_INSTALL_FAILED label=" + label
                        + " source=" + source.name + " value=" + source.value
                        + " error=" + t);
            }
        }
        return installed;
    }

    private void safeScan() {
        try {
            scanActiveRecordings();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastScanErrorAt > 2000) {
                lastScanErrorAt = now;
                Log.e(TAG, "Active recording monitor failed", t);
                state("WARNING", "Active recording monitor failed: " + rootMessage(t));
                log("SCAN_ERROR " + t);
            }
        }
    }

    private void scanActiveRecordings() {
        synchronized (lock) {
            if (!running || audioManager == null) return;
        }

        List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
        String signature = describeConfigs(configs);
        if (!signature.equals(lastConfigSignature)) {
            lastConfigSignature = signature;
            state("SCAN", "Active recordings: " + signature);
        }

        enforceAllSessions(configs);

        Candidate candidate;
        String oldPackage = "";
        int oldUid = -1;
        int oldSession = -1;
        boolean needsSwitch = false;
        boolean needsInitialRefresh = false;

        synchronized (lock) {
            if (!running) return;

            candidate = selectActiveCandidate(configs);

            // If another app/session appears while the tracked session is still alive,
            // rotate away from the old owner. We deliberately do not keep the old chain
            // alive: first close its recording, then remove our source-default registrations,
            // then recreate them and refresh the new session.
            Candidate different = findDifferentCandidate(configs, currentSessionId);
            if (currentSessionId > 0 && different != null) {
                oldSession = currentSessionId;
                oldUid = currentUid;
                oldPackage = currentPackage;
                candidate = different;
                needsSwitch = true;
            } else if (currentSessionId > 0 && !containsSession(configs, currentSessionId, currentUid, currentPackage)) {
                oldSession = currentSessionId;
                oldUid = currentUid;
                oldPackage = currentPackage;
                currentSessionId = -1;
                currentUid = -1;
                currentPackage = "";

                state("SESSION", "Previous microphone session ended: " + oldPackage
                        + " | uid=" + oldUid + " | session=" + oldSession
                        + ". Closing/rotating EchoRoute registrations before the next session.");
                log("SESSION_ENDED package=" + oldPackage + " uid=" + oldUid
                        + " session=" + oldSession);

                // If another session is already active, refresh that session too: close it
                // first, then rotate the registrations, then let it recreate its input chain.
                if (candidate != null) {
                    needsSwitch = true;
                } else {
                    rotateSourceDefaultRegistrationsLocked(oldSession, -1, "old-session-ended");
                }
            } else if (currentSessionId < 0 && candidate != null && !initialSessionPrepared) {
                // A target was already recording when monitoring started. Refresh it once so
                // the recording is recreated after the source-default registrations exist.
                needsInitialRefresh = true;
            }
        }

        if (needsSwitch) {
            performSessionSwitch(oldPackage, oldUid, oldSession, candidate);
            return;
        }

        if (needsInitialRefresh) {
            if (performInitialSessionRefresh(candidate)) {
                synchronized (lock) { initialSessionPrepared = true; }
            }
            return;
        }

        synchronized (lock) {
            if (!running) return;
            Candidate current = selectActiveCandidate(configs);
            if (current == null) {
                if (!"none".equals(lastActiveSignature)) {
                    lastActiveSignature = "none";
                    state("SESSION", "No external active microphone recording session detected.");
                    log("SESSION_NONE");
                }
                return;
            }

            String active = current.packageName + " uid=" + current.uid
                    + " session=" + current.sessionId
                    + " source=" + current.source
                    + " effects=" + effectNames(current.config.getEffects());
            if (!active.equals(lastActiveSignature)) {
                lastActiveSignature = active;
                state("SESSION", "Active microphone: " + active);
                log("SESSION_ACTIVE " + active);
            }

            if (currentSessionId < 0) {
                currentSessionId = current.sessionId;
                currentUid = current.uid;
                currentPackage = current.packageName;
                state("SESSION", "Tracking microphone Session ID " + currentSessionId
                        + " for " + currentPackage + " (uid=" + currentUid + ").");
                log("SESSION_TRACK session=" + currentSessionId + " package=" + currentPackage
                        + " uid=" + currentUid + " source=" + current.source);
                restoreSessionEffectsForSelection(currentSessionId);
            }
        }
    }

    private Candidate findDifferentCandidate(List<AudioRecordingConfiguration> configs, int trackedSession) {
        if (configs == null) return null;
        for (AudioRecordingConfiguration c : configs) {
            int session = c.getClientAudioSessionId();
            if (session <= 0 || session == trackedSession || c.isClientSilenced()) continue;
            String pkg = clientPackage(c);
            int uid = clientUid(c);
            if (pkg == null || pkg.isEmpty()) pkg = "uid:" + uid;
            if (pkg.equals(context.getPackageName())) continue;
            return new Candidate(c, session, uid, pkg, c.getClientAudioSource());
        }
        return null;
    }

    private boolean performInitialSessionRefresh(Candidate candidate) {
        if (candidate == null) return false;
        if (!releaseMicInput(candidate, "initial-session-refresh", 5000)) return false;

        synchronized (lock) {
            if (!running) return false;
            // The old input is actually released before rotating registrations.
            rotateSourceDefaultRegistrationsLocked(candidate.sessionId, -1, "initial-session-refresh");
        }

        resetUidAudioPolicyState(candidate.packageName);
        state("ROTATE", "Microphone re-enabled after fresh source-default registrations.");
        log("SESSION_INITIAL_REFRESH_DONE package=" + candidate.packageName
                + " oldSession=" + candidate.sessionId);
        return true;
    }

    private void performSessionSwitch(String oldPackage, int oldUid, int oldSession, Candidate next) {
        if (next == null) return;

        state("ROTATE", "Switching microphone session " + oldSession + " -> " + next.sessionId
                + ". Releasing both inputs for 5 seconds before effect rotation.");
        log("SESSION_SWITCH oldPackage=" + oldPackage + " oldUid=" + oldUid
                + " oldSession=" + oldSession + " newPackage=" + next.packageName
                + " newUid=" + next.uid + " newSession=" + next.sessionId);

        Candidate oldCandidate = findCandidateByIdentity(oldPackage, oldUid, oldSession);
        if (oldCandidate != null) {
            releaseMicInput(oldCandidate, "session-switch-old", 5000);
            Candidate oldStill = findAnyCandidateForPackage(oldPackage);
            if (oldStill != null) {
                suspendSessionEffects(oldStill.sessionId, wantAec, wantNs, wantAgc, true,
                        "session-switch-old-fallback");
            }
        } else if (oldPackage != null && !oldPackage.isEmpty()) {
            setUidAudioPolicyState(oldPackage, "idle");
            waitForSessionToDisappear(oldSession, oldUid, oldPackage, 1200);
            sleepQuietly(3800);
        }

        Candidate liveNext = findCandidateByIdentity(next.packageName, next.uid, next.sessionId);
        if (liveNext != null) {
            releaseMicInput(liveNext, "session-switch-next", 5000);
            Candidate nextStill = findAnyCandidateForPackage(next.packageName);
            if (nextStill != null) {
                suspendSessionEffects(nextStill.sessionId, wantAec, wantNs, wantAgc, true,
                        "session-switch-next-fallback");
            }
        } else {
            setUidAudioPolicyState(next.packageName, "idle");
            waitForSessionToDisappear(next.sessionId, next.uid, next.packageName, 1200);
            sleepQuietly(3800);
        }

        synchronized (lock) {
            if (!running) return;
            currentSessionId = -1;
            currentUid = -1;
            currentPackage = "";
            rotateSourceDefaultRegistrationsLocked(oldSession, -1, "session-switch");
        }

        if (oldPackage != null && !oldPackage.isEmpty()) {
            resetUidAudioPolicyState(oldPackage);
        }
        if (next != null) {
            resetUidAudioPolicyState(next.packageName);
        }

        state("ROTATE", "Session switch cleanup complete. Old input was released; fresh"
                + " source-default registrations are ready. New session will be detected automatically.");
        log("SESSION_SWITCH_REENABLE oldPackage=" + oldPackage
                + " oldUid=" + oldUid
                + " oldSession=" + oldSession
                + " nextPackage=" + next.packageName
                + " nextUid=" + next.uid
                + " nextSession=" + next.sessionId);
    }

    private Candidate findAnyCandidateForPackage(String pkg) {
        if (audioManager == null || pkg == null || pkg.isEmpty()) return null;
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return null;
            for (AudioRecordingConfiguration c : configs) {
                if (c.isClientSilenced()) continue;
                String cp = clientPackage(c);
                int cu = clientUid(c);
                if (cp == null || cp.isEmpty()) cp = "uid:" + cu;
                if (!pkg.equals(cp)) continue;
                int session = c.getClientAudioSessionId();
                if (session <= 0) continue;
                return new Candidate(c, session, cu, cp, c.getClientAudioSource());
            }
        } catch (Throwable t) {
            log("FIND_ANY_PACKAGE_FAILED package=" + pkg + " error=" + t);
        }
        return null;
    }

    private Candidate findCandidateByIdentity(String pkg, int uid, int sessionId) {
        if (audioManager == null) return null;
        try {
            List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
            if (configs == null) return null;
            for (AudioRecordingConfiguration c : configs) {
                if (c.getClientAudioSessionId() != sessionId) continue;
                int cu = clientUid(c);
                String cp = clientPackage(c);
                if (pkg != null && !pkg.isEmpty() && !pkg.equals(cp)) continue;
                if (uid > 0 && cu != uid) continue;
                return new Candidate(c, sessionId, cu, cp, c.getClientAudioSource());
            }
        } catch (Throwable t) {
            log("FIND_CANDIDATE_FAILED package=" + pkg
                    + " uid=" + uid + " session=" + sessionId
                    + " error=" + t);
        }
        return null;
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitForSessionToDisappear(int sessionId, int uid, String pkg, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
                if (!containsSession(configs, sessionId, uid, pkg)) {
                    log("SESSION_GONE confirmed package=" + pkg + " uid=" + uid + " session=" + sessionId);
                    return;
                }
            } catch (Throwable t) {
                log("SESSION_GONE_CHECK_FAILED package=" + pkg + " session=" + sessionId + " error=" + t);
                return;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        state("WARNING", "Session " + sessionId + " did not disappear within " + timeoutMs + "ms; continuing cleanup.");
        log("SESSION_GONE_TIMEOUT package=" + pkg + " uid=" + uid + " session=" + sessionId);
    }

    private Candidate selectActiveCandidate(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return null;

        if (currentSessionId > 0) {
            for (AudioRecordingConfiguration c : configs) {
                int session = c.getClientAudioSessionId();
                if (session != currentSessionId) continue;
                String pkg = clientPackage(c);
                int uid = clientUid(c);
                if (pkg == null || pkg.isEmpty()) pkg = "uid:" + uid;
                if (pkg.equals(context.getPackageName()) || c.isClientSilenced()) return null;
                if (!isManaged(pkg)) return null;
                return new Candidate(c, session, uid, pkg, c.getClientAudioSource());
            }
        }

        for (AudioRecordingConfiguration c : configs) {
            int session = c.getClientAudioSessionId();
            if (session <= 0 || c.isClientSilenced()) continue;
            String pkg = clientPackage(c);
            int uid = clientUid(c);
            if (pkg == null || pkg.isEmpty()) pkg = "uid:" + uid;
            if (pkg.equals(context.getPackageName())) continue;
            if (!isManaged(pkg)) continue;
            return new Candidate(c, session, uid, pkg, c.getClientAudioSource());
        }
        return null;
    }

    private boolean containsSession(List<AudioRecordingConfiguration> configs, int sessionId, int uid, String pkg) {
        if (sessionId <= 0 || configs == null) return false;
        for (AudioRecordingConfiguration c : configs) {
            if (c.getClientAudioSessionId() != sessionId) continue;
            if (c.isClientSilenced()) continue;
            String cp = clientPackage(c);
            int cu = clientUid(c);
            if (cp == null || cp.isEmpty()) cp = "uid:" + cu;
            if (cp.equals(context.getPackageName())) continue;
            return uid < 0 || cu == uid;
        }
        return false;
    }

    private boolean setUidAudioPolicyState(String packageName, String stateValue) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(context.getPackageName())) return false;
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/cmd", "media.audio_policy", "set-uid-state", packageName, stateValue)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 512) out.append(line);
                }
            }
            int rc = process.waitFor();
            boolean ok = rc == 0;
            log("UID_AUDIO_POLICY_STATE package=" + packageName + " state=" + stateValue
                    + " rc=" + rc + (out.length() == 0 ? "" : " output=" + out));
            if (!ok) state("WARNING", "AudioPolicy UID state " + stateValue
                    + " failed for " + packageName + " (rc=" + rc + ")");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "AudioPolicy UID state change failed for " + packageName + " -> " + stateValue, t);
            state("WARNING", "AudioPolicy UID state " + stateValue + " failed for "
                    + packageName + ": " + rootMessage(t));
            log("UID_AUDIO_POLICY_STATE_FAILED package=" + packageName + " state=" + stateValue
                    + " error=" + t);
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean resetUidAudioPolicyState(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(context.getPackageName())) return false;
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/cmd", "media.audio_policy", "reset-uid-state", packageName)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 512) out.append(line);
                }
            }
            int rc = process.waitFor();
            boolean ok = rc == 0;
            log("UID_AUDIO_POLICY_RESET package=" + packageName + " rc=" + rc
                    + (out.length() == 0 ? "" : " output=" + out));
            if (!ok) state("WARNING", "AudioPolicy UID reset failed for " + packageName
                    + " (rc=" + rc + ")");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "AudioPolicy UID reset failed for " + packageName, t);
            state("WARNING", "AudioPolicy UID reset failed for " + packageName
                    + ": " + rootMessage(t));
            log("UID_AUDIO_POLICY_RESET_FAILED package=" + packageName + " error=" + t);
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private void rotateSourceDefaultRegistrationsLocked(int oldSession, int newSession, String reason) {
        List<Integer> oldIds = new ArrayList<>(installedIds);
        if (!oldIds.isEmpty()) {
            state("CLEANUP", "Removing old source-default registration IDs before session "
                    + newSession + ": " + oldIds);
            for (Integer id : oldIds) removeEffectById(id);
            installedIds.clear();
            installedKeys.clear();
            keyIds.clear();
            clearPersistedState();
            log("SOURCE_DEFAULT_ROTATE_REMOVE oldSession=" + oldSession
                    + " newSession=" + newSession + " oldIds=" + oldIds);
        }

        int installed = 0;
        boolean[] uw = unionWanted();
        if (uw[0]) installed += installForAllSources(AudioEffect.EFFECT_TYPE_AEC, "AEC");
        if (uw[1]) installed += installForAllSources(AudioEffect.EFFECT_TYPE_NS, "NS");
        if (uw[2]) installed += installForAllSources(AGC2_TYPE, "AGC2");
        persistState();
        if (newSession > 0) {
            state("SESSION", "Session ID switched to " + newSession
                    + ". Fresh source-default IDs=" + installedIds);
        } else {
            state("CLEANUP", "Old session " + oldSession
                    + " removed. Fresh source-default IDs=" + installedIds
                    + " are ready for the next microphone session.");
        }
        log("SOURCE_DEFAULT_ROTATE_ADD oldSession=" + oldSession
                + " newSession=" + newSession + " reason=" + reason
                + " installed=" + installed + " ids=" + installedIds);
    }

    private int installDefaultEffect(UUID type, String label, int source) throws Exception {
        EffectInfo impl = EffectFinder.find(type);
        if (impl == null) {
            throw new IllegalStateException(label + " implementation not found by AudioEffect.queryEffects()");
        }

        Object service = getAudioPolicyService();
        Method add = findMethod(service.getClass(), "addSourceDefaultEffect");
        if (add == null) throw new NoSuchMethodException("IAudioPolicyService.addSourceDefaultEffect");

        Object typeUuid = toAudioUuid(type);
        Object implUuid = toAudioUuid(impl.implUuid);
        final String attributionPackage = "com.android.shell";

        Log.i(TAG, "addSourceDefaultEffect uid=" + android.os.Process.myUid()
                + " package=" + attributionPackage
                + " source=" + source + " label=" + label);

        Object result = add.invoke(service, typeUuid, attributionPackage, implUuid, 1000, source);
        int id = ((Number) result).intValue();
        if (id <= 0) {
            throw new IllegalStateException(label + " addSourceDefaultEffect returned " + id);
        }

        installedIds.add(id);
        persistState();
        Log.i(TAG, "Installed " + label + " source=" + source
                + " id=" + id + " impl=" + impl.implUuid);
        return id;
    }

    private void cleanupStaleRegistrations() {
        try {
            android.content.SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> ids = p.getStringSet(PREF_IDS, Collections.emptySet());
            if (ids == null || ids.isEmpty()) return;
            for (String value : ids) {
                try {
                    removeEffectById(Integer.parseInt(value));
                } catch (Throwable t) {
                    Log.w(TAG, "stale effect cleanup failed for id=" + value, t);
                }
            }
            p.edit().remove(PREF_IDS).apply();
            EchoAppLog.line(context, "STALE_SOURCE_DEFAULT_CLEANUP ids=" + ids);
        } catch (Throwable t) {
            Log.w(TAG, "stale source-default cleanup unavailable", t);
            EchoAppLog.line(context, "STALE_CLEANUP_FAILED " + t);
        }
    }

    private synchronized void stopLocked(String reason) {
        running = false;
        if (executor != null) {
            try { executor.shutdownNow(); } catch (Throwable ignored) {}
            executor = null;
        }

        List<Integer> ids;
        final List<SessionRecord> ledgerCopy = new ArrayList<>();
        final Set<String> closeSet = new LinkedHashSet<>();
        String stoppedPackage;
        int stoppedUid;
        int stoppedSession;
        synchronized (lock) {
            ids = new ArrayList<>(installedIds);
            stoppedPackage = currentPackage;
            stoppedUid = currentUid;
            stoppedSession = currentSessionId;

            // Resolve the target one final time at STOP. Automatic mode always prefers the
            // live microphone owner, while Manual mode always prefers the selected package.
            try {
                List<AudioRecordingConfiguration> liveConfigs = audioManager == null
                        ? Collections.emptyList()
                        : audioManager.getActiveRecordingConfigurations();
                boolean trackedSelected = !automaticMode && stoppedPackage != null && perApp.containsKey(stoppedPackage);
                boolean manualWithTarget = trackedSelected || (!automaticMode && targetPackage != null && !targetPackage.isEmpty());
                Candidate live = manualWithTarget ? null : selectActiveCandidate(liveConfigs);
                if (manualWithTarget) {
                    // Manual mode: force-close ONLY the selected app, never another mic owner.
                    String forced = trackedSelected ? stoppedPackage : targetPackage;
                    if (!forced.equals(stoppedPackage)) {
                        stoppedUid = -1;
                        stoppedSession = -1;
                    }
                    stoppedPackage = forced;
                    log("STOP_MANUAL_TARGET package=" + forced + " (other mic apps are not touched)");
                } else if (live != null) {
                    stoppedPackage = live.packageName;
                    stoppedUid = live.uid;
                    stoppedSession = live.sessionId;
                    log("STOP_TARGET_LIVE package=" + stoppedPackage
                            + " uid=" + stoppedUid + " session=" + stoppedSession
                            + " mode=" + (automaticMode ? "automatic" : "manual"));
                } else if (!automaticMode && targetPackage != null && !targetPackage.isEmpty()) {
                    stoppedPackage = targetPackage;
                    log("STOP_MANUAL_TARGET_FALLBACK package=" + targetPackage);
                }
            } catch (Throwable t) {
                log("STOP_TARGET_DISCOVERY_FAILED error=" + rootMessage(t));
                if (!automaticMode && targetPackage != null && !targetPackage.isEmpty()) {
                    stoppedPackage = targetPackage;
                }
            }

            // Everything that must be closed on STOP: the resolved target plus every app whose
            // session effects EchoRoute saved in the ledger.
            if (stoppedPackage != null && !stoppedPackage.isEmpty()
                    && !stoppedPackage.equals(context.getPackageName())) closeSet.add(stoppedPackage);
            for (SessionRecord r : ledger.values()) {
                ledgerCopy.add(r);
                if (r.pkg != null && !r.pkg.isEmpty() && !r.pkg.equals(context.getPackageName())) closeSet.add(r.pkg);
            }
            ledger.clear();
            keyIds.clear();
            installedIds.clear();
            installedKeys.clear();
            lastConfigSignature = "";
            lastActiveSignature = "";
            currentSessionId = -1;
            currentUid = -1;
            currentPackage = "";
        }

        boolean targetEligible = stoppedPackage != null && !stoppedPackage.isEmpty()
                && !stoppedPackage.equals(context.getPackageName());

        final boolean stopReason = "controller-stop".equals(reason) || "user-stop".equals(reason)
                || "service-stop".equals(reason);
        final List<String> closedPackages = new ArrayList<>();

        if (stopReason) {
            // 1) Close every saved session effect by its ID (works even if the mic stays open).
            suspendLedgerById(ledgerCopy, reason);
        }
        if (forceCloseTarget && stopReason) {
            // 2) Release only the microphone of each tracked app; kill the app only as a last resort.
            for (String pkg : closeSet) {
                boolean same = pkg.equals(stoppedPackage);
                log("FORCE_CLOSE_BEGIN package=" + pkg + " uid=" + (same ? stoppedUid : -1)
                        + " session=" + (same ? stoppedSession : -1) + " reason=" + reason);
                closeMicForPackage(pkg, same ? stoppedUid : -1, same ? stoppedSession : -1,
                        true, "stop", false);
                closedPackages.add(pkg);
            }
        }

        for (Integer id : ids) removeEffectById(id);
        clearPersistedState();

        if (!ids.isEmpty()) {
            state("CLEANUP", "Removed " + ids.size()
                    + " source-default registration(s). Future microphone sessions will no longer receive EchoRoute effects.");
            log("SOURCE_DEFAULT_REMOVE count=" + ids.size() + " reason=" + reason + " ids=" + ids);
        }

        for (String pkg : closedPackages) {
            resetUidAudioPolicyState(pkg);
            log("STOP_SESSION_CLEANUP_DONE package=" + pkg);
        }

        if (reason != null && !"restart".equals(reason)) {
            state("STOPPED", forceCloseTarget
                    ? "Effects stopped. Force Close setting was enabled for the tracked target."
                    : "Effects stopped without force-closing the tracked target.");
            log("MONITOR_STOP reason=" + reason + " forceClose=" + (forceCloseTarget && stopReason)
                    + " closed=" + closedPackages);
        }
    }

    private boolean forceStopPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(context.getPackageName())) return false;

        // This is the exact command path used by the older working EchoRoute versions.
        // Keep it simple: the Shizuku UserService already runs as shell UID 2000.
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "activity", "force-stop", packageName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 512) {
                    out.append(line);
                }
            }
            int rc = p.waitFor();
            log("FORCE_STOP command=cmd activity force-stop " + packageName
                    + " rc=" + rc
                    + (out.length() == 0 ? "" : " output=" + out));
            return rc == 0;
        } catch (Throwable t) {
            log("FORCE_STOP_FAILED package=" + packageName + " error=" + rootMessage(t));
            Log.w(TAG, "force-stop failed for " + packageName, t);
            return false;
        }
    }

    private void waitForPackageToDisappear(String packageName, int uid, int sessionId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
                boolean found = false;
                if (configs != null) {
                    for (AudioRecordingConfiguration c : configs) {
                        String pkg = clientPackage(c);
                        int cu = clientUid(c);
                        if (packageName.equals(pkg) || (uid > 0 && cu == uid)) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    log("FORCE_STOP_AUDIO_GONE package=" + packageName
                            + " uid=" + uid + " session=" + sessionId);
                    return;
                }
            } catch (Throwable t) {
                log("FORCE_STOP_AUDIO_CHECK_FAILED package=" + packageName + " error=" + t);
                return;
            }
            sleepQuietly(100);
        }
        state("WARNING", "Force-stop sent, but AudioPolicy still reports the microphone briefly for "
                + packageName + ". Continuing cleanup.");
        log("FORCE_STOP_AUDIO_GONE_TIMEOUT package=" + packageName
                + " uid=" + uid + " session=" + sessionId);
    }

    private void clearPersistedState() {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_IDS).apply();
        } catch (Throwable ignored) {}
    }

    private void persistState() {
        try {
            if (installedIds.isEmpty()) { clearPersistedState(); return; }
            HashSet<String> ids = new HashSet<>();
            for (Integer id : installedIds) ids.add(String.valueOf(id));
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet(PREF_IDS, ids).apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist state failed", t);
        }
    }

    private void removeEffectById(int id) {
        try {
            Object service = getAudioPolicyService();
            Method remove = findMethod(service.getClass(), "removeSourceDefaultEffect");
            if (remove == null) throw new NoSuchMethodException("IAudioPolicyService.removeSourceDefaultEffect");
            remove.invoke(service, id);
            Log.i(TAG, "Removed source-default effect id=" + id);
        } catch (Throwable t) {
            Log.w(TAG, "Failed removing source-default effect id=" + id, t);
            EchoAppLog.line(context, "SOURCE_DEFAULT_REMOVE_FAILED id=" + id + " error=" + t);
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                return m;
            }
        }
        return null;
    }

    private Object getAudioPolicyService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "media.audio_policy");
        if (binder == null) binder = (IBinder) getService.invoke(null, "audio_policy");
        if (binder == null) throw new IllegalStateException("media.audio_policy binder unavailable");
        Class<?> stub = Class.forName("android.media.IAudioPolicyService$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    private Object toAudioUuid(UUID u) throws Exception {
        Class<?> c = Class.forName("android.media.audio.common.AudioUuid");
        Object out = c.getConstructor().newInstance();
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        setInt(c, out, "timeLow", (int) (msb >>> 32));
        setInt(c, out, "timeMid", (int) ((msb >>> 16) & 0xffff));
        setInt(c, out, "timeHiAndVersion", (int) (msb & 0xffff));
        setInt(c, out, "clockSeq", (int) ((lsb >>> 48) & 0xffff));
        byte[] node = new byte[6];
        for (int i = 0; i < 6; i++) {
            node[i] = (byte) ((lsb >>> (40 - 8 * i)) & 0xff);
        }
        Field nodeField = c.getField("node");
        nodeField.set(out, node);
        return out;
    }

    private void setInt(Class<?> c, Object obj, String field, int value) throws Exception {
        c.getField(field).setInt(obj, value);
    }

    private String describeConfigs(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (AudioRecordingConfiguration c : configs) {
            if (sb.length() > 0) sb.append(" | ");
            String pkg = clientPackage(c);
            int uid = clientUid(c);
            if (pkg == null || pkg.isEmpty()) pkg = "uid:" + uid;
            sb.append(pkg)
                    .append("/uid=").append(uid)
                    .append("/session=").append(c.getClientAudioSessionId())
                    .append("/port=").append(clientPortId(c))
                    .append("/silenced=").append(c.isClientSilenced())
                    .append("/source=").append(c.getClientAudioSource())
                    .append("/effects=").append(effectNames(c.getEffects()));
        }
        return sb.toString();
    }

    private String effectNames(List<AudioEffect.Descriptor> effects) {
        if (effects == null || effects.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (AudioEffect.Descriptor d : effects) {
            if (d == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(d.name == null ? d.type : d.name);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private String clientPackage(AudioRecordingConfiguration config) {
        try {
            Method m = AudioRecordingConfiguration.class.getMethod("getClientPackageName");
            Object value = m.invoke(config);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int clientUid(AudioRecordingConfiguration config) {
        try {
            Method m = AudioRecordingConfiguration.class.getMethod("getClientUid");
            Object value = m.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int clientPortId(AudioRecordingConfiguration config) {
        if (config == null) return -1;
        try {
            Method m = AudioRecordingConfiguration.class.getMethod("getClientPortId");
            Object value = m.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Temporarily releases exactly one app microphone input through AudioPolicy.
     * This does not force-stop the app process.
     */
    private boolean releaseMicInput(Candidate candidate, String reason, long holdMs) {
        if (candidate == null || candidate.config == null) return false;

        final int portId = clientPortId(candidate.config);
        final long startedAt = System.currentTimeMillis();

        state("MIC_CLOSE", "Closing microphone input for " + candidate.packageName
                + " | uid=" + candidate.uid
                + " | session=" + candidate.sessionId
                + " | portId=" + portId
                + " | hold=" + holdMs + "ms");
        log("MIC_CLOSE_BEGIN package=" + candidate.packageName
                + " uid=" + candidate.uid
                + " session=" + candidate.sessionId
                + " portId=" + portId
                + " holdMs=" + holdMs
                + " reason=" + reason);

        boolean idleOk = setUidAudioPolicyState(candidate.packageName, "idle");
        boolean released = false;

        try {
            Object service = getAudioPolicyService();

            if (portId <= 0) {
                throw new IllegalStateException("Invalid client port ID: " + portId);
            }

            Method stop = findMethod(service.getClass(), "stopInput");
            if (stop != null) {
                try {
                    Object result = stop.invoke(service, portId);
                    log("MIC_CLOSE_STOP_INPUT portId=" + portId + " result=" + result);
                } catch (Throwable t) {
                    log("MIC_CLOSE_STOP_INPUT_FAILED portId=" + portId
                            + " error=" + rootMessage(t));
                }
            } else {
                log("MIC_CLOSE_STOP_INPUT_UNAVAILABLE");
            }

            Method release = findMethod(service.getClass(), "releaseInput");
            if (release == null) {
                throw new NoSuchMethodException("IAudioPolicyService.releaseInput");
            }

            Object result = release.invoke(service, portId);
            released = true;
            log("MIC_CLOSE_RELEASE_INPUT portId=" + portId + " result=" + result);
        } catch (Throwable t) {
            state("WARNING", "releaseInput failed for " + candidate.packageName
                    + ": " + rootMessage(t));
            log("MIC_CLOSE_RELEASE_INPUT_FAILED package=" + candidate.packageName
                    + " session=" + candidate.sessionId
                    + " portId=" + portId
                    + " error=" + t);
        }

        waitForSessionToDisappear(candidate.sessionId, candidate.uid, candidate.packageName, 1200);

        long remaining = holdMs - (System.currentTimeMillis() - startedAt);
        if (remaining > 0) {
            state("MIC_CLOSE", "Microphone remains closed for " + remaining + "ms.");
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log("MIC_CLOSE_DONE package=" + candidate.packageName
                + " uid=" + candidate.uid
                + " session=" + candidate.sessionId
                + " portId=" + portId
                + " released=" + released
                + " idleOk=" + idleOk);
        return released || idleOk;
    }

    private void restoreSuspendedEffectsIfPossible() {
        int session;
        synchronized (lock) { session = currentSessionId; }
        if (session > 0) {
            restoreSessionEffectsForSelection(session);
        }
    }

    private void restoreSessionEffectsForSelection(int sessionId) {
        String pkg = currentPackage;
        try {
            for (AudioRecordingConfiguration c : audioManager.getActiveRecordingConfigurations()) {
                if (c.getClientAudioSessionId() != sessionId) continue;
                String p = clientPackage(c);
                if (p != null && !p.isEmpty()) pkg = p;
            }
        } catch (Throwable ignored) {}
        enforceSessionPolicy(sessionId, pkg, currentUid);
    }

    private void suspendSessionEffects(int sessionId, boolean aec, boolean ns, boolean agc,
                                       boolean suspended, String reason) {
        if (sessionId <= 0) return;
        List<EffectInstance> effects = querySessionEffects(sessionId);
        if (effects.isEmpty()) {
            log("EFFECT_SUSPEND_NO_INSTANCES session=" + sessionId + " reason=" + reason);
            return;
        }
        int matched = 0;
        for (EffectInstance e : effects) {
            boolean match = (aec && isAecType(e.type))
                    || (ns && isNsType(e.type))
                    || (agc && isAgc2Type(e.type));
            if (!match) continue;
            matched++;
            if (invokeSetEffectSuspended(e.id, sessionId, suspended)) {
                synchronized (lock) {
                    if (suspended) {
                        suspendedEffectIds.add(e.id);
                        suspendedSessionId = sessionId;
                    } else {
                        suspendedEffectIds.remove(e.id);
                        if (suspendedEffectIds.isEmpty()) suspendedSessionId = -1;
                    }
                }
            }
        }
        log("EFFECT_SUSPEND_RESULT session=" + sessionId
                + " requested=" + suspended
                + " matched=" + matched
                + " ids=" + effectsToIds(effects)
                + " reason=" + reason);
    }

    private boolean invokeSetEffectSuspended(int effectId, int sessionId, boolean suspended) {
        if (effectId <= 0 || sessionId <= 0) return false;
        try {
            Object service = getAudioFlingerService();
            Method m = findMethod(service.getClass(), "setEffectSuspended");
            if (m == null) throw new NoSuchMethodException("IAudioFlinger.setEffectSuspended");
            Object result = m.invoke(service, effectId, sessionId, suspended);
            log("EFFECT_SUSPEND_CALL effectId=" + effectId
                    + " session=" + sessionId
                    + " suspended=" + suspended
                    + " result=" + result);
            return true;
        } catch (Throwable t) {
            state("WARNING", "setEffectSuspended failed effect=" + effectId
                    + " session=" + sessionId + ": " + rootMessage(t));
            log("EFFECT_SUSPEND_FAILED effectId=" + effectId
                    + " session=" + sessionId
                    + " suspended=" + suspended
                    + " error=" + t);
            return false;
        }
    }

    private Object getAudioFlingerService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "media.audio_flinger");
        if (binder == null) binder = (IBinder) getService.invoke(null, "audio_flinger");
        if (binder == null) throw new IllegalStateException("media.audio_flinger binder unavailable");
        Class<?> stub = Class.forName("android.media.IAudioFlinger$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    private List<EffectInstance> querySessionEffects(int sessionId) {
        List<EffectInstance> result = new ArrayList<>();
        if (sessionId <= 0) return result;
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "media.audio_flinger")
                    .redirectErrorStream(true).start();
            StringBuilder block = new StringBuilder();
            boolean inSession = false;
            String line;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
            while ((line = reader.readLine()) != null) {
                if (line.contains("Effects for session " + sessionId)) {
                    inSession = true;
                    block.setLength(0);
                    block.append(line).append('\n');
                    continue;
                }
                if (inSession && line.contains("Effects for session ")) break;
                if (inSession) block.append(line).append('\n');
            }
            }
            process.waitFor();
            String[] lines = block.toString().split("\\n");
            int currentId = -1;
            String currentType = "";
            String currentName = "";
            for (String l : lines) {
                Matcher idm = EFFECT_ID_PATTERN.matcher(l.trim());
                if (idm.find()) {
                    if (currentId > 0) result.add(new EffectInstance(currentId, currentType, currentName));
                    currentId = Integer.parseInt(idm.group(1));
                    currentType = "";
                    currentName = "";
                    continue;
                }
                Matcher tm = TYPE_PATTERN.matcher(l.trim());
                if (tm.find()) currentType = tm.group(1).toLowerCase();
                String t = l.trim();
                if (t.startsWith("- name:")) currentName = t.substring(7).trim();
                else if (t.startsWith("name:")) currentName = t.substring(5).trim();
            }
            if (currentId > 0) result.add(new EffectInstance(currentId, currentType, currentName));
            log("EFFECT_QUERY session=" + sessionId + " instances=" + effectsToIds(result)
                    + " details=" + effectsToDetails(result));
        } catch (Throwable t) {
            log("EFFECT_QUERY_FAILED session=" + sessionId + " error=" + t);
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
        return result;
    }

    private boolean isManagedType(String type) {
        return isAecType(type) || isNsType(type) || isAgc2Type(type);
    }

    private boolean isAecType(String type) {
        return type != null && (AEC_TYPE_TEXT.equalsIgnoreCase(type) || AEC_TYPE.toString().equalsIgnoreCase(type));
    }

    private boolean isNsType(String type) {
        return type != null && (NS_TYPE_TEXT.equalsIgnoreCase(type) || NS_TYPE.toString().equalsIgnoreCase(type));
    }

    private boolean isAgc2Type(String type) {
        return type != null && AGC2_TYPE.toString().equalsIgnoreCase(type);
    }

    private String effectsToIds(List<EffectInstance> effects) {
        StringBuilder sb = new StringBuilder("[");
        for (EffectInstance e : effects) {
            if (sb.length() > 1) sb.append(',');
            sb.append(e.id);
        }
        return sb.append(']').toString();
    }

    private String effectsToDetails(List<EffectInstance> effects) {
        StringBuilder sb = new StringBuilder("[");
        for (EffectInstance e : effects) {
            if (sb.length() > 1) sb.append(';');
            sb.append(e.id).append('/').append(e.name).append('/').append(e.type);
        }
        return sb.append(']').toString();
    }

    private static final class EffectInstance {
        final int id;
        final String type;
        final String name;
        EffectInstance(int id, String type, String name) {
            this.id = id;
            this.type = type == null ? "" : type;
            this.name = name == null ? "" : name;
        }
    }

    private void state(String state, String detail) {
        StateSink local;
        synchronized (lock) { local = sink; }
        if (local != null) {
            try { local.state(state, detail); } catch (Throwable ignored) {}
        }
        log("[" + state + "] " + detail);
    }

    private void log(String line) {
        Log.i(TAG, line);
        EchoAppLog.line(context, line);
    }

    private String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        String message = String.valueOf(x.getMessage());
        return x.getClass().getSimpleName() + (message == null || "null".equals(message) ? "" : ": " + message);
    }

    interface StateSink { void state(String state, String detail); }

    static final class Candidate {
        final AudioRecordingConfiguration config;
        final int sessionId;
        final int uid;
        final String packageName;
        final int source;

        Candidate(AudioRecordingConfiguration config, int sessionId, int uid,
                  String packageName, int source) {
            this.config = config;
            this.sessionId = sessionId;
            this.uid = uid;
            this.packageName = packageName;
            this.source = source;
        }
    }

    /** Saved effect instances of one live microphone session (effect ID -> AEC/NS/AGC). */
    static final class SessionRecord {
        final int session;
        final int uid;
        final String pkg;
        final Map<Integer, String> effects = new LinkedHashMap<>();
        final Set<Integer> suspended = new HashSet<>();

        SessionRecord(int session, int uid, String pkg) {
            this.session = session;
            this.uid = uid;
            this.pkg = pkg;
        }
    }

    private static final class CloseJob {
        final String pkg;
        final int uid;
        final int session;
        final Set<String> names;

        CloseJob(String pkg, int uid, int session, Set<String> names) {
            this.pkg = pkg;
            this.uid = uid;
            this.session = session;
            this.names = names;
        }
    }

    static final class SourceSpec {
        final int value;
        final String name;
        SourceSpec(int value, String name) {
            this.value = value;
            this.name = name;
        }
    }

    static final class EffectInfo {
        final UUID implUuid;
        EffectInfo(UUID implUuid) { this.implUuid = implUuid; }
    }

    static final class EffectFinder {
        static EffectInfo find(UUID type) {
            try {
                AudioEffect.Descriptor[] ds = AudioEffect.queryEffects();
                if (ds == null) return null;
                for (AudioEffect.Descriptor d : ds) {
                    if (d != null && type.equals(d.type)) return new EffectInfo(d.uuid);
                }
            } catch (Throwable t) {
                Log.e(TAG, "AudioEffect.queryEffects failed", t);
            }
            return null;
        }
    }
}
