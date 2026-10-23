package com.echoroute.aec;

import com.echoroute.aec.IEchoCallback;

interface IEchoUserService {
    void start(boolean enableAec, boolean enableNs, boolean enableAgc, boolean automaticMode, String targetPackage, boolean forceCloseTarget, String perAppConfig, String ignoredAutoApps, IEchoCallback callback);
    void updateConfig(String perAppConfig, String ignoredAutoApps);
    String queryAppliedEffects();
    void stop();
    boolean isRunning();
    String forceCloseMic();
    String forceClosePackage(String packageName);
}
