package com.pcmlink.doctor;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SessionStore {
    private static final Object LOCK = new Object();
    private static final String PREF = "pcm_link_doctor";
    private static final String KEY_SESSION = "current_session";

    private SessionStore() {}

    public static File startSession(Context context) {
        synchronized (LOCK) {
            File base = new File(context.getFilesDir(), "sessions");
            base.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(base, "session_" + stamp);
            dir.mkdirs();
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SESSION, dir.getAbsolutePath()).apply();

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            StringBuilder info = new StringBuilder();
            info.append("PCM Link Doctor ").append(BuildConfig.VERSION_NAME).append('\n');
            info.append("Started: ").append(isoNow()).append('\n');
            info.append("ElapsedRealtimeMs: ").append(SystemClock.elapsedRealtime()).append('\n');
            info.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            info.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            info.append("Security patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n');
            info.append("Build display: ").append(Build.DISPLAY).append('\n');
            info.append("Interactive at start: ").append(pm != null && pm.isInteractive()).append('\n');
            info.append("Keyguard locked at start: ").append(km != null && km.isKeyguardLocked()).append('\n');
            info.append("USB primary source: android.hardware.usb.action.USB_STATE dynamic receiver\n");
            info.append("Shizuku: optional enhanced diagnostics; shell uid=2000 only\n");
            info.append("Samsung Auto Blocker: not read through private settings; record/check manually if relevant\n");
            info.append("Network backend: none\n");

            try {
                PackageInfo aa = context.getPackageManager().getPackageInfo("com.google.android.projection.gearhead", 0);
                info.append("Android Auto versionName: ").append(aa.versionName).append('\n');
                info.append("Android Auto versionCode: ").append(aa.getLongVersionCode()).append('\n');
                info.append("Android Auto firstInstallTime: ").append(aa.firstInstallTime).append('\n');
                info.append("Android Auto lastUpdateTime: ").append(aa.lastUpdateTime).append('\n');
            } catch (Throwable t) {
                info.append("Android Auto package info: unavailable\n");
            }

            write(dir, "device.txt", info.toString());
            return dir;
        }
    }

    public static File latestSession(Context context) {
        File base = new File(context.getFilesDir(), "sessions");
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        File latest = dirs[0];
        for (File d : dirs) if (d.lastModified() > latest.lastModified()) latest = d;
        return latest;
    }

    public static void appendTimeline(File dir, String severity, String title, String detail) {
        if (dir == null) return;
        append(dir, "timeline.txt",
                System.currentTimeMillis() + "|" + SystemClock.elapsedRealtime() + "|" + severity + "|"
                        + sanitize(title) + "|" + sanitize(detail) + "\n");
    }

    public static void append(File dir, String fileName, String text) {
        if (dir == null || text == null) return;
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(new File(dir, fileName), true)) {
                String safe = Redactor.redact(text);
                writer.write(safe);
                if (!safe.endsWith("\n")) writer.write("\n");
            } catch (Exception ignored) {}
        }
    }

    public static void write(File dir, String fileName, String text) {
        if (dir == null || text == null) return;
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(new File(dir, fileName), false)) {
                writer.write(Redactor.redact(text));
            } catch (Exception ignored) {}
        }
    }

    public static List<EventLine> readTailEvents(Context context, int limit) {
        List<EventLine> result = new ArrayList<>();
        File dir = latestSession(context);
        if (dir == null) return result;
        File f = new File(dir, "timeline.txt");
        if (!f.isFile()) return result;
        Deque<String> tail = new ArrayDeque<>(limit);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (tail.size() == limit) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (Exception ignored) {}
        for (String line : tail) {
            String[] p = line.split("\\|", 5);
            if (p.length == 5) {
                try { result.add(new EventLine(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3], p[4])); }
                catch (NumberFormatException ignored) {}
            } else {
                String[] legacy = line.split("\\|", 4);
                if (legacy.length == 4) {
                    try { result.add(new EventLine(Long.parseLong(legacy[0]), -1, legacy[1], legacy[2], legacy[3])); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return result;
    }

    public static File exportLatest(Context context) throws Exception {
        File dir = latestSession(context);
        if (dir == null) throw new IllegalStateException("Нет диагностической сессии");
        File exports = new File(context.getCacheDir(), "exports");
        exports.mkdirs();
        File out = new File(exports, "PCM_Link_Doctor_" + dir.getName().replace("session_", "") + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            File[] files = dir.listFiles(File::isFile);
            if (files != null) {
                byte[] buf = new byte[16_384];
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry(f.getName()));
                    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                        int n;
                        while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                    }
                    zip.closeEntry();
                }
            }
        }
        return out;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return Redactor.redact(s).replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    public static final class EventLine {
        public final long time;
        public final long elapsed;
        public final String severity;
        public final String title;
        public final String detail;
        public EventLine(long time, long elapsed, String severity, String title, String detail) {
            this.time = time;
            this.elapsed = elapsed;
            this.severity = severity;
            this.title = title;
            this.detail = detail;
        }
    }
}
