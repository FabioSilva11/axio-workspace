package com.saaspaymentsolutions.axion;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.NumberPicker;

public class VersionDialog {
    private final Activity activity;
    private AlertDialog dialog;

    public VersionDialog(Activity activity) {
        this.activity = activity;
    }

    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.myprojects_settings_version_control_title);

        NumberPicker picker = new NumberPicker(activity);
        picker.setMinValue(1);
        picker.setMaxValue(100);
        picker.setValue(1);
        builder.setView(picker);

        builder.setPositiveButton(R.string.common_word_ok, (d, which) -> {
            d.dismiss();
        });
        builder.setNegativeButton(R.string.common_word_cancel, null);

        dialog = builder.create();
        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}


