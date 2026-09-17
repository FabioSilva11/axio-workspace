package com.saaspaymentsolutions.axion;

import android.view.View;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputEditText;

import com.saaspaymentsolutions.axion.ProjectManager;
import java.io.File;

public class ProjectSettings {
    public static final String SETTING_NEW_XML_COMMAND = "new_xml_command";
    public static final String SETTING_ENABLE_VIEWBINDING = "enable_viewbinding";
    public static final String SETTING_COMPILE_SDK_VERSION = "compile_sdk_version";
    public static final String SETTING_MINIMUM_SDK_VERSION = "minimum_sdk_version";
    public static final String SETTING_TARGET_SDK_VERSION = "target_sdk_version";
    public static final String SETTING_GENERIC_VALUE_TRUE = "true";
    public static final String SETTING_GENERIC_VALUE_FALSE = "false";

    private final String scId;

    public ProjectSettings(String scId) {
        this.scId = scId;
    }

    public String getValue(String key) {
        String settingsPath = getSettingsPath();
        File settingsFile = new File(settingsPath);
        if (!settingsFile.exists()) return "";
        String content = FileUtil.readFile(settingsPath);
        int idx = content.indexOf(key + "=");
        if (idx < 0) return "";
        int start = idx + key.length() + 1;
        int end = content.indexOf("\n", start);
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }

    public String getValue(String key, String defaultValue) {
        String value = getValue(key);
        return value.isEmpty() ? defaultValue : value;
    }

    public void setValue(String key, String value) {
        String settingsPath = getSettingsPath();
        File settingsFile = new File(settingsPath);
        File parent = settingsFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String content = "";
        if (settingsFile.exists()) {
            content = FileUtil.readFile(settingsPath);
        }

        int idx = content.indexOf(key + "=");
        if (idx >= 0) {
            int start = idx + key.length() + 1;
            int end = content.indexOf("\n", start);
            if (end < 0) end = content.length();
            content = content.substring(0, idx) + key + "=" + value + content.substring(end);
        } else {
            content = content.trim() + "\n" + key + "=" + value + "\n";
        }

        FileUtil.writeFile(settingsPath, content);
    }

    public void setValues(View[] preferences) {
        for (View v : preferences) {
            Object tag = v.getTag();
            if (tag instanceof String) {
                String key = (String) tag;
                String val = "";
                if (v instanceof TextInputEditText) {
                    val = ((TextInputEditText) v).getText().toString();
                } else if (v instanceof EditText) {
                    val = ((EditText) v).getText().toString();
                }
                setValue(key, val);
            }
        }
    }

    private String getSettingsPath() {
        return ProjectManager.getProjectDir(scId) + File.separator + "settings.properties";
    }
}


