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
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 8402;
    private static final int EXPORT_REQUEST = 8403;
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
    private Button markCarButton;
    private Button markFailedButton;
    private Button markRecoveredButton;
    private File pendingExportFile;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_REQUEST) return;
        refreshShizuku();
        if (grantResult == PackageManager.PERMISSION_GRANTED && isRunning()) {
            startService(new Intent(this, DiagnosticService.class)
                    .setAction(DiagnosticService.ACTION_ENABLE_ENHANCED));
        }
    };

    private final BroadcastReceiver diagnosticReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DiagnosticService.ACTION_STATE.equals(intent.getAction())) {
                updateState(intent.getStringExtra("main"), intent.getStringExtra("usb"),
                        intent.getStringExtra("accessory"), intent.getStringExtra("aa"),
                        intent.getStringExtra("level"));
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
        setContentView(buildUi());

        IntentFilter filter = new IntentFilter();
        filter.addAction(DiagnosticService.ACTION_STATE);
        filter.addAction(DiagnosticService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(diagnosticReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(diagnosticReceiver, filter);
        }

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
        top.addView(text("PCM LINK DOCTOR", 13, C_INFO, true), weight(-2, -2, 1));
        TextView version = text("v" + BuildConfig.VERSION_NAME, 12, C_MUTED, false);
        version.setBackground(round(C_CARD_2, 99, C_LINE, 1));
        version.setPadding(dp(10), dp(5), dp(10), dp(5));
        top.addView(version);
        root.addView(top);

        TextView title = text("Поймаем, где ломается\nAndroid Auto", 31, C_TEXT, true);
        LinearLayout.LayoutParams titleLp = lp(-1, -2);
        titleLp.topMargin = dp(18);
        root.addView(title, titleLp);

        TextView sub = text("Samsung ↔ USB ↔ Porsche PCM. Чистый базовый режим ничего не меняет в USB и не включает Shizuku автоматически.", 14, C_MUTED, false);
        sub.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams subLp = lp(-1, -2);
        subLp.topMargin = dp(10);
        subLp.bottomMargin = dp(18);
        root.addView(sub, subLp);

        LinearLayout statusCard = column();
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusCard.setBackground(round(C_CARD, 22, C_LINE, 1));
        root.addView(statusCard, lp(-1, -2));

        LinearLayout statusHead = row();
        statusHead.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 20, C_MUTED, true);
        statusHead.addView(statusDot, lp(dp(28), -2));
        mainStatus = text("Диагностика остановлена", 19, C_TEXT, true);
        statusHead.addView(mainStatus, weight(-2, -2, 1));
        statusCard.addView(statusHead);

        statusHint = text("Запусти диагностику до подключения кабеля.", 13, C_MUTED, false);
        LinearLayout.LayoutParams hintLp = lp(-1, -2);
        hintLp.topMargin = dp(8);
        statusCard.addView(statusHint, hintLp);

        LinearLayout chips = row();
        LinearLayout.LayoutParams chipsLp = lp(-1, -2);
        chipsLp.topMargin = dp(16);
        statusCard.addView(chips, chipsLp);
        usbValue = addStatusChip(chips, "USB");
        accessoryValue = addStatusChip(chips, "ACCESSORY");
        aaValue = addStatusChip(chips, "AA SIGNAL");

        shizukuStatus = text("Shizuku: проверяю…", 13, C_MUTED, false);
        shizukuStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        shizukuStatus.setBackground(round(C_CARD_2, 14, C_LINE, 1));
        shizukuStatus.setOnClickListener(v -> onShizukuClick());
        LinearLayout.LayoutParams shLp = lp(-1, -2);
        shLp.topMargin = dp(12);
        root.addView(shizukuStatus, shLp);

        startStopButton = button("Начать диагностику", C_INFO, Color.rgb(7, 17, 32));
        startStopButton.setOnClickListener(v -> onStartStop());
        LinearLayout.LayoutParams startLp = lp(-1, dp(56));
        startLp.topMargin = dp(16);
        root.addView(startStopButton, startLp);

        TextView markerTitle = text("МЕТКИ ВО ВРЕМЯ ТЕСТА", 12, C_MUTED, true);
        markerTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams mtLp = lp(-1, -2);
        mtLp.topMargin = dp(24);
        mtLp.bottomMargin = dp(10);
        root.addView(markerTitle, mtLp);

        markCarButton = button("Подключил к авто", C_CARD_2, C_INFO);
        markCarButton.setOnClickListener(v -> sendMarker(DiagnosticService.ACTION_MARK_CAR));
        root.addView(markCarButton, lp(-1, dp(48)));

        LinearLayout markers = row();
        LinearLayout.LayoutParams markersLp = lp(-1, dp(62));
        markersLp.topMargin = dp(10);
        root.addView(markers, markersLp);

        markFailedButton = button("AA не\nзапустился", Color.rgb(55, 24, 29), C_BAD);
        markFailedButton.setTextSize(13);
        markFailedButton.setOnClickListener(v -> sendMarker(DiagnosticService.ACTION_MARK_FAILED));
        LinearLayout.LayoutParams badLp = weight(0, -1, 1);
        badLp.rightMargin = dp(6);
        markers.addView(markFailedButton, badLp);

        markRecoveredButton = button("После PTP\nзаработало", Color.rgb(20, 47, 36), C_OK);
        markRecoveredButton.setTextSize(13);
        markRecoveredButton.setOnClickListener(v -> sendMarker(DiagnosticService.ACTION_MARK_RECOVERED));
        LinearLayout.LayoutParams goodLp = weight(0, -1, 1);
        goodLp.leftMargin = dp(6);
        markers.addView(markRecoveredButton, goodLp);

        LinearLayout timelineHead = row();
        timelineHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thLp = lp(-1, -2);
        thLp.topMargin = dp(26);
        thLp.bottomMargin = dp(10);
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

        TextView safety = text("KNOX-SAFE  •  base mode без ADB/Shizuku  •  enhanced только вручную  •  без INTERNET permission", 11, C_MUTED, false);
        safety.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams safetyLp = lp(-1, -2);
        safetyLp.topMargin = dp(18);
        root.addView(safety, safetyLp);

        return scroll;
    }

    private TextView addStatusChip(LinearLayout parent, String label) {
        LinearLayout chip = column();
        chip.setPadding(dp(9), dp(9), dp(9), dp(9));
        chip.setBackground(round(C_CARD_2, 12, C_LINE, 1));
        LinearLayout.LayoutParams chipLp = weight(0, dp(67), 1);
        if (parent.getChildCount() > 0) chipLp.leftMargin = dp(6);
        parent.addView(chip, chipLp);
        TextView l = text(label, 9, C_MUTED, true);
        l.setGravity(Gravity.CENTER);
        chip.addView(l, lp(-1, -2));
        TextView v = text("—", 11, C_TEXT, true);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = lp(-1, -2);
        vLp.topMargin = dp(6);
        chip.addView(v, vLp);
        return v;
    }

    private void onStartStop() {
        if (isRunning()) {
            startService(new Intent(this, DiagnosticService.class).setAction(DiagnosticService.ACTION_STOP));
            return;
        }
        Intent start = new Intent(this, DiagnosticService.class).setAction(DiagnosticService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
    }

    private void sendMarker(String action) {
        if (!isRunning()) {
            Toast.makeText(this, "Сначала запусти диагностику", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, DiagnosticService.class).setAction(action));
    }

    private void onShizukuClick() {
        if (!isRunning()) {
            Toast.makeText(this, "Сначала запусти базовую диагностику. Enhanced включается отдельно.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (launch != null) startActivity(launch);
                else Toast.makeText(this, "Shizuku не запущен. Для чистого кейса он не нужен.", Toast.LENGTH_LONG).show();
                return;
            }
            int uid = Shizuku.getUid();
            if (!ShellIdentityPolicy.isSafeBackendUid(uid)) {
                Toast.makeText(this, "Root/unsafe Shizuku заблокирован. Нужен shell uid=2000.", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            } else {
                startService(new Intent(this, DiagnosticService.class)
                        .setAction(DiagnosticService.ACTION_ENABLE_ENHANCED));
                Toast.makeText(this, "Enhanced mode включается отдельно от чистого теста", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku недоступен — базовый режим остаётся активным", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.setText("Shizuku: недоступен · для чистого теста это нормально");
                shizukuStatus.setTextColor(C_MUTED);
                return;
            }
            int uid = Shizuku.getUid();
            if (!ShellIdentityPolicy.isSafeBackendUid(uid)) {
                shizukuStatus.setText("Shizuku: ЗАБЛОКИРОВАН unsafe/root uid=" + uid);
                shizukuStatus.setTextColor(C_BAD);
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuStatus.setText("Shizuku: shell uid=2000 · enhanced выключен по умолчанию · нажми только для A/B");
                shizukuStatus.setTextColor(C_WARN);
            } else {
                shizukuStatus.setText("Shizuku: shell uid=2000 · разрешение не выдано · base mode чистый");
                shizukuStatus.setTextColor(C_MUTED);
            }
        } catch (Throwable t) {
            shizukuStatus.setText("Shizuku: недоступен · базовая диагностика работает");
            shizukuStatus.setTextColor(C_MUTED);
        }
    }

    private void exportSession() {
        if (isRunning()) {
            Toast.makeText(this, "Сначала останови диагностику, затем экспортируй ZIP", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            pendingExportFile = SessionStore.exportLatest(this);
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, pendingExportFile.getName());
            startActivityForResult(create, EXPORT_REQUEST);
        } catch (Throwable t) {
            Toast.makeText(this, "Экспорт: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data == null || pendingExportFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (FileInputStream in = new FileInputStream(pendingExportFile);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Не удалось открыть файл назначения");
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            Toast.makeText(this, "Диагностика сохранена", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Не удалось сохранить ZIP: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadPersistedState() {
        android.content.SharedPreferences p = getSharedPreferences(DiagnosticService.PREF, MODE_PRIVATE);
        updateState(p.getString(DiagnosticService.KEY_MAIN_STATUS, "Диагностика остановлена"),
                p.getString(DiagnosticService.KEY_USB, "—"),
                p.getString(DiagnosticService.KEY_ACCESSORY, "—"),
                p.getString(DiagnosticService.KEY_AA, "—"),
                p.getString(DiagnosticService.KEY_LEVEL, "IDLE"));
    }

    private void updateState(String main, String usb, String accessory, String aa, String level) {
        mainStatus.setText(safe(main, "—"));
        usbValue.setText(safe(usb, "—"));
        accessoryValue.setText(safe(accessory, "—"));
        aaValue.setText(safe(aa, "—"));
        int color = "GOOD".equals(level) ? C_OK
                : "WARN".equals(level) ? C_WARN
                : "BAD".equals(level) ? C_BAD
                : "INFO".equals(level) ? C_INFO : C_MUTED;
        statusDot.setTextColor(color);
        statusHint.setText("GOOD".equals(level) ? "Handshake/accessory уже появился."
                : "WARN".equals(level) ? "Есть признак зависания — поставь метку, если AA не стартовал."
                : isRunning() ? "Слушаю каждый USB_STATE. Никакого dumpsys polling в base mode."
                : "Запусти диагностику до подключения кабеля.");
    }

    private void refreshRunningUi() {
        boolean running = isRunning();
        startStopButton.setText(running ? "Остановить диагностику" : "Начать диагностику");
        startStopButton.setBackground(round(running ? Color.rgb(47, 29, 32) : C_INFO, 16, 0, 0));
        startStopButton.setTextColor(running ? C_BAD : Color.rgb(7, 17, 32));
        markCarButton.setEnabled(running);
        markFailedButton.setEnabled(running);
        markRecoveredButton.setEnabled(running);
        markCarButton.setAlpha(running ? 1f : 0.45f);
        markFailedButton.setAlpha(running ? 1f : 0.45f);
        markRecoveredButton.setAlpha(running ? 1f : 0.45f);
    }

    private boolean isRunning() {
        return getSharedPreferences(DiagnosticService.PREF, MODE_PRIVATE)
                .getBoolean(DiagnosticService.KEY_RUNNING, false);
    }

    private void loadRecentEvents() {
        List<SessionStore.EventLine> events = SessionStore.readTailEvents(this, 14);
        if (events.isEmpty()) return;
        eventContainer.removeAllViews();
        for (SessionStore.EventLine e : events) addEvent(e.time, e.severity, e.title, e.detail, false);
    }

    private void addEvent(long time, String severity, String title, String detail, boolean newestFirst) {
        View empty = eventContainer.findViewWithTag("empty");
        if (empty != null) eventContainer.removeView(empty);
        LinearLayout item = column();
        item.setPadding(dp(2), dp(10), dp(2), dp(10));
        TextView head = text(new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(time))
                        + "   " + title, 12, severityColor(severity), true);
        item.addView(head, lp(-1, -2));
        if (detail != null && !detail.isBlank()) {
            TextView d = text(detail, 11, C_MUTED, false);
            LinearLayout.LayoutParams dLp = lp(-1, -2);
            dLp.topMargin = dp(4);
            item.addView(d, dLp);
        }
        if (newestFirst) eventContainer.addView(item, 0, lp(-1, -2));
        else eventContainer.addView(item, lp(-1, -2));
        while (eventContainer.getChildCount() > 18) {
            eventContainer.removeViewAt(eventContainer.getChildCount() - 1);
        }
    }

    private int severityColor(String severity) {
        if ("GOOD".equals(severity)) return C_OK;
        if ("WARN".equals(severity)) return C_WARN;
        if ("BAD".equals(severity)) return C_BAD;
        return C_INFO;
    }

    private void maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8404);
        }
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String value, int bg, int fg) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, 16, 0, 0));
        return b;
    }

    private Button miniButton(String value) {
        Button b = button(value, C_CARD_2, C_TEXT);
        b.setTextSize(11);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams weight(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(diagnosticReceiver); } catch (Throwable ignored) {}
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}
