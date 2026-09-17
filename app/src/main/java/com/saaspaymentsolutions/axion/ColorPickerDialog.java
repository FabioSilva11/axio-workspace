package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.SeekBar;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ColorPickerDialog {
    private final Context context;
    private final int initialColor;
    private final boolean showAlpha;
    private final boolean showHex;
    private b listener;
    private AlertDialog dialog;

    public ColorPickerDialog(Context context, int initialColor, boolean showAlpha, boolean showHex) {
        this.context = context;
        this.initialColor = initialColor;
        this.showAlpha = showAlpha;
        this.showHex = showHex;
    }

    public void a(b listener) {
        this.listener = listener;
    }

    public void showAtLocation(View anchor, int gravity, int xOff, int yOff) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = createColorPickerView();
        builder.setView(view);
        dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();

        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.gravity = gravity;
        params.x = xOff;
        params.y = yOff;
        dialog.getWindow().setAttributes(params);
    }

    private View createColorPickerView() {
        FrameLayout container = new FrameLayout(context);
        container.setPadding(48, 32, 48, 32);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(6);
        grid.setRowCount(4);

        int[] colors = {
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
            0xFFFF5722, 0xFF9C27B0, 0xFF3F51B5, 0xFF009688, 0xFF8BC34A, 0xFFFFC107,
            0xFF795548, 0xFF607D8B, 0xFFE91E63, 0xFF2196F3, 0xFF4CAF50, 0xFFFF9800,
            0xFF000000, 0xFF333333, 0xFF666666, 0xFF999999, 0xFFCCCCCC, 0xFFFFFFFF
        };

        for (int color : colors) {
            View colorView = new View(context);
            int size = (int) (48 * context.getResources().getDisplayMetrics().density);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(4, 4, 4, 4);
            colorView.setLayoutParams(params);
            colorView.setBackgroundColor(color);
            colorView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.a(color);
                }
                dialog.dismiss();
            });
            grid.addView(colorView);
        }

        container.addView(grid);
        return container;
    }

    public interface b {
        void a(int color);
        void a(String hex, int color);
    }
}

