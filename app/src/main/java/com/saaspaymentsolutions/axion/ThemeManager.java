package com.saaspaymentsolutions.axion;

import android.graphics.Color;

public class ThemeManager {

    public static class ThemePreset {
        public String name;
        public int colorAccent;
        public int colorPrimary;
        public int colorPrimaryDark;
        public int colorControlHighlight;
        public int colorControlNormal;

        public ThemePreset(String name, int accent, int primary, int primaryDark, int highlight, int normal) {
            this.name = name;
            this.colorAccent = accent;
            this.colorPrimary = primary;
            this.colorPrimaryDark = primaryDark;
            this.colorControlHighlight = highlight;
            this.colorControlNormal = normal;
        }
    }

    public static ThemePreset[] getThemePresets() {
        return new ThemePreset[]{
            new ThemePreset("Material Blue", 0xFF2196F3, 0xFF1976D2, 0xFF0D47A1, 0x402196F3, 0xFF757575),
            new ThemePreset("Material Green", 0xFF4CAF50, 0xFF388E3C, 0xFF1B5E20, 0x404CAF50, 0xFF757575),
            new ThemePreset("Material Purple", 0xFF9C27B0, 0xFF7B1FA2, 0xFF4A148C, 0x409C27B0, 0xFF757575),
            new ThemePreset("Material Red", 0xFFF44336, 0xFFD32F2F, 0xFFB71C1C, 0x40F44336, 0xFF757575),
            new ThemePreset("Material Orange", 0xFFFF9800, 0xFFF57C00, 0xFFE65100, 0x40FF9800, 0xFF757575),
            new ThemePreset("Teal", 0xFF009688, 0xFF00796B, 0xFF004D40, 0x40009688, 0xFF757575),
            new ThemePreset("Indigo", 0xFF3F51B5, 0xFF303F9F, 0xFF1A237E, 0x403F51B5, 0xFF757575),
            new ThemePreset("Pink", 0xFFE91E63, 0xFFC2185B, 0xFF880E4F, 0x40E91E63, 0xFF757575),
        };
    }

    public static ThemePreset getDefault() {
        return getThemePresets()[0];
    }

    public static ThemePreset generateRandomTheme() {
        int accent = Color.argb(255, randomChannel(), randomChannel(), randomChannel());
        int primary = darken(accent);
        int primaryDark = darken(primary);
        int highlight = (accent & 0x00FFFFFF) | 0x40000000;
        int normal = 0xFF757575;
        return new ThemePreset("Random", accent, primary, primaryDark, highlight, normal);
    }

    private static int randomChannel() {
        return (int) (Math.random() * 200) + 55;
    }

    private static int darken(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv);
    }
}
