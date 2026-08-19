package com.saaspaymentsolutions.axion.account;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, real-time snapshot of the signed-in user's plan/status.
 *
 * <p>This is intentionally NOT backed by SharedPreferences or any other disk cache.
 * It only ever reflects the latest value pushed by {@link FirebaseAccountStore}'s live
 * "users/{uid}" listener. As soon as the app is opened and the user is authenticated,
 * this fills in from the real-time listener; there is nothing here left over from a
 * previous session to go stale or drift from the server.</p>
 *
 * <p>Anything that needs to decide what a user is allowed to see or do based on their
 * plan (e.g. filtering the model catalog) should read {@link #isPaid()} /
 * {@link #current()} instead of a cached preference value.</p>
 */
public final class AxionSession {

    public interface Listener {
        void onSessionChanged();
    }

    @Nullable
    private static volatile AxionAccount current;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private AxionSession() {
    }

    /** Called by FirebaseAccountStore whenever "users/{uid}" changes, in real time. */
    public static void update(@Nullable AxionAccount account) {
        current = account;
        notifyListeners();
    }

    /** Called when the account listener stops (e.g. sign-out). */
    public static void clear() {
        current = null;
        notifyListeners();
    }

    @Nullable
    public static AxionAccount current() {
        return current;
    }

    /** True once the real-time account listener has delivered at least one value. */
    public static boolean isLoaded() {
        return current != null;
    }

    /** Whether the signed-in user currently has an active paid plan, right now. */
    public static boolean isPaid() {
        AxionAccount account = current;
        return account != null && PlanEntitlements.isPaid(account.planId, account.status);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onSessionChanged();
        }
    }
}
