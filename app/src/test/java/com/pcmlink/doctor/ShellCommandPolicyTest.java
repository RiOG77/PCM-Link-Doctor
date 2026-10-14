package com.pcmlink.doctor;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShellCommandPolicyTest {
    @Test public void allowsOnlyExplicitReadOnlyGetprop() {
        assertTrue(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/getprop", "sys.usb.state"}));
        assertTrue(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/getprop", "sys.usb.config"}));
        assertTrue(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/getprop", "persist.sys.usb.config"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/getprop", "ro.serialno"}));
    }

    @Test public void rejectsShellPipesRedirectsAndMutatingDumpsys() {
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/sh", "-c", "dumpsys usb"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/dumpsys", "battery", "unplug"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/dumpsys", "battery", "reset"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/dumpsys", "usb", ">", "/sdcard/x"}));
        assertTrue(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/dumpsys", "usb"}));
    }

    @Test public void rejectsLogcatClearAndArbitraryFlags() {
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/logcat", "-c"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/logcat", "-f", "/sdcard/log.txt"}));
        assertTrue(ShellCommandPolicy.isAllowed(new String[]{
                "/system/bin/logcat",
                "-b", "main",
                "-b", "system",
                "-b", "events",
                "-b", "crash",
                "-v", "threadtime",
                "-v", "year",
                "-T", "2026-10-14 21:00:00.000",
                "-d"
        }));
    }

    @Test public void rejectsNewlinesAndUnknownExecutables() {
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/getprop", "sys.usb.state\nanything"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/date"}));
        assertFalse(ShellCommandPolicy.isAllowed(new String[]{"/system/bin/echo", "x"}));
    }
}
