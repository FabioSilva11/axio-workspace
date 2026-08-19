package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsManager {
    private final SharedPreferences prefs;

    public PrefsManager(Context context, String name) {
        prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public int d(String key) {
        return prefs.getInt(key, 0);
    }

    @SuppressWarnings("unchecked")
    public <T> T a(String key, T defaultValue) {
        if (defaultValue instanceof Integer) {
            return (T) Integer.valueOf(prefs.getInt(key, (Integer) defaultValue));
        } else if (defaultValue instanceof Float) {
            return (T) Float.valueOf(prefs.getFloat(key, (Float) defaultValue));
        } else if (defaultValue instanceof Long) {
            return (T) Long.valueOf(prefs.getLong(key, (Long) defaultValue));
        } else if (defaultValue instanceof Boolean) {
            return (T) Boolean.valueOf(prefs.getBoolean(key, (Boolean) defaultValue));
        } else if (defaultValue instanceof String) {
            return (T) prefs.getString(key, (String) defaultValue);
        }
        return defaultValue;
    }

    public void a(String key, int value, boolean apply) {
        prefs.edit().putInt(key, value).apply();
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    public void a(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public void a(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    public void c(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public String e(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void e(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public boolean b(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void b(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    public float a(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }

    public void b(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public long c(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    public SharedPreferences preferences() {
        return prefs;
    }
}


