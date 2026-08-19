package com.saaspaymentsolutions.axion;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ConfigActivity extends AppCompatActivity {
    public static final String SETTING_USE_NEW_VERSION_CONTROL = "use_new_version_control";

    private static final String PREFS_NAME = "config";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public static boolean isSettingEnabled(String settingKey) {
        android.content.SharedPreferences prefs = com.saaspaymentsolutions.axion.SketchApplication.getContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getBoolean(settingKey, false);
    }

    public static void setSetting(String settingKey, boolean enabled) {
        android.content.SharedPreferences prefs = com.saaspaymentsolutions.axion.SketchApplication.getContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        prefs.edit().putBoolean(settingKey, enabled).apply();
    }
}
