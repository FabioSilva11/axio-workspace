package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HandoffToolTest {

    @Test
    public void toolNameFollowsTransferPrefix() {
        Agent target = Agent.Builder.forName("Code Reviewer", "review").build();
        assertEquals("transfer_to_Code_Reviewer", HandoffTool.toolNameFor(target));
    }

    @Test
    public void payloadEncodesTargetAndParsesBack() {
        Agent target = Agent.Builder.forName("helper", "help").build();
        String payload = HandoffProtocol.resultPayload(target);
        assertEquals("helper", HandoffProtocol.targetAgentName(payload));
    }

    @Test
    public void nonHandoffPayloadReturnsNull() {
        assertNull(HandoffProtocol.targetAgentName("plain text"));
        assertNull(HandoffProtocol.targetAgentName(null));
    }
}
