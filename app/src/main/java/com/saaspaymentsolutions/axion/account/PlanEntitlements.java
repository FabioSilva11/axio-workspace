package com.saaspaymentsolutions.axion.account;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.saaspaymentsolutions.axion.AxionManagedApi;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;

import java.util.Locale;

/**
 * Single source of truth for client-side plan restrictions.
 *
 * <p>The Firebase/server checks remain authoritative. These cached values make
 * the Android UI and request paths apply the same restrictions while the user
 * is navigating the app. Missing or invalid data always falls back to Free.</p>
 */
public final class PlanEntitlements {
    public static final String PREF_PLAN_ID = "account_entitlement_plan_id";
    public static final String PREF_PLAN_STATUS = "account_entitlement_plan_status";
    public static final String PREF_TOKEN_LIMIT = "account_entitlement_token_limit";
    public static final String PREF_TOKENS_USED = "account_entitlement_tokens_used";
    public static final String PREF_TOKENS_REMAINING = "account_entitlement_tokens_remaining";

    private PlanEntitlements() {
    }

    public static void sync(@NonNull Context context, @NonNull AxionAccount account) {
        VoidPortSettings.prefs(context.getApplicationContext())
                .edit()
                .putString(PREF_PLAN_ID, account.planId)
                .putString(PREF_PLAN_STATUS, account.status)
                .putLong(PREF_TOKEN_LIMIT, account.tokenLimit)
                .putLong(PREF_TOKENS_USED, account.tokensUsed)
                .putLong(PREF_TOKENS_REMAINING, account.tokensRemaining)
                .apply();
    }

    /** Atualiza plano/status recebidos do bootstrap sem escrever no Firebase. */
    public static void syncServerPlan(
            @NonNull Context context,
            String planId,
            String status
    ) {
        SharedPreferences prefs = VoidPortSettings.prefs(context.getApplicationContext());
        SharedPreferences.Editor editor = prefs.edit();
        String normalizedPlan = planId == null ? "" : planId.trim();
        String normalizedStatus = status == null ? "" : status.trim();
        if (!normalizedPlan.isEmpty()) {
            editor.putString(PREF_PLAN_ID, AxionAccount.normalizePlan(normalizedPlan));
        }
        if (!normalizedStatus.isEmpty()) {
            editor.putString(PREF_PLAN_STATUS, normalize(normalizedStatus));
        }
        editor.apply();
    }

    /** Mantém a trava local de saldo alinhada à carteira autoritativa do servidor. */
    public static void syncManagedWallet(
            @NonNull Context context,
            long used,
            long limit,
            long remaining
    ) {
        VoidPortSettings.prefs(context.getApplicationContext())
                .edit()
                .putLong(PREF_TOKEN_LIMIT, Math.max(1L, limit))
                .putLong(PREF_TOKENS_USED, Math.max(0L, used))
                .putLong(PREF_TOKENS_REMAINING, Math.max(0L, remaining))
                .apply();
    }

    public static String planId(@NonNull SharedPreferences prefs) {
        return AxionAccount.normalizePlan(
                prefs.getString(PREF_PLAN_ID, AxionAccount.PLAN_FREE)
        );
    }

    public static String status(@NonNull SharedPreferences prefs) {
        String value = prefs.getString(PREF_PLAN_STATUS, AxionAccount.STATUS_ACTIVE);
        return value == null || value.trim().isEmpty()
                ? AxionAccount.STATUS_ACTIVE
                : value.trim().toLowerCase(Locale.US);
    }

    public static boolean isPaid(@NonNull SharedPreferences prefs) {
        return isPaid(planId(prefs), status(prefs));
    }

    public static boolean isPaid(String planId, String status) {
        return !AxionAccount.PLAN_FREE.equals(AxionAccount.normalizePlan(planId))
                && AxionAccount.STATUS_ACTIVE.equals(normalize(status));
    }

    public static boolean allowsImages(@NonNull SharedPreferences prefs) {
        return isPaid(prefs);
    }

    public static boolean allowsFiles(@NonNull SharedPreferences prefs) {
        return isPaid(prefs);
    }

    public static boolean allowsPremiumTools(@NonNull SharedPreferences prefs) {
        return isPaid(prefs);
    }

    public static int maxUserApiKeys(@NonNull SharedPreferences prefs) {
        return isPaid(prefs) ? Integer.MAX_VALUE : 1;
    }

    public static long tokensRemaining(@NonNull SharedPreferences prefs) {
        long defaultValue = AxionAccount.tokenLimitForPlan(planId(prefs));
        return Math.max(0L, prefs.getLong(PREF_TOKENS_REMAINING, defaultValue));
    }

    public static boolean canUseManagedAi(@NonNull SharedPreferences prefs) {
        return AxionAccount.STATUS_ACTIVE.equals(status(prefs))
                && tokensRemaining(prefs) > 0L;
    }

    /**
     * Checks a provider family against the product plan. Custom provider IDs
     * must pass their normalized family as {@code providerFamily}.
     */
    public static boolean isProviderAllowed(
            @NonNull SharedPreferences prefs,
            String providerId,
            String providerFamily
    ) {
        return isProviderAllowed(planId(prefs), status(prefs), providerId, providerFamily);
    }

    public static boolean isProviderAllowed(
            String planId,
            String status,
            String providerId,
            String providerFamily
    ) {
        String id = normalize(providerId);
        String family = normalize(providerFamily);
        if (AxionManagedApi.PROVIDER_ID.equals(id)) {
            return AxionAccount.STATUS_ACTIVE.equals(normalize(status));
        }

        if (!isPaid(planId, status)) {
            // Free has one built-in Gemini API slot. Custom provider entries
            // remain stored for a future upgrade but are not exposed or usable.
            return "gemini".equals(id);
        }

        return isPaidProvider(id) || isPaidProvider(family);
    }

    public static boolean isPremiumTool(String toolName) {
        return normalize(toolName).startsWith("mcp_");
    }

    private static boolean isPaidProvider(String value) {
        return "openai".equals(value)
                || "gemini".equals(value)
                || "anthropic".equals(value)
                || "grok_xai".equals(value)
                || "xai".equals(value)
                || "deepseek".equals(value)
                || "openrouter".equals(value)
                || "openai_codex".equals(value)
                || "codex".equals(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
