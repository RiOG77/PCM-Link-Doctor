package com.pcmlink.doctor;

import android.content.Context;
import android.system.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ShellUserService extends IRemoteShell.Stub {
    private static final int MAX_OUTPUT_CHARS = 450_000;
    private static final int SAFE_SHELL_UID = 2000;

    public ShellUserService() {
        enforceSafeUid();
    }

    public ShellUserService(Context context) {
        enforceSafeUid();
    }

    private static void enforceSafeUid() {
        int uid = Os.getuid();
        if (uid != SAFE_SHELL_UID) {
            throw new SecurityException(
                    "PCM Link Doctor Knox-safe policy: refusing Shizuku uid=" + uid
                            + "; only Android shell uid=2000 is allowed");
        }
    }

    @Override
    public String exec(String command) {
        enforceSafeUid();
        if (command == null || command.isBlank()) return "";
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() + line.length() + 1 > MAX_OUTPUT_CHARS) {
                        out.append("\n[output truncated]");
                        break;
                    }
                    out.append(line).append('\n');
                }
            }
            if (!process.waitFor(7, TimeUnit.SECONDS)) {
                process.destroy();
                return out + "\n[command timeout]";
            }
            return out.toString().trim();
        } catch (SecurityException securityException) {
            throw securityException;
        } catch (Throwable t) {
            return "[exec error] " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override public int uid() { return Os.getuid(); }
    @Override public void destroy() { System.exit(0); }
}
