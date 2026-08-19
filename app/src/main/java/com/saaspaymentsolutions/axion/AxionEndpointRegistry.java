package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public final class AxionEndpointRegistry {
    private static final String TAG = "AxionEndpointRegistry";
    private static final String PREFS = "axion_endpoint_registry";
    private static final String KEY_ENDPOINT = "firebase_endpoint";
    private static final String DATABASE_PATH = "config/api";

    private static volatile String liveEndpoint = "";
    private static volatile boolean started;
    private static DatabaseReference endpointReference;
    private static ValueEventListener endpointListener;

    private AxionEndpointRegistry() {
    }

    public static synchronized void start(Context context) {
        if (context == null || started) {
            return;
        }
        started = true;
        Context appContext = context.getApplicationContext();
        String databaseUrl = BuildConfig.FIREBASE_DATABASE_URL == null
                ? ""
                : BuildConfig.FIREBASE_DATABASE_URL.trim();
        if (databaseUrl.isEmpty()) {
            Log.w(TAG, "FIREBASE_DATABASE_URL não configurada.");
            return;
        }

        try {
            endpointReference = FirebaseDatabase.getInstance(databaseUrl)
                    .getReference(DATABASE_PATH);
            endpointReference.keepSynced(true);
            endpointListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    String endpoint = snapshot.child("endpoint").getValue(String.class);
                    Boolean online = snapshot.child("online").getValue(Boolean.class);
                    applyRemoteEndpoint(appContext, endpoint, Boolean.TRUE.equals(online));
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Falha ao observar endpoint Firebase: " + error.getMessage());
                }
            };
            endpointReference.addValueEventListener(endpointListener);
        } catch (Exception error) {
            Log.e(TAG, "Falha ao iniciar Realtime Database.", error);
        }
    }

    public static String getEndpoint(Context context) {
        String current = liveEndpoint;
        if (isValidRemoteEndpoint(current)) {
            return normalize(current);
        }
        // O endpoint não pode ser fixado no APK. Sem config/api válido e online,
        // pagamentos e gateway gerenciado permanecem indisponíveis.
        return "";
    }

    public static boolean hasRemoteEndpoint() {
        return isValidRemoteEndpoint(liveEndpoint);
    }

    private static void applyRemoteEndpoint(Context context, String endpoint, boolean online) {
        String normalized = normalize(endpoint);
        if (!online || !isValidRemoteEndpoint(normalized)) {
            liveEndpoint = "";
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ENDPOINT)
                    .apply();
            return;
        }

        boolean changed = !normalized.equals(liveEndpoint);
        liveEndpoint = normalized;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENDPOINT, normalized)
                .apply();
        if (changed) {
            Log.i(TAG, "Endpoint Axion atualizado pelo Firebase.");
            AxionManagedApi.refreshAccountAndProviders(context, null);
        }
    }

    private static boolean isValidRemoteEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
