package com.saaspaymentsolutions.axion;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.lang.ref.WeakReference;

/** Applies the server-controlled access block to every Activity in the application. */
public final class AxionAppBlockManager {
    public static final class State {
        public final boolean enabled;
        @NonNull public final String title;
        @NonNull public final String body;

        private State(boolean enabled, @NonNull String title, @NonNull String body) {
            this.enabled = enabled;
            this.title = title;
            this.body = body;
        }
    }

    private static final State DISABLED = new State(false, "", "");
    private static volatile State state = DISABLED;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static boolean initialized;

    private AxionAppBlockManager() {}

    public static synchronized void initialize(@NonNull Application application) {
        if (initialized) return;
        initialized = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}
            @Override public void onActivityStarted(@NonNull Activity activity) {}

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                resumedActivity = new WeakReference<>(activity);
                applyState(activity);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (resumedActivity.get() == activity) resumedActivity.clear();
            }

            @Override public void onActivityStopped(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });

        FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL)
                .getReference("config/app/accessBlock")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                        String title = clean(snapshot.child("title").getValue(String.class));
                        String body = clean(snapshot.child("body").getValue(String.class));
                        state = new State(Boolean.TRUE.equals(enabled), title, body);
                        Activity activity = resumedActivity.get();
                        if (activity != null) activity.runOnUiThread(() -> applyState(activity));
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // Fail open: a network or Firebase outage must not lock users out by itself.
                    }
                });
    }

    @NonNull
    public static State currentState() {
        return state;
    }

    private static void applyState(@NonNull Activity activity) {
        State current = state;
        if (activity instanceof AppBlockedActivity) {
            AppBlockedActivity blockedActivity = (AppBlockedActivity) activity;
            if (current.enabled) blockedActivity.render(current);
            else blockedActivity.releaseBlock();
            return;
        }
        if (!current.enabled || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = new Intent(activity, AppBlockedActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
