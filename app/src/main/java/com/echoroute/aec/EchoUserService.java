/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/** Shizuku UserService: installs global source-default microphone effects and monitors sessions. */
public class EchoUserService extends IEchoUserService.Stub {
    private static final String TAG = "EchoRouteAEC";
    private final Context context;
    private PlatformDefaultEffects effects;
    private IBinder linkedClient;
    private final IBinder.DeathRecipient clientDeath;

    public EchoUserService(Context context) {
        this.context = context;
        this.clientDeath = () -> {
            synchronized (EchoUserService.this) {
                Log.w(TAG, "EchoRoute controller binder died; removing session effects immediately.");
                EchoAppLog.line(this.context, "CONTROLLER_DEATH -> cleanup session effects");
                cleanupLocked("controller-death");
            }
        };
    }

    @Override
    public synchronized void start(boolean enableAec, boolean enableNs, boolean enableAgc,
                                   boolean automaticMode, String targetPackage, boolean forceCloseTarget,
                                   String perAppConfig, String ignoredAutoApps,
                                   IEchoCallback callback) {
        cleanupClientLinkLocked();
        try {
            if (effects == null) effects = new PlatformDefaultEffects(context);
            effects.start(enableAec, enableNs, enableAgc, automaticMode, targetPackage, forceCloseTarget,
                    perAppConfig, ignoredAutoApps,
                    (state, detail) -> send(callback, state, detail));
            linkClientLocked(callback);
        } catch (Throwable t) {
            Log.e(TAG, "Session effect monitor start failed", t);
            EchoAppLog.line(context, "START_FAILED " + rootMessage(t));
            cleanupLocked("start-failed");
            send(callback, "ERROR", rootMessage(t));
        }
    }

    @Override
    public synchronized void stop() {
        if (effects == null) {
            // Nothing in memory (for example Shizuku restarted): still remove stale registrations.
            try { new PlatformDefaultEffects(context).cleanupStale(); } catch (Throwable t) {
                Log.w(TAG, "stale cleanup on stop failed", t);
            }
        }
        cleanupLocked("controller-stop");
    }

    @Override
    public synchronized boolean isRunning() {
        return effects != null && effects.isRunning();
    }

    @Override
    public String forceCloseMic() {
        PlatformDefaultEffects e;
        synchronized (this) { e = effects; }
        if (e == null) e = new PlatformDefaultEffects(context);   // works even when effects are stopped
        return e.forceCloseMic();
    }

    @Override
    public synchronized void updateConfig(String perAppConfig, String ignoredAutoApps) {
        if (effects != null) effects.updateConfig(perAppConfig, ignoredAutoApps);
    }

    @Override
    public String queryAppliedEffects() {
        PlatformDefaultEffects e;
        synchronized (this) { e = effects; }
        return e == null ? "" : e.queryAppliedEffects();
    }

    private void cleanupLocked(String reason) {
        cleanupClientLinkLocked();
        if (effects != null) {
            try { effects.stop(); } catch (Throwable t) {
                Log.w(TAG, "effect monitor cleanup failed reason=" + reason, t);
            }
            effects = null;
        }
    }

    private void linkClientLocked(IEchoCallback callback) {
        if (callback == null) return;
        IBinder b = callback.asBinder();
        try {
            b.linkToDeath(clientDeath, 0);
            linkedClient = b;
            Log.i(TAG, "Linked controller binder for automatic effect cleanup.");
        } catch (RemoteException e) {
            Log.w(TAG, "Controller already dead while linking cleanup binder", e);
            cleanupLocked("client-already-dead");
        }
    }

    private void cleanupClientLinkLocked() {
        if (linkedClient == null) return;
        try { linkedClient.unlinkToDeath(clientDeath, 0); } catch (Throwable ignored) {}
        linkedClient = null;
    }

    private void send(IEchoCallback cb, String state, String detail) {
        try { if (cb != null) cb.onState(state, detail); } catch (Throwable ignored) {}
    }

    private String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        return x.getClass().getSimpleName() + ": " + String.valueOf(x.getMessage());
    }
}
