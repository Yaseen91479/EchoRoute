/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import rikka.shizuku.Shizuku;

/** Persistent foreground controller and Shizuku client for the automatic session-effect monitor. */
public final class EchoKeepAliveService extends Service {
    static final String ACTION_START = "com.echoroute.aec.action.START";
    static final String ACTION_STOP = "com.echoroute.aec.action.STOP";
    static final String ACTION_EXIT = "com.echoroute.aec.action.EXIT";
    static final String ACTION_SYNC = "com.echoroute.aec.action.SYNC";
    static final String ACTION_CONFIG = "com.echoroute.aec.action.CONFIG";
    static final String ACTION_FORCE_CLOSE = "com.echoroute.aec.action.FORCE_CLOSE";
    private static volatile EchoKeepAliveService instance;

    private static final String CHANNEL_ID = "echoroute_controller";
    private static final int NOTIFICATION_ID = 2602;
    private static final int SHIZUKU_REQUEST = 7311;
    private static final String PREFS = "echoroute";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_MODE = "mode";
    private static final String PREF_CONTROL_MODE = "control_mode";
    private static final String PREF_TARGET_PACKAGE = "target_package";

    private IEchoUserService userService;
    private boolean bound;

    private final IEchoCallback callback = new IEchoCallback.Stub() {
        @Override public void onState(String state, String detail) {
            String line = "[" + state + "] " + detail;
            EchoAppLog.line(EchoKeepAliveService.this, line);
            updateNotification();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            userService = IEchoUserService.Stub.asInterface(service);
            EchoAppLog.line(EchoKeepAliveService.this, "SHIZUKU_SERVICE_CONNECTED");
            if (isEnabled()) applyStart();
            else applyStop();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            userService = null;
            EchoAppLog.line(EchoKeepAliveService.this, "SHIZUKU_SERVICE_DISCONNECTED");
            updateNotification();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
        } catch (Throwable t) {
            EchoAppLog.line(this, "SHIZUKU_LISTENER_SETUP_FAILED " + t);
        }
        EchoAppLog.line(this, "SERVICE_CREATE pid=" + android.os.Process.myPid());
        EchoAppLog.line(this, "STATE_RESTORED " + EchoState.describe(this));
        instance = this;
    }

    private final Shizuku.OnBinderReceivedListener binderListener =
            () -> {
                EchoAppLog.line(this, "SHIZUKU_BINDER_RECEIVED");
                // Resume (enabled) or finish a pending cleanup (stopped while Shizuku was off).
                if (isEnabled() || EchoState.isDirty(this)) bindUserServiceIfPossible();
                updateNotification();
            };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> {
                EchoAppLog.line(this, "SHIZUKU_BINDER_DEAD");
                EchoState.save(this, isEnabled() ? "WAITING_FOR_SHIZUKU" : "STOPPED", "shizuku-binder-dead");
                updateNotification();
            };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SYNC : intent.getAction();
        EchoAppLog.line(this, "SERVICE_COMMAND action=" + action + " startId=" + startId);
        if (ACTION_START.equals(action)) {
            getPrefs().edit().putBoolean(PREF_ENABLED, true).apply();
            bindUserServiceIfPossible();
        } else if (ACTION_CONFIG.equals(action)) {
            try {
                if (isEnabled() && userService != null) {
                    userService.updateConfig(AppEffectsConfig.effectiveConfig(getPrefs()),
                            AppEffectsConfig.ignoredString(getPrefs()));
                }
            } catch (Throwable t) {
                EchoAppLog.line(this, "CONFIG_UPDATE_FAILED " + t);
            }
        } else if (ACTION_FORCE_CLOSE.equals(action)) {
            forceCloseMicAsync();
        } else if (ACTION_STOP.equals(action)) {
            getPrefs().edit().putBoolean(PREF_ENABLED, false).apply();
            // Closing mics can take a few seconds: never block the main thread.
            new Thread(this::applyStop, "EchoRoute-Stop").start();
            // Shizuku is off: remember to clean up as soon as it is back.
            if (userService == null) bindUserServiceIfPossible();
        } else if (ACTION_EXIT.equals(action)) {
            getPrefs().edit().putBoolean(PREF_ENABLED, false).apply();
            new Thread(() -> {
                applyStop();
                new Handler(Looper.getMainLooper()).post(() -> {
                    disconnectUserService();
                    EchoAppLog.line(this, "SERVICE_EXIT");
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }, "EchoRoute-Exit").start();
        } else {
            if (isEnabled() || EchoState.isDirty(this)) bindUserServiceIfPossible();
            updateNotification();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { applyStop(); } catch (Throwable ignored) {}
        disconnectUserService();
        try { Shizuku.removeBinderReceivedListener(binderListener); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener); } catch (Throwable ignored) {}
        EchoAppLog.line(this, "SERVICE_DESTROY");
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void bindUserServiceIfPossible() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13) {
                EchoAppLog.line(this, "SHIZUKU_UNAVAILABLE");
                updateNotification();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                EchoAppLog.line(this, "SHIZUKU_PERMISSION_NOT_GRANTED");
                updateNotification();
                return;
            }
            if (bound || userService != null) {
                if (isEnabled()) applyStart(); else applyStop();
                return;
            }
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), EchoUserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("effects")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
            Shizuku.bindUserService(args, connection);
            EchoAppLog.line(this, "SHIZUKU_BIND_REQUESTED");
        } catch (Throwable t) {
            EchoAppLog.line(this, "SHIZUKU_BIND_FAILED " + t);
            updateNotification();
        }
    }

    /** Counts configured targets from the comma/semicolon/newline-delimited preference value. */
    private static int countTargets(String configs) {
        if (configs == null) return 0;
        String value = configs.trim();
        if (value.isEmpty()) return 0;
        int count = 0;
        for (String item : value.split("[,;\\r\\n]+")) {
            if (!item.trim().isEmpty()) count++;
        }
        return count;
    }

    private void applyStart() {
        if (!isEnabled() || userService == null) return;
        String mode = getPrefs().getString(PREF_MODE, "AEC+NS+AGC2");
        String controlMode = getPrefs().getString(PREF_CONTROL_MODE, "automatic");
        boolean automatic = !"manual".equals(controlMode);
        String targetPackage = getPrefs().getString(PREF_TARGET_PACKAGE, "");
        boolean aec = mode.contains("AEC");
        boolean ns = mode.contains("NS");
        boolean agc = mode.contains("AGC2");
        try {
            userService.start(aec, ns, agc, automatic, targetPackage, getPrefs().getBoolean("force_close_target", true),
                    AppEffectsConfig.effectiveConfig(getPrefs()), AppEffectsConfig.ignoredString(getPrefs()), callback);
            EchoAppLog.line(this, "ROUTE_START mode=" + mode + " controlMode=" + controlMode + " target=" + targetPackage);
            EchoState.setDirty(this, true);
            EchoState.save(this, automatic ? "RUNNING_AUTOMATIC" : "RUNNING_MANUAL", "route-start");
        } catch (Throwable t) {
            EchoAppLog.line(this, "ROUTE_START_FAILED " + t);
        }
        updateNotification();
    }

    /** Blocking: call from a background thread. Lines "package|session|AEC+NS" or null if not running. */
    static String queryApplied() {
        EchoKeepAliveService svc = instance;
        if (svc == null || svc.userService == null) return null;
        try { return svc.userService.queryAppliedEffects(); } catch (Throwable t) { return null; }
    }

    private void applyStop() {
        boolean done = false;
        try {
            IEchoUserService svc = userService;
            if (svc != null) {
                svc.stop();
                done = true;
            }
        } catch (Throwable t) {
            EchoAppLog.line(this, "ROUTE_STOP_FAILED " + t);
        }
        if (done) {
            EchoState.setDirty(this, false);
            EchoState.save(this, "STOPPED", "route-stop");
        } else if (EchoState.isDirty(this)) {
            EchoState.save(this, "STOPPED_CLEANUP_PENDING", "shizuku-unavailable");
            EchoAppLog.line(this, "[INFO] Stopped. Cleanup will finish automatically when Shizuku is back.");
        } else {
            EchoState.save(this, "STOPPED", "route-stop");
        }
        updateNotification();
    }

    /** Notification "Force close": closes the microphone of the app that is recording. */
    private void forceCloseMicAsync() {
        final IEchoUserService svc = userService;
        if (svc == null) {
            EchoAppLog.line(this, "[WARNING] Force close: not connected to Shizuku yet. Start Shizuku and try again.");
            bindUserServiceIfPossible();
            return;
        }
        new Thread(() -> {
            try {
                String r = svc.forceCloseMic();
                EchoAppLog.line(this, "FORCE_CLOSE_MIC_RESULT " + String.valueOf(r).replace('\n', ' '));
            } catch (Throwable t) {
                EchoAppLog.line(this, "[WARNING] Force close failed: " + t);
            }
            updateNotification();
        }, "EchoRoute-ForceClose").start();
    }

    private void disconnectUserService() {
        if (!bound) return;
        try {
            Shizuku.unbindUserService(new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), EchoUserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("effects")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE), connection, true);
        } catch (Throwable ignored) {}
        bound = false;
        userService = null;
    }

    private boolean isEnabled() {
        return getPrefs().getBoolean(PREF_ENABLED, false);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "EchoRoute", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps EchoRoute available and controls microphone effects.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, EchoKeepAliveService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private PendingIntent openAppAction() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 2605, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent exitAction() {
        Intent intent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_EXIT_FROM_NOTIFICATION)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 2603, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat() {
        updateNotification();
    }

    void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        boolean enabled = isEnabled();
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        boolean manual = "manual".equals(getPrefs().getString(PREF_CONTROL_MODE, "automatic"));
        String target = getPrefs().getString(PREF_TARGET_PACKAGE, "");
        builder.setSmallIcon(R.drawable.ic_stat_echo)
                .setColor(0xFF88E4CD)
                .setContentTitle("EchoRoute")
                .setContentText(enabled
                        ? (userService == null ? "Waiting for Shizuku…"
                            : (manual ? "Manual: " + AppEffectsConfig.loadPerApp(getPrefs()).size() + " app(s)" : "Automatic microphone monitoring"))
                        : (EchoState.isDirty(this) ? "Stopped · cleanup pending (Shizuku off)" : "Effects stopped"))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppAction())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        enabled ? "STOP" : "START",
                        serviceAction(enabled ? ACTION_STOP : ACTION_START, 2604)).build());
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_delete,
                "FORCE CLOSE",
                serviceAction(ACTION_FORCE_CLOSE, 2606)).build());
        builder.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "EXIT",
                        exitAction()).build());

        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
