package com.pcmlink.doctor;

import android.content.Context;
import android.os.Build;

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
            append(dir, "device.txt", "PCM Link Doctor " + BuildConfig.VERSION_NAME + "\n"
                    + "Started: " + isoNow() + "\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "Product: " + Build.PRODUCT + "\n"
                    + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                    + "Security patch: " + Build.VERSION.SECURITY_PATCH + "\n"
                    + "Build: " + Build.DISPLAY + "\n");
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
        append(dir, "timeline.txt", System.currentTimeMillis() + "|" + severity + "|"
                + sanitize(title) + "|" + sanitize(detail) + "\n");
    }

    public static void append(File dir, String fileName, String text) {
        if (dir == null || text == null) return;
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(new File(dir, fileName), true)) {
                writer.write(text);
                if (!text.endsWith("\n")) writer.write("\n");
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
            String[] p = line.split("\\|", 4);
            if (p.length == 4) {
                try { result.add(new EventLine(Long.parseLong(p[0]), p[1], p[2], p[3])); }
                catch (NumberFormatException ignored) {}
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
        return s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    public static final class EventLine {
        public final long time;
        public final String severity;
        public final String title;
        public final String detail;
        public EventLine(long time, String severity, String title, String detail) {
            this.time = time; this.severity = severity; this.title = title; this.detail = detail;
        }
    }
}
