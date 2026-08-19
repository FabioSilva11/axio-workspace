package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class AxionManagedApiTest {
    @Test
    public void freePlanSeesOnlyFreeModels() {
        assertTrue(AxionManagedApi.isModelAllowedForPlan("free", false));
        assertFalse(AxionManagedApi.isModelAllowedForPlan("paid", false));
        assertFalse(AxionManagedApi.isModelAllowedForPlan("pro", false));
    }

    @Test
    public void paidPlanSeesFreeAndPaidModels() {
        assertTrue(AxionManagedApi.isModelAllowedForPlan("free", true));
        assertTrue(AxionManagedApi.isModelAllowedForPlan("paid", true));
        assertTrue(AxionManagedApi.isModelAllowedForPlan("pro", true));
    }

    @Test
    public void serverFilteredCatalogPreservesFreeOnlyProvider() throws Exception {
        JSONObject payload = new JSONObject("{\"plan\":\"free\",\"providers\":[{"
                + "\"id\":\"free-only\",\"name\":\"Free only\","
                + "\"availablePlans\":\"free\",\"models\":[{"
                + "\"id\":\"free-model\",\"name\":\"Free model\"}]}]}");

        AxionManagedApi.applyProviderCatalogPayload(payload);

        assertEquals(1, AxionManagedApi.availableProviders().size());
        assertEquals("free", AxionManagedApi.availableProviders().get(0).availablePlans);
        assertEquals("free-model", AxionManagedApi.availableProviders().get(0).models.get(0).id);
    }
}
