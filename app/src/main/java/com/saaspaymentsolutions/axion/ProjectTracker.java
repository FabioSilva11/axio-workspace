package com.saaspaymentsolutions.axion;

public class ProjectTracker {
    private static String scId;

    public static void setScId(String scId) {
        ProjectTracker.scId = scId;
    }

    public static String getScId() {
        return scId;
    }
}
