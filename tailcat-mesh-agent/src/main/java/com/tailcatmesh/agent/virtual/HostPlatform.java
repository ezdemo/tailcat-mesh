package com.tailcatmesh.agent.virtual;

import java.util.Locale;

/** Host platforms for which the M7 operating-system adapters are defined. */
public enum HostPlatform {
    WINDOWS,
    LINUX,
    UNSUPPORTED;

    public static HostPlatform detect() {
        return detect(System.getProperty("os.name", ""));
    }

    static HostPlatform detect(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) {
            return WINDOWS;
        }
        if (normalized.contains("linux")) {
            return LINUX;
        }
        return UNSUPPORTED;
    }
}
