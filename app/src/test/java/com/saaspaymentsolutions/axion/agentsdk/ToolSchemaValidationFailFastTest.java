package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.schema.ToolSchemaValidationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Tests verifying that invalid tool schemas cause IMMEDIATE failure
 * before any HTTP request is made to the provider.
 */
public class ToolSchemaValidationFailFastTest {

    /**
     * Invalid schema should throw exception BEFORE HTTP request.
     */
    @Test
    public void invalidSchema_throwsException_beforeHttpRequest() throws Exception {
        // Create a tool with INVALID schema (items as array)
        final JSONObject badParams = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("items", new JSONObject()
                                .put("type", "array")
                                .put("items", new JSONArray()  // BUG: should be JSONObject
                                        .put(new JSONObject().put("type", "string")))));
        AgentTool badTool = new AgentTool() {
            @Override
            public String name() { return "bad_tool"; }

            @Override
            public String description() { return "Tool with invalid schema"; }

            @Override
            public JSONObject parameters() {
                return badParams;
            }

            @Override
            public AgentToolResult execute(RunContext context, JSONObject args) {
                return AgentToolResult.success("ok");
            }
        };

        RecordingGateway gateway = new RecordingGateway();
        EventStream events = new EventStream();

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .build();

        Agent agent = Agent.Builder.forName("test", "Test agent")
                .tools(badTool)
                .build();

        // Run should FAIL
        RunResult result = runtime.run(agent, "Test input", "sc1");

        // Should be a failure
        assertFalse("Should fail due to invalid schema", result.isSuccessful());
        assertTrue("Should have error message", !result.getFailureReason().isEmpty() &&
                result.getFailureReason().contains("schema"));

        // CRITICAL: No HTTP request should have been made
        assertEquals("Should have ZERO HTTP calls (fail-fast)", 0, gateway.getCallCount());
    }

    /**
     * Valid schema should proceed normally.
     */
    @Test
    public void validSchema_proceedsNormally() throws Exception {
        // Create a tool with VALID schema
        final JSONObject goodParams = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("items", new JSONObject()
                                .put("type", "array")
                                .put("items", new JSONObject()  // CORRECT: JSONObject
                                        .put("type", "string"))));
        AgentTool goodTool = new AgentTool() {
            @Override
            public String name() { return "good_tool"; }

            @Override
            public String description() { return "Tool with valid schema"; }

            @Override
            public JSONObject parameters() {
                return goodParams;
            }

            @Override
            public AgentToolResult execute(RunContext context, JSONObject args) {
                return AgentToolResult.success("ok");
            }
        };

        RecordingGateway gateway = new RecordingGateway();
        gateway.setNextResponse("Done", null);

        EventStream events = new EventStream();
        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .build();

        Agent agent = Agent.Builder.forName("test", "Test agent")
                .tools(goodTool)
                .build();

        RunResult result = runtime.run(agent, "Test input", "sc1");

        // Should succeed
        assertTrue("Should succeed with valid schema", result.isSuccessful());

        // HTTP request SHOULD have been made
        assertEquals("Should have ONE HTTP call", 1, gateway.getCallCount());
    }

    /**
     * Multiple tools with one invalid schema should fail-fast for entire toolset.
     */
    @Test
    public void oneInvalidTool_failsEntireToolset() throws Exception {
        AgentTool goodTool = new AgentTool() {
            @Override
            public String name() { return "good_tool"; }
            @Override
            public String description() { return "Good"; }
            @Override
            public JSONObject parameters() { return new JSONObject(); }
            @Override
            public AgentToolResult execute(RunContext ctx, JSONObject args) {
                return AgentToolResult.success("ok");
            }
        };

        final JSONObject badParams = new JSONObject().put("type", "string");  // Root must be object
        AgentTool badTool = new AgentTool() {
            @Override
            public String name() { return "bad_tool"; }
            @Override
            public String description() { return "Bad"; }
            @Override
            public JSONObject parameters() {
                return badParams;  // INVALID schema
            }
            @Override
            public AgentToolResult execute(RunContext ctx, JSONObject args) {
                return AgentToolResult.success("ok");
            }
        };

        RecordingGateway gateway = new RecordingGateway();
        EventStream events = new EventStream();
        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .build();

        Agent agent = Agent.Builder.forName("test", "Test agent")
                .tools(goodTool, badTool)
                .build();

        RunResult result = runtime.run(agent, "Test", "sc1");

        // Should fail
        assertFalse("Should fail", result.isSuccessful());

        // NO HTTP request should have been made
        assertEquals("Should have ZERO HTTP calls", 0, gateway.getCallCount());
    }

    // Helper stubs

    private static class RecordingGateway implements AgentLlmGateway {
        private String nextText;
        private java.util.List<com.saaspaymentsolutions.axion.toolcalling.ToolCall> nextCalls;
        private int callCount = 0;

        void setNextResponse(String text, java.util.List<com.saaspaymentsolutions.axion.toolcalling.ToolCall> calls) {
            this.nextText = text;
            this.nextCalls = calls;
        }

        int getCallCount() { return callCount; }

        @Override
        public LlmTurnOutput completeTurn(String systemPrompt, org.json.JSONArray tools,
                                          java.util.List<com.saaspaymentsolutions.axion.ChatMessage> history,
                                          com.saaspaymentsolutions.axion.AiOperationContext opContext) {
            callCount++;
            return new LlmTurnOutput(nextText, "", "stop", nextCalls);
        }

        @Override
        public void setDeltaListener(java.util.function.Consumer<String> listener) {}
    }
}
