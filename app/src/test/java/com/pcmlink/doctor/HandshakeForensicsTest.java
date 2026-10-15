package com.pcmlink.doctor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HandshakeForensicsTest {
    @Test public void ptpReenumerationRecovery_isDetected() {
        HandshakeForensics f = new HandshakeForensics();
        f.onAttach(1_000L);
        f.onConfiguredRise(1_100L);
        f.onStuck();
        f.onPtp(50_000L);
        f.onConfiguredDrop(50_603L);
        f.onConfiguredRise(50_622L);
        f.onConfiguredDrop(52_145L);
        HandshakeForensics.AccessoryResult r = f.onAccessory(52_145L);

        assertTrue(r.ptpReenumerationRecovery);
        assertEquals(2_145L, r.ptpToAccessoryMs);
        assertEquals(1, f.ptpRecoveryCount());
        assertTrue(f.findingsText(false, false).contains("clean_base_case=true"));
        assertTrue(f.findingsText(false, false).contains("PTP_REENUMERATION_RECOVERS_AOA"));
    }

    @Test public void accessoryWithoutPtp_isNotClassifiedAsPtpRecovery() {
        HandshakeForensics f = new HandshakeForensics();
        f.onAttach(0L);
        f.onConfiguredRise(100L);
        f.onStuck();
        HandshakeForensics.AccessoryResult r = f.onAccessory(12_000L);

        assertFalse(r.ptpReenumerationRecovery);
        assertEquals(0, f.ptpRecoveryCount());
    }

    @Test public void adbOrEnhancedMode_marksCaseDirty() {
        HandshakeForensics f = new HandshakeForensics();
        assertTrue(f.findingsText(false, false).contains("clean_base_case=true"));
        assertTrue(f.findingsText(true, false).contains("clean_base_case=false"));
        assertTrue(f.findingsText(false, true).contains("clean_base_case=false"));
    }
}
