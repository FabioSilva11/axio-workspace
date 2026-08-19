package com.saaspaymentsolutions.axion;

import android.os.SystemClock;
import android.view.View;
import android.view.animation.AnimationUtils;

public class ClickGuard {
    private static long lastClickTime = 0;
    private static final int MIN_CLICK_INTERVAL = 500;

    public static boolean a() {
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return true;
        }
        lastClickTime = currentTime;
        return false;
    }

    public static void a(View view) {
        if (view != null) {
            view.setEnabled(false);
            view.postDelayed(() -> view.setEnabled(true), 300);
        }
    }
}

