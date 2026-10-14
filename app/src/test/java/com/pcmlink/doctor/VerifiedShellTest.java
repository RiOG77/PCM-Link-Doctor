package com.pcmlink.doctor;

import android.os.IBinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class VerifiedShellTest {
    @Test public void rejectsRootRemoteShell() {
        IRemoteShell fake = fakeShell(0, "u:r:su:s0");
        assertThrows(SecurityException.class, () -> VerifiedShell.verify(fake));
    }

    @Test public void rejectsShellUidWithWrongSelinuxContext() {
        IRemoteShell fake = fakeShell(2000, "u:r:untrusted_app:s0");
        assertThrows(SecurityException.class, () -> VerifiedShell.verify(fake));
    }

    @Test public void acceptsOnlyShellUidAndShellContext() throws Exception {
        VerifiedShell shell = VerifiedShell.verify(fakeShell(2000, "u:r:shell:s0\n"));
        assertEquals(2000, shell.uid());
        assertEquals("u:r:shell:s0", shell.selinuxContext());
    }

    private static IRemoteShell fakeShell(int uid, String context) {
        return new IRemoteShell() {
            @Override public String exec(String[] argv) { return ""; }
            @Override public int uid() { return uid; }
            @Override public String selinuxContext() { return context; }
            @Override public void destroy() {}
            @Override public IBinder asBinder() { return null; }
        };
    }
}
