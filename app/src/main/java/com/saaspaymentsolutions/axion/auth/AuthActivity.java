package com.saaspaymentsolutions.axion.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.saaspaymentsolutions.axion.BuildConfig;
import com.saaspaymentsolutions.axion.MainActivity;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.account.FirebaseAccountStore;
import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;

import java.util.Locale;
import java.util.Map;

public class AuthActivity extends AppCompatActivity {
    private TextInputLayout nameLayout;
    private TextInputLayout emailLayout;
    private TextInputLayout passwordLayout;
    private TextInputLayout confirmPasswordLayout;
    private TextInputEditText nameInput;
    private TextInputEditText emailInput;
    private TextInputEditText passwordInput;
    private TextInputEditText confirmPasswordInput;
    private MaterialButton submitButton;
    private MaterialButtonToggleGroup modeToggle;
    private View loadingOverlay;
    private TextView title;
    private TextView subtitle;

    private FirebaseAuth auth;
    private DatabaseReference usersReference;
    private boolean registrationMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();
        usersReference = FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL)
                .getReference("users");

        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            openMain(currentUser, false, bestName(currentUser, null));
            return;
        }

        setContentView(R.layout.activity_auth);
        bindViews();
        setupModeToggle();
        submitButton.setOnClickListener(v -> submit());
        renderMode(false);
    }

    private void bindViews() {
        nameLayout = findViewById(R.id.auth_name_layout);
        emailLayout = findViewById(R.id.auth_email_layout);
        passwordLayout = findViewById(R.id.auth_password_layout);
        confirmPasswordLayout = findViewById(R.id.auth_confirm_password_layout);
        nameInput = findViewById(R.id.auth_name);
        emailInput = findViewById(R.id.auth_email);
        passwordInput = findViewById(R.id.auth_password);
        confirmPasswordInput = findViewById(R.id.auth_confirm_password);
        submitButton = findViewById(R.id.auth_submit);
        modeToggle = findViewById(R.id.auth_mode_toggle);
        loadingOverlay = findViewById(R.id.auth_loading_overlay);
        title = findViewById(R.id.auth_title);
        subtitle = findViewById(R.id.auth_subtitle);
    }

    private void setupModeToggle() {
        modeToggle.check(R.id.auth_mode_login);
        modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            boolean signup = checkedId == R.id.auth_mode_signup;
            renderMode(signup);
            AxionAnalytics.logEvent(
                    this,
                    AxionAnalytics.Events.AUTH_MODE_SELECTED,
                    AxionAnalytics.params(AxionAnalytics.Params.MODE, signup ? "signup" : "login"));
        });
    }

    private void renderMode(boolean registration) {
        registrationMode = registration;
        nameLayout.setVisibility(registration ? View.VISIBLE : View.GONE);
        confirmPasswordLayout.setVisibility(registration ? View.VISIBLE : View.GONE);
        title.setText(registration ? R.string.auth_signup_title : R.string.auth_login_title);
        subtitle.setText(registration ? R.string.auth_signup_subtitle : R.string.auth_login_subtitle);
        submitButton.setText(registration ? R.string.auth_create_account : R.string.auth_enter);
        clearErrors();
    }

    private void submit() {
        clearErrors();
        String name = text(nameInput);
        String email = text(emailInput).toLowerCase(Locale.US);
        String password = text(passwordInput);
        String confirmation = text(confirmPasswordInput);

        boolean valid = true;
        if (registrationMode && name.length() < 2) {
            nameLayout.setError(getString(R.string.auth_name_error));
            valid = false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailLayout.setError(getString(R.string.auth_email_error));
            valid = false;
        }
        if (password.length() < 6) {
            passwordLayout.setError(getString(R.string.auth_password_error));
            valid = false;
        }
        if (registrationMode && !password.equals(confirmation)) {
            confirmPasswordLayout.setError(getString(R.string.auth_password_match_error));
            valid = false;
        }
        if (!valid) {
            AxionAnalytics.logEvent(
                    this,
                    AxionAnalytics.Events.AUTH_RESULT,
                    AxionAnalytics.params(
                            AxionAnalytics.Params.MODE,
                            registrationMode ? "signup" : "login",
                            AxionAnalytics.Params.RESULT,
                            "failure",
                            AxionAnalytics.Params.ERROR_CATEGORY,
                            "validation"));
            return;
        }

        hideKeyboard();
        setLoading(true);
        AxionAnalytics.logEvent(
                this,
                AxionAnalytics.Events.AUTH_ATTEMPT,
                AxionAnalytics.params(
                        AxionAnalytics.Params.MODE,
                        registrationMode ? "signup" : "login"));
        if (registrationMode) {
            register(name, email, password);
        } else {
            login(email, password);
        }
    }

    private void register(String name, String email, String password) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        showFailure(null);
                        return;
                    }
                    UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                            .setDisplayName(name)
                            .build();
                    user.updateProfile(profile)
                            .addOnCompleteListener(task -> createFreeProfile(user, name, email));
                })
                .addOnFailureListener(this::showFailure);
    }

    private void createFreeProfile(FirebaseUser user, String name, String email) {
        Map<String, Object> profile = FirebaseAccountStore.newFreeProfile(user, name);
        usersReference.child(user.getUid()).updateChildren(profile)
                .addOnSuccessListener(unused -> {
                    Bundle params = new Bundle();
                    params.putString(FirebaseAnalytics.Param.METHOD, "password");
                    AxionAnalytics.logEvent(this, FirebaseAnalytics.Event.SIGN_UP, params);
                    AxionAnalytics.logResult(
                            this,
                            AxionAnalytics.Events.AUTH_RESULT,
                            true,
                            null);
                    openMain(user, true, name);
                })
                .addOnFailureListener(error -> {
                    setLoading(false);
                    AxionAnalytics.logResult(
                            this,
                            AxionAnalytics.Events.AUTH_RESULT,
                            false,
                            error);
                    Toast.makeText(this, R.string.auth_profile_save_error, Toast.LENGTH_LONG).show();
                });
    }

    private void login(String email, String password) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        showFailure(null);
                        return;
                    }
                    Bundle params = new Bundle();
                    params.putString(FirebaseAnalytics.Param.METHOD, "password");
                    AxionAnalytics.logEvent(this, FirebaseAnalytics.Event.LOGIN, params);
                    loadOrCreateProfile(user);
                })
                .addOnFailureListener(this::showFailure);
    }

    private void loadOrCreateProfile(FirebaseUser user) {
        DatabaseReference profileReference = usersReference.child(user.getUid());
        profileReference.get()
                .addOnSuccessListener(snapshot -> finishLogin(user, profileReference, snapshot))
                .addOnFailureListener(error -> {
                    setLoading(false);
                    AxionAnalytics.logResult(
                            this,
                            AxionAnalytics.Events.AUTH_RESULT,
                            false,
                            error);
                    Toast.makeText(this, R.string.auth_profile_load_error, Toast.LENGTH_LONG).show();
                });
    }

    private void finishLogin(FirebaseUser user, DatabaseReference reference, DataSnapshot snapshot) {
        String storedName = snapshot.child("name").getValue(String.class);
        String name = bestName(user, storedName);
        Boolean welcomeValue = snapshot.child("welcomeShown").getValue(Boolean.class);
        boolean showWelcome = welcomeValue == null || !welcomeValue;

        Map<String, Object> updates = FirebaseAccountStore.missingValues(
                user,
                snapshot,
                true
        );

        reference.updateChildren(updates)
                .addOnSuccessListener(unused -> {
                    AxionAnalytics.logResult(
                            this,
                            AxionAnalytics.Events.AUTH_RESULT,
                            true,
                            null);
                    openMain(user, showWelcome, name);
                })
                .addOnFailureListener(error -> {
                    setLoading(false);
                    AxionAnalytics.logResult(
                            this,
                            AxionAnalytics.Events.AUTH_RESULT,
                            false,
                            error);
                    Toast.makeText(this, R.string.auth_profile_save_error, Toast.LENGTH_LONG).show();
                });
    }

    private void openMain(FirebaseUser user, boolean showWelcome, String name) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("show_welcome", showWelcome);
        intent.putExtra("user_name", name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private String bestName(FirebaseUser user, String storedName) {
        if (!TextUtils.isEmpty(storedName)) {
            return storedName.trim();
        }
        if (!TextUtils.isEmpty(user.getDisplayName())) {
            return user.getDisplayName().trim();
        }
        String email = user.getEmail();
        if (!TextUtils.isEmpty(email) && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return getString(R.string.auth_default_user_name);
    }

    private void showFailure(Exception error) {
        setLoading(false);
        AxionAnalytics.logResult(
                this,
                AxionAnalytics.Events.AUTH_RESULT,
                false,
                error);
        String message = getString(R.string.auth_generic_error);
        if (error instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) error).getErrorCode();
            if ("ERROR_EMAIL_ALREADY_IN_USE".equals(code)) {
                message = getString(R.string.auth_email_in_use);
            } else if ("ERROR_INVALID_CREDENTIAL".equals(code)
                    || "ERROR_WRONG_PASSWORD".equals(code)
                    || "ERROR_USER_NOT_FOUND".equals(code)) {
                message = getString(R.string.auth_invalid_credentials);
            } else if ("ERROR_NETWORK_REQUEST_FAILED".equals(code)) {
                message = getString(R.string.auth_network_error);
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setLoading(boolean loading) {
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        submitButton.setEnabled(!loading);
        modeToggle.setEnabled(!loading);
        nameInput.setEnabled(!loading);
        emailInput.setEnabled(!loading);
        passwordInput.setEnabled(!loading);
        confirmPasswordInput.setEnabled(!loading);
    }

    private void clearErrors() {
        nameLayout.setError(null);
        emailLayout.setError(null);
        passwordLayout.setError(null);
        confirmPasswordLayout.setError(null);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
