package com.saaspaymentsolutions.axion.account;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AxionAccountTest {
    @Test
    public void preservesDynamicPlanIds() {
        assertEquals(AxionAccount.PLAN_PRO, AxionAccount.normalizePlan("pro"));
        assertEquals("future_plan", AxionAccount.normalizePlan("future_plan"));
    }

    @Test
    public void defaultsInvalidPlanToFree() {
        assertEquals(AxionAccount.PLAN_FREE, AxionAccount.normalizePlan("INVALID PLAN!"));
        assertEquals(AxionAccount.PLAN_FREE, AxionAccount.normalizePlan(null));
    }

    @Test
    public void appliesPlanTokenLimits() {
        assertEquals(
                AxionAccount.FREE_TOKEN_LIMIT,
                AxionAccount.tokenLimitForPlan(AxionAccount.PLAN_FREE)
        );
        assertEquals(
                AxionAccount.PAID_TOKEN_LIMIT,
                AxionAccount.tokenLimitForPlan(AxionAccount.PLAN_PAID)
        );
    }

    @Test
    public void remainingNeverBecomesNegative() {
        assertEquals(3_000L, AxionAccount.remaining(2_000L, 5_000L));
        assertEquals(0L, AxionAccount.remaining(7_000L, 5_000L));
    }

    @Test
    public void canonicalRootPlanWinsOverDuplicateSubscriptionPlan() {
        assertEquals(
                AxionAccount.PLAN_PAID,
                FirebaseAccountStore.resolvePlanId("paid", "free")
        );
    }

    @Test
    public void duplicateSubscriptionPlanIsReadOnlyFallback() {
        assertEquals(
                AxionAccount.PLAN_PAID,
                FirebaseAccountStore.resolvePlanId("", "paid")
        );
    }
}
