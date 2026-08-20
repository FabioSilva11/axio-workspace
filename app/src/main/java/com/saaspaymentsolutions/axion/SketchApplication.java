package com.saaspaymentsolutions.axion;

import android.app.Application;
import android.content.Context;

import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;

public class SketchApplication extends Application {
    private static SketchApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ChatFlowLogger.initialize(this);
        AxionAnalytics.initialize(this);
    }

    public static Context getContext() {
        return instance != null ? instance.getApplicationContext() : null;
    }

    public static SketchApplication getInstance() {
        return instance;
    }
}
