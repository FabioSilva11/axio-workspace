package com.saaspaymentsolutions.axion.account;

import androidx.annotation.NonNull;

import java.util.Locale;

public final class AxionAccount {
    public static final String PLAN_FREE = "free";
    public static final String PLAN_PAID = "paid";
    public static final String PLAN_STARTER = "starter";
    public static final String PLAN_PRO = "pro";
    public static final String PLAN_MAX = "max";
    public static final String STATUS_ACTIVE = "active";
    public static final long FREE_CREDIT_LIMIT = 500L;
    public static final long PAID_CREDIT_LIMIT = 4_000L;
    /** @deprecated Use {@link #FREE_CREDIT_LIMIT}. */
    @Deprecated public static final long FREE_TOKEN_LIMIT = FREE_CREDIT_LIMIT;
    /** @deprecated Use {@link #PAID_CREDIT_LIMIT}. */
    @Deprecated public static final long PAID_TOKEN_LIMIT = PAID_CREDIT_LIMIT;

    public final String uid;
    public final String name;
    public final String email;
    public final String planId;
    public final String status;
    public final long tokensUsed;
    public final long tokenLimit;
    public final long tokensRemaining;
    public final long creditsUsed;
    public final long creditLimit;
    public final long creditsRemaining;
    public final long creditsReserved;
    public final long periodStart;
    public final long periodEnd;

    public AxionAccount(
            String uid,
            String name,
            String email,
            String planId,
            String status,
            long tokensUsed,
            long tokenLimit,
            long tokensRemaining,
            long periodStart,
            long periodEnd
    ) {
        this(uid, name, email, planId, status, tokensUsed, tokenLimit,
                tokensRemaining, 0L, periodStart, periodEnd);
    }

    public AxionAccount(
            String uid,
            String name,
            String email,
            String planId,
            String status,
            long creditsUsed,
            long creditLimit,
            long creditsRemaining,
            long creditsReserved,
            long periodStart,
            long periodEnd
    ) {
        this.uid = safe(uid);
        this.name = safe(name);
        this.email = safe(email);
        this.planId = normalizePlan(planId);
        this.status = safe(status).isEmpty() ? STATUS_ACTIVE : safe(status);
        this.creditsUsed = Math.max(0L, creditsUsed);
        this.creditLimit = Math.max(1L, creditLimit);
        this.creditsReserved = Math.max(0L, Math.min(creditsReserved, this.creditLimit));
        this.creditsRemaining = Math.max(
                0L,
                Math.min(creditsRemaining, this.creditLimit - this.creditsReserved));
        this.tokensUsed = this.creditsUsed;
        this.tokenLimit = this.creditLimit;
        this.tokensRemaining = this.creditsRemaining;
        this.periodStart = Math.max(0L, periodStart);
        this.periodEnd = Math.max(0L, periodEnd);
    }

    public boolean isPaid() {
        return !PLAN_FREE.equals(planId) && STATUS_ACTIVE.equals(status);
    }

    @NonNull
    public static String normalizePlan(String value) {
        String normalized = safe(value).toLowerCase(Locale.US);
        return normalized.matches("[a-z0-9_-]{2,80}") ? normalized : PLAN_FREE;
    }

    public static long tokenLimitForPlan(String planId) {
        return !PLAN_FREE.equals(normalizePlan(planId))
                ? PAID_CREDIT_LIMIT
                : FREE_CREDIT_LIMIT;
    }

    public static long remaining(long used, long limit) {
        long safeLimit = Math.max(1L, limit);
        return Math.max(0L, safeLimit - Math.max(0L, used));
    }

    public static long remaining(long used, long reserved, long limit) {
        long safeLimit = Math.max(1L, limit);
        return Math.max(0L, safeLimit - Math.max(0L, used) - Math.max(0L, reserved));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
