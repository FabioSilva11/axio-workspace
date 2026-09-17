package com.saaspaymentsolutions.axion;

import android.app.Application;
import android.content.Context;

import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;

public class SketchApplication extends Application {
    private static SketchApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ChatFlowLogger.initialize(this);
        AxionAnalytics.initialize(this);
        // Re-encrypt legacy plaintext API keys / MCP headers once per process.
        // Cheap after the first run: values already look like "enc:v1:...".
        new Thread(() -> SecurePrefs.migrateSecrets(
                getApplicationContext()
                        .getSharedPreferences(VoidPortSettings.PREFS_NAME, Context.MODE_PRIVATE)),
                "secret-migration").start();
    }

    public static Context getContext() {
        return instance != null ? instance.getApplicationContext() : null;
    }

    public static SketchApplication getInstance() {
        return instance;
    }
}
