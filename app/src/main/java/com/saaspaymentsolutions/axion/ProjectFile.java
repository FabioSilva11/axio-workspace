package com.saaspaymentsolutions.axion;

import android.graphics.Color;

public class ProjectFile {
    public static final int COLOR_ACCENT = 0;
    public static final int COLOR_PRIMARY = 1;
    public static final int COLOR_PRIMARY_DARK = 2;
    public static final int COLOR_CONTROL_HIGHLIGHT = 3;
    public static final int COLOR_CONTROL_NORMAL = 4;

    private static final int[] DEFAULT_COLORS = {
        0xFF2196F3, // accent - blue
        0xFF3F51B5, // primary - indigo
        0xFF1A237E, // primary dark
        0x402196F3, // control highlight
        0xFF757575  // control normal
    };

    public static int getDefaultColor(int colorType) {
        if (colorType >= 0 && colorType < DEFAULT_COLORS.length) {
            return DEFAULT_COLORS[colorType];
        }
        return Color.BLACK;
    }
}
