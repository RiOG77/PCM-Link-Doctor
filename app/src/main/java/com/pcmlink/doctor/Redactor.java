package com.pcmlink.doctor;

import java.util.regex.Pattern;

public final class Redactor {
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9])(?:25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])){3}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern SERIAL = Pattern.compile("(?i)(serial(?:number)?\\s*[=:]\\s*)[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BSSID = Pattern.compile("(?i)(bssid\\s*[=:]\\s*)[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSID_QUOTED = Pattern.compile("(?i)(ssid\\s*[=:]\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private Redactor() {}

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String s = MAC.matcher(input).replaceAll("[REDACTED_MAC]");
        s = IPV4.matcher(s).replaceAll("[REDACTED_IP]");
        s = EMAIL.matcher(s).replaceAll("[REDACTED_EMAIL]");
        s = SERIAL.matcher(s).replaceAll("$1[REDACTED_SERIAL]");
        s = BSSID.matcher(s).replaceAll("$1[REDACTED_BSSID]");
        s = SSID_QUOTED.matcher(s).replaceAll("$1\"[REDACTED_SSID]\"");
        return s;
    }
}
