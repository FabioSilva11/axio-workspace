package com.saaspaymentsolutions.axion.analytics;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.saaspaymentsolutions.axion.BuildConfig;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single privacy-aware entry point for Axion usage analytics.
 *
 * <p>Never send user-authored text, e-mail addresses, API keys, endpoints, project names,
 * file paths, model responses or source code through this class. Events should describe
 * actions and outcomes using a small set of stable categorical values.</p>
 */
public final class AxionAnalytics {
    private static final String TAG = "AxionAnalytics";
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_VALUE_LENGTH = 100;
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");

    public static final class Events {
        public static final String AUTH_MODE_SELECTED = "auth_mode_selected";
        public static final String AUTH_ATTEMPT = "auth_attempt";
        public static final String AUTH_RESULT = "auth_result";
        public static final String LOGOUT = "logout";
        public static final String WELCOME_SEEN = "welcome_seen";
        public static final String PROFILE_OPENED = "profile_opened";
        public static final String PLANS_OPENED = "plans_opened";
        public static final String ACCOUNT_LOAD_RESULT = "account_load_result";

        public static final String PROJECT_CREATE_STARTED = "project_create_started";
        public static final String PROJECT_IMPORT_STARTED = "project_import_started";
        public static final String PROJECT_IMPORT_RESULT = "project_import_result";
        public static final String PROJECT_OPENED = "project_opened";
        public static final String PROJECT_PIN_CHANGED = "project_pin_changed";
        public static final String PROJECT_BACKUP_RESULT = "project_backup_result";
        public static final String PROJECT_DELETED = "project_deleted";
        public static final String PROJECT_SETTINGS_SAVED = "project_settings_saved";

        public static final String CHAT_MESSAGE_SENT = "chat_message_sent";
        public static final String CHAT_RUN_RESULT = "chat_run_result";
        public static final String CHAT_RUN_CANCELLED = "chat_run_cancelled";
        public static final String CHAT_THREAD_CREATED = "chat_thread_created";
        public static final String CHAT_THREAD_OPENED = "chat_thread_opened";
        public static final String CHAT_THREAD_RENAMED = "chat_thread_renamed";
        public static final String CHAT_THREAD_DELETED = "chat_thread_deleted";
        public static final String CHAT_REFERENCE_ADDED = "chat_reference_added";
        public static final String CHAT_EXPORTED = "chat_exported";
        public static final String CHAT_MODE_CHANGED = "chat_mode_changed";
        public static final String MODEL_SELECTED = "model_selected";

        public static final String PROJECT_BUILD_STARTED = "project_build_started";
        public static final String PROJECT_BUILD_RESULT = "project_build_result";
        public static final String PROJECT_BUILD_CANCELLED = "project_build_cancelled";
        public static final String AUTO_REPAIR_STARTED = "auto_repair_started";

        public static final String PROVIDER_CREATED = "provider_created";
        public static final String PROVIDER_IMPORTED = "provider_imported";
        public static final String PROVIDER_UPDATED = "provider_updated";
        public static final String PROVIDER_REMOVED = "provider_removed";
        public static final String PROVIDER_TEST_RESULT = "provider_test_result";
        public static final String MODELS_FETCH_RESULT = "models_fetch_result";
        public static final String MODEL_ADDED = "model_added";
        public static final String MODEL_REMOVED = "model_removed";
        public static final String SETTING_CHANGED = "setting_changed";

        public static final String LIBRARY_DOWNLOAD_RESULT = "library_download_result";
        public static final String LIBRARY_REMOVED = "library_removed";

        private Events() {
        }
    }

    public static final class Params {
        public static final String ACTION = "action";
        public static final String MODE = "mode";
        public static final String RESULT = "result";
        public static final String ERROR_CATEGORY = "error_category";
        public static final String SOURCE = "source";
        public static final String ENABLED = "enabled";
        public static final String HAS_TEXT = "has_text";
        public static final String HAS_ATTACHMENTS = "has_attachments";
        public static final String ATTACHMENT_TYPE = "attachment_type";
        public static final String PROJECT_TYPE = "project_type";
        public static final String THREAD_COUNT = "thread_count";
        public static final String MESSAGE_COUNT = "message_count";
        public static final String MODEL_COUNT = "model_count";
        public static final String DURATION_MS = "duration_ms";

        private Params() {
        }
    }

    private static volatile FirebaseAnalytics firebaseAnalytics;
    private static volatile boolean installed;

    private AxionAnalytics() {
    }

    public static synchronized void initialize(@NonNull Application application) {
        if (installed) {
            return;
        }
        FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(application);
        analytics.setAnalyticsCollectionEnabled(true);

        Bundle defaults = new Bundle();
        defaults.putString("app_build_type", BuildConfig.BUILD_TYPE);
        analytics.setDefaultEventParameters(defaults);

        firebaseAnalytics = analytics;
        application.registerActivityLifecycleCallbacks(new AnalyticsLifecycleCallbacks());
        installed = true;
    }

    public static void logEvent(@NonNull Context context, @NonNull String eventName) {
        logEvent(context, eventName, null);
    }

    public static void logEvent(
            @NonNull Context context,
            @NonNull String eventName,
            @Nullable Bundle parameters
    ) {
        String safeEventName = validateName(eventName);
        if (safeEventName == null) {
            Log.w(TAG, "Analytics event ignored because its name is invalid");
            return;
        }
        analytics(context).logEvent(safeEventName, sanitize(parameters));
    }

    public static void logResult(
            @NonNull Context context,
            @NonNull String eventName,
            boolean success,
            @Nullable Throwable error
    ) {
        Bundle params = new Bundle();
        params.putString(Params.RESULT, success ? "success" : "failure");
        if (!success && error != null) {
            params.putString(Params.ERROR_CATEGORY, errorCategory(error));
        }
        logEvent(context, eventName, params);
    }

    public static void setUser(
            @NonNull Context context,
            @Nullable String userId,
            @Nullable String plan
    ) {
        FirebaseAnalytics analytics = analytics(context);
        analytics.setUserId(emptyToNull(userId));
        analytics.setUserProperty("plan", emptyToNull(plan));
    }

    public static void clearUser(@NonNull Context context) {
        FirebaseAnalytics analytics = analytics(context);
        analytics.setUserId(null);
        analytics.setUserProperty("plan", null);
    }

    @NonNull
    public static Bundle params(@NonNull Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Analytics parameters must be key/value pairs");
        }
        Bundle bundle = new Bundle();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (!(key instanceof String)) {
                continue;
            }
            putSupportedValue(bundle, (String) key, keyValues[i + 1]);
        }
        return bundle;
    }

    @NonNull
    public static String errorCategory(@NonNull Throwable error) {
        String name = error.getClass().getSimpleName();
        if (name == null || name.trim().isEmpty()) {
            return "unknown_error";
        }
        String normalized = camelToSnake(name);
        return normalized.length() > MAX_VALUE_LENGTH
                ? normalized.substring(0, MAX_VALUE_LENGTH)
                : normalized;
    }

    private static FirebaseAnalytics analytics(@NonNull Context context) {
        FirebaseAnalytics current = firebaseAnalytics;
        if (current != null) {
            return current;
        }
        synchronized (AxionAnalytics.class) {
            if (firebaseAnalytics == null) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
            }
            return firebaseAnalytics;
        }
    }

    @Nullable
    private static String validateName(@Nullable String name) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith("firebase_")
                || lower.startsWith("google_")
                || lower.startsWith("ga_")) {
            return null;
        }
        return name;
    }

    @NonNull
    private static Bundle sanitize(@Nullable Bundle source) {
        Bundle clean = new Bundle();
        if (source == null) {
            return clean;
        }
        Set<String> keys = source.keySet();
        for (String key : keys) {
            String safeKey = validateName(key);
            if (safeKey == null) {
                continue;
            }
            putSupportedValue(clean, safeKey, source.get(key));
        }
        return clean;
    }

    private static void putSupportedValue(
            @NonNull Bundle bundle,
            @NonNull String key,
            @Nullable Object value
    ) {
        String safeKey = validateName(key);
        if (safeKey == null || value == null) {
            return;
        }
        if (value instanceof String) {
            String text = (String) value;
            bundle.putString(safeKey, text.length() > MAX_VALUE_LENGTH
                    ? text.substring(0, MAX_VALUE_LENGTH)
                    : text);
        } else if (value instanceof Integer) {
            bundle.putLong(safeKey, ((Integer) value).longValue());
        } else if (value instanceof Long) {
            bundle.putLong(safeKey, (Long) value);
        } else if (value instanceof Short) {
            bundle.putLong(safeKey, ((Short) value).longValue());
        } else if (value instanceof Byte) {
            bundle.putLong(safeKey, ((Byte) value).longValue());
        } else if (value instanceof Double) {
            bundle.putDouble(safeKey, (Double) value);
        } else if (value instanceof Float) {
            bundle.putDouble(safeKey, ((Float) value).doubleValue());
        } else if (value instanceof Boolean) {
            bundle.putLong(safeKey, (Boolean) value ? 1L : 0L);
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    @NonNull
    private static String screenName(@NonNull Object screen) {
        String simpleName = screen.getClass().getSimpleName();
        simpleName = simpleName.replaceFirst("(Activity|Fragment)$", "");
        String name = camelToSnake(simpleName);
        return name.isEmpty() ? "unknown_screen" : name;
    }

    @NonNull
    private static String camelToSnake(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.US);
        return normalized;
    }

    private static void logFragmentScreen(
            @NonNull Fragment fragment,
            @NonNull Context context
    ) {
        Bundle params = new Bundle();
        params.putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName(fragment));
        params.putString(
                FirebaseAnalytics.Param.SCREEN_CLASS,
                fragment.getClass().getSimpleName());
        analytics(context).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, params);
    }

    private static final class AnalyticsLifecycleCallbacks
            implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(
                @NonNull Activity activity,
                @Nullable Bundle savedInstanceState
        ) {
            if (activity instanceof FragmentActivity) {
                FragmentActivity fragmentActivity = (FragmentActivity) activity;
                fragmentActivity.getSupportFragmentManager()
                        .registerFragmentLifecycleCallbacks(
                                new FragmentManager.FragmentLifecycleCallbacks() {
                                    @Override
                                    public void onFragmentResumed(
                                            @NonNull FragmentManager fragmentManager,
                                            @NonNull Fragment fragment
                                    ) {
                                        if (fragment.isVisible()) {
                                            logFragmentScreen(fragment, fragmentActivity);
                                        }
                                    }
                                },
                                true);
            }
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(
                @NonNull Activity activity,
                @NonNull Bundle outState
        ) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
