package com.pcmlink.doctor;

import java.util.ArrayList;
import java.util.List;

final class HandshakeForensics {
    static final long PTP_RECOVERY_WINDOW_MS = 5_000L;
    private static final long PTP_REENUM_WINDOW_MS = 10_000L;

    static final class AccessoryResult {
        final long attachToAccessoryMs;
        final long initialConfiguredToAccessoryMs;
        final long lastConfiguredToAccessoryMs;
        final long ptpToAccessoryMs;
        final long configDropToAccessoryMs;
        final boolean ptpReenumerationRecovery;

        AccessoryResult(long attachToAccessoryMs,
                        long initialConfiguredToAccessoryMs,
                        long lastConfiguredToAccessoryMs,
                        long ptpToAccessoryMs,
                        long configDropToAccessoryMs,
                        boolean ptpReenumerationRecovery) {
            this.attachToAccessoryMs = attachToAccessoryMs;
            this.initialConfiguredToAccessoryMs = initialConfiguredToAccessoryMs;
            this.lastConfiguredToAccessoryMs = lastConfiguredToAccessoryMs;
            this.ptpToAccessoryMs = ptpToAccessoryMs;
            this.configDropToAccessoryMs = configDropToAccessoryMs;
            this.ptpReenumerationRecovery = ptpReenumerationRecovery;
        }
    }

    private long attachElapsed = -1L;
    private long firstConfiguredElapsed = -1L;
    private long lastConfiguredRiseElapsed = -1L;
    private long lastConfiguredDropElapsed = -1L;
    private long ptpElapsed = -1L;
    private boolean stuckThisConnection;

    private int configuredRiseCount;
    private int configuredDropWhileConnectedCount;
    private int reenumerationCount;
    private int stuckCount;
    private int ptpRecoveryCount;

    private final List<Long> initialConfigToAccessoryMs = new ArrayList<>();
    private final List<Long> lastConfigToAccessoryMs = new ArrayList<>();
    private final List<Long> ptpToAccessoryMs = new ArrayList<>();
    private final List<Long> ptpToConfigDropMs = new ArrayList<>();
    private final List<Long> configDropToAccessoryMs = new ArrayList<>();

    void onAttach(long now) {
        attachElapsed = now;
        firstConfiguredElapsed = -1L;
        lastConfiguredRiseElapsed = -1L;
        lastConfiguredDropElapsed = -1L;
        ptpElapsed = -1L;
        stuckThisConnection = false;
    }

    void onDetach() {
        attachElapsed = -1L;
        firstConfiguredElapsed = -1L;
        lastConfiguredRiseElapsed = -1L;
        lastConfiguredDropElapsed = -1L;
        ptpElapsed = -1L;
        stuckThisConnection = false;
    }

    int onConfiguredRise(long now) {
        configuredRiseCount++;
        if (firstConfiguredElapsed < 0) firstConfiguredElapsed = now;
        if (lastConfiguredDropElapsed >= 0 && attachElapsed >= 0 && lastConfiguredDropElapsed >= attachElapsed) {
            reenumerationCount++;
        }
        lastConfiguredRiseElapsed = now;
        return configuredRiseCount;
    }

    void onConfiguredDrop(long now) {
        configuredDropWhileConnectedCount++;
        if (ptpElapsed >= 0 && now >= ptpElapsed && now - ptpElapsed <= PTP_REENUM_WINDOW_MS) {
            ptpToConfigDropMs.add(now - ptpElapsed);
        }
        lastConfiguredDropElapsed = now;
    }

    void onPtp(long now) {
        ptpElapsed = now;
    }

    void onStuck() {
        if (!stuckThisConnection) stuckCount++;
        stuckThisConnection = true;
    }

    AccessoryResult onAccessory(long now) {
        long fromAttach = delta(attachElapsed, now);
        long fromInitialConfig = delta(firstConfiguredElapsed, now);
        long fromLastConfig = delta(lastConfiguredRiseElapsed, now);
        long fromPtp = delta(ptpElapsed, now);
        long fromDrop = delta(lastConfiguredDropElapsed, now);

        if (fromInitialConfig >= 0) initialConfigToAccessoryMs.add(fromInitialConfig);
        if (fromLastConfig >= 0) lastConfigToAccessoryMs.add(fromLastConfig);
        if (fromPtp >= 0) ptpToAccessoryMs.add(fromPtp);
        if (fromDrop >= 0) configDropToAccessoryMs.add(fromDrop);

        boolean recovered = stuckThisConnection
                && fromPtp >= 0
                && fromPtp <= PTP_RECOVERY_WINDOW_MS
                && lastConfiguredDropElapsed >= ptpElapsed;
        if (recovered) ptpRecoveryCount++;
        stuckThisConnection = false;

        return new AccessoryResult(fromAttach, fromInitialConfig, fromLastConfig,
                fromPtp, fromDrop, recovered);
    }

    int configuredRiseCount() { return configuredRiseCount; }
    int configuredDropWhileConnectedCount() { return configuredDropWhileConnectedCount; }
    int reenumerationCount() { return reenumerationCount; }
    int stuckCount() { return stuckCount; }
    int ptpRecoveryCount() { return ptpRecoveryCount; }
    long lastConfiguredRiseElapsed() { return lastConfiguredRiseElapsed; }

    String metricsText() {
        StringBuilder out = new StringBuilder();
        out.append("configured_rise_count=").append(configuredRiseCount).append('\n');
        out.append("configured_drop_while_connected_count=").append(configuredDropWhileConnectedCount).append('\n');
        out.append("reenumeration_count=").append(reenumerationCount).append('\n');
        out.append("stuck_handshake_count=").append(stuckCount).append('\n');
        out.append("ptp_reenumeration_recovery_count=").append(ptpRecoveryCount).append('\n');
        out.append("initial_config_to_accessory_ms=").append(join(initialConfigToAccessoryMs)).append('\n');
        out.append("last_config_to_accessory_ms=").append(join(lastConfigToAccessoryMs)).append('\n');
        out.append("ptp_to_accessory_ms=").append(join(ptpToAccessoryMs)).append('\n');
        out.append("ptp_to_config_drop_ms=").append(join(ptpToConfigDropMs)).append('\n');
        out.append("config_drop_to_accessory_ms=").append(join(configDropToAccessoryMs)).append('\n');
        return out.toString();
    }

    String findingsText(boolean wiredAdbSeen, boolean enhancedShizukuUsed) {
        boolean cleanBase = !wiredAdbSeen && !enhancedShizukuUsed;
        String pattern = ptpRecoveryCount > 0
                ? "PTP_REENUMERATION_RECOVERS_AOA"
                : (stuckCount > 0 ? "CONFIGURED_WITHOUT_ACCESSORY" : "NO_STUCK_PATTERN");
        return "clean_base_case=" + cleanBase + '\n'
                + "pattern=" + pattern + '\n'
                + "stuck_handshake_count=" + stuckCount + '\n'
                + "ptp_reenumeration_recovery_count=" + ptpRecoveryCount + '\n'
                + "interpretation=" + interpretation(pattern) + '\n';
    }

    private static String interpretation(String pattern) {
        if ("PTP_REENUMERATION_RECOVERS_AOA".equals(pattern)) {
            return "USB reached configured without accessory; PTP was followed by USB re-enumeration and accessory appeared within 5 s.";
        }
        if ("CONFIGURED_WITHOUT_ACCESSORY".equals(pattern)) {
            return "USB reached configured but accessory did not appear during the stuck window.";
        }
        return "No stuck configured-without-accessory pattern was confirmed in this session.";
    }

    private static long delta(long start, long end) {
        return start >= 0 && end >= start ? end - start : -1L;
    }

    private static String join(List<Long> values) {
        if (values.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(values.get(i));
        }
        return out.toString();
    }
}
