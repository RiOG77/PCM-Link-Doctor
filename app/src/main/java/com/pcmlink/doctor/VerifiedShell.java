package com.pcmlink.doctor;

import android.os.RemoteException;

/**
 * The diagnostic engine accepts this type rather than a raw IRemoteShell.
 * Instances are created only after both UID and SELinux context are verified.
 */
public final class VerifiedShell {
    private final IRemoteShell remote;
    private final int uid;
    private final String selinuxContext;

    private VerifiedShell(IRemoteShell remote, int uid, String selinuxContext) {
        this.remote = remote;
        this.uid = uid;
        this.selinuxContext = selinuxContext;
    }

    public static VerifiedShell verify(IRemoteShell candidate) throws RemoteException {
        if (candidate == null) throw new SecurityException("Missing Shizuku user service");
        int uid = candidate.uid();
        String context = ShellIdentityPolicy.normalize(candidate.selinuxContext());
        if (!ShellIdentityPolicy.isSafeUserService(uid, context)) {
            throw new SecurityException("Unsafe Shizuku identity uid=" + uid + " context=" + context);
        }
        return new VerifiedShell(candidate, uid, context);
    }

    public String exec(String... argv) throws RemoteException {
        ShellCommandPolicy.requireAllowed(argv);
        return remote.exec(argv);
    }

    public int uid() { return uid; }
    public String selinuxContext() { return selinuxContext; }
}
