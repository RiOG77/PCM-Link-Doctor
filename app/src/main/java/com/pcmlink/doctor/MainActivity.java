package com.pcmlink.doctor;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 8402;
    private static final int C_BG = Color.rgb(9, 11, 16);
    private static final int C_CARD = Color.rgb(18, 22, 30);
    private static final int C_CARD_2 = Color.rgb(23, 28, 38);
    private static final int C_TEXT = Color.rgb(242, 245, 250);
    private static final int C_MUTED = Color.rgb(142, 151, 166);
    private static final int C_OK = Color.rgb(76, 217, 144);
    private static final int C_WARN = Color.rgb(255, 184, 76);
    private static final int C_BAD = Color.rgb(255, 92, 104);
    private static final int C_INFO = Color.rgb(105, 164, 255);
    private static final int C_LINE = Color.rgb(44, 51, 65);

    private TextView mainStatus;
    private TextView statusHint;
    private TextView usbValue;
    private TextView accessoryValue;
    private TextView aaValue;
    private TextView shizukuStatus;
    private TextView statusDot;
    private LinearLayout eventContainer;
    private Button startStopButton;
    private Button markFailedButton;
    private Button markRecoveredButton;
    private boolean pendingStart;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_REQUEST) return;
        refreshShizuku();
        if (grantResult == PackageManager.PERMISSION_GRANTED && pendingStart) {
            pendingStart = false;
            startDiagnostics();
        }
    };

    private final BroadcastReceiver diagnosticReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DiagnosticService.ACTION_STATE.equals(intent.getAction())) {
                updateState(intent.getStringExtra("main"), intent.getStringExtra("usb"),
                        intent.getStringExtra("accessory"), intent.getStringExtra("aa"), intent.getStringExtra("level"));
                refreshRunningUi();
            } else if (DiagnosticService.ACTION_EVENT.equals(intent.getAction())) {
                addEvent(intent.getLongExtra("time", System.currentTimeMillis()),
                        safe(intent.getStringExtra("severity"), "INFO"),
                        safe(intent.getStringExtra("title"), "Событие"),
                        safe(intent.getStringExtra("detail"), ""), true);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
        setContentView(buildUi());

        IntentFilter filter = new IntentFilter();
        filter.addAction(DiagnosticService.ACTION_STATE);
        filter.addAction(DiagnosticService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(diagnosticReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(diagnosticReceiver, filter);

        Shizuku.addRequestPermissionResultListener(permissionListener);
        loadPersistedState();
        loadRecentEvents();
        refreshShizuku();
        refreshRunningUi();
        maybeAskNotifications();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshShizuku();
        refreshRunningUi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("PCM LINK DOCTOR", 13, C_INFO, true);
        top.addView(brand, weight(-2, -2, 1));
        TextView version = text("v" + BuildConfig.VERSION_NAME, 12, C_MUTED, false);
        version.setBackground(round(C_CARD_2, 99, C_LINE, 1));
        version.setPadding(dp(10), dp(5), dp(10), dp(5));
        top.addView(version);
        root.addView(top);

        TextView title = text("Поймаем, где ломается\nAndroid Auto", 31, C_TEXT, true);
        title.setLetterSpacing(-0.015f);
        LinearLayout.LayoutParams titleLp = lp(-1, -2); titleLp.topMargin = dp(18);
        root.addView(title, titleLp);

        TextView sub = text("Samsung ↔ USB ↔ Porsche PCM. Только диагностика — приложение ничего не меняет в системе.", 14, C_MUTED, false);
        sub.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams subLp = lp(-1, -2); subLp.topMargin = dp(10); subLp.bottomMargin = dp(18);
        root.addView(sub, subLp);

        LinearLayout statusCard = column();
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusCard.setBackground(round(C_CARD, 22, C_LINE, 1));
        root.addView(statusCard, lp(-1, -2));

        LinearLayout statusHead = row();
        statusHead.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 20, C_MUTED, true);
        LinearLayout.LayoutParams dotLp = lp(dp(28), -2);
        statusHead.addView(statusDot, dotLp);
        mainStatus = text("Диагностика остановлена", 19, C_TEXT, true);
        statusHead.addView(mainStatus, weight(-2, -2, 1));
        statusCard.addView(statusHead);

        statusHint = text("Запусти диагностику до подключения кабеля.", 13, C_MUTED, false);
        statusHint.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams hintLp = lp(-1, -2); hintLp.topMargin = dp(8);
        statusCard.addView(statusHint, hintLp);

        LinearLayout chips = row();
        LinearLayout.LayoutParams chipsLp = lp(-1, -2); chipsLp.topMargin = dp(16);
        statusCard.addView(chips, chipsLp);
        usbValue = addStatusChip(chips, "USB");
        accessoryValue = addStatusChip(chips, "ACCESSORY");
        aaValue = addStatusChip(chips, "ANDROID AUTO");

        shizukuStatus = text("Shizuku: проверяю…", 13, C_MUTED, false);
        shizukuStatus.setGravity(Gravity.CENTER_VERTICAL);
        shizukuStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        shizukuStatus.setBackground(round(C_CARD_2, 14, C_LINE, 1));
        shizukuStatus.setOnClickListener(v -> openShizukuOrSettings());
        LinearLayout.LayoutParams shLp = lp(-1, -2); shLp.topMargin = dp(12);
        root.addView(shizukuStatus, shLp);

        startStopButton = button("Начать диагностику", C_INFO, Color.rgb(7, 17, 32));
        startStopButton.setOnClickListener(v -> onStartStop());
        LinearLayout.LayoutParams startLp = lp(-1, dp(56)); startLp.topMargin = dp(16);
        root.addView(startStopButton, startLp);

        TextView markerTitle = text("МЕТКИ ВО ВРЕМЯ ТЕСТА", 12, C_MUTED, true);
        markerTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams mtLp = lp(-1, -2); mtLp.topMargin = dp(24); mtLp.bottomMargin = dp(10);
        root.addView(markerTitle, mtLp);

        LinearLayout markers = row();
        root.addView(markers, lp(-1, dp(62)));
        markFailedButton = button("AA не\nзапустился", Color.rgb(55, 24, 29), C_BAD);
        markFailedButton.setTextSize(13);
        markFailedButton.setOnClickListener(v -> sendMarker(DiagnosticService.ACTION_MARK_FAILED));
        LinearLayout.LayoutParams badLp = weight(0, -1, 1); badLp.rightMargin = dp(6);
        markers.addView(markFailedButton, badLp);

        markRecoveredButton = button("После PTP\nзаработало", Color.rgb(20, 47, 36), C_OK);
        markRecoveredButton.setTextSize(13);
        markRecoveredButton.setOnClickListener(v -> sendMarker(DiagnosticService.ACTION_MARK_RECOVERED));
        LinearLayout.LayoutParams goodLp = weight(0, -1, 1); goodLp.leftMargin = dp(6);
        markers.addView(markRecoveredButton, goodLp);

        LinearLayout timelineHead = row();
        timelineHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thLp = lp(-1, -2); thLp.topMargin = dp(26); thLp.bottomMargin = dp(10);
        root.addView(timelineHead, thLp);
        TextView timelineTitle = text("ПОСЛЕДНИЕ СОБЫТИЯ", 12, C_MUTED, true);
        timelineTitle.setLetterSpacing(0.08f);
        timelineHead.addView(timelineTitle, weight(-2, -2, 1));
        Button export = miniButton("Экспорт ZIP");
        export.setOnClickListener(v -> exportSession());
        timelineHead.addView(export, lp(dp(108), dp(38)));

        eventContainer = column();
        eventContainer.setPadding(dp(14), dp(5), dp(14), dp(5));
        eventContainer.setBackground(round(C_CARD, 18, C_LINE, 1));
        root.addView(eventContainer, lp(-1, -2));
        TextView empty = text("События появятся после запуска диагностики", 13, C_MUTED, false);
        empty.setTag("empty");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(8), dp(24), dp(8), dp(24));
        eventContainer.addView(empty, lp(-1, -2));

        TextView footer = text("Собираются только технические USB/Android Auto логи. Root не нужен.", 12, C_MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = lp(-1, -2); fLp.topMargin = dp(18);
        root.addView(footer, fLp);
        return scroll;
    }

    private TextView addStatusChip(LinearLayout parent, String label) {
        LinearLayout box = column();
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(round(C_CARD_2, 13, C_LINE, 1));
        LinearLayout.LayoutParams boxLp = weight(0, dp(66), 1);
        boxLp.leftMargin = dp(3); boxLp.rightMargin = dp(3);
        parent.addView(box, boxLp);
        TextView l = text(label, 9, C_MUTED, true);
        l.setLetterSpacing(0.06f);
        box.addView(l);
        TextView v = text("—", 12, C_TEXT, true);
        LinearLayout.LayoutParams vLp = lp(-1, -2); vLp.topMargin = dp(6);
        box.addView(v, vLp);
        return v;
    }

    private void onStartStop() {
        if (isRunning()) {
            startService(new Intent(this, DiagnosticService.class).setAction(DiagnosticService.ACTION_STOP));
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku не запущен. Нажми на строку Shizuku и запусти его через Wireless debugging.");
                openShizukuOrSettings();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                pendingStart = true;
                Shizuku.requestPermission(SHIZUKU_REQUEST);
                return;
            }
            startDiagnostics();
        } catch (Throwable t) {
            toast("Shizuku недоступен: " + t.getMessage());
        }
    }

    private void startDiagnostics() {
        Intent i = new Intent(this, DiagnosticService.class).setAction(DiagnosticService.ACTION_START);
        startForegroundService(i);
        getSharedPreferences(DiagnosticService.PREF, MODE_PRIVATE).edit().putBoolean(DiagnosticService.KEY_RUNNING, true).apply();
        refreshRunningUi();
        statusHint.setText("Диагностика активна. Теперь подключи кабель к PCM обычным способом.");
    }

    private void sendMarker(String action) {
        if (!isRunning()) { toast("Сначала запусти диагностику"); return; }
        startService(new Intent(this, DiagnosticService.class).setAction(action));
        toast(DiagnosticService.ACTION_MARK_FAILED.equals(action)
                ? "Отмечено: Android Auto не запустился" : "Отмечено: после PTP заработало");
    }

    private void loadPersistedState() {
        var p = getSharedPreferences(DiagnosticService.PREF, MODE_PRIVATE);
        updateState(p.getString(DiagnosticService.KEY_MAIN_STATUS, "Диагностика остановлена"),
                p.getString(DiagnosticService.KEY_USB, "—"),
                p.getString(DiagnosticService.KEY_ACCESSORY, "—"),
                p.getString(DiagnosticService.KEY_AA, "—"),
                p.getString(DiagnosticService.KEY_LEVEL, "IDLE"));
    }

    private void updateState(String main, String usb, String accessory, String aa, String level) {
        if (mainStatus == null) return;
        mainStatus.setText(safe(main, "Диагностика остановлена"));
        usbValue.setText(shortValue(safe(usb, "—")));
        accessoryValue.setText(shortValue(safe(accessory, "—")));
        aaValue.setText(shortValue(safe(aa, "—")));
        statusDot.setTextColor(levelColor(safe(level, "IDLE")));
        if ("GOOD".equals(level)) statusHint.setText("Handshake найден. Оставь запись ещё на 20–30 секунд.");
        else if ("WARN".equals(level)) statusHint.setText("USB есть, но handshake не появился. Поставь красную метку перед PTP.");
    }

    private String shortValue(String s) {
        if (s.length() <= 16) return s;
        if (s.contains("accessory")) return "accessory";
        if (s.contains("ptp")) return "ptp";
        if (s.contains("mtp")) return "mtp";
        return s.substring(0, 14) + "…";
    }

    private void loadRecentEvents() {
        List<SessionStore.EventLine> events = SessionStore.readTailEvents(this, 8);
        for (int i = events.size() - 1; i >= 0; i--) {
            SessionStore.EventLine e = events.get(i);
            addEvent(e.time, e.severity, e.title, e.detail, false);
        }
    }

    private void addEvent(long time, String severity, String title, String detail, boolean newestFirst) {
        if (eventContainer == null) return;
        removeEmptyEvent();
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(2), dp(12), dp(2), dp(12));
        TextView dot = text("●", 14, levelColor(severity), true);
        row.addView(dot, lp(dp(24), -2));
        LinearLayout body = column();
        row.addView(body, weight(0, -2, 1));
        TextView t = text(title, 13, C_TEXT, true);
        body.addView(t);
        if (detail != null && !detail.isBlank()) {
            TextView d = text(detail, 11, C_MUTED, false);
            d.setMaxLines(2);
            LinearLayout.LayoutParams dLp = lp(-1, -2); dLp.topMargin = dp(3);
            body.addView(d, dLp);
        }
        TextView clock = text(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time)), 10, C_MUTED, false);
        row.addView(clock, lp(dp(54), -2));
        if (newestFirst) eventContainer.addView(row, 0); else eventContainer.addView(row);
        while (eventContainer.getChildCount() > 9) eventContainer.removeViewAt(eventContainer.getChildCount() - 1);
    }

    private void removeEmptyEvent() {
        for (int i = eventContainer.getChildCount() - 1; i >= 0; i--) {
            View v = eventContainer.getChildAt(i);
            if ("empty".equals(v.getTag())) eventContainer.removeViewAt(i);
        }
    }

    private void refreshShizuku() {
        if (shizukuStatus == null) return;
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.setText("Shizuku  •  не запущен  ›");
                shizukuStatus.setTextColor(C_WARN);
                return;
            }
            boolean permission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            shizukuStatus.setText(permission ? "Shizuku  •  готов  •  shell uid=" + Shizuku.getUid()
                    : "Shizuku  •  работает, нужно разрешение  ›");
            shizukuStatus.setTextColor(permission ? C_OK : C_WARN);
        } catch (Throwable t) {
            shizukuStatus.setText("Shizuku  •  недоступен  ›");
            shizukuStatus.setTextColor(C_WARN);
        }
    }

    private void refreshRunningUi() {
        if (startStopButton == null) return;
        boolean running = isRunning();
        startStopButton.setText(running ? "Остановить диагностику" : "Начать диагностику");
        startStopButton.setBackground(round(running ? Color.rgb(45, 24, 28) : C_INFO, 16, running ? C_BAD : C_INFO, 1));
        startStopButton.setTextColor(running ? C_BAD : Color.rgb(7, 17, 32));
        markFailedButton.setEnabled(running);
        markRecoveredButton.setEnabled(running);
        markFailedButton.setAlpha(running ? 1f : 0.42f);
        markRecoveredButton.setAlpha(running ? 1f : 0.42f);
        if (!running) statusHint.setText("Запусти диагностику до подключения кабеля. Приложение ничего не меняет в системе.");
    }

    private boolean isRunning() {
        return getSharedPreferences(DiagnosticService.PREF, MODE_PRIVATE).getBoolean(DiagnosticService.KEY_RUNNING, false);
    }

    private void exportSession() {
        try {
            File zip = SessionStore.exportLatest(this);
            Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".files", zip);
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Отправить диагностику"));
        } catch (Exception e) {
            toast("Пока нечего экспортировать: " + e.getMessage());
        }
    }

    private void openShizukuOrSettings() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) { startActivity(launch); return; }
        } catch (Throwable ignored) {}
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9101);
    }

    private int levelColor(String level) {
        switch (level) {
            case "GOOD": return C_OK;
            case "WARN": return C_WARN;
            case "BAD": return C_BAD;
            case "INFO": return C_INFO;
            default: return C_MUTED;
        }
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(round(bg, 16, bg, 1));
        b.setStateListAnimator(null);
        return b;
    }

    private Button miniButton(String label) {
        Button b = button(label, C_CARD_2, C_TEXT);
        b.setTextSize(11);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(round(C_CARD_2, 12, C_LINE, 1));
        return b;
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams weight(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static String safe(String s, String fallback) { return s == null ? fallback : s; }

    @Override protected void onDestroy() {
        try { unregisterReceiver(diagnosticReceiver); } catch (Throwable ignored) {}
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}
