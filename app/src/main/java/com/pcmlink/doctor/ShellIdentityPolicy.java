package com.pcmlink.doctor;

public final class ShellIdentityPolicy {
    public static final int SAFE_UID = 2000;
    public static final String SAFE_SELINUX = "u:r:shell:s0";

    private ShellIdentityPolicy() {}

    public static boolean isSafeBackendUid(int uid) {
        return uid == SAFE_UID;
    }

    public static boolean isSafeUserService(int uid, String selinuxContext) {
        return uid == SAFE_UID && SAFE_SELINUX.equals(normalize(selinuxContext));
    }

    public static String normalize(String context) {
        if (context == null) return "";
        return context.replace("\u0000", "").trim();
    }
}
