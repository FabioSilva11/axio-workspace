package com.saaspaymentsolutions.axion.account;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.saaspaymentsolutions.axion.BuildConfig;
import com.saaspaymentsolutions.axion.TokenUsageStore;

import java.util.HashMap;
import java.util.Map;

public final class FirebaseAccountStore {
    public interface Listener {
        void onAccountChanged(@NonNull AxionAccount account);

        void onError(@NonNull Exception error);
    }

    private final Context appContext;
    private final FirebaseDatabase database;
    private DatabaseReference activeReference;
    private ValueEventListener activeListener;
    private boolean migrationStarted;
    private boolean touchLogin;

    public FirebaseAccountStore(@NonNull Context context) {
        appContext = context.getApplicationContext();
        database = FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL);
    }

    public void start(
            @NonNull FirebaseUser user,
            boolean updateLastLogin,
            @NonNull Listener listener
    ) {
        stop();
        migrationStarted = false;
        touchLogin = updateLastLogin;
        DatabaseReference reference = database.getReference("users").child(user.getUid());
        reference.keepSynced(true);
        activeReference = reference;
        activeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                AxionAccount account = readAccount(user, snapshot);
                TokenUsageStore.updateFromServer(
                        appContext,
                        account.creditsUsed,
                        account.creditLimit,
                        account.creditsRemaining,
                        account.creditsReserved);
                PlanEntitlements.sync(appContext, account);
                AxionSession.update(account);
                listener.onAccountChanged(account);
                if (!migrationStarted) {
                    migrationStarted = true;
                    Map<String, Object> missing = missingValues(user, snapshot, touchLogin);
                    if (!missing.isEmpty()) {
                        reference.updateChildren(missing)
                                .addOnFailureListener(listener::onError);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.toException());
            }
        };
        reference.addValueEventListener(activeListener);
    }

    public void stop() {
        if (activeReference != null && activeListener != null) {
            activeReference.removeEventListener(activeListener);
        }
        activeReference = null;
        activeListener = null;
        AxionSession.clear();
    }

    @NonNull
    public static Map<String, Object> newFreeProfile(
            @NonNull FirebaseUser user,
            @NonNull String requestedName
    ) {
        String name = bestName(user, requestedName);
        Map<String, Object> values = new HashMap<>();
        values.put("uid", user.getUid());
        values.put("name", name);
        values.put("email", safe(user.getEmail()));
        values.put("createdAt", ServerValue.TIMESTAMP);
        values.put("lastLoginAt", ServerValue.TIMESTAMP);
        values.put("welcomeShown", true);
        // Plano, assinatura e saldo são criados exclusivamente pelo servidor em
        // /v1/account/bootstrap. O cliente nunca grava plan, subscription ou managedUsage.
        return values;
    }

    @NonNull
    public static Map<String, Object> missingValues(
            @NonNull FirebaseUser user,
            @NonNull DataSnapshot snapshot,
            boolean updateLastLogin
    ) {
        Map<String, Object> updates = new HashMap<>();
        String name = bestName(user, stringValue(snapshot.child("name")));

        putWhenMissing(updates, snapshot, "uid", user.getUid());
        putWhenMissing(updates, snapshot, "name", name);
        putWhenMissing(updates, snapshot, "email", safe(user.getEmail()));
        putWhenMissing(updates, snapshot, "createdAt", ServerValue.TIMESTAMP);
        putWhenMissing(updates, snapshot, "welcomeShown", true);
        if (updateLastLogin) {
            updates.put("lastLoginAt", ServerValue.TIMESTAMP);
        }

        return updates;
    }

    @NonNull
    public static AxionAccount readAccount(
            @NonNull FirebaseUser user,
            @NonNull DataSnapshot snapshot
    ) {
        String name = bestName(user, stringValue(snapshot.child("name")));
        String email = stringValue(snapshot.child("email"));
        if (email.isEmpty()) {
            email = safe(user.getEmail());
        }
        String storedPlan = stringValue(snapshot.child("plan"));
        // Compatibilidade somente de leitura para perfis que o app já gravou
        // no formato duplicado. Novas gravações usam apenas users/{uid}/plan.
        String duplicatePlan = stringValue(snapshot.child("subscription").child("planId"));
        String planId = resolvePlanId(storedPlan, duplicatePlan);
        String status = stringValue(snapshot.child("subscription").child("status"));
        if (status.isEmpty()) {
            status = AxionAccount.STATUS_ACTIVE;
        }
        long used = firstLong(
                snapshot.child("managedUsage").child("creditsUsed"),
                snapshot.child("managedUsage").child("tokensUsed"),
                snapshot.child("usage").child("tokensUsed"),
                snapshot.child("tokensUsed")
        );
        long limit = firstPositiveLong(
                AxionAccount.tokenLimitForPlan(planId),
                snapshot.child("managedUsage").child("creditLimit"),
                snapshot.child("managedUsage").child("tokenLimit"),
                snapshot.child("usage").child("tokenLimit"),
                snapshot.child("tokenLimit")
        );
        Long storedRemaining = longValue(
                snapshot.child("managedUsage").child("creditsRemaining")
        );
        if (storedRemaining == null) {
            storedRemaining = longValue(snapshot.child("managedUsage").child("tokensRemaining"));
        }
        Long storedReserved = longValue(
                snapshot.child("managedUsage").child("creditsReserved")
        );
        if (storedReserved == null) {
            storedReserved = longValue(snapshot.child("managedUsage").child("reservedTokens"));
        }
        long reserved = storedReserved == null ? 0L : Math.max(0L, storedReserved);
        long remaining = storedRemaining == null
                ? AxionAccount.remaining(used, reserved, limit)
                : Math.max(0L, storedRemaining);
        Long storedPeriodStart = longValue(
                snapshot.child("subscription").child("periodStart")
        );
        Long storedPeriodEnd = longValue(
                snapshot.child("subscription").child("periodEnd")
        );
        return new AxionAccount(
                user.getUid(),
                name,
                email,
                planId,
                status,
                used,
                limit,
                remaining,
                reserved,
                storedPeriodStart == null ? 0L : storedPeriodStart,
                storedPeriodEnd == null ? 0L : storedPeriodEnd
        );
    }

    private static void putWhenMissing(
            Map<String, Object> updates,
            DataSnapshot snapshot,
            String path,
            Object value
    ) {
        DataSnapshot current = snapshot;
        for (String segment : path.split("/")) {
            current = current.child(segment);
        }
        if (!current.exists()) {
            updates.put(path, value);
        }
    }

    static String resolvePlanId(String canonicalPlan, String duplicatePlan) {
        String canonical = safe(canonicalPlan);
        return AxionAccount.normalizePlan(
                canonical.isEmpty() ? safe(duplicatePlan) : canonical
        );
    }

    private static long firstLong(DataSnapshot... snapshots) {
        for (DataSnapshot snapshot : snapshots) {
            Long value = longValue(snapshot);
            if (value != null) {
                return Math.max(0L, value);
            }
        }
        return 0L;
    }

    private static long firstPositiveLong(long fallback, DataSnapshot... snapshots) {
        for (DataSnapshot snapshot : snapshots) {
            Long value = longValue(snapshot);
            if (value != null && value > 0L) {
                return value;
            }
        }
        return fallback;
    }

    @Nullable
    private static Long longValue(DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringValue(DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return safe(value);
    }

    private static String bestName(FirebaseUser user, String storedName) {
        if (!safe(storedName).isEmpty()) {
            return safe(storedName);
        }
        if (!safe(user.getDisplayName()).isEmpty()) {
            return safe(user.getDisplayName());
        }
        String email = safe(user.getEmail());
        if (email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "Usuário Axion";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
