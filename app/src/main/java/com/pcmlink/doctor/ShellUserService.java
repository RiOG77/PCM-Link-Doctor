package com.pcmlink.doctor;

import android.content.Context;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ShellUserService extends IRemoteShell.Stub {
    private static final int MAX_OUTPUT_CHARS = 500_000;

    public ShellUserService() { enforceIdentity(); }
    public ShellUserService(Context context) { enforceIdentity(); }

    private static void enforceIdentity() {
        int uid = Os.getuid();
        String context = readSelinuxContext();
        if (!ShellIdentityPolicy.isSafeUserService(uid, context)) {
            throw new SecurityException(
                    "PCM Link Doctor Knox-safe policy: refusing uid=" + uid
                            + " context=" + ShellIdentityPolicy.normalize(context));
        }
    }

    @Override
    public String exec(String[] argv) {
        enforceIdentity();
        ShellCommandPolicy.requireAllowed(argv);

        Process process = null;
        try {
            process = new ProcessBuilder(argv)
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
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
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
    @Override public String selinuxContext() { return ShellIdentityPolicy.normalize(readSelinuxContext()); }
    @Override public void destroy() { System.exit(0); }

    private static String readSelinuxContext() {
        File file = new File("/proc/self/attr/current");
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (Throwable t) {
            return "";
        }
    }
}
