package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the Codex-parity tools: get_context_remaining and
 * request_user_input (fail-closed and routed through the ApprovalHandler).
 */
public class CodexParityToolsTest {

    // ------------------------------------------------------------------
    // get_context_remaining
    // ------------------------------------------------------------------

    @Test
    public void contextRemaining_withoutBudget_reportsGracefulFallback() throws Exception {
        RunContext context = RunContext.bare("sc1", "agent", new ContextTracker(null));
        ContextRemainingTool tool = new ContextRemainingTool();

        AgentToolResult result = tool.execute(context, new JSONObject());

        assertFalse(result.isError());
        JSONObject report = new JSONObject(result.output());
        assertEquals(false, report.optBoolean("budget_enforced"));
        assertTrue(report.has("note"));
    }

    @Test
    public void contextRemaining_withoutTracker_returnsGracefulNote() throws Exception {
        RunContext legacyContext = RunContext.bare("sc1", "agent", null);
        ContextRemainingTool tool = new ContextRemainingTool();

        AgentToolResult result = tool.execute(legacyContext, new JSONObject());

        assertFalse(result.isError());
        assertEquals(false, new JSONObject(result.output()).optBoolean("budget_enforced"));
    }

    @Test
    public void contextRemaining_withBudget_reportsRealNumbers() throws Exception {
        RunBudget budget = new RunBudget(10_000);
        RunBudget.Handle handle = budget.reserve(1_500);
        budget.settle(handle, 1_500);
        RunContext context = RunContext.bare("sc1", "agent", new ContextTracker(budget));
        context.contextTracker().recordInputEstimate(2_000);
        ContextRemainingTool tool = new ContextRemainingTool();

        AgentToolResult result = tool.execute(context, new JSONObject());

        assertFalse(result.isError());
        JSONObject report = new JSONObject(result.output());
        assertTrue(report.optBoolean("budget_enforced"));
        assertEquals(10_000L, report.optLong("maximum_tokens"));
        assertEquals(1_500L, report.optLong("used_tokens"));
        assertEquals(8_500L, report.optLong("remaining_tokens"));
        assertEquals(2_000L, report.optLong("last_input_tokens"));
    }

    @Test
    public void contextRemaining_isExposedWithSchemaAndNoApproval() {
        ContextRemainingTool tool = new ContextRemainingTool();
        assertEquals("get_context_remaining", tool.name());
        assertFalse(tool.requiresApproval());
        assertEquals("object", tool.parameters().optString("type"));
    }

    // ------------------------------------------------------------------
    // request_user_input
    // ------------------------------------------------------------------

    @Test
    public void requestUserInput_withoutHandler_failsClosedWithGuidance() throws Exception {
        RequestUserInputTool tool = new RequestUserInputTool(null);

        AgentToolResult result = tool.execute(
                RunContext.bare("sc1", "agent", null),
                new JSONObject().put("question", "Qual banco usar?"));

        assertTrue(result.isError());
        assertTrue(result.output().contains("Proceed with the best safe default"));
    }

    @Test
    public void requestUserInput_emptyQuestion_isRejected() throws Exception {
        RequestUserInputTool tool = new RequestUserInputTool(null);

        AgentToolResult result = tool.execute(
                RunContext.bare("sc1", "agent", null), new JSONObject());

        assertTrue(result.isError());
        assertTrue(result.output().contains("question"));
    }

    @Test
    public void requestUserInput_routesThroughHandlerAndReturnsAnswer() throws Exception {
        RecordingInputChannel channel = new RecordingInputChannel();
        channel.answer = "Room, porque o projeto já usa SQL nas telas atuais.";
        RequestUserInputTool tool = new RequestUserInputTool(channel);

        AgentToolResult result = tool.execute(
                RunContext.bare("sc1", "agent", null),
                new JSONObject()
                        .put("question", "Persistência local?")
                        .put("options", new org.json.JSONArray()
                                .put(new JSONObject().put("label", "Room"))
                                .put(new JSONObject().put("label", "SQLite puro"))));

        assertFalse(result.isError());
        assertTrue(result.output().contains("Room, porque"));
        assertEquals(1, channel.questions);
        assertEquals("request_user_input", channel.lastTool);
    }

    @Test
    public void requestUserInput_dismissedFallsBackToDefault() throws Exception {
        RecordingInputChannel channel = new RecordingInputChannel();
        channel.decision = PermissionDecision.DENY;
        RequestUserInputTool tool = new RequestUserInputTool(channel);

        AgentToolResult result = tool.execute(
                RunContext.bare("sc1", "agent", null),
                new JSONObject().put("question", "Renomear tudo?"));

        assertFalse(result.isError());
        assertTrue(result.output().contains("dismissed"));
    }

    @Test
    public void requestUserInput_optionsAreNormalizedToMaxFour() throws Exception {
        RecordingInputChannel channel = new RecordingInputChannel();
        channel.answer = "opt 1";
        RequestUserInputTool tool = new RequestUserInputTool(channel);

        org.json.JSONArray six = new org.json.JSONArray();
        for (int i = 0; i < 6; i++) {
            six.put(new JSONObject().put("label", "opt " + (i + 1)));
        }
        tool.execute(RunContext.bare("sc1", "agent", null),
                new JSONObject().put("question", "q").put("options", six));

        assertEquals("handler must receive at most 4 options",
                4, new JSONObject(channel.lastReason).optJSONArray("options").length());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** ApprovalHandler that doubles as a scripted user answering the question. */
    private static final class RecordingInputChannel implements ApprovalHandler {
        PermissionDecision decision = PermissionDecision.ALLOW;
        String answer;
        int questions;
        String lastTool;
        String lastReason;

        @Override
        public PermissionDecision onRequest(PermissionRequest request) {
            questions++;
            lastTool = request.getTool();
            lastReason = request.getReason();
            return decision;
        }

        @Override
        public String lastResponseText() {
            return answer;
        }
    }
}
