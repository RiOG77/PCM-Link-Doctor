package com.pcmlink.doctor;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class DiagnosticService extends Service {
    public static final String ACTION_START = "com.pcmlink.doctor.START";
    public static final String ACTION_STOP = "com.pcmlink.doctor.STOP";
    public static final String ACTION_ENABLE_ENHANCED = "com.pcmlink.doctor.ENABLE_ENHANCED";
    public static final String ACTION_MARK_CAR = "com.pcmlink.doctor.MARK_CAR";
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

    private static final String USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE";
    private static final String USB_PORT_CHANGED_ACTION = "android.hardware.usb.action.USB_PORT_CHANGED";
    private static final int NOTIFICATION_ID = 8401;
    private static final String CHANNEL_ID = "pcm_link_diagnostics";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService logScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Integer> logSignatureCounts = new ConcurrentHashMap<>();
    private final List<Long> attachToAccessoryMs = new ArrayList<>();
    private final List<Long> accessoryDurationsMs = new ArrayList<>();
    private final HandshakeForensics forensics = new HandshakeForensics();

    private File sessionDir;
    private PowerManager.WakeLock wakeLock;
    private VerifiedShell shell;
    private Shizuku.UserServiceArgs userServiceArgs;
    private boolean bindingShell;
    private boolean enhancedRequested;
    private boolean enhancedEverAvailable;
    private boolean receiverRegistered;
    private boolean started;

    private boolean usbConnected;
    private boolean usbConfigured;
    private boolean usbAccessory;
    private boolean usbMtp;
    private boolean usbPtp;
    private boolean usbAdb;
    private boolean usbPower;
    private boolean carMarked;
    private boolean wiredAdbSeen;
    private boolean usbStateSeen;
    private boolean projectionProcessSeen;
    private long attachElapsed = -1L;
    private long ptpElapsed = -1L;
    private long accessoryStartElapsed = -1L;
    private long lastUsbBroadcastElapsed = -1L;
    private int attachCount;
    private int detachCount;
    private int usbStateBroadcastCount;
    private String lastUsbFingerprint = "";
    private String lastPropsFingerprint = "";
    private long logCursorMs;
    private Boolean lastInteractive;
    private Boolean lastKeyguardLocked;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (!started || !enhancedRequested) return;
        verifyBackendAndMaybeBind("Shizuku binder received");
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        shell = null;
        bindingShell = false;
        if (started && enhancedRequested) {
            event("WARN", "Shizuku потерян", "Продолжаю базовую USB-диагностику без Shizuku");
            publishState();
        }
    };

    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bindingShell = false;
            try {
                IRemoteShell candidate = IRemoteShell.Stub.asInterface(service);
                VerifiedShell verified = VerifiedShell.verify(candidate);
                shell = verified;
                enhancedEverAvailable = true;
                event("GOOD", "Расширенная диагностика включена",
                        "Shizuku uid=" + verified.uid() + ", SELinux=" + verified.selinuxContext());
                SessionStore.append(sessionDir, "shell_identity.txt",
                        SessionStore.isoNow() + " uid=" + verified.uid()
                                + " context=" + verified.selinuxContext());
                captureLightSnapshot("shizuku_verified");
                publishState();
            } catch (Throwable t) {
                shell = null;
                event("BAD", "Shizuku заблокирован Knox-safe policy",
                        "UserService не прошёл UID/SELinux проверку: " + t.getMessage());
                publishState();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            bindingShell = false;
            if (started && enhancedRequested) {
                event("WARN", "Shizuku UserService отключён", "Базовая диагностика продолжает работать");
                publishState();
            }
        }
    };

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!started || intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (USB_STATE_ACTION.equals(action)) {
                handleUsbState(intent, "broadcast");
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                recordAuxEvent(intent, "accessory_attached");
                event("GOOD", "USB accessory attached", "UsbManager ACTION_USB_ACCESSORY_ATTACHED");
                recordAccessoryInfo("ACTION_USB_ACCESSORY_ATTACHED");
                mainHandler.postDelayed(() -> captureDeepSnapshot("accessory_attached"), 1200L);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                recordAuxEvent(intent, "accessory_detached");
                event("INFO", "USB accessory detached", "UsbManager ACTION_USB_ACCESSORY_DETACHED");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)
                    || USB_PORT_CHANGED_ACTION.equals(action)) {
                recordAuxEvent(intent, "usb_aux");
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                handleBattery(intent);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                event("INFO", "Экран включён", lockState());
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                event("INFO", "Экран выключен", lockState());
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                event("INFO", "Телефон разблокирован", lockState());
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PCMLinkDoctor:diagnostics");
        wakeLock.setReferenceCounted(false);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopDiagnostics();
            return START_NOT_STICKY;
        }
        if (!started) startDiagnostics();

        if (ACTION_ENABLE_ENHANCED.equals(action)) {
            if (!enhancedRequested) {
                enhancedRequested = true;
                event("WARN", "Запрошен enhanced mode",
                        "Отдельный A/B режим: не использовать для чистого кейса, если USB debugging меняет Android Auto");
            }
            verifyBackendAndMaybeBind("explicit enhanced request");
        } else if (ACTION_MARK_CAR.equals(action)) {
            carMarked = true;
            event("INFO", "Метка: подключено к авто", currentUsbSummary() + "; " + lockState());
            captureLightSnapshot("manual_car_marker");
        } else if (ACTION_MARK_FAILED.equals(action)) {
            event("BAD", "Метка: AA не запустился", currentUsbSummary() + "; " + lockState());
            captureDeepSnapshot("marker_failed");
            writeFindings();
        } else if (ACTION_MARK_RECOVERED.equals(action)) {
            event("GOOD", "Метка: после PTP заработало", currentUsbSummary() + "; " + lockState());
            captureDeepSnapshot("marker_recovered");
            writeFindings();
        }
        return START_STICKY;
    }

    private void startDiagnostics() {
        started = true;
        enhancedRequested = false;
        sessionDir = SessionStore.startSession(this);
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        registerSystemSignals();
        logCursorMs = Math.max(0, System.currentTimeMillis() - 3000L);
        event("INFO", "Диагностика запущена",
                "Чистый базовый режим активен; Shizuku/enhanced не включается автоматически");
        logLockStateIfChanged("session_start");
        logScheduler.scheduleAtFixedRate(this::captureLogcatWindow, 2, 4, TimeUnit.SECONDS);
        publishState();
    }

    private void registerSystemSignals() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(USB_STATE_ACTION);
        filter.addAction(USB_PORT_CHANGED_ACTION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = registerReceiver(systemReceiver, filter);
        }
        receiverRegistered = true;
        if (sticky != null) {
            if (USB_STATE_ACTION.equals(sticky.getAction())) handleUsbState(sticky, "register_sticky");
            if (Intent.ACTION_BATTERY_CHANGED.equals(sticky.getAction())) handleBattery(sticky);
        }
    }

    private void handleUsbState(Intent intent, String source) {
        usbStateSeen = true;
        long now = SystemClock.elapsedRealtime();
        recordUsbStateBroadcast(intent, source, now);

        boolean connected = intent.getBooleanExtra("connected", false);
        boolean configured = intent.getBooleanExtra("configured", false);
        boolean accessory = intent.getBooleanExtra("accessory", false);
        boolean mtp = intent.getBooleanExtra("mtp", false);
        boolean ptp = intent.getBooleanExtra("ptp", false);
        boolean adb = intent.getBooleanExtra("adb", false);
        boolean changed = connected != usbConnected || configured != usbConfigured
                || accessory != usbAccessory || mtp != usbMtp || ptp != usbPtp || adb != usbAdb;

        if (connected && !usbConnected) {
            attachCount++;
            attachElapsed = now;
            ptpElapsed = -1L;
            forensics.onAttach(now);
            event("INFO", "USB подключён", "USB_STATE connected=true");
        }
        if (!connected && usbConnected) {
            detachCount++;
            finishAccessoryDuration(now);
            forensics.onDetach();
            event("INFO", "USB отключён", "USB_STATE connected=false");
            attachElapsed = -1L;
            ptpElapsed = -1L;
        }
        if (configured && !usbConfigured) {
            int cycle = forensics.onConfiguredRise(now);
            String suffix = cycle > 1 ? "; configured cycle=" + cycle + " (re-enumeration candidate)" : "; configured cycle=1";
            event("INFO", "USB сконфигурирован", "connected=true, configured=true" + suffix);
            scheduleHandshakeCheckpoints(cycle);
        }
        if (!configured && usbConfigured && connected) {
            forensics.onConfiguredDrop(now);
            event("WARN", "USB configuration потеряна",
                    "connected=true, configured=false; drops=" + forensics.configuredDropWhileConnectedCount());
        }
        if (ptp && !usbPtp) {
            ptpElapsed = now;
            forensics.onPtp(now);
            event("INFO", "PTP включён", "USB_STATE ptp=true");
        }
        if (mtp && !usbMtp) {
            event("INFO", "MTP включён", "USB_STATE mtp=true");
        }
        if (adb != usbAdb) {
            if (adb) {
                wiredAdbSeen = true;
                event("WARN", "В USB-конфигурации появился adb",
                        "Кейс помечен dirty: проводной ADB меняет USB function set и может запускать Android Auto");
            } else {
                event("INFO", "adb исчез из USB-конфигурации", "USB_STATE adb=false");
            }
        }
        if (accessory && !usbAccessory) {
            accessoryStartElapsed = now;
            long fromAttach = attachElapsed > 0 ? now - attachElapsed : -1L;
            if (fromAttach >= 0) attachToAccessoryMs.add(fromAttach);
            HandshakeForensics.AccessoryResult result = forensics.onAccessory(now);
            StringBuilder detail = new StringBuilder("accessory=true");
            if (fromAttach >= 0) detail.append("; attach→accessory=").append(fromAttach).append(" ms");
            if (result.initialConfiguredToAccessoryMs >= 0) {
                detail.append("; initial configured→accessory=").append(result.initialConfiguredToAccessoryMs).append(" ms");
            }
            if (ptpElapsed > 0) detail.append("; PTP→accessory=").append(now - ptpElapsed).append(" ms");
            if (result.configDropToAccessoryMs >= 0) {
                detail.append("; last config drop→accessory=").append(result.configDropToAccessoryMs).append(" ms");
            }
            if (result.ptpReenumerationRecovery) detail.append("; pattern=PTP_REENUMERATION_RECOVERS_AOA");
            event("GOOD", ptpElapsed > 0 ? "PTP → accessory" : "Accessory mode активирован", detail.toString());
            recordAccessoryInfo("USB_STATE accessory=true");
            writeFindings();
            mainHandler.postDelayed(() -> captureDeepSnapshot("accessory_stable"), 1200L);
        }
        if (!accessory && usbAccessory) {
            finishAccessoryDuration(now);
            event("INFO", "Accessory mode завершён", "accessory=false");
        }

        usbConnected = connected;
        usbConfigured = configured;
        usbAccessory = accessory;
        usbMtp = mtp;
        usbPtp = ptp;
        usbAdb = adb;

        if (changed) {
            writeUsbStateIfChanged();
            logLockStateIfChanged("usb_state");
            captureLightSnapshot("usb_state_change");
            publishState();
        }
    }

    private void scheduleHandshakeCheckpoints(int cycle) {
        scheduleHandshakeCheckpoint(2_000L, cycle);
        scheduleHandshakeCheckpoint(5_000L, cycle);
        scheduleHandshakeCheckpoint(10_000L, cycle);
        scheduleHandshakeCheckpoint(30_000L, cycle);
    }

    private void scheduleHandshakeCheckpoint(long delayMs, int cycle) {
        mainHandler.postDelayed(() -> {
            if (!started || !usbConnected || !usbConfigured || usbAccessory) return;
            if (forensics.configuredRiseCount() != cycle) return;
            long now = SystemClock.elapsedRealtime();
            long fromRise = forensics.lastConfiguredRiseElapsed() >= 0
                    ? now - forensics.lastConfiguredRiseElapsed() : -1L;
            SessionStore.append(sessionDir, "handshake_checkpoints.txt",
                    SessionStore.isoNow() + " elapsed=" + now
                            + " cycle=" + cycle + " waited_ms=" + fromRise
                            + " state=" + currentUsbSummary());
            if (delayMs == 10_000L) {
                forensics.onStuck();
                event("WARN", "Возможен зависший handshake",
                        "USB connected+configured уже 10 с, но accessory mode не появился"
                                + (carMarked ? "; пользователь отметил подключение к авто" : ""));
                captureDeepSnapshot("stuck_handshake");
                writeFindings();
            }
        }, delayMs);
    }

    private void recordUsbStateBroadcast(Intent intent, String source, long now) {
        usbStateBroadcastCount++;
        long delta = lastUsbBroadcastElapsed >= 0 ? now - lastUsbBroadcastElapsed : -1L;
        lastUsbBroadcastElapsed = now;
        SessionStore.append(sessionDir, "usb_state_events.txt",
                "seq=" + usbStateBroadcastCount
                        + " wall=" + System.currentTimeMillis()
                        + " iso=" + SessionStore.isoNow()
                        + " elapsed=" + now
                        + " elapsed_nanos=" + SystemClock.elapsedRealtimeNanos()
                        + " delta_prev_ms=" + delta
                        + " source=" + source
                        + " extras=" + safeIntentExtras(intent));
    }

    private void recordAuxEvent(Intent intent, String source) {
        if (sessionDir == null || intent == null) return;
        SessionStore.append(sessionDir, "usb_aux_events.txt",
                "wall=" + System.currentTimeMillis()
                        + " iso=" + SessionStore.isoNow()
                        + " elapsed=" + SystemClock.elapsedRealtime()
                        + " source=" + source
                        + " action=" + intent.getAction()
                        + " extras=" + safeIntentExtras(intent));
    }

    private static String safeIntentExtras(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null || extras.isEmpty()) return "{}";
            List<String> keys = new ArrayList<>(extras.keySet());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                if (i > 0) out.append(", ");
                out.append(key).append('=');
                try {
                    out.append(safeExtraValue(extras.get(key)));
                } catch (Throwable t) {
                    out.append("<unreadable:").append(t.getClass().getSimpleName()).append('>');
                }
            }
            return Redactor.redact(out.append('}').toString());
        } catch (Throwable t) {
            return "<extras unavailable:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String safeExtraValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof CharSequence
                || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return Redactor.redact(String.valueOf(value));
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 16);
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                Object item = Array.get(value, i);
                if (item == null || item instanceof String || item instanceof Number || item instanceof Boolean) {
                    out.append(Redactor.redact(String.valueOf(item)));
                } else {
                    out.append('<').append(item.getClass().getSimpleName()).append('>');
                }
            }
            if (Array.getLength(value) > length) out.append(",…");
            return out.append(']').toString();
        }
        return "<" + type.getSimpleName() + ">";
    }

    private void recordAccessoryInfo(String reason) {
        if (sessionDir == null) return;
        try {
            UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
            UsbAccessory[] accessories = manager != null ? manager.getAccessoryList() : null;
            StringBuilder out = new StringBuilder();
            out.append(SessionStore.isoNow()).append(" reason=").append(reason).append('\n');
            if (accessories == null || accessories.length == 0) {
                out.append("accessories=none\n");
            } else {
                out.append("accessories=").append(accessories.length).append('\n');
                for (int i = 0; i < accessories.length; i++) {
                    UsbAccessory a = accessories[i];
                    out.append('#').append(i)
                            .append(" manufacturer=").append(a.getManufacturer())
                            .append(" model=").append(a.getModel())
                            .append(" description=").append(a.getDescription())
                            .append(" version=").append(a.getVersion())
                            .append(" uri=").append(a.getUri())
                            .append(" serial=[REDACTED]\n");
                }
            }
            SessionStore.append(sessionDir, "accessory_info.txt", out.toString());
        } catch (Throwable t) {
            SessionStore.append(sessionDir, "accessory_info.txt",
                    SessionStore.isoNow() + " reason=" + reason + " error=" + t.getClass().getSimpleName());
        }
    }

    private void handleBattery(Intent intent) {
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean powered = (plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0;
        if (powered != usbPower) {
            usbPower = powered;
            event("INFO", powered ? "USB-питание появилось" : "USB-питание пропало",
                    "BatteryManager; не используется как критерий handshake");
        }
    }

    private void finishAccessoryDuration(long now) {
        if (accessoryStartElapsed > 0) {
            long duration = Math.max(0, now - accessoryStartElapsed);
            accessoryDurationsMs.add(duration);
            accessoryStartElapsed = -1L;
        }
    }

    private void writeUsbStateIfChanged() {
        String value = SessionStore.isoNow() + " elapsed=" + SystemClock.elapsedRealtime()
                + " connected=" + usbConnected
                + " configured=" + usbConfigured
                + " accessory=" + usbAccessory
                + " mtp=" + usbMtp
                + " ptp=" + usbPtp
                + " adb=" + usbAdb
                + " power=" + usbPower;
        String fingerprint = value.substring(value.indexOf(" connected="));
        if (fingerprint.equals(lastUsbFingerprint)) return;
        lastUsbFingerprint = fingerprint;
        SessionStore.append(sessionDir, "raw_usb.txt", value);
    }

    private void verifyBackendAndMaybeBind(String reason) {
        if (!enhancedRequested) return;
        try {
            if (!Shizuku.pingBinder()) {
                shell = null;
                event("WARN", "Shizuku недоступен", "Базовая диагностика продолжает работать");
                return;
            }
            int backendUid = Shizuku.getUid();
            if (!ShellIdentityPolicy.isSafeBackendUid(backendUid)) {
                shell = null;
                bindingShell = false;
                event("BAD", "Shizuku root/unsafe mode заблокирован",
                        reason + ": backend uid=" + backendUid + "; bindUserService НЕ выполнялся");
                publishState();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                shell = null;
                event("WARN", "Shizuku без разрешения", "Базовая диагностика активна; enhanced mode не включён");
                return;
            }
            if (bindingShell || shell != null) return;

            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), ShellUserService.class.getName()))
                    .processNameSuffix("diagnostics")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                    .daemon(false)
                    .tag("pcm_link_shell");
            bindingShell = true;
            Shizuku.bindUserService(userServiceArgs, shellConnection);
        } catch (Throwable t) {
            shell = null;
            bindingShell = false;
            event("WARN", "Расширенная диагностика недоступна", String.valueOf(t.getMessage()));
        }
    }

    private void captureLightSnapshot(String reason) {
        VerifiedShell s = shell;
        if (s == null || sessionDir == null) return;
        io.execute(() -> {
            try {
                String state = s.exec("/system/bin/getprop", "sys.usb.state");
                String config = s.exec("/system/bin/getprop", "sys.usb.config");
                String persist = s.exec("/system/bin/getprop", "persist.sys.usb.config");
                String body = SessionStore.isoNow() + " reason=" + reason
                        + "\nsys.usb.state=" + state
                        + "\nsys.usb.config=" + config
                        + "\npersist.sys.usb.config=" + persist + "\n";
                String fp = state + "|" + config + "|" + persist;
                if (!fp.equals(lastPropsFingerprint)) {
                    lastPropsFingerprint = fp;
                    SessionStore.append(sessionDir, "usb_props.txt", body);
                }
            } catch (Throwable t) {
                event("WARN", "Не удалось прочитать USB properties", t.getClass().getSimpleName());
            }
        });
    }

    private void captureDeepSnapshot(String reason) {
        VerifiedShell s = shell;
        if (s == null || sessionDir == null) {
            SessionStore.append(sessionDir, "snapshots.txt",
                    "\n=== " + reason + " " + SessionStore.isoNow() + " ===\n"
                            + "Enhanced snapshot unavailable; clean base mode stays read-only.\n"
                            + currentUsbSummary() + "\n" + lockState() + "\n");
            return;
        }
        io.execute(() -> {
            try {
                String state = s.exec("/system/bin/getprop", "sys.usb.state");
                String config = s.exec("/system/bin/getprop", "sys.usb.config");
                String persist = s.exec("/system/bin/getprop", "persist.sys.usb.config");
                String usb = s.exec("/system/bin/dumpsys", "usb");
                String ps = s.exec("/system/bin/ps", "-A");
                projectionProcessSeen = hasProjectionProcess(ps);
                String body = "\n=== " + reason + " " + SessionStore.isoNow()
                        + " elapsed=" + SystemClock.elapsedRealtime() + " ===\n"
                        + "--- public USB state ---\n" + currentUsbSummary() + "\n"
                        + "--- lock/screen ---\n" + lockState() + "\n"
                        + "--- explicit properties ---\n"
                        + "sys.usb.state=" + state + "\n"
                        + "sys.usb.config=" + config + "\n"
                        + "persist.sys.usb.config=" + persist + "\n"
                        + "--- dumpsys usb ---\n" + usb + "\n"
                        + "--- Android Auto processes ---\n" + filterGearheadProcesses(ps) + "\n";
                SessionStore.append(sessionDir, "snapshots.txt", body);
                publishState();
            } catch (Throwable t) {
                event("WARN", "Ошибка расширенного snapshot", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private void captureLogcatWindow() {
        VerifiedShell s = shell;
        if (!started || !enhancedRequested || s == null || sessionDir == null) return;
        long from = logCursorMs;
        long next = System.currentTimeMillis();
        try {
            String fromText = logcatTime(from);
            String out = s.exec(
                    "/system/bin/logcat",
                    "-b", "main",
                    "-b", "system",
                    "-b", "events",
                    "-b", "crash",
                    "-v", "threadtime",
                    "-v", "year",
                    "-T", fromText,
                    "-d");
            logCursorMs = next;
            if (out == null || out.isBlank()) return;
            StringBuilder keep = new StringBuilder();
            for (String line : out.split("\\R")) {
                if (!isRelevantLogLine(line)) continue;
                String safe = Redactor.redact(line);
                keep.append(safe).append('\n');
                logSignatureCounts.merge(logSignature(safe), 1, Integer::sum);
            }
            if (keep.length() > 0) SessionStore.append(sessionDir, "logcat_filtered.txt", keep.toString());
        } catch (Throwable t) {
            SessionStore.append(sessionDir, "logcat_errors.txt",
                    SessionStore.isoNow() + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isRelevantLogLine(String line) {
        if (line == null) return false;
        String l = line.toLowerCase(Locale.ROOT);
        boolean direct = l.contains("usb") || l.contains("accessory") || l.contains("aoa")
                || l.contains("gearhead") || l.contains("android auto") || l.contains("androidauto")
                || l.contains("carservice") || l.contains("car.") || l.contains("car_");
        if (direct) return true;
        if (l.contains("projection")) {
            return l.contains("gearhead") || l.contains("android auto") || l.contains("car");
        }
        return false;
    }

    private static String logSignature(String line) {
        if (line == null) return "";
        return line.replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+", "")
                .trim();
    }

    private static String logcatTime(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(millis));
    }

    private static boolean hasProjectionProcess(String ps) {
        if (ps == null) return false;
        for (String line : ps.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("com.google.android.projection.gearhead:projection")) return true;
        }
        return false;
    }

    private static String filterGearheadProcesses(String ps) {
        if (ps == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : ps.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).contains("com.google.android.projection.gearhead")) {
                out.append(line).append('\n');
            }
        }
        return out.length() == 0 ? "none" : out.toString().trim();
    }

    private void logLockStateIfChanged(String reason) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();
        if (lastInteractive == null || lastKeyguardLocked == null
                || lastInteractive != interactive || lastKeyguardLocked != locked) {
            lastInteractive = interactive;
            lastKeyguardLocked = locked;
            event("INFO", "Экран/блокировка", reason + ": interactive=" + interactive + ", keyguardLocked=" + locked);
        }
    }

    private String lockState() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return "interactive=" + (pm != null && pm.isInteractive())
                + ", keyguardLocked=" + (km != null && km.isKeyguardLocked());
    }

    private String currentUsbSummary() {
        return "connected=" + usbConnected
                + ", configured=" + usbConfigured
                + ", accessory=" + usbAccessory
                + ", mtp=" + usbMtp
                + ", ptp=" + usbPtp
                + ", adb=" + usbAdb
                + ", usbPower=" + usbPower;
    }

    private void event(String severity, String title, String detail) {
        if (sessionDir != null) SessionStore.appendTimeline(sessionDir, severity, title, detail);
        sendBroadcast(new Intent(ACTION_EVENT).setPackage(getPackageName())
                .putExtra("severity", severity)
                .putExtra("title", title)
                .putExtra("detail", Redactor.redact(detail))
                .putExtra("time", System.currentTimeMillis()));
    }

    private void publishState() {
        String main;
        String level;
        if (!usbConnected) {
            main = "Жду подключения кабеля";
            level = "IDLE";
        } else if (usbAccessory) {
            main = "Accessory handshake найден";
            level = "GOOD";
        } else if (usbConfigured) {
            main = "USB configured — жду accessory";
            level = "INFO";
        } else {
            main = "USB подключён — жду configuration";
            level = "INFO";
        }
        String usb = usbStateSeen
                ? "conn=" + yesNo(usbConnected) + " cfg=" + yesNo(usbConfigured)
                    + (usbPtp ? " PTP" : "") + (usbMtp ? " MTP" : "") + (usbAdb ? " ADB" : "")
                : (usbPower ? "питание, USB_STATE нет" : "—");
        String accessory = usbAccessory ? "активен" : (usbConnected ? "нет" : "—");
        String aa = projectionProcessSeen ? "projection process" : (usbAccessory ? "accessory signal" : "—");

        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, started)
                .putString(KEY_MAIN_STATUS, main)
                .putString(KEY_USB, usb)
                .putString(KEY_ACCESSORY, accessory)
                .putString(KEY_AA, aa)
                .putString(KEY_LEVEL, level).apply();
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("main", main).putExtra("usb", usb)
                .putExtra("accessory", accessory).putExtra("aa", aa).putExtra("level", level));
    }

    private static String yesNo(boolean value) { return value ? "1" : "0"; }

    private void stopDiagnostics() {
        if (!started) { stopSelf(); return; }
        event("INFO", "Диагностика остановлена", "Сессия готова к экспорту через SAF");
        finishAccessoryDuration(SystemClock.elapsedRealtime());
        writeMetrics();
        writeFindings();
        writeLogRepeatStats();
        started = false;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_MAIN_STATUS, "Диагностика остановлена")
                .putString(KEY_USB, "—").putString(KEY_ACCESSORY, "—")
                .putString(KEY_AA, "—").putString(KEY_LEVEL, "IDLE").apply();
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("main", "Диагностика остановлена").putExtra("usb", "—")
                .putExtra("accessory", "—").putExtra("aa", "—").putExtra("level", "IDLE"));
        if (receiverRegistered) {
            try { unregisterReceiver(systemReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        safeUnbindShell();
        shell = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void writeMetrics() {
        StringBuilder out = new StringBuilder();
        out.append("attach_count=").append(attachCount).append('\n');
        out.append("detach_count=").append(detachCount).append('\n');
        out.append("flap_transitions=").append(attachCount + detachCount).append('\n');
        out.append("manual_car_marker=").append(carMarked).append('\n');
        out.append("usb_state_seen=").append(usbStateSeen).append('\n');
        out.append("usb_state_broadcast_count=").append(usbStateBroadcastCount).append('\n');
        out.append("wired_adb_seen=").append(wiredAdbSeen).append('\n');
        out.append("enhanced_requested=").append(enhancedRequested).append('\n');
        out.append("enhanced_shizuku_ever_available=").append(enhancedEverAvailable).append('\n');
        out.append("clean_base_case=").append(!wiredAdbSeen && !enhancedEverAvailable).append('\n');
        out.append("attach_to_accessory_ms=").append(joinLongs(attachToAccessoryMs)).append('\n');
        out.append("accessory_session_ms=").append(joinLongs(accessoryDurationsMs)).append('\n');
        out.append(forensics.metricsText());
        SessionStore.write(sessionDir, "metrics.txt", out.toString());
    }

    private void writeFindings() {
        if (sessionDir == null) return;
        SessionStore.write(sessionDir, "findings.txt",
                forensics.findingsText(wiredAdbSeen, enhancedEverAvailable));
    }

    private void writeLogRepeatStats() {
        StringBuilder out = new StringBuilder();
        logSignatureCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(300)
                .forEach(e -> out.append('×').append(e.getValue()).append(' ').append(e.getKey()).append('\n'));
        if (out.length() == 0) out.append("No repeated relevant log signatures detected.\n");
        SessionStore.write(sessionDir, "log_repeat_stats.txt", out.toString());
    }

    private static String joinLongs(List<Long> values) {
        if (values.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(values.get(i));
        }
        return b.toString();
    }

    private void safeUnbindShell() {
        try {
            if (userServiceArgs != null && Shizuku.pingBinder()
                    && ShellIdentityPolicy.isSafeBackendUid(Shizuku.getUid())) {
                Shizuku.unbindUserService(userServiceArgs, shellConnection, true);
            }
        } catch (Throwable ignored) {}
        bindingShell = false;
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
                .setContentText("Чистая событийная USB / Android Auto диагностика")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                    "PCM Link diagnostics", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Read-only USB/Android Auto diagnostics");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(systemReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        safeUnbindShell();
        shell = null;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        io.shutdownNow();
        logScheduler.shutdownNow();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
