package com.pcmlink.doctor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict allow-list for commands executed by the Shizuku user service.
 * No shell parsing, pipes, redirects, command substitution or arbitrary flags are allowed.
 */
public final class ShellCommandPolicy {
    private static final String GETPROP = "/system/bin/getprop";
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final String PS = "/system/bin/ps";
    private static final String LOGCAT = "/system/bin/logcat";

    private static final Set<String> ALLOWED_PROPS = new HashSet<>(Arrays.asList(
            "sys.usb.state",
            "sys.usb.config",
            "persist.sys.usb.config"
    ));

    private static final Pattern LOGCAT_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");

    private ShellCommandPolicy() {}

    public static boolean isAllowed(String[] argv) {
        if (argv == null || argv.length == 0 || argv.length > 24) return false;
        for (String arg : argv) {
            if (arg == null || arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                return false;
            }
        }

        switch (argv[0]) {
            case GETPROP:
                return argv.length == 2 && ALLOWED_PROPS.contains(argv[1]);
            case DUMPSYS:
                return argv.length == 2 && "usb".equals(argv[1]);
            case PS:
                return argv.length == 2 && "-A".equals(argv[1]);
            case LOGCAT:
                return isAllowedLogcat(argv);
            default:
                return false;
        }
    }

    private static boolean isAllowedLogcat(String[] a) {
        if (a.length != 17) return false;
        return LOGCAT.equals(a[0])
                && "-b".equals(a[1]) && "main".equals(a[2])
                && "-b".equals(a[3]) && "system".equals(a[4])
                && "-b".equals(a[5]) && "events".equals(a[6])
                && "-b".equals(a[7]) && "crash".equals(a[8])
                && "-v".equals(a[9]) && "threadtime".equals(a[10])
                && "-v".equals(a[11]) && "year".equals(a[12])
                && "-T".equals(a[13]) && LOGCAT_TIME.matcher(a[14]).matches()
                && "-d".equals(a[15])
                && "-t".equals(a[16]) == false;
    }

    public static void requireAllowed(String[] argv) {
        if (!isAllowed(argv)) {
            throw new SecurityException("Command rejected by PCM Link Doctor read-only policy");
        }
    }
}
