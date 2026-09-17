package com.saaspaymentsolutions.axion;

import java.util.HashMap;

public class ProjectMapUtils {

    public static boolean getBoolean(HashMap<String, Object> map, String key) {
        if (map == null) return false;
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    public static int getInt(HashMap<String, Object> map, String key, int defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static String getString(HashMap<String, Object> map, String key, String defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
