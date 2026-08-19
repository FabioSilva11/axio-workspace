package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputLayout;

public class TextInputValidator {
    private final Context context;
    private final TextInputLayout textInputLayout;
    private final EditText editText;

    public TextInputValidator(Context context, TextInputLayout textInputLayout) {
        this.context = context;
        this.textInputLayout = textInputLayout;
        this.editText = textInputLayout.getEditText();

        if (editText != null) {
            editText.addTextChangedListener(new TextWatcher() {
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
        if (editText == null) return false;
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) {
            textInputLayout.setError("This field cannot be empty");
            return false;
        }
        textInputLayout.setError(null);
        return true;
    }

    public String getText() {
        return editText != null ? editText.getText().toString().trim() : "";
    }
}


