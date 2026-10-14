package com.pcmlink.doctor;

interface IRemoteShell {
    String exec(String command);
    int uid();
    void destroy();
}
