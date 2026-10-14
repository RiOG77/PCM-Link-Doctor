package com.pcmlink.doctor;

interface IRemoteShell {
    String exec(in String[] argv);
    int uid();
    String selinuxContext();
    void destroy();
}
