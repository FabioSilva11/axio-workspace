package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.AgentManager;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolCatalog;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression suite for the duplicate-tool-card bug: ONE tool call must
 * produce exactly ONE {@code TYPE_TOOL} message (identity = callId, never
 * toolName/toolArgs/timestamp), shared between the HostBridge visual card and
 * the runtime's semantic history entry.
 *
 * <p>The suite drives the PRODUCTION path: real {@link AgentRuntime} +
 * real {@link AgentManager.HostBridge} over a shared conversation list, plus
 * deterministic event injection for fine-grained lifecycle sequences. The
 * {@code EventStream} runs with an inline executor so delivery order matches
 * emission order.</p>
 */
public class HostBridgeToolCardDeduplicationTest {

    private static final String SC = "sc-tool-dupe";

    private final List<ChatMessage> messages = new ArrayList<>();
    private final CaptureListener listener = new CaptureListener();
    private EventStream stream;
    private AgentRuntime runtime;
    private AgentManager.HostBridge bridge;

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static AxionToolRegistry registry() {
        AxionToolRegistry registry = new AxionToolRegistry();
        ToolRegistration read = ToolRegistration.function(
                        "read_file", "Read a file.", new org.json.JSONObject())
                .executor(context -> AgentToolResult.success("file contents"))
                .source("test")
                .build();
        ToolRegistration search = ToolRegistration.function(
                        "search_for_files", "Search files.", new org.json.JSONObject())
                .executor(context -> AgentToolResult.success("hits"))
                .source("test")
                .build();
        registry.register(read);
        registry.register(search);
        return registry;
    }

    private static Agent coordinator() {
        return Agent.Builder.forName("coordinator", "You coordinate.").build();
    }

    private void newInlineStreams() {
        stream = new EventStream(Runnable::run, 128);
    }

    private void newBridge() {
        bridge = new AgentManager.HostBridge(
                runtime, () -> coordinator(), listener, SC,
                Runnable::run, stream, null, unused -> {
                });
        bridge.attachMessages(messages);
    }

    private void newRuntimeBridge(AxionToolRegistry registry, ScriptGateway gateway, int maxTurns) {
        stream = new EventStream(Runnable::run, 128);
        runtime = new AgentRuntime.Builder(gateway)
                .toolRegistry(registry)
                .events(stream)
                .maxTurns(maxTurns)
                .includeProjectInstructions(false)
                .build();
        bridge = new AgentManager.HostBridge(
                runtime, () -> coordinator(), listener, SC,
                Runnable::run, stream, null, unused -> {
                });
        bridge.attachMessages(messages);
    }

    @After
    public void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
    }

    private static List<ChatMessage> toolMessages(List<ChatMessage> list) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : list) {
            if (message != null && message.getType() == ChatMessage.TYPE_TOOL) {
                result.add(message);
            }
        }
        return result;
    }

    private static long countForCallId(List<ChatMessage> list, String callId) {
        long count = 0;
        for (ChatMessage message : toolMessages(list)) {
            if (callId.equals(message.getToolId())) {
                count++;
            }
        }
        return count;
    }

    private void emit(AgentEvent event) {
        stream.emit(event);
    }

    private static ToolCall call(String name, String callId) {
        return new ToolCall(name, "{}", callId);
    }

    private static ToolCall call(String name, String args, String callId) {
        return new ToolCall(name, args, callId);
    }

    private static AgentEvent.ToolCallStarted started(String callId) {
        return new AgentEvent.ToolCallStarted(SC, "read_file", call("read_file", callId));
    }

    private static AgentEvent.ToolCallCompleted completed(String callId, AgentToolResult result) {
        return new AgentEvent.ToolCallCompleted(SC, "read_file", call("read_file", callId), result);
    }

    // ------------------------------------------------------------------
    // 1. A single ToolCallStarted creates a single card
    // ------------------------------------------------------------------

    @Test
    public void startedUnicoCriaExatamenteUmCard() {
        newInlineStreams();
        newBridge();

        emit(started("call_1"));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals("one tool call must render one card", 1, tools.size());
        ChatMessage card = tools.get(0);
        assertEquals("call_1", card.getToolId());
        assertEquals("read_file", card.getToolName());
        assertTrue(card.isToolRunning());
        assertEquals("running_now", card.getToolState());
        assertEquals("the started card must be announced once", 1, listener.added.size());
        assertSame(card, listener.added.get(0));
    }

    // ------------------------------------------------------------------
    // 2. ToolCallStarted + ToolCallCompleted must share ONE card
    // ------------------------------------------------------------------

    @Test
    public void startedMaisCompletedCompartilhamOMesmoCard() {
        newInlineStreams();
        newBridge();

        emit(started("call_1"));
        emit(completed("call_1", AgentToolResult.success("ok-r1")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals("1 call + 1 completion must not duplicate", 1, tools.size());
        ChatMessage card = tools.get(0);
        assertSame("started and completed must be THE SAME object",
                listener.added.get(0), card);
        assertFalse(card.isToolRunning());
        assertFalse(card.isToolError());
        assertEquals("ok-r1", card.getToolResult());
        assertEquals("success", card.getToolState());
    }

    // ------------------------------------------------------------------
    // 3. Two legit callIds produce two distinct cards
    // ------------------------------------------------------------------

    @Test
    public void doisCallIdsDistintosCriamCardsDistintos() {
        newInlineStreams();
        newBridge();

        emit(started("call_A"));
        emit(completed("call_A", AgentToolResult.success("ra")));
        emit(started("call_B"));
        emit(completed("call_B", AgentToolResult.success("rb")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(2, tools.size());
        assertEquals(1, countForCallId(messages, "call_A"));
        assertEquals(1, countForCallId(messages, "call_B"));
        assertNotSame(tools.get(0), tools.get(1));
        assertEquals("ra", tools.get(0).getToolResult());
        assertEquals("rb", tools.get(1).getToolResult());
    }

    // ------------------------------------------------------------------
    // 4. Same toolName AND same args, different callIds -> still distinct
    // ------------------------------------------------------------------

    @Test
    public void mesmoNomeEArgsNaoCoalesceCards() {
        newInlineStreams();
        newBridge();

        emit(new AgentEvent.ToolCallStarted(SC, "read_file", call("read_file", "{\"path\":\"x\"}", "call_X")));
        emit(new AgentEvent.ToolCallStarted(SC, "read_file", call("read_file", "{\"path\":\"x\"}", "call_Y")));

        assertEquals(2, toolMessages(messages).size());
        assertEquals(1, countForCallId(messages, "call_X"));
        assertEquals(1, countForCallId(messages, "call_Y"));
    }

    // ------------------------------------------------------------------
    // 5. A duplicate ToolCallStarted must not create a second card
    // ------------------------------------------------------------------

    @Test
    public void startedDuplicadoNaoDuplicaCard() {
        newInlineStreams();
        newBridge();

        emit(started("call_1"));
        ChatMessage first = toolMessages(messages).get(0);
        emit(started("call_1"));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(1, tools.size());
        assertSame("a re-started call must reuse the existing card", first, tools.get(0));
        assertTrue(tools.get(0).isToolRunning());
        assertTrue("re-start must announce an update, not an add",
                listener.updated.contains(first));
    }

    // ------------------------------------------------------------------
    // 6. A duplicate ToolCallCompleted must update, not duplicate
    // ------------------------------------------------------------------

    @Test
    public void completedDuplicadoNaoDuplicaCard() {
        newInlineStreams();
        newBridge();

        emit(started("call_1"));
        emit(completed("call_1", AgentToolResult.success("r1")));
        ChatMessage first = toolMessages(messages).get(0);
        emit(completed("call_1", AgentToolResult.success("r2")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(1, tools.size());
        assertSame(first, tools.get(0));
        assertEquals("the last completion wins on the same card", "r2", tools.get(0).getToolResult());
    }

    // ------------------------------------------------------------------
    // 7. The tool result is present for the NEXT turn (model context)
    // ------------------------------------------------------------------

    @Test
    public void resultadoDoToolChegaAoProximoTurnoSemDuplicar() throws Exception {
        ScriptGateway gateway = new ScriptGateway()
                .turn(0, () -> new LlmTurnOutput("", "", "tool_calls",
                        Collections.singletonList(call("read_file", "call_A"))))
                .turn(1, () -> new LlmTurnOutput("done", "", "stop", null));
        newRuntimeBridge(registry(), gateway, 4);

        RunResult result = bridge.processUserMessage("work");
        assertTrue("run must complete: " + result.getFailureReason(), result.isSuccessful());

        List<ChatMessage> nextTurnHistory = gateway.turnSnapshots().get(1);
        List<ChatMessage> nextTurnTools = toolMessages(nextTurnHistory);
        assertEquals("the next turn must see exactly ONE tool result", 1, nextTurnTools.size());
        assertEquals("call_A", nextTurnTools.get(0).getToolId());
        assertEquals("file contents", nextTurnTools.get(0).getToolResult());

        ChatMessage card = toolMessages(messages).get(0);
        assertEquals("the card and the semantic history entry are one message",
                "file contents", card.getToolResult());
        assertEquals("no duplicate in the shared list", 1, countForCallId(messages, "call_A"));
    }

    // ------------------------------------------------------------------
    // 8. Approval shares the SAME card as started/completed
    // ------------------------------------------------------------------

    @Test
    public void aprovacaoCompartilhaOMesmoCard() {
        newInlineStreams();
        newBridge();

        AgentEvent.ApprovalRequired approval = new AgentEvent.ApprovalRequired(SC, "read_file",
                call("read_file", "call_A"),
                new PermissionRequest("perm-1", "read_file", call("read_file", "call_A"), "need"));
        emit(approval);
        emit(new AgentEvent.PermissionResolved(SC, "read_file",
                PermissionDecision.ALLOW, true));
        emit(started("call_A"));
        emit(completed("call_A", AgentToolResult.success("ra")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(1, tools.size());
        ChatMessage card = tools.get(0);
        assertEquals("identity is the callId, never the opaque request id",
                "call_A", card.getToolId());
        assertSame(listener.added.get(0), card);
        assertTrue(card.getRequiresApproval());
        assertEquals("ra", card.getToolResult());
        assertFalse(card.isRejected());
        assertEquals(1, listener.approvals.size());
        assertEquals("perm-1|read_file", listener.approvals.get(0));
    }

    // ------------------------------------------------------------------
    // 9. A denied approval keeps ONE rejected card (no error twin)
    // ------------------------------------------------------------------

    @Test
    public void aprovacaoNegadaMantemUmCardRejeitado() {
        newInlineStreams();
        newBridge();

        AgentEvent.ApprovalRequired approval = new AgentEvent.ApprovalRequired(SC, "read_file",
                call("read_file", "call_A"),
                new PermissionRequest("perm-1", "read_file", call("read_file", "call_A"), "need"));
        emit(approval);
        emit(new AgentEvent.PermissionResolved(SC, "read_file",
                PermissionDecision.DENY, false, ApprovalHandler.ApprovalState.DENIED));
        emit(completed("call_A", AgentToolResult.error("Error: denied by policy or user.")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals("denial must not spawn a second error card", 1, tools.size());
        ChatMessage card = tools.get(0);
        assertSame(listener.added.get(0), card);
        assertTrue(card.isRejected());
        assertFalse(card.isToolRunning());
        assertEquals("rejected", card.getToolState());
        assertEquals("the completion must not overwrite the rejected card", "call_A", card.getToolId());
    }

    // ------------------------------------------------------------------
    // 10. Cancellation between turns produces no stray/duplicate card
    // ------------------------------------------------------------------

    @Test
    public void cancelamentoNaoCriaCardsDuplicados() throws Exception {
        CountDownLatch turn2Entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptGateway gateway = new ScriptGateway()
                .turn(0, () -> new LlmTurnOutput("", "", "tool_calls",
                        Collections.singletonList(call("read_file", "call_A"))))
                .turn(1, () -> {
                    try {
                        turn2Entered.countDown();
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new LlmTurnOutput("", "", "tool_calls",
                            Collections.singletonList(call("read_file", "call_B")));
                })
                .turn(2, () -> new LlmTurnOutput("done", "", "stop", null));
        newRuntimeBridge(registry(), gateway, 4);

        final RunResult[] result = new RunResult[1];
        Thread runner = new Thread(() -> result[0] = bridge.processUserMessage("work"));
        runner.start();
        assertTrue("turn 2 must be reached", turn2Entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        runtime.cancel();
        release.countDown();
        runner.join(5000);

        assertFalse("cancelled run must not be successful", result[0].isSuccessful());
        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(1, tools.size());
        assertEquals("the executed call must keep its single card", "call_A", tools.get(0).getToolId());
        assertEquals("the cancelled call must never materialise", 0, countForCallId(messages, "call_B"));
    }

    // ------------------------------------------------------------------
    // 11. A tool error keeps exactly one card
    // ------------------------------------------------------------------

    @Test
    public void erroDeFeramentaMantemUmCard() {
        newInlineStreams();
        newBridge();

        emit(started("call_1"));
        emit(completed("call_1", AgentToolResult.error("Error: boom")));

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(1, tools.size());
        ChatMessage card = tools.get(0);
        assertTrue(card.isToolError());
        assertFalse(card.isToolRunning());
        assertEquals("error", card.getToolState());
        assertTrue(card.isExpanded());
        assertEquals("Error: boom", card.getToolResult());
    }

    // ------------------------------------------------------------------
    // 12. Three sequential tools produce three cards
    // ------------------------------------------------------------------

    @Test
    public void tresFerramentasSequenciaisCriamTresCards() throws Exception {
        ScriptGateway gateway = new ScriptGateway().turn(0, () -> new LlmTurnOutput(
                        "", "", "tool_calls",
                        Arrays.asList(call("read_file", "call_1"),
                                call("search_for_files", "call_2"),
                                call("search_for_files", "call_3"))))
                .turn(1, () -> new LlmTurnOutput("done", "", "stop", null));
        newRuntimeBridge(registry(), gateway, 4);

        RunResult result = bridge.processUserMessage("work");
        assertTrue(result.isSuccessful());

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals(3, tools.size());
        assertEquals(1, countForCallId(messages, "call_1"));
        assertEquals(1, countForCallId(messages, "call_2"));
        assertEquals(1, countForCallId(messages, "call_3"));
        for (ChatMessage card : tools) {
            assertFalse(card.isToolRunning());
            assertFalse("results must be present on the card", card.getToolResult().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 13. The same tool three times (three callIds) -> three cards
    // ------------------------------------------------------------------

    @Test
    public void mesmaFeramentaTresVezesCriaTresCards() throws Exception {
        ScriptGateway gateway = new ScriptGateway().turn(0, () -> new LlmTurnOutput(
                        "", "", "tool_calls",
                        Arrays.asList(call("read_file", "call_1"),
                                call("read_file", "call_2"),
                                call("read_file", "call_3"))))
                .turn(1, () -> new LlmTurnOutput("done", "", "stop", null));
        newRuntimeBridge(registry(), gateway, 4);

        RunResult result = bridge.processUserMessage("work");
        assertTrue(result.isSuccessful());

        List<ChatMessage> tools = toolMessages(messages);
        assertEquals("identify is callId, not the tool name", 3, tools.size());
        List<String> ids = new ArrayList<>();
        for (ChatMessage card : tools) {
            ids.add(card.getToolId());
        }
        assertEquals(3, ids.stream().distinct().count());
        for (String id : new String[]{"call_1", "call_2", "call_3"}) {
            assertEquals(1, countForCallId(messages, id));
        }
    }

    // ------------------------------------------------------------------
    // 14. Reopening the conversation over the same history must not re-add
    // ------------------------------------------------------------------

    @Test
    public void reabrirConversaNaoDuplicaCards() throws Exception {
        ScriptGateway gateway = new ScriptGateway()
                .turn(0, () -> new LlmTurnOutput("", "", "tool_calls",
                        Collections.singletonList(call("read_file", "call_A"))))
                .turn(1, () -> new LlmTurnOutput("done", "", "stop", null));
        newRuntimeBridge(registry(), gateway, 4);

        RunResult first = bridge.processUserMessage("work");
        assertTrue(first.isSuccessful());
        assertEquals("baseline after run 1", 1, toolMessages(messages).size());

        // Reopen: the SAME shared history is fed back as the full conversation.
        RunResult second = bridge.processHistory(messages);
        assertTrue(second.isSuccessful());

        assertEquals("reopening must not duplicate tool cards",
                1, toolMessages(messages).size());
        assertEquals(1, countForCallId(messages, "call_A"));
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Scripted gateway: each turn index returns its canned turn (else "done"). */
    private static final class ScriptGateway implements AgentLlmGateway {
        private final AtomicInteger served = new AtomicInteger();
        private final java.util.concurrent.ConcurrentHashMap<Integer, java.util.function.Supplier<LlmTurnOutput>> script =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final List<List<ChatMessage>> turnSnapshots = new ArrayList<>();

        ScriptGateway turn(int index, java.util.function.Supplier<LlmTurnOutput> turn) {
            script.put(index, turn);
            return this;
        }

        @Override
        public LlmTurnOutput completeTurn(String systemPrompt, ToolCatalog catalog,
                                          List<ChatMessage> messages,
                                          com.saaspaymentsolutions.axion.AiOperationContext operationContext)
                throws Exception {
            turnSnapshots.add(new ArrayList<>(messages));
            java.util.function.Supplier<LlmTurnOutput> turn = script.get(served.getAndIncrement());
            if (turn == null) {
                return new LlmTurnOutput("done", "", "stop", null);
            }
            return turn.get();
        }

        @Override
        public LlmTurnOutput completeTurn(String systemPrompt, org.json.JSONArray tools,
                                          List<ChatMessage> messages,
                                          com.saaspaymentsolutions.axion.AiOperationContext operationContext) {
            throw new AssertionError("production path must use the ToolCatalog overload");
        }

        List<List<ChatMessage>> turnSnapshots() {
            return turnSnapshots;
        }
    }

    /** Captures every Ui callback together with the ChatMessage objects. */
    private static final class CaptureListener implements AgentManager.AgentListener {
        final List<ChatMessage> added = new ArrayList<>();
        final List<ChatMessage> updated = new ArrayList<>();
        final List<String> approvals = new ArrayList<>();

        @Override
        public void onMessageAdded(ChatMessage message) {
            added.add(message);
        }

        @Override
        public void onMessageUpdated(ChatMessage message) {
            updated.add(message);
        }

        @Override
        public void onMessageRemoved(ChatMessage message, int index) {
        }

        @Override
        public void onStatusChanged(String status) {
        }

        @Override
        public void onDebug(String message) {
        }

        @Override
        public void onProcessingFinished() {
        }

        @Override
        public void onToolExecuted(String toolName, boolean isMutation) {
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onApprovalRequired(String requestId, String toolName) {
            approvals.add(requestId + "|" + toolName);
        }
    }
}