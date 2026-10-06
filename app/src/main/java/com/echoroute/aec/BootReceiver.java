/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */

package com.echoroute.aec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the saved EchoRoute state after the phone reboots or the app is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        // After a reboot the audio server was restarted, so nothing is registered any more.
        if (boot) EchoState.setDirty(context, false);
        boolean enabled = context.getSharedPreferences("echoroute", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (!enabled && !EchoState.isDirty(context)) return;
        EchoAppLog.line(context, "BOOT_RESTORE action=" + action + " " + EchoState.describe(context));
        try {
            context.startForegroundService(new Intent(context, EchoKeepAliveService.class)
                    .setAction(EchoKeepAliveService.ACTION_SYNC));
        } catch (Throwable t) {
            EchoAppLog.line(context, "BOOT_RESTORE_FAILED " + t);
        }
    }
}
