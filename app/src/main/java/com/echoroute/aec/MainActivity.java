/*
 * EchoRoute
 * Copyright (c) 2026 Yaseen91479
 * Contact: yaseenwaleeddis99@gmail.com
 * GitHub: Yaseen91479
 * All rights reserved. See the project LICENSE file.
 */
package com.echoroute.aec;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import rikka.shizuku.Shizuku;

/**
 * EchoRoute main screen. Three tabs:
 *  Control  - start/stop, Automatic/Manual, default effects, Force Close.
 *  Activity - saved apps with their own effects (pencil = edit, X = remove effects from that app only).
 *  Log      - User view (green summary, no scrolling) and Developer view (full real log, auto-scroll).
 * Shizuku status (red = off, green = on) is always visible at the top.
 */
public class MainActivity extends Activity {
    static final String ACTION_EXIT_FROM_NOTIFICATION =
            "com.echoroute.aec.action.EXIT_FROM_NOTIFICATION";
    private static final int SHIZUKU_REQUEST = 7311;
    private static final int NOTIFICATION_REQUEST = 7412;
    private static final String PREFS = "echoroute";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_MODE = "mode";
    private static final String PREF_CONTROL_MODE = "control_mode";
    private static final String PREF_TARGET_PACKAGE = "target_package";
    private static final String PREF_FORCE_CLOSE = "force_close_target";

    // Palette sampled from the reference screenshot (dark blue-black + mint).
    private static final int BG = Color.rgb(10, 15, 19);          // page background
    private static final int SURFACE = Color.rgb(15, 25, 27);     // cards, nav bar
    private static final int SURFACE2 = Color.rgb(35, 68, 61);    // selected pill / chips
    private static final int LINE = Color.rgb(26, 40, 43);        // subtle card edge
    private static final int TEXT = Color.rgb(250, 253, 253);
    private static final int MUTED = Color.rgb(168, 180, 187);
    private static final int GREEN = Color.rgb(136, 228, 205);    // mint accent
    private static final int RED = Color.rgb(242, 109, 109);
    private static final int AMBER = Color.rgb(232, 176, 74);
    // Primary buttons: deep teal fill with a lighter edge and white text (like START in the screenshot).
    private static final int BTN_START = Color.rgb(35, 91, 78);
    private static final int BTN_START_EDGE = Color.rgb(66, 116, 104);
    private static final int BTN_STOP = Color.rgb(125, 48, 56);
    private static final int BTN_STOP_EDGE = Color.rgb(176, 84, 92);

    // Log tab names (long-press a tab to rename it; saved in prefs).
    private static final String PREF_LOG_NAME_SIMPLE = "log_name_simple";
    private static final String PREF_LOG_NAME_FULL = "log_name_full";
    private static final String DEFAULT_LOG_NAME_SIMPLE = "Results";
    private static final String DEFAULT_LOG_NAME_FULL = "Full log";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> labelCache = new HashMap<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // shell
    private FrameLayout content;
    private View controlView;
    private View appsView;
    private View logView;
    private final TextView[] tabs = new TextView[3];
    private int currentTab = 0;
    private View shizukuDot;
    private TextView shizukuText;
    private ObjectAnimator pulse;
    private boolean resumed;

    // control tab
    private TextView runTitle;
    private TextView runSub;
    private TextView startButton;
    private TextView modeAuto;
    private TextView modeManual;
    private TextView modeDesc;
    private TextView lockHint;
    private LinearLayout effectsCard;
    private CheckBox aecBox;
    private CheckBox nsBox;
    private CheckBox agcBox;
    private CheckBox forceBox;

    // activity tab
    private LinearLayout appsList;

    // log tab
    private boolean devLog = false;
    private TextView logUserTab;
    private TextView logDevTab;
    private FrameLayout logBody;
    private LinearLayout userLogBox;
    private ScrollView devScroll;
    private TextView devText;
    private String lastRaw = "";
    private boolean devFirst = true;
    private boolean shizukuStartFailed = false;

    // motion state
    private View topBar;
    private View navBar;
    private GradientDrawable statusBg;
    private ValueAnimator statusPulse;
    private int lastFill = 0;
    private GradientDrawable dotDrawable;
    private int lastDotColor = 0;
    private boolean logAnimate = false;
    private int userLineIdx = 0;

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            if (!resumed || currentTab != 2) return;
            refreshLog();
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable shizukuTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateShizuku();
            updateUi();
            ui.postDelayed(this, 2000);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> runOnUiThread(this::updateShizuku);
    private final Shizuku.OnBinderDeadListener binderDead =
            () -> runOnUiThread(this::updateShizuku);

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestNotificationPermissionIfNeeded();
        migrateLegacyTarget();
        restorePrefs();
        selectTab(0);
        if (state == null) playIntro();
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
        } catch (Throwable ignored) {}
        appendStatus("INFO", "EchoRoute ready.");
        if (isEnabled() || EchoState.isDirty(this)) {
            appendStatus("INFO", "Last state restored: " + EchoState.describe(this));
        }

        if (ACTION_EXIT_FROM_NOTIFICATION.equals(getIntent().getAction())) {
            performExit();
            return;
        }
        syncControllerService(EchoKeepAliveService.ACTION_SYNC);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_EXIT_FROM_NOTIFICATION.equals(intent.getAction())) performExit();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateShizuku();
        updateUi();
        ui.removeCallbacks(shizukuTick);
        ui.postDelayed(shizukuTick, 2000);
        if (currentTab == 2) {
            ui.removeCallbacks(logTick);
            ui.post(logTick);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        setStatusPulse(false);
        if (pulse != null) pulse.cancel();
        if (shizukuDot != null) shizukuDot.setAlpha(1f);
        ui.removeCallbacks(shizukuTick);
        ui.removeCallbacks(logTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        try { Shizuku.removeBinderReceivedListener(binderReceived); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderDeadListener(binderDead); } catch (Throwable ignored) {}
        removePermissionListener();
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams weight(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(90)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2.4f)).start();
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ motion

    private interface ColorSink { void set(int color); }

    private void animateColor(int from, int to, long ms, final ColorSink sink) {
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        va.setDuration(ms);
        va.addUpdateListener(a -> sink.set((Integer) a.getAnimatedValue()));
        va.start();
    }

    /** Small elastic "pop" used on selection changes. */
    private void popView(View v) {
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(280)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    private void fadeTo(View v, float a) {
        if (Math.abs(v.getAlpha() - a) < 0.01f) return;
        v.animate().alpha(a).setStartDelay(0).setDuration(220).start();
    }

    /** Children slide up and fade in one after another. */
    private void staggerIn(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            final View c = parent.getChildAt(i);
            c.animate().cancel();
            c.setAlpha(0f);
            c.setTranslationY(dp(18));
            c.animate().alpha(1f).translationY(0f).setStartDelay(45L * i).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .withEndAction(() -> c.animate().setStartDelay(0)).start();
        }
    }

    /** Cross-fades a TextView to new text/color; plain set when not visible or unchanged. */
    private void swapText(final TextView t, final String text, final int color) {
        if (t.getText().toString().equals(text)) {
            t.setTextColor(color);
            if (t.getAlpha() < 1f) { t.animate().cancel(); t.setAlpha(1f); }
            return;
        }
        if (!resumed || t.getWindowToken() == null) {
            t.setText(text);
            t.setTextColor(color);
            return;
        }
        t.animate().cancel();
        t.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction(() -> {
            t.setText(text);
            t.setTextColor(color);
            t.animate().alpha(1f).setStartDelay(0).setDuration(170).start();
        }).start();
    }

    private void setStatusPulse(boolean on) {
        if (statusBg == null) return;
        if (on && resumed) {
            if (statusPulse != null && statusPulse.isRunning()) return;
            statusPulse = ValueAnimator.ofObject(new ArgbEvaluator(), LINE, GREEN);
            statusPulse.setDuration(1500);
            statusPulse.setRepeatCount(ValueAnimator.INFINITE);
            statusPulse.setRepeatMode(ValueAnimator.REVERSE);
            statusPulse.addUpdateListener(a -> statusBg.setStroke(dp(1), (Integer) a.getAnimatedValue()));
            statusPulse.start();
        } else {
            if (statusPulse != null) { statusPulse.cancel(); statusPulse = null; }
            statusBg.setStroke(dp(1), LINE);
        }
    }

    private void showAnimated(Dialog d, final View root) {
        root.setAlpha(0f);
        root.setScaleX(0.92f);
        root.setScaleY(0.92f);
        root.setTranslationY(dp(28));
        d.show();
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    private void closeAnimated(final Dialog d, View root) {
        root.animate().cancel();
        root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(dp(16)).setStartDelay(0)
                .setDuration(150).withEndAction(d::dismiss).start();
    }

    private void playIntro() {
        if (topBar != null) {
            topBar.setAlpha(0f);
            topBar.setTranslationY(-dp(20));
            topBar.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        if (navBar != null) {
            navBar.setAlpha(0f);
            navBar.setTranslationY(dp(24));
            navBar.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private TextView primaryButton(String label, Runnable action) {
        TextView t = button(label, BTN_START, TEXT, action);
        t.setBackground(shape(BTN_START, BTN_START_EDGE, 6));
        return t;
    }

    private TextView button(String label, int fill, int fg, Runnable action) {
        TextView t = tv(label, 15, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(shape(fill, 0, 6));
        t.setOnClickListener(v -> action.run());
        pressable(t);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = vbox();
        c.setBackground(shape(SURFACE, LINE, 6));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = tv(s, 13, MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private CheckBox checkbox(String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(15);
        c.setTextColor(TEXT);
        c.setButtonTintList(ColorStateList.valueOf(GREEN));
        c.setPadding(dp(6), dp(8), 0, dp(8));
        return c;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, false);
    }

    private boolean isManual() {
        return "manual".equals(prefs().getString(PREF_CONTROL_MODE, "automatic"));
    }

    private String label(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String out = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            out = String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable ignored) {}
        labelCache.put(pkg, out);
        return out;
    }

    private Drawable icon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return new ColorDrawable(SURFACE2);
        }
    }

    // ------------------------------------------------------------------ shell

    private void buildUi() {
        LinearLayout root = vbox();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        // top bar: title + Shizuku status
        LinearLayout top = hbox();
        top.setPadding(dp(20), dp(14), dp(16), dp(10));
        TextView title = tv("EchoRoute", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, weight(0, 0, 0, 0));
        LinearLayout chip = hbox();
        chip.setBackground(shape(SURFACE, LINE, 6));
        chip.setPadding(dp(10), dp(7), dp(12), dp(7));
        shizukuDot = new View(this);
        chip.addView(shizukuDot, lp(dp(10), dp(10), 0, 0, 8, 0));
        shizukuText = tv("Shizuku", 13, TEXT);
        chip.addView(shizukuText);
        chip.setOnClickListener(v -> onShizukuChipTapped());
        pressable(chip);
        top.addView(chip);
        root.addView(top);
        topBar = top;

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        controlView = buildControl();
        appsView = buildApps();
        logView = buildLog();
        content.addView(controlView);
        content.addView(appsView);
        content.addView(logView);

        // bottom navigation
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout nav = hbox();
        nav.setBackgroundColor(SURFACE);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        String[] names = {"Control", "Activity", "Log"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = tv(names[i], 14, MUTED);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(11), dp(8), dp(11));
            t.setOnClickListener(v -> selectTab(idx));
            pressable(t);
            tabs[i] = t;
            nav.addView(t, weight(3, 0, 3, 0));
        }
        root.addView(nav);
        navBar = nav;
        setContentView(root);
    }

    private void selectTab(int i) {
        final int prev = currentTab;
        currentTab = i;
        View[] views = {controlView, appsView, logView};
        int dir = i > prev ? 1 : (i < prev ? -1 : 0);
        for (int k = 0; k < 3; k++) {
            final View v = views[k];
            final int idx = k;
            boolean sel = k == i;
            if (sel) {
                v.animate().cancel();
                v.setVisibility(View.VISIBLE);
                v.setAlpha(0f);
                v.setTranslationX(dir * dp(36));
                v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(280)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            } else if (k == prev && prev != i && v.getVisibility() == View.VISIBLE) {
                v.animate().cancel();
                v.animate().alpha(0f).translationX(-dir * dp(24)).setStartDelay(0).setDuration(140)
                        .withEndAction(() -> {
                            if (currentTab != idx) {
                                v.setVisibility(View.GONE);
                                v.setTranslationX(0f);
                                v.setAlpha(1f);
                            }
                        }).start();
            } else {
                v.animate().cancel();
                v.setVisibility(View.GONE);
            }
            final TextView t = tabs[k];
            int from = t.getCurrentTextColor();
            int to = sel ? TEXT : MUTED;
            if (from != to) animateColor(from, to, 220, t::setTextColor);
            t.setBackground(sel ? shape(SURFACE2, 0, 6) : null);
            if (sel && prev != i) popView(t);
        }
        ui.removeCallbacks(logTick);
        if (i == 0) staggerIn((ViewGroup) ((ScrollView) controlView).getChildAt(0));
        if (i == 1) {
            renderApps(false);
            staggerIn((ViewGroup) ((ScrollView) appsView).getChildAt(0));
        }
        if (i == 2) {
            devFirst = true;
            lastRaw = "";
            logAnimate = true;
            ui.post(logTick);
        }
    }

    private void updateShizuku() {
        boolean alive = false;
        boolean granted = false;
        try {
            alive = Shizuku.pingBinder();
            if (alive) granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}
        int color = !alive ? RED : (granted ? GREEN : AMBER);
        String text = !alive ? "Shizuku: off" : (granted ? "Shizuku: on" : "Shizuku: allow");
        if (dotDrawable == null) {
            dotDrawable = new GradientDrawable();
            dotDrawable.setShape(GradientDrawable.OVAL);
            dotDrawable.setColor(color);
            shizukuDot.setBackground(dotDrawable);
            lastDotColor = color;
            shizukuText.setText(text);
        } else {
            if (color != lastDotColor) {
                animateColor(lastDotColor, color, 350, c -> dotDrawable.setColor(c));
                lastDotColor = color;
            }
            swapText(shizukuText, text, TEXT);
        }
        if (alive && granted) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(shizukuDot, "alpha", 1f, 0.35f);
                pulse.setDuration(900);
                pulse.setRepeatCount(ObjectAnimator.INFINITE);
                pulse.setRepeatMode(ObjectAnimator.REVERSE);
            }
            if (!pulse.isStarted()) pulse.start();
        } else {
            if (pulse != null) pulse.cancel();
            shizukuDot.setAlpha(1f);
        }
    }

    private void onShizukuChipTapped() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.addRequestPermissionResultListener(permissionListener);
                Shizuku.requestPermission(SHIZUKU_REQUEST);
                return;
            }
        } catch (Throwable ignored) {}
        updateShizuku();
    }

    // ------------------------------------------------------------------ control tab

    private View buildControl() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = vbox();
        col.setPadding(dp(16), dp(6), dp(16), dp(24));
        sv.addView(col);

        LinearLayout status = card();
        statusBg = (GradientDrawable) status.getBackground();
        runTitle = tv("Stopped", 22, TEXT);
        runTitle.setTypeface(Typeface.DEFAULT_BOLD);
        runSub = tv("", 13, MUTED);
        startButton = button("START", BTN_START, TEXT, this::toggle);
        status.addView(runTitle);
        status.addView(runSub);
        status.addView(startButton, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        col.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 14));

        LinearLayout modeCard = card();
        modeCard.addView(sectionTitle("Mode"));
        LinearLayout seg = hbox();
        seg.setBackground(shape(BG, LINE, 6));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeAuto = tv("Automatic", 14, MUTED);
        modeManual = tv("Manual", 14, MUTED);
        for (TextView t : new TextView[]{modeAuto, modeManual}) {
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            pressable(t);
        }
        modeAuto.setOnClickListener(v -> setControlMode(false, true));
        modeManual.setOnClickListener(v -> setControlMode(true, true));
        seg.addView(modeAuto, weight(0, 0, 0, 0));
        seg.addView(modeManual, weight(0, 0, 0, 0));
        modeCard.addView(seg);
        modeDesc = tv("", 13, MUTED);
        modeDesc.setPadding(0, dp(10), 0, 0);
        modeCard.addView(modeDesc);
        col.addView(modeCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));

        effectsCard = card();
        effectsCard.addView(sectionTitle("Default effects"));
        TextView ed = tv("Used in Automatic mode for every app that has no effects of its own.", 13, MUTED);
        ed.setPadding(0, 0, 0, dp(4));
        effectsCard.addView(ed);
        aecBox = checkbox("AEC  ·  Echo cancellation");
        nsBox = checkbox("NS  ·  Noise suppression");
        agcBox = checkbox("AGC  ·  Automatic gain control");
        effectsCard.addView(aecBox);
        effectsCard.addView(nsBox);
        effectsCard.addView(agcBox);
        View.OnClickListener effectsChanged = v -> {
            saveDefaults();
            appendStatus("INFO", "Default effects: " + selectedMode().replace("AGC2", "AGC"));
        };
        aecBox.setOnClickListener(effectsChanged);
        nsBox.setOnClickListener(effectsChanged);
        agcBox.setOnClickListener(effectsChanged);
        col.addView(effectsCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout fc = card();
        fc.addView(sectionTitle("Force Close"));
        forceBox = checkbox("Force Stop");
        forceBox.setOnClickListener(v -> {
            prefs().edit().putBoolean(PREF_FORCE_CLOSE, forceBox.isChecked()).apply();
            appendStatus("INFO", "Force Close on STOP: " + (forceBox.isChecked() ? "enabled" : "disabled"));
        });
        fc.addView(forceBox);
        TextView note = tv("Effects only attach or detach while an app's microphone is closed. With this on, EchoRoute "
                + "closes only the microphone of the selected app (the app keeps running) when you press STOP "
                + "or remove its effects, and force-stops the app only if the microphone stays open. "
                + "In Manual mode only the selected apps are touched.", 13, MUTED);
        note.setPadding(0, dp(4), 0, 0);
        fc.addView(note);
        col.addView(fc, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));

        lockHint = tv("Stop EchoRoute to change the mode, default effects or Force Close.", 12, AMBER);
        col.addView(lockHint);
        return sv;
    }

    private void setControlMode(boolean manual, boolean fromUser) {
        if (fromUser && isEnabled()) return;
        prefs().edit().putString(PREF_CONTROL_MODE, manual ? "manual" : "automatic").apply();
        modeAuto.setBackground(manual ? null : shape(SURFACE2, 0, 5));
        modeManual.setBackground(manual ? shape(SURFACE2, 0, 5) : null);
        modeAuto.setTextColor(manual ? MUTED : TEXT);
        modeManual.setTextColor(manual ? TEXT : MUTED);
        String desc = manual
                ? "Manual: effects go only to the apps you choose in the Activity tab, each with its own effects."
                : "Automatic: effects follow whichever app is using the microphone. Apps in the Ignored list are skipped.";
        if (fromUser) {
            swapText(modeDesc, desc, MUTED);
            fadeTo(effectsCard, manual ? 0.45f : 1f);
            popView(manual ? modeManual : modeAuto);
        } else {
            modeDesc.setText(desc);
            effectsCard.setAlpha(manual ? 0.45f : 1f);
        }
        if (fromUser) appendStatus("INFO", "Control mode: " + (manual ? "Manual" : "Automatic"));
        updateUi();
    }

    private String selectedMode() {
        StringBuilder m = new StringBuilder();
        if (aecBox.isChecked()) m.append("AEC");
        if (nsBox.isChecked()) {
            if (m.length() > 0) m.append('+');
            m.append("NS");
        }
        if (agcBox.isChecked()) {
            if (m.length() > 0) m.append('+');
            m.append("AGC2");
        }
        return m.length() == 0 ? "NONE" : m.toString();
    }

    private void saveDefaults() {
        prefs().edit().putString(PREF_MODE, selectedMode()).apply();
    }

    private void restorePrefs() {
        SharedPreferences p = prefs();
        String mode = p.getString(PREF_MODE, "AEC+NS+AGC2");
        aecBox.setChecked(mode.contains("AEC"));
        nsBox.setChecked(mode.contains("NS"));
        agcBox.setChecked(mode.contains("AGC"));
        forceBox.setChecked(p.getBoolean(PREF_FORCE_CLOSE, true));
        setControlMode(isManual(), false);
    }

    /** Old versions had one Manual target; turn it into a normal saved app once. */
    private void migrateLegacyTarget() {
        SharedPreferences p = prefs();
        String target = p.getString(PREF_TARGET_PACKAGE, "");
        if (target.isEmpty()) return;
        Map<String, Set<String>> cfg = AppEffectsConfig.loadPerApp(p);
        if (!cfg.containsKey(target)) {
            Set<String> set = new TreeSet<>();
            String mode = p.getString(PREF_MODE, "AEC+NS+AGC2");
            if (mode.contains("AEC")) set.add("AEC");
            if (mode.contains("NS")) set.add("NS");
            if (mode.contains("AGC")) set.add("AGC");
            if (!set.isEmpty()) {
                cfg.put(target, set);
                AppEffectsConfig.savePerApp(p, cfg);
            }
        }
        p.edit().putString(PREF_TARGET_PACKAGE, "").apply();
    }

    private void toggle() {
        if (isEnabled()) {
            prefs().edit().putBoolean(PREF_ENABLED, false).apply();
            appendStatus("INFO", "STOP pressed" + (forceBox.isChecked() ? " (Force Close is on)." : "."));
            if (!shizukuAlive() && EchoState.isDirty(this)) {
                appendStatus("WARNING", "Shizuku is off. Effects will be removed automatically as soon as Shizuku is back.");
            }
            syncControllerService(EchoKeepAliveService.ACTION_STOP);
        } else {
            saveDefaults();
            if (isManual()) {
                if (AppEffectsConfig.loadPerApp(prefs()).isEmpty()) {
                    appendStatus("WARNING", "Manual mode needs at least one app. Add apps in the Activity tab.");
                    selectTab(1);
                    return;
                }
            } else if (!aecBox.isChecked() && !nsBox.isChecked() && !agcBox.isChecked()) {
                appendStatus("WARNING", "Select at least one default effect before Start.");
                return;
            }
            prefs().edit().putBoolean(PREF_ENABLED, true).apply();
            appendStatus("INFO", isManual()
                    ? "Starting Manual mode for: " + describeConfig()
                    : "Starting Automatic mode (default effects " + selectedMode().replace("AGC2", "AGC") + ").");
            ensureShizukuThenStart();
        }
        updateUi();
    }

    private void updateUi() {
        if (startButton == null) return;
        boolean enabled = isEnabled();
        boolean manual = isManual();
        boolean alive = shizukuAlive();
        String sub;
        if (enabled) {
            sub = manual ? "Manual · " + AppEffectsConfig.loadPerApp(prefs()).size() + " app(s)"
                    : "Automatic · following the microphone";
            if (!alive) sub += " · waiting for Shizuku";
        } else if (EchoState.isDirty(this) && !alive) {
            sub = (manual ? "Manual mode" : "Automatic mode") + " · cleanup pending until Shizuku is back";
        } else {
            sub = manual ? "Manual mode" : "Automatic mode";
        }
        swapText(runTitle, enabled ? "Running" : "Stopped", enabled ? GREEN : TEXT);
        swapText(runSub, sub, MUTED);
        swapText(startButton, enabled ? "STOP" : "START", TEXT);
        int fill = enabled ? BTN_STOP : BTN_START;
        final int edge = enabled ? BTN_STOP_EDGE : BTN_START_EDGE;
        if (lastFill == 0) {
            startButton.setBackground(shape(fill, edge, 6));
        } else if (lastFill != fill) {
            animateColor(lastFill, fill, 300, c -> startButton.setBackground(shape(c, edge, 6)));
            popView(startButton);
        }
        lastFill = fill;
        setStatusPulse(enabled);
        float a = enabled ? 0.5f : 1f;
        modeAuto.setEnabled(!enabled);
        modeManual.setEnabled(!enabled);
        aecBox.setEnabled(!enabled);
        nsBox.setEnabled(!enabled);
        agcBox.setEnabled(!enabled);
        forceBox.setEnabled(!enabled);
        fadeTo(modeAuto, a);
        fadeTo(modeManual, a);
        lockHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ activity tab

    private View buildApps() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = vbox();
        col.setPadding(dp(16), dp(6), dp(16), dp(24));
        sv.addView(col);
        TextView head = tv("Apps with effects", 20, TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(head, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 8, 0, 4));
        TextView sub = tv("Each app keeps its own effects. Tap the app icon (✎) to edit, ✕ to remove effects from that app only.", 13, MUTED);
        col.addView(sub, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 2, 12));
        col.addView(primaryButton("+  ADD APPS", () -> showEditor(null)),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));
        appsList = vbox();
        col.addView(appsList);
        return sv;
    }

    private void renderApps(boolean animate) {
        if (appsList == null) return;
        appsList.removeAllViews();
        Map<String, Set<String>> cfg = AppEffectsConfig.loadPerApp(prefs());
        Set<String> ign = AppEffectsConfig.loadIgnored(prefs());
        if (cfg.isEmpty()) {
            TextView empty = tv("No apps yet. Tap ADD APPS and choose the apps (and effects) you want.", 14, MUTED);
            empty.setPadding(dp(2), dp(8), dp(2), dp(8));
            appsList.addView(empty);
        }
        for (Map.Entry<String, Set<String>> e : cfg.entrySet()) {
            appsList.addView(appCard(e.getKey(), e.getValue(), ign.contains(e.getKey()), true),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));
        }
        boolean header = false;
        for (String pkg : ign) {
            if (cfg.containsKey(pkg)) continue;
            if (!header) {
                TextView h = sectionTitle("Ignored in Automatic");
                h.setPadding(dp(2), dp(10), 0, dp(8));
                appsList.addView(h);
                header = true;
            }
            appsList.addView(appCard(pkg, null, true, false),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));
        }
        if (animate) staggerIn(appsList);
    }

    private TextView chip(String s, int fill, int fg) {
        TextView c = tv(s, 11, fg);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setPadding(dp(8), dp(3), dp(8), dp(3));
        c.setBackground(shape(fill, 0, 4));
        return c;
    }

    private View appCard(final String pkg, Set<String> fx, boolean ignored, boolean hasEffects) {
        LinearLayout row = hbox();
        row.setBackground(shape(SURFACE, LINE, 6));
        row.setPadding(dp(12), dp(12), dp(8), dp(12));

        FrameLayout iconWrap = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setImageDrawable(icon(pkg));
        iconWrap.addView(img, new FrameLayout.LayoutParams(dp(44), dp(44)));
        TextView pencil = tv("✎", 11, BG);
        pencil.setGravity(Gravity.CENTER);
        pencil.setBackground(shape(GREEN, 0, 9));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END);
        iconWrap.addView(pencil, pl);
        iconWrap.setOnClickListener(v -> { popView(iconWrap); showEditor(pkg); });
        pressable(iconWrap);
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout info = vbox();
        info.setPadding(dp(12), 0, dp(8), 0);
        TextView name = tv(label(pkg), 15, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView pk = tv(pkg, 11, MUTED);
        pk.setSingleLine(true);
        pk.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        info.addView(pk);
        LinearLayout chips = hbox();
        if (fx != null) {
            for (String f : fx) chips.addView(chip(f, SURFACE2, GREEN), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
        }
        if (ignored) chips.addView(chip("⊘ ignored in Auto", SURFACE2, AMBER));
        info.addView(chips, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        row.addView(info, weight(0, 0, 0, 0));

        TextView x = tv("✕", 18, TEXT);
        x.setGravity(Gravity.CENTER);
        x.setBackground(shape(SURFACE2, 0, 6));
        x.setOnClickListener(v -> {
    if (!shizukuAlive()) {
        appendStatus("WARNING",
                "Shizuku is off. Cannot remove effects from " + label(pkg) + ".");
        return;
    }

    x.setEnabled(false);
    row.animate().cancel();
    row.animate().alpha(0f).translationX(dp(56)).setStartDelay(0).setDuration(200)
            .setInterpolator(new AccelerateInterpolator(1.4f))
            .withEndAction(() -> removeApp(pkg, hasEffects)).start();
});
        pressable(x);
        row.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return row;
    }

    /** X button: remove effects from this app only. */
    private void removeApp(String pkg, boolean hadEffects) {
    if (!shizukuAlive()) {
        appendStatus("WARNING",
                "Shizuku is off. Cannot remove effects from " + label(pkg) + ".");
        return;
    }

    Map<String, Set<String>> cfg = AppEffectsConfig.loadPerApp(prefs());
        Set<String> ign = AppEffectsConfig.loadIgnored(prefs());
        if (hadEffects) {
            cfg.remove(pkg);
            // In Automatic an app without its own entry would get the default effects again.
            if (!isManual()) ign.add(pkg);
            AppEffectsConfig.savePerApp(prefs(), cfg);
            AppEffectsConfig.saveIgnored(prefs(), ign);
            onAppsChanged("Removed effects from " + label(pkg) + " only.");
        } else {
            ign.remove(pkg);
            AppEffectsConfig.saveIgnored(prefs(), ign);
            onAppsChanged(label(pkg) + " is no longer ignored in Automatic.");
        }
    }

    private void onAppsChanged(String what) {
        appendStatus(isEnabled() ? "APPLIED" : "INFO",
                what + " Now: " + describeConfig() + ".");
        if (isEnabled()) {
            try {
                startService(new Intent(this, EchoKeepAliveService.class)
                        .setAction(EchoKeepAliveService.ACTION_CONFIG));
            } catch (Throwable t) {
                appendStatus("WARNING", "Could not send the new settings: " + t);
            }
        }
        renderApps(true);
        updateUi();
    }

    private String describeConfig() {
        Map<String, Set<String>> cfg = AppEffectsConfig.loadPerApp(prefs());
        if (cfg.isEmpty()) return "no apps selected";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : cfg.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(label(e.getKey())).append(" (").append(String.join("+", e.getValue())).append(')');
        }
        return sb.toString();
    }

    private List<String> candidateApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String> out = new ArrayList<>();
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                ApplicationInfo ai = ri.activityInfo == null ? null : ri.activityInfo.applicationInfo;
                if (ai == null) continue;
                String pkg = ai.packageName;
                if (pkg.equals(getPackageName()) || out.contains(pkg)) continue;
                try {
                    if (pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg)
                            != PackageManager.PERMISSION_GRANTED) continue;
                } catch (Throwable ignored) { continue; }
                out.add(pkg);
            }
        } catch (Throwable ignored) {}
        Collections.sort(out, Comparator.comparing(p -> label(p).toLowerCase(Locale.ROOT)));
        return out;
    }

    private void styleChip(TextView c, boolean on, boolean ignoreChip) {
        c.setTextColor(on ? BG : MUTED);
        c.setBackground(shape(on ? (ignoreChip ? AMBER : GREEN) : SURFACE2, 0, 4));
    }

    /** Add / edit dialog: one row per app with AEC / NS / AGC boxes and an ignore box. */
    private void showEditor(final String onlyPkg) {
        final Map<String, Set<String>> cfg = AppEffectsConfig.loadPerApp(prefs());
        final Set<String> ign = AppEffectsConfig.loadIgnored(prefs());
        final List<String> pkgs = new ArrayList<>();
        if (onlyPkg != null) {
            pkgs.add(onlyPkg);
        } else {
            pkgs.addAll(candidateApps());
            for (String p : cfg.keySet()) if (!pkgs.contains(p)) pkgs.add(p);
            for (String p : ign) if (!pkgs.contains(p)) pkgs.add(p);
        }
        final Map<String, boolean[]> st = new LinkedHashMap<>();
        for (String p : pkgs) {
            Set<String> f = cfg.get(p);
            st.put(p, new boolean[]{f != null && f.contains("AEC"), f != null && f.contains("NS"),
                    f != null && f.contains("AGC"), ign.contains(p)});
        }
        final boolean[] defaults = {aecBox.isChecked(), nsBox.isChecked(), agcBox.isChecked()};

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vbox();
        root.setBackground(shape(SURFACE, LINE, 8));
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        TextView title = tv(onlyPkg == null ? "Add apps" : label(onlyPkg), 18, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView hint = tv("Tap an app name to select it with the default effects, or tap AEC / NS / AGC for exact control. "
                + "⊘ = ignore this app in Automatic mode.", 12, MUTED);
        hint.setPadding(0, dp(4), 0, dp(8));
        root.addView(hint);

        final EditText search = new EditText(this);
        if (onlyPkg == null) {
            search.setHint("Search apps");
            search.setHintTextColor(MUTED);
            search.setTextColor(TEXT);
            search.setSingleLine(true);
            search.setTextSize(14);
            search.setBackground(shape(BG, LINE, 6));
            search.setPadding(dp(12), dp(10), dp(12), dp(10));
            root.addView(search, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        }

        final LinearLayout listBox = vbox();
        ScrollView sv = new ScrollView(this);
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                onlyPkg == null ? dp(360) : ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            listBox.removeAllViews();
            String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (final String pkg : pkgs) {
                if (!q.isEmpty() && !label(pkg).toLowerCase(Locale.ROOT).contains(q)
                        && !pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
                final boolean[] a = st.get(pkg);
                LinearLayout row = hbox();
                row.setPadding(0, dp(6), 0, dp(6));
                ImageView img = new ImageView(this);
                img.setImageDrawable(icon(pkg));
                row.addView(img, new LinearLayout.LayoutParams(dp(34), dp(34)));
                TextView name = tv(label(pkg), 14, TEXT);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(10), 0, dp(6), 0);
                row.addView(name, weight(0, 0, 0, 0));
                final TextView[] chips = new TextView[4];
                String[] names = {"AEC", "NS", "AGC", "⊘"};
                for (int i = 0; i < 4; i++) {
                    final int idx = i;
                    TextView c = tv(names[i], 11, MUTED);
                    c.setTypeface(Typeface.DEFAULT_BOLD);
                    c.setGravity(Gravity.CENTER);
                    styleChip(c, a[i], i == 3);
                    c.setOnClickListener(v -> {
                        a[idx] = !a[idx];
                        styleChip(chips[idx], a[idx], idx == 3);
                    });
                    chips[i] = c;
                    row.addView(c, lp(dp(i == 3 ? 30 : 38), dp(32), 3, 0, 0, 0));
                }
                name.setOnClickListener(v -> {
                    boolean any = a[0] || a[1] || a[2];
                    a[0] = !any && defaults[0];
                    a[1] = !any && defaults[1];
                    a[2] = !any && defaults[2];
                    for (int i = 0; i < 3; i++) styleChip(chips[i], a[i], false);
                });
                listBox.addView(row);
            }
        };
        render[0].run();
        if (onlyPkg == null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st0, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st0, int b, int c) {}
                @Override public void afterTextChanged(Editable e) { render[0].run(); }
            });
        }

        LinearLayout buttons = hbox();
        buttons.addView(button("CANCEL", SURFACE2, TEXT, () -> closeAnimated(d, root)), weight(0, 0, 6, 0));
        buttons.addView(primaryButton("SAVE", () -> {
            Map<String, Set<String>> out = AppEffectsConfig.loadPerApp(prefs());
            Set<String> outIgn = AppEffectsConfig.loadIgnored(prefs());
            for (Map.Entry<String, boolean[]> e : st.entrySet()) {
                boolean[] a = e.getValue();
                Set<String> s = new TreeSet<>();
                if (a[0]) s.add("AEC");
                if (a[1]) s.add("NS");
                if (a[2]) s.add("AGC");
                if (s.isEmpty()) out.remove(e.getKey()); else out.put(e.getKey(), s);
                if (a[3]) outIgn.add(e.getKey()); else outIgn.remove(e.getKey());
            }
            AppEffectsConfig.savePerApp(prefs(), out);
            AppEffectsConfig.saveIgnored(prefs(), outIgn);
            closeAnimated(d, root);
            onAppsChanged("Effects saved.");
        }), weight(6, 0, 0, 0));
        root.addView(buttons, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        d.setContentView(root);
        showAnimated(d, root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    // ------------------------------------------------------------------ log tab

    private static final class IconView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final int bg;

        IconView(Context c, int kind, int color, int bg) {
            super(c);
            this.kind = kind;
            this.bg = bg;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            stroke.setStrokeWidth(s * 0.075f);
            if (kind == 0) {
                // reset: circular arrow
                float r = s * 0.24f;
                c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -45f, 270f, false, stroke);
                double a = Math.toRadians(225);
                float ex = (float) (cx + r * Math.cos(a));
                float ey = (float) (cy + r * Math.sin(a));
                float dx = (float) (-Math.sin(a));
                float dy = (float) Math.cos(a);
                float k = s * 0.13f;
                Path t = new Path();
                t.moveTo(ex + dx * k, ey + dy * k);
                t.lineTo(ex - dx * k * 0.3f - dy * k, ey - dy * k * 0.3f + dx * k);
                t.lineTo(ex - dx * k * 0.3f + dy * k, ey - dy * k * 0.3f - dx * k);
                t.close();
                c.drawPath(t, fill);
            } else {
                // copy: two stacked sheets
                float rw = s * 0.30f;
                float rh = s * 0.36f;
                RectF back = new RectF(cx - rw - s * 0.04f, cy - rh - s * 0.02f, cx + rw * 0.2f, cy + rh * 0.55f);
                RectF front = new RectF(cx - rw * 0.2f, cy - rh * 0.5f, cx + rw + s * 0.04f, cy + rh + s * 0.02f);
                c.drawRoundRect(back, s * 0.05f, s * 0.05f, stroke);
                Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgPaint.setColor(bg);
                c.drawRoundRect(front, s * 0.05f, s * 0.05f, bgPaint);
                c.drawRoundRect(front, s * 0.05f, s * 0.05f, stroke);
            }
        }
    }

    private View buildLog() {
        LinearLayout root = vbox();
        root.setPadding(dp(16), dp(6), dp(16), dp(10));

        LinearLayout bar = hbox();
        IconView reset = new IconView(this, 0, TEXT, SURFACE2);
        reset.setBackground(shape(SURFACE2, 0, 6));
        reset.setOnClickListener(v -> {
            EchoAppLog.clear(this);
            lastRaw = "";
            appendStatus("INFO", "Log cleared.");
        });
        pressable(reset);
        bar.addView(reset, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout seg = hbox();
        seg.setBackground(shape(SURFACE, LINE, 6));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        logUserTab = tv(logName(false), 13, MUTED);
        logDevTab = tv(logName(true), 13, MUTED);
        for (TextView t : new TextView[]{logUserTab, logDevTab}) {
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            pressable(t);
        }
        logUserTab.setOnClickListener(v -> setLogView(false));
        logDevTab.setOnClickListener(v -> setLogView(true));
        logUserTab.setOnLongClickListener(v -> { showRenameLogTab(false); return true; });
        logDevTab.setOnLongClickListener(v -> { showRenameLogTab(true); return true; });
        seg.addView(logUserTab, weight(0, 0, 0, 0));
        seg.addView(logDevTab, weight(0, 0, 0, 0));
        bar.addView(seg, weight(10, 0, 10, 0));

        IconView copy = new IconView(this, 1, TEXT, SURFACE2);
        copy.setBackground(shape(SURFACE2, 0, 6));
        copy.setOnClickListener(v -> copyAppLog());
        pressable(copy);
        bar.addView(copy, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 10));

        logBody = new FrameLayout(this);
        logBody.setBackground(shape(SURFACE, LINE, 6));
        root.addView(logBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        userLogBox = vbox();
        userLogBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        devScroll = new ScrollView(this);
        devText = tv("", 11, TEXT);
        devText.setTypeface(Typeface.MONOSPACE);
        devText.setPadding(dp(10), dp(8), dp(10), dp(8));
        devText.setTextIsSelectable(true);
        devScroll.addView(devText);
        logBody.addView(userLogBox);
        logBody.addView(devScroll);
        setLogView(false);
        return root;
    }

    private String logName(boolean dev) {
        String v = prefs().getString(dev ? PREF_LOG_NAME_FULL : PREF_LOG_NAME_SIMPLE, "");
        if (v == null || v.trim().isEmpty()) return dev ? DEFAULT_LOG_NAME_FULL : DEFAULT_LOG_NAME_SIMPLE;
        return v.trim();
    }

    /** Long-press on a log tab: rename it. Empty text restores the default name. */
    private void showRenameLogTab(final boolean dev) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vbox();
        root.setBackground(shape(SURFACE, LINE, 8));
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        TextView title = tv("Rename tab", 18, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        final EditText input = new EditText(this);
        input.setText(logName(dev));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        input.setBackground(shape(BG, LINE, 6));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        TextView hint = tv("Leave it empty to use the default name.", 12, MUTED);
        hint.setPadding(0, dp(6), 0, 0);
        root.addView(hint);
        LinearLayout buttons = hbox();
        buttons.addView(button("CANCEL", SURFACE2, TEXT, () -> closeAnimated(d, root)), weight(0, 0, 6, 0));
        buttons.addView(primaryButton("SAVE", () -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            prefs().edit().putString(dev ? PREF_LOG_NAME_FULL : PREF_LOG_NAME_SIMPLE, name).apply();
            (dev ? logDevTab : logUserTab).setText(logName(dev));
            closeAnimated(d, root);
        }), weight(6, 0, 0, 0));
        root.addView(buttons, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        d.setContentView(root);
        showAnimated(d, root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private boolean shizukuAlive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** START pressed while Shizuku is off: jump to the Log tab, first (simple) view, red message. */
    private void showShizukuOffInLog() {
        shizukuStartFailed = true;
        appendStatus("WARNING", "Shizuku is not running. Start Shizuku, then press START again.");
        selectTab(2);
        setLogView(false);
    }

    private void setLogView(boolean dev) {
        devLog = dev;
        logUserTab.setTextColor(dev ? MUTED : TEXT);
        logDevTab.setTextColor(dev ? TEXT : MUTED);
        logUserTab.setBackground(dev ? null : shape(SURFACE2, 0, 5));
        logDevTab.setBackground(dev ? shape(SURFACE2, 0, 5) : null);
        userLogBox.setVisibility(dev ? View.GONE : View.VISIBLE);
        devScroll.setVisibility(dev ? View.VISIBLE : View.GONE);
        logAnimate = true;
        final View shown = dev ? devScroll : userLogBox;
        shown.setAlpha(0f);
        shown.animate().alpha(1f).setStartDelay(0).setDuration(220).start();
        devFirst = true;
        lastRaw = "";
        refreshLog();
    }

    private String[] splitLine(String line) {
        int sp = line.indexOf(' ');
        if (sp > 0) {
            try {
                long t = Long.parseLong(line.substring(0, sp));
                return new String[]{timeFmt.format(new Date(t)), line.substring(sp + 1)};
            } catch (NumberFormatException ignored) {}
        }
        return new String[]{"", line};
    }

    private String stateOf(String rest) {
        if (rest.startsWith("[")) {
            int e = rest.indexOf(']');
            if (e > 1) return rest.substring(1, e);
        }
        return "";
    }

    private String detailOf(String rest) {
        int e = rest.indexOf("] ");
        return rest.startsWith("[") && e > 0 ? rest.substring(e + 2) : rest;
    }

    private void refreshLog() {
        if (logBody == null) return;
        String raw = EchoAppLog.read(this);
        boolean enabled = isEnabled();
        String key = raw + "|" + devLog + "|" + enabled + "|" + isManual() + "|" + shizukuStartFailed + "|" + shizukuAlive();
        if (key.equals(lastRaw)) return;
        lastRaw = key;
        String[] lines = raw.split("\n");
        if (devLog) renderDev(lines); else renderUser(lines);
    }

    private void renderDev(String[] lines) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int from = Math.max(0, lines.length - 600);
        for (int i = from; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            String[] p = splitLine(line);
            String rest = p[1];
            int color = TEXT;
            String upper = rest.toUpperCase(Locale.ROOT);
            if (rest.startsWith("[APPLIED]") || rest.contains("POLICY_APPLIED")) color = GREEN;
            else if (upper.contains("WARNING") || upper.contains("ERROR") || upper.contains("FAILED")
                    || upper.contains("DENIED")) color = RED;
            else if (rest.startsWith("[SCAN]")) color = MUTED;
            int start = sb.length();
            sb.append(p[0]).append("  ").append(rest).append('\n');
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boolean stick = devFirst;
        if (!stick && devScroll.getChildCount() > 0) {
            View child = devScroll.getChildAt(0);
            stick = child.getBottom() - (devScroll.getHeight() + devScroll.getScrollY()) < dp(60);
        }
        devText.setText(sb);
        devFirst = false;
        if (stick) devScroll.post(() -> devScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderUser(String[] lines) {
        userLogBox.removeAllViews();
        userLineIdx = 0;
        boolean enabled = isEnabled();
        boolean manual = isManual();

        // Shizuku was off when START was pressed: red headline until Shizuku is back.
        if (shizukuStartFailed) {
            if (shizukuAlive()) shizukuStartFailed = false;
            else addUserLine("✕ Shizuku is not running", RED, 3, true);
        }

        // first: green headline (no scrolling in this view)
        String head;
        int headColor = GREEN;
        if (manual) {
            if (enabled) head = "✓ Effects are set for: " + describeConfig();
            else { head = "Manual mode is stopped. Apps: " + describeConfig(); headColor = MUTED; }
        } else {
            if (enabled) head = "● Automatic: effects follow the app that is using the microphone.";
            else { head = "Automatic mode is stopped."; headColor = MUTED; }
        }
        addUserLine(head, headColor, 3, true);

        List<String> applied = new ArrayList<>();
        List<String[]> system = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] p = splitLine(line);
            String state = stateOf(p[1]);
            if (state.isEmpty() || state.equals("SCAN")) continue;
            if (state.equals("APPLIED")) applied.add(detailOf(p[1]));
            else system.add(new String[]{state, detailOf(p[1])});
        }
        for (int i = Math.max(0, applied.size() - 3); i < applied.size(); i++) {
            addUserLine("✓ " + applied.get(i), GREEN, 2, false);
        }
        int from = Math.max(0, system.size() - 6);
        for (int i = from; i < system.size(); i++) {
            String st = system.get(i)[0];
            int color = (st.equals("WARNING") || st.equals("ERROR")) ? RED : TEXT;
            addUserLine(system.get(i)[1], color, 2, false);
        }
        logAnimate = false;
    }

    private void addUserLine(String s, int color, int maxLines, boolean bold) {
        TextView t = tv(s, bold ? 15 : 13, color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        userLogBox.addView(t, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, bold ? 12 : 6));
        if (logAnimate) {
            t.setAlpha(0f);
            t.setTranslationY(dp(10));
            t.animate().alpha(1f).translationY(0f).setStartDelay(60L * userLineIdx++).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> t.animate().setStartDelay(0)).start();
        }
    }

    private void appendStatus(String state, String detail) {
        EchoAppLog.line(this, "[" + state + "] " + detail);
        if (currentTab == 2 && logBody != null) refreshLog();
    }

    private void copyAppLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("EchoRoute app log", EchoAppLog.read(this)));
            appendStatus("INFO", "Log copied to clipboard.");
        }
    }

    // ------------------------------------------------------------------ service / Shizuku

    private void syncControllerService(String action) {
        try {
            Intent intent = new Intent(this, EchoKeepAliveService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable t) {
            appendStatus("WARNING", "Controller service failed: " + t);
        }
    }

    private void performExit() {
        prefs().edit().putBoolean(PREF_ENABLED, false).apply();
        try {
            Intent stop = new Intent(this, EchoKeepAliveService.class).setAction(EchoKeepAliveService.ACTION_EXIT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stop);
            else startService(stop);
        } catch (Throwable ignored) {}
        finishAndRemoveTask();
    }

    private void ensureShizukuThenStart() {
        try {
            boolean ready = Shizuku.pingBinder() && Shizuku.getVersion() >= 13;
            if (!ready) {
                prefs().edit().putBoolean(PREF_ENABLED, false).apply();
                updateUi();
                showShizukuOffInLog();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                syncControllerService(EchoKeepAliveService.ACTION_START);
            } else {
                appendStatus("INFO", "Requesting Shizuku permission…");
                Shizuku.addRequestPermissionResultListener(permissionListener);
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            }
        } catch (Throwable t) {
            prefs().edit().putBoolean(PREF_ENABLED, false).apply();
            updateUi();
            showShizukuOffInLog();
            appendStatus("ERROR", "Shizuku setup failed: " + t);
        }
    }

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                if (requestCode != SHIZUKU_REQUEST) return;
                removePermissionListener();
                updateShizuku();
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    appendStatus("INFO", "Shizuku permission granted.");
                    if (isEnabled()) syncControllerService(EchoKeepAliveService.ACTION_START);
                } else {
                    appendStatus("WARNING", "Shizuku permission denied; effects were not started.");
                    prefs().edit().putBoolean(PREF_ENABLED, false).apply();
                    updateUi();
                }
            });

    private void removePermissionListener() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {}
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }
            }
