package com.saaspaymentsolutions.axion;

import android.widget.Toast;

import com.saaspaymentsolutions.axion.SketchApplication;

public class AxionUtil {

    public static void toast(String message) {
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            Toast.makeText(SketchApplication.getContext(), message, Toast.LENGTH_SHORT).show();
        });
    }

    public static void toastError(String message) {
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            Toast.makeText(SketchApplication.getContext(), message, Toast.LENGTH_LONG).show();
        });
    }
}


