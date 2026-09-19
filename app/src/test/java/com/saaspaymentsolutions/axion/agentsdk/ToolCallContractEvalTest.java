package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.FileChangeTracker;
import com.saaspaymentsolutions.axion.FileChangeTrackerWorkspaceTest.FakeWorkspaceFileSystem;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;
import com.saaspaymentsolutions.axion.workspace.Workspace;
import com.saaspaymentsolutions.axion.workspace.WorkspaceManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tool-call execution contract evals (Codex router/stream parity):
 *
 * <pre>
 * MODEL RESPONSE = MESSAGE  ⊕  TOOL CALL
 *      │                        │
 *      ▼                        ▼
 * assistant text (display)  AgentToolRouter → executor → result
 * </pre>
 *
 * Text is never re-derived into a call; structured calls are the only
 * executable truth; dedupe is by callId; the UI sees AssistantMessageDelta
 * only for real text and ToolCall/FileChanged-type events for tools.
 */
public class ToolCallContractEvalTest {

    private FakeWorkspaceFileSystem fs;
    private EventStream events;
    private List<AgentEvent> received;
    private static final String SC = "sc_contract";

    @Before
    public void setUp() {
        fs = new FakeWorkspaceFileSystem();
        fsRef.set(fs);
        WorkspaceManager.INSTANCE.setCustomFileSystemForTesting(fs, fakeWorkspace());
        FileChangeTracker.clearChanges(SC);
        events = new EventStream(Runnable::run, 512);
        received = new ArrayList<>();
        events.subscribe(received::add);
        RuntimeFileContext.clearForTest();
    }

    @After
    public void tearDown() {
        RunContextFactory.clearOverrideForTest();
        RuntimeFileContext.clearForTest();
        FileChangeTracker.clearChanges(SC);
        WorkspaceManager.INSTANCE.setCustomFileSystemForTesting(
                new FakeWorkspaceFileSystem(), fakeWorkspace());
    }

    // ------------------------------------------------------------------
    // Tests 1-3: text, code and JSON-like payloads never execute
    // ------------------------------------------------------------------

    @Test
    public void eval_plainText_producesZeroToolCallsAndZeroMutations() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text("Vou alterar MainActivity.java."));
        RunResult result = runtime(gateway, agent(), false).run(agent(), "edita", SC);

        assertTrue(result.isSuccessful());
        assertEquals("Vou alterar MainActivity.java.", result.getOutput());
        assertEquals(0, toolCallStartedCount());
        assertEquals(0, fileChangedCount());
        assertEquals("workspace untouched", 0, fs.files.size());
    }

    @Test
    public void eval_javaCodeInText_isJustText() {
        String code = "public class MainActivity {\n    void onCreate() {}\n}";
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text(code));
        RunResult result = runtime(gateway, agent(), false).run(agent(), "gere a classe", SC);

        assertTrue(result.isSuccessful());
        assertEquals(code, result.getOutput());
        assertEquals(0, toolCallStartedCount());
        assertEquals(0, fs.files.size());
    }

    @Test
    public void eval_jsonResemblingToolCall_isJustText() throws JSONException {
        String payload = new JSONObject()
                .put("name", "apply_patch")
                .put("arguments", new JSONObject().put("patch", "*** Begin Patch"))
                .toString();
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text(payload));
        RunResult result = runtime(gateway, agent(), false).run(agent(), "o que faltou?", SC);

        assertTrue(result.isSuccessful());
        assertEquals("JSON tool protocol in text stays text", payload, result.getOutput());
        assertEquals(0, toolCallStartedCount());
        assertEquals(0, fileChangedCount());
        assertEquals(0, fs.files.size());
    }

    // ------------------------------------------------------------------
    // Test 4: structured function_call executes exactly once
    // ------------------------------------------------------------------

    @Test
    public void eval_structuredApplyPatch_oneCallOneExecutionOneFileChanged() throws Exception {
        fs.writeText("src/Main.java", "class Main {}\n");
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/Main.java\n@@\n-class Main {}\n+class Main { int x; }\n*** End Patch").toString()),
                FakeAgentLlmGateway.ScriptedTurn.text("pronto"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "edita", SC);

        assertTrue(result.isSuccessful());
        assertEquals("class Main { int x; }\n", fs.readText("src/Main.java"));
        assertEquals("1 structured call → 1 execution", 1, toolCallStartedCount());
        assertEquals("1 mutation → 1 FileChanged (from the patch)", 1, fileChangedCount());
    }

    // ------------------------------------------------------------------
    // Test 5: streaming keeps tool arguments out of AssistantMessageDelta
    // ------------------------------------------------------------------

    @Test
    public void eval_streamedToolCall_argumentsNeverLeakAsAssistantDelta() throws Exception {
        fs.writeText("src/Main.java", "class Main {}\n");
        // The scripted gateway models the provider contract: argument chunks
        // are accumulated internally (AiProviderService.ToolCallAccumulator)
        // and only the COMPLETE structured call reaches the runtime — the
        // runtime never sees partial arguments, so it can never publish them
        // as assistant text.
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/Main.java\n@@\n-class Main {}\n+class Main { int y; }\n*** End Patch").toString()),
                FakeAgentLlmGateway.ScriptedTurn.text("ok"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "edita", SC);

        assertTrue(result.isSuccessful());
        for (AgentEvent event : received) {
            if (event instanceof AgentEvent.AssistantMessageDelta) {
                String text = ((AgentEvent.AssistantMessageDelta) event).getDelta();
                assertFalse("patch arguments must never appear as assistant delta",
                        text.contains("*** Begin Patch"));
                assertFalse(text.contains("apply_patch"));
            }
        }
        assertEquals("class Main { int y; }\n", fs.readText("src/Main.java"));
    }

    // ------------------------------------------------------------------
    // Tests 6-7: multiple calls and same-name-different-id both execute
    // ------------------------------------------------------------------

    @Test
    public void eval_multipleStructuredCalls_allPreservedInOrder() throws Exception {
        fs.writeText("src/A.java", "a\n");
        fs.writeText("src/B.java", "b\n");
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCalls(
                        call("read_file", "call_1", new JSONObject().put("uri", "src/A.java")),
                        call("apply_patch", "call_2", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/B.java\n@@\n-b\n+B2\n*** End Patch")),
                        call("read_file", "call_3", new JSONObject().put("uri", "src/A.java"))),
                FakeAgentLlmGateway.ScriptedTurn.text("fim"));

        RunResult result = runtime(gateway, agentWithPatchAndEcho(), false).run(agentWithPatchAndEcho(), "trabalha", SC);

        assertTrue(result.isSuccessful());
        assertEquals("three distinct call ids, three executions", 3, toolCallStartedCount());
        assertEquals("B2\n", fs.readText("src/B.java"));
    }

    @Test
    public void eval_sameNameDifferentCallIds_bothExecute() throws Exception {
        fs.writeText("src/A.java", "v1\n");
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCalls(
                        call("apply_patch", "call_1", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+v2\n*** End Patch")),
                        call("apply_patch", "call_2", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v2\n+v3\n*** End Patch"))),
                FakeAgentLlmGateway.ScriptedTurn.text("fim"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "edita duas vezes", SC);

        assertTrue(result.isSuccessful());
        assertEquals("both apply_patch ids executed, in order", "v3\n", fs.readText("src/A.java"));
        assertEquals(2, toolCallStartedCount());
    }

    // ------------------------------------------------------------------
    // Test 8: duplicated event (same call_id) executes once
    // ------------------------------------------------------------------

    @Test
    public void eval_duplicatedCallId_executesExactlyOnce() throws Exception {
        fs.writeText("src/A.java", "v1\n");
        // The same structured call delivered twice (provider retry/event dup):
        // dedupe by callId → exactly one execution, one mutation.
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCalls(
                        call("apply_patch", "call_dup", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+v2\n*** End Patch")),
                        call("apply_patch", "call_dup", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+v9\n*** End Patch"))),
                FakeAgentLlmGateway.ScriptedTurn.text("fim"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "edita", SC);

        assertTrue(result.isSuccessful());
        assertEquals("second (duplicate) call never ran", "v2\n", fs.readText("src/A.java"));
        assertEquals("one execution only", 1, toolCallStartedCount());
    }

    // ------------------------------------------------------------------
    // Tests 9-10: unknown tool and invalid args are controlled errors
    // ------------------------------------------------------------------

    @Test
    public void eval_unknownTool_controlledError_noCrash() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("tool_that_does_not_exist", "{}"),
                FakeAgentLlmGateway.ScriptedTurn.text("entendi, sigo sem a tool"));

        RunResult result = runtime(gateway, agent(), false).run(agent(), "tenta", SC);

        assertTrue("runtime survives an unknown structured call", result.isSuccessful());
        assertEquals("nothing started: there is no tool to start", 0, toolCallStartedCount());
        boolean erroredToModel = received.stream().anyMatch(e ->
                e instanceof AgentEvent.ToolCallCompleted
                        && ((AgentEvent.ToolCallCompleted) e).getResult().isError());
        assertTrue("the model receives a controlled error result", erroredToModel);
    }

    @Test
    public void eval_invalidArguments_controlledStructuredError() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("read_file", "{invalid json"),
                FakeAgentLlmGateway.ScriptedTurn.text("ok, sigo"));

        RunResult result = runtime(gateway, agentWithPatchAndEcho(), false).run(agentWithPatchAndEcho(), "vai", SC);

        assertTrue(result.isSuccessful());
        boolean invalidArgsError = received.stream().anyMatch(e ->
                e instanceof AgentEvent.ToolCallCompleted
                        && ((AgentEvent.ToolCallCompleted) e).getResult().output()
                        .contains("invalid JSON arguments"));
        assertTrue(invalidArgsError);
    }

    // ------------------------------------------------------------------
    // Test 11: text + structured call stay semantically separated
    // ------------------------------------------------------------------

    @Test
    public void eval_textPlusToolCall_bothKeptSeparate() throws Exception {
        fs.writeText("src/A.java", "v1\n");
        // Turn 1 carries BOTH an assistant message and a structured call
        // (the provider envelope supports it; AxionAgentGateway keeps them
        // as separate fields of LlmTurnOutput).
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.textWithToolCall(
                        "Vou atualizar o arquivo agora.",
                        call("apply_patch", "call_mix", new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+v2\n*** End Patch"))),
                FakeAgentLlmGateway.ScriptedTurn.text("concluí"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "edita", SC);

        assertTrue(result.isSuccessful());
        assertEquals("the call executed", "v2\n", fs.readText("src/A.java"));
        // The narrative text reached the UI as assistant content, not lost.
        boolean textShown = received.stream().anyMatch(e ->
                e instanceof AgentEvent.AssistantMessageDelta
                        && ((AgentEvent.AssistantMessageDelta) e).getDelta()
                        .contains("Vou atualizar o arquivo agora."));
        assertTrue(textShown);
    }

    // ------------------------------------------------------------------
    // Test 13: ASK_USER parks on ApprovalRequired, proceeds after approval
    // ------------------------------------------------------------------

    @Test
    public void eval_askUser_parksOnApproval_thenProceeds() throws Exception {
        fs.writeText("src/A.java", "v1\n");
        RecordingApproval approvals = new RecordingApproval();
        approvals.decision = PermissionDecision.ALLOW;
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+vapproved\n*** End Patch").toString()),
                FakeAgentLlmGateway.ScriptedTurn.text("aprovado e aplicado"));

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .permissions(new PermissionLayer(
                        new ToolPolicy.Builder().mutation(ToolPolicy.Rule.ASK_USER).build(),
                        approvals,
                        events))
                .build();
        RunResult result = runtime.run(agentWithPatchTool(), "edita", SC);

        assertTrue(result.isSuccessful());
        assertEquals("approval asked exactly once", 1, approvals.requests);
        assertEquals("vapproved\n", fs.readText("src/A.java"));
        int asked = firstIndexOf(AgentEvent.ApprovalRequired.class);
        int started = firstIndexOf(AgentEvent.ToolCallStarted.class);
        assertTrue("ApprovalRequired precedes execution", asked >= 0 && started > asked);
    }

    // ------------------------------------------------------------------
    // Test 14: the structured tool operates on the run's RunContext
    // ------------------------------------------------------------------

    @Test
    public void eval_structuredCall_usesTheRunContextFilesystem() throws Exception {
        fs.writeText("AGENTS.md", "CONTRACT-WS-RULE");
        RunContextFactory.setOverrideForTest(new RunContextFactory.Resolved(
                new WorkspaceIdentity(SC, "ws-contract", "file:///ws", "ws", ""), fs));
        RuntimeFileContext.pin(
                new WorkspaceIdentity(SC, "ws-contract", "file:///ws", "ws", ""), fs);
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        new JSONObject().put("patch",
                                "*** Begin Patch\n*** Add File: src/New.java\n+class New {}\n*** End Patch").toString()),
                FakeAgentLlmGateway.ScriptedTurn.text("fim"));

        RunResult result = runtime(gateway, agentWithPatchTool(), false).run(agentWithPatchTool(), "cria", SC);

        assertTrue(result.isSuccessful());
        assertEquals("the run's filesystem received the mutation", "class New {}\n",
                fs.readText("src/New.java"));
        // Isolation artifacts intact: the run context carried workspace + memory.
        assertNotNull(TaskMemoryStore.load(SC));
        TaskMemoryStore.clear(SC);
    }

    // ------------------------------------------------------------------
    // Test 15: plain-text ending when a mutation is required → ONE nudge
    // ------------------------------------------------------------------

    @Test
    public void eval_recovery_plainTextWhenMutationExpected_oneNudgeThenStop() {
        RecordingApproval approvals = new RecordingApproval();
        approvals.decision = PermissionDecision.ALLOW;
        // Turn 1: plain text, no mutation. Turn 2 (after the single nudge):
        // plain text again → the run ends WITHOUT mutating, no loop.
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text("Criei um patch para Main.java: ..."),
                FakeAgentLlmGateway.ScriptedTurn.text("Não há mais nada a alterar."));

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .expectFileMutations(true)
                .build();

        RunResult result = runtime.run(agent(), "aplique o patch", SC);

        assertTrue(result.isSuccessful());
        assertEquals("no workspace mutation happened", 0, fs.files.size());
        assertEquals("exactly one recovery nudge, then stop", 2, gateway.turnsConsumed());
    }

    @Test
    public void eval_recovery_afterNudge_structuredCallRuns() throws Exception {
        fs.writeText("src/A.java", "v1\n");
        RecordingApproval approvals = new RecordingApproval();
        approvals.decision = PermissionDecision.ALLOW;
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text("Criei um patch para A.java: ..."),
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        new JSONObject().put("patch",
                                "*** Begin Patch\n*** Update File: src/A.java\n@@\n-v1\n+v2\n*** End Patch").toString()),
                FakeAgentLlmGateway.ScriptedTurn.text("aplicado de verdade"));

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(events)
                .expectFileMutations(true)
                .build();

        RunResult result = runtime.run(agentWithPatchTool(), "aplique o patch", SC);

        assertTrue(result.isSuccessful());
        assertEquals("the nudged model executed the structured call", "v2\n",
                fs.readText("src/A.java"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AgentRuntime runtime(AgentLlmGateway gateway, Agent agent, boolean expectMutations) {
        return new AgentRuntime.Builder(gateway)
                .events(events)
                .expectFileMutations(expectMutations)
                .build();
    }

    private static Agent agent() {
        return Agent.Builder.forName("coder", "You complete coding tasks.").build();
    }

    private static Agent agentWithPatchTool() {
        // No stream: the router lends the run's own EventStream, so
        // FileChanged flows on the same channel the test observes.
        return Agent.Builder.forName("coder", "You edit files.")
                .tools(new ApplyPatchTool(SC, null, fsStatic()))
                .build();
    }

    private static Agent agentWithPatchAndEcho() {
        AgentTool echo = new AgentTool() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "reads a file";
            }

            @Override
            public JSONObject parameters() {
                try {
                    return new JSONObject().put("type", "object");
                } catch (JSONException e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public AgentToolResult execute(RunContext context, JSONObject args) {
                return AgentToolResult.success("read ok");
            }
        };
        return Agent.Builder.forName("coder", "You edit files.")
                .tools(echo, new ApplyPatchTool(SC, null, fsStatic()))
                .build();
    }

    private static FakeWorkspaceFileSystem fsStatic() {
        return fsRef.get();
    }

    private static final java.util.concurrent.atomic.AtomicReference<FakeWorkspaceFileSystem> fsRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static ToolCall call(String name, String id, JSONObject args) {
        return new ToolCall(name, args.toString(), id);
    }

    private int toolCallStartedCount() {
        int count = 0;
        for (AgentEvent event : received) {
            if (event instanceof AgentEvent.ToolCallStarted) {
                count++;
            }
        }
        return count;
    }

    private int fileChangedCount() {
        int count = 0;
        for (AgentEvent event : received) {
            if (event instanceof AgentEvent.FileChanged) {
                count++;
            }
        }
        return count;
    }

    private int firstIndexOf(Class<? extends AgentEvent> type) {
        for (int i = 0; i < received.size(); i++) {
            if (type.isInstance(received.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Workspace fakeWorkspace() {
        return new Workspace("ws-contract", "Contract", "/", "/", false,
                Workspace.PermissionState.GRANTED, 0L, "");
    }

    /** Approval handler that records every request (test 13). */
    private static final class RecordingApproval implements ApprovalHandler {
        PermissionDecision decision = PermissionDecision.ALLOW;
        int requests;

        @Override
        public PermissionDecision onRequest(PermissionRequest request) {
            requests++;
            return decision;
        }
    }
}
