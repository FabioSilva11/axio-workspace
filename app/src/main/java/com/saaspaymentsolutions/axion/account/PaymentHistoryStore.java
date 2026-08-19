package com.saaspaymentsolutions.axion.account;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Histórico de pagamentos privado e local. Nunca é sincronizado com o Firebase. */
final class PaymentHistoryStore {
    private static final String PREFS = "axion_payment_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 30;

    private final SharedPreferences preferences;

    PaymentHistoryStore(@NonNull Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void recordCreated(
            @NonNull String checkoutId,
            @NonNull String planId,
            @NonNull String planName,
            int amountCents,
            long expiresAt
    ) {
        JSONArray current = readArray();
        JSONArray next = new JSONArray();
        JSONObject entry = new JSONObject();
        try {
            entry.put("checkoutId", checkoutId);
            entry.put("planId", planId);
            entry.put("planName", planName);
            entry.put("amountCents", Math.max(0, amountCents));
            entry.put("status", "pending");
            entry.put("createdAt", System.currentTimeMillis());
            entry.put("expiresAt", Math.max(0L, expiresAt));
            next.put(entry);
            for (int index = 0; index < current.length() && next.length() < MAX_ENTRIES; index++) {
                JSONObject item = current.optJSONObject(index);
                if (item != null && !checkoutId.equals(item.optString("checkoutId", ""))) {
                    next.put(item);
                }
            }
            save(next);
        } catch (JSONException ignored) {
            // Um registro local inválido não pode bloquear a criação do Pix.
        }
    }

    synchronized void updateStatus(@NonNull String checkoutId, @NonNull String status) {
        if (checkoutId.trim().isEmpty()) return;
        JSONArray current = readArray();
        boolean changed = false;
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item == null || !checkoutId.equals(item.optString("checkoutId", ""))) continue;
            try {
                item.put("status", status);
                item.put("updatedAt", System.currentTimeMillis());
                changed = true;
            } catch (JSONException ignored) {
                return;
            }
            break;
        }
        if (changed) save(current);
    }

    @NonNull
    synchronized List<Entry> entries() {
        JSONArray array = readArray();
        List<Entry> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) continue;
            result.add(new Entry(
                    item.optString("planName", item.optString("planId", "Axion")),
                    item.optInt("amountCents", 0),
                    item.optString("status", "pending"),
                    item.optLong("createdAt", 0L)
            ));
        }
        return result;
    }

    @NonNull
    private JSONArray readArray() {
        try {
            return new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private void save(@NonNull JSONArray value) {
        preferences.edit().putString(KEY_ENTRIES, value.toString()).apply();
    }

    static final class Entry {
        final String planName;
        final int amountCents;
        final String status;
        final long createdAt;

        Entry(String planName, int amountCents, String status, long createdAt) {
            this.planName = planName;
            this.amountCents = amountCents;
            this.status = status;
            this.createdAt = createdAt;
        }
    }
}
