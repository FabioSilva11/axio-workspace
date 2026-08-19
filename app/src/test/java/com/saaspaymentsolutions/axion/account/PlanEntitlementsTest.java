package com.saaspaymentsolutions.axion.account;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlanEntitlementsTest {

    @Test
    public void freeAllowsManagedAndBuiltInGeminiOnly() {
        assertTrue(PlanEntitlements.isProviderAllowed(
                "free", "active", "axion_managed", "axion_managed"));
        assertTrue(PlanEntitlements.isProviderAllowed(
                "free", "active", "gemini", "gemini"));

        assertFalse(PlanEntitlements.isProviderAllowed(
                "free", "active", "openai", "openai"));
        assertFalse(PlanEntitlements.isProviderAllowed(
                "free", "active", "custom_gemini", "gemini"));
    }

    @Test
    public void paidAllowsOnlyDocumentedProviderFamilies() {
        assertTrue(PlanEntitlements.isProviderAllowed(
                "paid", "active", "openai", "openai"));
        assertTrue(PlanEntitlements.isProviderAllowed(
                "paid", "active", "custom_claude", "anthropic"));
        assertTrue(PlanEntitlements.isProviderAllowed(
                "paid", "active", "grok_xai", "grok_xai"));
        assertTrue(PlanEntitlements.isProviderAllowed(
                "paid", "active", "deepseek", "deepseek"));
        assertTrue(PlanEntitlements.isProviderAllowed(
                "paid", "active", "openrouter", "openrouter"));

        assertFalse(PlanEntitlements.isProviderAllowed(
                "paid", "active", "groq", "groq"));
        assertFalse(PlanEntitlements.isProviderAllowed(
                "paid", "active", "ollama", "ollama"));
        assertFalse(PlanEntitlements.isProviderAllowed(
                "paid", "active", "openai_compatible", "openai_compatible"));
    }

    @Test
    public void inactivePaidAccountDoesNotReceivePaidEntitlements() {
        assertFalse(PlanEntitlements.isPaid("paid", "suspended"));
        assertFalse(PlanEntitlements.isProviderAllowed(
                "paid", "suspended", "openai", "openai"));
    }

    @Test
    public void onlyMcpToolsAreClassifiedAsPremiumToday() {
        assertTrue(PlanEntitlements.isPremiumTool("mcp_server_search"));
        assertFalse(PlanEntitlements.isPremiumTool("read_file"));
        assertFalse(PlanEntitlements.isPremiumTool("edit_file"));
    }
}
