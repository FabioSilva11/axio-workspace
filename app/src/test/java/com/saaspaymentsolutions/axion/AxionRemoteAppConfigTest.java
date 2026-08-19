package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AxionRemoteAppConfigTest {
    @Test
    public void parsesFirebaseNumericRepresentationsWithoutNumberDeserialization() {
        assertEquals(42L, AxionRemoteAppConfig.parseLongValue(42L, -1L));
        assertEquals(42L, AxionRemoteAppConfig.parseLongValue(42.9d, -1L));
        assertEquals(42L, AxionRemoteAppConfig.parseLongValue("42", -1L));
    }

    @Test
    public void fallsBackForMissingOrInvalidRevision() {
        assertEquals(7L, AxionRemoteAppConfig.parseLongValue(null, 7L));
        assertEquals(7L, AxionRemoteAppConfig.parseLongValue("invalid", 7L));
    }
}
