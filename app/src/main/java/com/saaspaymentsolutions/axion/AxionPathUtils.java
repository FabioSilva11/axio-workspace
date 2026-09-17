package com.saaspaymentsolutions.axion;

import android.os.Environment;

import java.io.File;

public class AxionPathUtils {
    public static String e() {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + File.separator + ".axion_ide" + File.separator + "icons";
    }

    public static String g() {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + File.separator + ".axion_ide" + File.separator + "temp";
    }

    public static String getAndroidStudioProjectsRoot() {
        return ProjectManager.getAndroidStudioProjectsRoot();
    }

    public static String getAndroidStudioProjectPath(String scId) {
        return ProjectManager.getAndroidStudioProjectPath(scId);
    }

    public static String getWebProjectPath(String scId) {
        return ProjectManager.getWebProjectPath(scId);
    }
}


