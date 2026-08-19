package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;

import com.google.android.material.textfield.TextInputLayout;

public class AppNameValidator {
    private final Context context;
    private final TextInputLayout textInputLayout;

    public AppNameValidator(Context context, TextInputLayout textInputLayout) {
        this.context = context;
        this.textInputLayout = textInputLayout;

        if (textInputLayout.getEditText() != null) {
            textInputLayout.getEditText().addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    textInputLayout.setError(null);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    public boolean b() {
        if (textInputLayout.getEditText() == null) return false;
        String text = textInputLayout.getEditText().getText().toString().trim();
        if (text.isEmpty()) {
            textInputLayout.setError("App name cannot be empty");
            return false;
        }
        if (text.length() > 30) {
            textInputLayout.setError("App name too long (max 30 chars)");
            return false;
        }
        textInputLayout.setError(null);
        return true;
    }
}


