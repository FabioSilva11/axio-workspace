package com.saaspaymentsolutions.axion;

import android.app.Activity;
import android.content.Context;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputLayout;

public class Helper {

    public static String getResString(int resId) {
        Context context = com.saaspaymentsolutions.axion.SketchApplication.getContext();
        if (context != null) {
            return context.getString(resId);
        }
        return "";
    }

    public static String getText(EditText editText) {
        if (editText == null) return "";
        return editText.getText().toString().trim();
    }

    public static String getText(TextView textView) {
        if (textView == null) return "";
        return textView.getText().toString().trim();
    }

    public static String getText(TextInputLayout textInputLayout) {
        if (textInputLayout == null || textInputLayout.getEditText() == null) return "";
        return textInputLayout.getEditText().getText().toString().trim();
    }

    public static int getInt(EditText editText, int defaultValue) {
        if (editText == null) return defaultValue;
        try {
            return Integer.parseInt(editText.getText().toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
