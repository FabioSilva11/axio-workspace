package com.saaspaymentsolutions.axion.port;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoidPortConvertToLlmMessageServiceTest {

    @Test
    public void emptyAssistantContentDoesNotCreateVisibleProtocolText() {
        assertEquals("", VoidPortConvertToLlmMessageService.buildAssistantContent(
                "", "", false));
    }

    @Test
    public void recognizesLegacyMarkerAndItsStreamingPrefix() {
        assertTrue(VoidPortConvertToLlmMessageService.isProtocolEmptyMessage(
                "  (empty message) "));
        assertTrue(VoidPortConvertToLlmMessageService.isProtocolEmptyMessagePrefix(
                "(empty mes"));
        assertFalse(VoidPortConvertToLlmMessageService.isProtocolEmptyMessagePrefix(
                "real answer"));
    }

    @Test
    public void emptyToolResultUsesStructuredValue() {
        assertEquals(
                "{\"result\":null}",
                VoidPortConvertToLlmMessageService.nonEmptyToolResult(""));
    }
}
