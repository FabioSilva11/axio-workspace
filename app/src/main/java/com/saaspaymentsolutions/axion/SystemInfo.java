package com.saaspaymentsolutions.axion;

import java.util.Locale;

public final class SystemInfo {
    private SystemInfo() {
    }

    public static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "mac";
        }
        if (name.contains("linux") || name.contains("android")) {
            return "linux";
        }
        return null;
    }
}
