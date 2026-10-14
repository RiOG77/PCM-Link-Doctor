package com.pcmlink.doctor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class DiagnosticService extends Service {
    public static final String ACTION_START = "com.pcmlink.doctor.START";
    public static final String ACTION_STOP = "com.pcmlink.doctor.STOP";
    public static final String ACTION_MARK_FAILED = "com.pcmlink.doctor.MARK_FAILED";
    public static final String ACTION_MARK_RECOVERED = "com.pcmlink.doctor.MARK_RECOVERED";
    public static final String ACTION_STATE = "com.pcmlink.doctor.STATE";
    public static final String ACTION_EVENT = "com.pcmlink.doctor.EVENT";

    public static final String PREF = "pcm_link_doctor";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_MAIN_STATUS = "main_status";
    public static final String KEY_USB = "usb";
    public static final String KEY_ACCESSORY = "accessory";
    public static final String KEY_AA = "aa";
    public static final String KEY_LEVEL = "level";

    private static final int SAFE_SHIZUKU_UID = 2000;
    private static final int NOTIFICATION_ID = 8401;
    private static final String CHANNEL_ID = "pcm_link_diagnostics";

    private ScheduledExecutorService scheduler;
    private final Set<String> seenLogLines = new LinkedHashSet<>();
    private final Object logLock = new Object();
    private IRemoteShell shell;
    private Shizuku.UserServiceArgs userServiceArgs;
    private File sessionDir;
    private PowerManager.WakeLock wakeLock;

    private boolean previousUsbPowered;
    private boolean previousAccessory;
    private String previousUsbState = "";
    private String previousAaPid = "";
    private long usbConnectedAt;
    private long lastPtpAt;
    private boolean stuckReported;
    private boolean started;

    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            IRemoteShell candidate = IRemoteShell.Stub.asInterface(service);
            try {
                int uid = candidate.uid();
                if (uid != SAFE_SHIZUKU_UID) {
                    rejectUnsafeShizuku(uid);
                    return;
                }

                shell = candidate;
                event("GOOD", "Knox-safe проверка пройдена",
                        "Shizuku uid=2000 (Android shell). Root не используется");
                captureDeviceDetails();
                publishState("Жду подключения кабеля", "—", "—", "—", "IDLE");
            } catch (Throwable t) {
                shell = null;
                event("BAD", "Ошибка проверки Shizuku", String.valueOf(t.getMessage()));
                publishState("Не удалось проверить Shizuku", "—", "—", "—", "BAD");
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            if (started) {
                event("WARN", "Shizuku отключён", "Системная USB-диагностика приостановлена");
                publishState("Жду Shizuku", "—", "—", "—", "WARN");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        scheduler = Executors.newScheduledThreadPool(2);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PCMLinkDoctor:diagnostics");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopDiagnostics();
            return START_NOT_STICKY;
        }
        if (ACTION_MARK_FAILED.equals(action)) {
            event("BAD", "Метка: AA не запустился", currentCompactState());
            snapshotNow("marker_failed");
            return START_STICKY;
        }
        if (ACTION_MARK_RECOVERED.equals(action)) {
            event("GOOD", "Метка: после PTP заработало", currentCompactState());
            snapshotNow("marker_recovered");
            return START_STICKY;
        }
        if (!started) startDiagnostics();
        return START_STICKY;
    }

    private void startDiagnostics() {
        started = true;
        sessionDir = SessionStore.startSession(this);
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        event("INFO", "Диагностика запущена", "Сначала проверяю Knox-safe режим Shizuku");
        bindShell();
        scheduler.scheduleAtFixedRate(this::pollUsb, 500, 1200, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::pollLogcat, 1200, 2200, TimeUnit.MILLISECONDS);
    }

    private void bindShell() {
        try {
            if (!Shizuku.pingBinder()) {
                event("WARN", "Shizuku недоступен", "Запусти Shizuku и вернись в приложение");
                publishState("Жду Shizuku", "—", "—", "—", "WARN");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                event("WARN", "Нет разрешения Shizuku", "Разреши PCM Link Doctor в Shizuku");
                publishState("Нужно разрешение Shizuku", "—", "—", "—", "WARN");
                return;
            }
            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), ShellUserService.class.getName()))
                    .processNameSuffix("diagnostics")
                    .debuggable(BuildConfig.DEBUG)
                    .version(2)
                    .daemon(false)
                    .tag("pcm_link_shell");
            Shizuku.bindUserService(userServiceArgs, shellConnection);
        } catch (Throwable t) {
            event("BAD", "Не удалось подключить Shizuku", String.valueOf(t.getMessage()));
            publishState("Ошибка Shizuku", "—", "—", "—", "BAD");
        }
    }

    private void rejectUnsafeShizuku(int uid) {
        String mode = uid == 0 ? "root (uid=0)" : "uid=" + uid;
        event("BAD", "Knox-safe блокировка",
                "Обнаружен Shizuku " + mode + ". Разрешён только Android shell uid=2000. Диагностика остановлена.");
        publishState("ЗАБЛОКИРОВАНО: небезопасный Shizuku", "—", "—", "—", "BAD");
        started = false;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        try {
            if (userServiceArgs != null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, shellConnection, true);
            }
        } catch (Throwable ignored) {}
        shell = null;
        if (scheduler != null) scheduler.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void pollUsb() {
        IRemoteShell s = shell;
        if (s == null) return;
        try {
            String out = s.exec("echo __STATE__; getprop sys.usb.state; "
                    + "echo __CONFIG__; getprop sys.usb.config; "
                    + "echo __BATTERY__; dumpsys battery | grep -i -E 'USB powered|powered:|AC powered|Wireless powered' | head -n 8; "
                    + "echo __USB__; dumpsys usb 2>/dev/null | grep -i -E 'connected|configured|current.*function|screen.*function|accessory|mtp|ptp|data unlocked|host connected' | head -n 120; "
                    + "echo __AA_PID__; pidof com.google.android.projection.gearhead 2>/dev/null || true");
            handleParsed(Parsed.from(out), out);
        } catch (Throwable t) {
            event("WARN", "Ошибка чтения USB", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void handleParsed(Parsed p, String raw) {
        long now = System.currentTimeMillis();

        if (p.usbPowered != previousUsbPowered) {
            previousUsbPowered = p.usbPowered;
            if (p.usbPowered) {
                usbConnectedAt = now;
                stuckReported = false;
                event("INFO", "USB подключён", "PCM/кабель подал USB-питание");
                SessionStore.append(sessionDir, "raw_usb.txt", "\n=== USB CONNECT " + SessionStore.isoNow() + " ===\n" + raw);
            } else {
                usbConnectedAt = 0L;
                stuckReported = false;
                event("INFO", "USB отключён", "Соединение завершено");
                SessionStore.append(sessionDir, "raw_usb.txt", "\n=== USB DISCONNECT " + SessionStore.isoNow() + " ===\n" + raw);
            }
        }

        if (!p.usbState.equals(previousUsbState)) {
            String old = previousUsbState;
            previousUsbState = p.usbState;
            if (!p.usbState.isBlank()) {
                event("INFO", "USB-функции изменились", (old.isBlank() ? "—" : old) + " → " + p.usbState);
                SessionStore.append(sessionDir, "raw_usb.txt", "\n=== USB STATE " + SessionStore.isoNow() + " ===\n" + raw);
            }
            if (containsToken(p.usbState, "ptp")) lastPtpAt = now;
        }

        if (p.accessory != previousAccessory) {
            previousAccessory = p.accessory;
            if (p.accessory) {
                if (lastPtpAt > 0 && now - lastPtpAt <= 15_000) {
                    event("GOOD", "PTP → accessory", "Handshake появился через "
                            + String.format(Locale.US, "%.1f", (now - lastPtpAt) / 1000.0) + " c");
                } else {
                    event("GOOD", "Accessory mode активирован", "Телефон перешёл в USB AOA/accessory mode");
                }
                snapshotNow("accessory_on");
            } else if (p.usbPowered) {
                event("WARN", "Accessory mode пропал", "USB всё ещё подключён");
            }
        }

        if (!p.aaPid.equals(previousAaPid)) {
            boolean was = !previousAaPid.isBlank();
            boolean is = !p.aaPid.isBlank();
            previousAaPid = p.aaPid;
            if (!was && is) event("INFO", "Android Auto процесс активен", "gearhead pid=" + p.aaPid);
            if (was && !is) event("INFO", "Android Auto процесс завершился", "gearhead больше не обнаружен");
        }

        if (p.usbPowered && !p.accessory && usbConnectedAt > 0
                && now - usbConnectedAt > 10_000 && !stuckReported) {
            stuckReported = true;
            event("WARN", "Возможен зависший handshake", "USB есть >10 c, но accessory mode не появился");
            snapshotNow("stuck_handshake");
        }

        String main;
        String level;
        if (!p.usbPowered) {
            main = "Жду подключения кабеля";
            level = "IDLE";
        } else if (p.accessory) {
            main = "Handshake найден";
            level = "GOOD";
        } else if (usbConnectedAt > 0 && now - usbConnectedAt > 10_000) {
            main = "USB есть, handshake не найден";
            level = "WARN";
        } else {
            main = "USB подключён — анализирую";
            level = "INFO";
        }
        publishState(main,
                p.usbPowered ? (p.usbState.isBlank() ? "подключён" : p.usbState) : "—",
                p.accessory ? "активен" : (p.usbPowered ? "нет" : "—"),
                p.aaPid.isBlank() ? "—" : "процесс есть",
                level);
    }

    private void pollLogcat() {
        IRemoteShell s = shell;
        if (s == null || sessionDir == null) return;
        try {
            String out = s.exec("logcat -b main -b system -d -v threadtime -t 220 2>/dev/null | grep -i -E 'usb|accessory|aoa|projection|gearhead|android.?auto|carservice|mtp|ptp' | tail -n 180");
            if (out == null || out.isBlank()) return;
            StringBuilder fresh = new StringBuilder();
            synchronized (logLock) {
                for (String line : out.split("\\R")) {
                    if (line.isBlank() || seenLogLines.contains(line)) continue;
                    seenLogLines.add(line);
                    fresh.append(line).append('\n');
                }
                if (seenLogLines.size() > 2500) seenLogLines.clear();
            }
            if (fresh.length() > 0) SessionStore.append(sessionDir, "logcat_filtered.txt", fresh.toString());
        } catch (Throwable ignored) {}
    }

    private void captureDeviceDetails() {
        IRemoteShell s = shell;
        if (s == null || sessionDir == null) return;
        try {
            String details = s.exec("echo '--- Knox-safe Shizuku mode ---'; id; "
                    + "echo '--- USB props ---'; getprop | grep -i -E '\\[.*usb.*\\]|persist.sys.usb'; "
                    + "echo '--- Android Auto package ---'; dumpsys package com.google.android.projection.gearhead 2>/dev/null | grep -i -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -n 20");
            SessionStore.append(sessionDir, "device.txt", "\n" + details);
        } catch (Throwable ignored) {}
    }

    private void snapshotNow(String reason) {
        IRemoteShell s = shell;
        if (s == null || sessionDir == null || scheduler == null || scheduler.isShutdown()) return;
        scheduler.execute(() -> {
            try {
                String cmd = "echo '=== SNAPSHOT " + reason + " ==='; date '+%Y-%m-%d %H:%M:%S.%3N %z'; "
                        + "echo '--- usb props ---'; getprop sys.usb.state; getprop sys.usb.config; "
                        + "echo '--- dumpsys usb ---'; dumpsys usb 2>/dev/null | head -n 280; "
                        + "echo '--- battery ---'; dumpsys battery | head -n 80; "
                        + "echo '--- AA process ---'; ps -A 2>/dev/null | grep -i 'projection.gearhead' || true; "
                        + "echo '--- filtered log ---'; logcat -b main -b system -d -v threadtime -t 450 2>/dev/null | grep -i -E 'usb|accessory|aoa|projection|gearhead|android.?auto|carservice|mtp|ptp' | tail -n 320";
                SessionStore.append(sessionDir, "snapshots.txt", "\n" + s.exec(cmd));
            } catch (Throwable ignored) {}
        });
    }

    private String currentCompactState() {
        return "usb=" + (previousUsbPowered ? "powered" : "off")
                + ", functions=" + previousUsbState
                + ", accessory=" + previousAccessory
                + ", aaPid=" + (previousAaPid.isBlank() ? "none" : previousAaPid);
    }

    private void event(String severity, String title, String detail) {
        if (sessionDir != null) SessionStore.appendTimeline(sessionDir, severity, title, detail);
        sendBroadcast(new Intent(ACTION_EVENT).setPackage(getPackageName())
                .putExtra("severity", severity)
                .putExtra("title", title)
                .putExtra("detail", detail)
                .putExtra("time", System.currentTimeMillis()));
    }

    private void publishState(String main, String usb, String accessory, String aa, String level) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, started)
                .putString(KEY_MAIN_STATUS, main)
                .putString(KEY_USB, usb)
                .putString(KEY_ACCESSORY, accessory)
                .putString(KEY_AA, aa)
                .putString(KEY_LEVEL, level)
                .apply();
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("main", main)
                .putExtra("usb", usb)
                .putExtra("accessory", accessory)
                .putExtra("aa", aa)
                .putExtra("level", level));
    }

    private void stopDiagnostics() {
        if (!started) {
            stopSelf();
            return;
        }
        event("INFO", "Диагностика остановлена", "Сессия сохранена и готова к экспорту");
        snapshotNow("session_stop");
        started = false;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_MAIN_STATUS, "Диагностика остановлена")
                .putString(KEY_USB, "—")
                .putString(KEY_ACCESSORY, "—")
                .putString(KEY_AA, "—")
                .putString(KEY_LEVEL, "IDLE")
                .apply();
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("main", "Диагностика остановлена")
                .putExtra("usb", "—")
                .putExtra("accessory", "—")
                .putExtra("aa", "—")
                .putExtra("level", "IDLE"));
        try {
            if (userServiceArgs != null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, shellConnection, true);
            }
        } catch (Throwable ignored) {}
        shell = null;
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, DiagnosticService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(0, "Остановить", stopPi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PCM Link Doctor")
                .setContentText("Knox-safe USB / Android Auto диагностика")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "PCM Link diagnostics", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Knox-safe фоновая USB/Android Auto диагностика");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private static boolean containsToken(String state, String token) {
        if (state == null) return false;
        for (String s : state.toLowerCase(Locale.ROOT).split(",")) {
            if (s.trim().equals(token)) return true;
        }
        return false;
    }

    @Override public void onDestroy() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    static final class Parsed {
        final String usbState;
        final String usbConfig;
        final boolean usbPowered;
        final boolean accessory;
        final String aaPid;

        Parsed(String usbState, String usbConfig, boolean usbPowered, boolean accessory, String aaPid) {
            this.usbState = usbState;
            this.usbConfig = usbConfig;
            this.usbPowered = usbPowered;
            this.accessory = accessory;
            this.aaPid = aaPid;
        }

        static Parsed from(String raw) {
            String state = sectionFirst(raw, "__STATE__", "__CONFIG__");
            String config = sectionFirst(raw, "__CONFIG__", "__BATTERY__");
            String battery = section(raw, "__BATTERY__", "__USB__");
            String usb = section(raw, "__USB__", "__AA_PID__");
            String pid = sectionFirst(raw, "__AA_PID__", null);
            String lowBattery = battery.toLowerCase(Locale.ROOT);
            boolean powered = lowBattery.contains("usb powered: true")
                    || lowBattery.contains("usb powered=true");
            boolean accessory = containsToken(state, "accessory") || looksLikeCurrentAccessory(usb);
            return new Parsed(state.trim(), config.trim(), powered, accessory, pid.trim());
        }

        private static boolean looksLikeCurrentAccessory(String usb) {
            for (String line : usb.split("\\R")) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("accessory")
                        && (l.contains("current") || l.contains("function"))
                        && !l.contains("supported")) {
                    return true;
                }
            }
            return false;
        }

        private static String sectionFirst(String raw, String start, String end) {
            String s = section(raw, start, end);
            for (String line : s.split("\\R")) {
                if (!line.trim().isEmpty()) return line.trim();
            }
            return "";
        }

        private static String section(String raw, String start, String end) {
            if (raw == null) return "";
            int a = raw.indexOf(start);
            if (a < 0) return "";
            a += start.length();
            int b = end == null ? raw.length() : raw.indexOf(end, a);
            if (b < 0) b = raw.length();
            return raw.substring(a, b).trim();
        }
    }
}
