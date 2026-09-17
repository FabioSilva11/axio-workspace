package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;

import com.google.android.material.textfield.TextInputLayout;

import java.util.regex.Pattern;

public class PackageNameValidator {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$");
    private final Context context;
    private final TextInputLayout textInputLayout;

    public PackageNameValidator(Context context, TextInputLayout textInputLayout) {
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
            textInputLayout.setError("Package name cannot be empty");
            return false;
        }
        if (!PACKAGE_PATTERN.matcher(text).matches()) {
            textInputLayout.setError("Invalid package name format");
            return false;
        }
        String[] parts = text.split("\\.");
        if (parts.length < 2) {
            textInputLayout.setError("Package name must have at least 2 parts");
            return false;
        }
        textInputLayout.setError(null);
        return true;
    }
}


