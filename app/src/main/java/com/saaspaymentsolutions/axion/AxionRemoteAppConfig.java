package com.saaspaymentsolutions.axion;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/** Public notification settings maintained by the admin panel. */
public final class AxionRemoteAppConfig {
    private AxionRemoteAppConfig() {}

    public interface Listener {
        void onLoaded(@NonNull Config config);
        void onError(@NonNull DatabaseError error);
    }

    public static final class Config {
        public boolean dialogEnabled;
        public String dialogTitle = "Atenção";
        public String dialogBody = "";
        public String dialogFrequency = "once_per_revision";
        public String dialogButtonLabel = "Entendi";
        public String dialogButtonUrl = "";
        public long dialogRevision;
    }

    public static void load(@NonNull Context context, @NonNull Listener listener) {
        FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL)
                .getReference("config/app/notificationDialog")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Config result = new Config();
                        result.dialogEnabled = booleanValue(snapshot.child("enabled"), false);
                        result.dialogTitle = stringValue(snapshot.child("title"), "Atenção");
                        result.dialogBody = stringValue(snapshot.child("body"), "");
                        result.dialogFrequency = stringValue(snapshot.child("frequency"), "once_per_revision");
                        result.dialogButtonLabel = stringValue(snapshot.child("buttonLabel"), "Entendi");
                        result.dialogButtonUrl = stringValue(snapshot.child("buttonUrl"), "");
                        result.dialogRevision = longValue(snapshot.child("revision"), 0L);
                        listener.onLoaded(result);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        listener.onError(error);
                    }
                });
    }

    private static String stringValue(DataSnapshot snapshot, String fallback) {
        Object value = snapshot.getValue();
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(DataSnapshot snapshot, boolean fallback) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }

    private static long longValue(DataSnapshot snapshot, long fallback) {
        return parseLongValue(snapshot.getValue(), fallback);
    }

    static long parseLongValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
