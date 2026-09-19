package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.AgentManager;
import com.saaspaymentsolutions.axion.AiOperationContext;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * V2-only runtime tests: legacy-free API parity (async approvals consultable
 * by the host UI) and the bridge contract for the migrated host
 * (AgentManager-delegates-to-runtime migration path).
 */
public class RuntimeV2OnlyTest {

    // ------------------------------------------------------------------
    // Consultable pending approval (Codex ReviewDecision parity)
    // ------------------------------------------------------------------

    @Test
    public void pendingApproval_isConsultableAndResolvableByHost() throws Exception {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("edit_file", "{\"uri\":\"a.txt\"}"),
                FakeAgentLlmGateway.ScriptedTurn.text("done"));

        CompletableFuture<PermissionDecision> decision = new CompletableFuture<>();
        PolicyDenialCollector events = new PolicyDenialCollector();
        PermissionLayer layer = new PermissionLayer(ToolPolicy.interactive(),
                new ApprovalHandler() {
                    @Override
                    public PermissionDecision onRequest(PermissionRequest request) {
                        try {
                            // Blocks the responder thread until the host
                            // (the test main path) completes the decision.
                            return decision.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            return PermissionDecision.DENY;
                        }
                    }
                }, events.stream);

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .permissions(layer)
                .maxTurns(4)
                .build();
        Agent agent = Agent.Builder.forName("a", "i")
                .tools(approvalTool("edit_file"))
                .build();

        // Run on a thread; the runtime parks on the approval handler.
        Thread runThread = new Thread(() -> runtime.run(agent, "go", "sc1"));
        runThread.start();
        Thread.sleep(150);

        AgentRuntime.PendingApproval pending = runtime.currentPendingApproval();
        assertNotNull("host must be able to see the pending approval", pending);
        assertEquals("edit_file", pending.getTool());
        assertNull("not yet decided", pending.peekDecision());

        decision.complete(PermissionDecision.ALLOW);
        runThread.join(5000);
        assertFalse(runThread.isAlive());

        assertEquals(PermissionDecision.ALLOW, pending.peekDecision());
        assertFalse("denial must not be emitted for an allowed tool",
                events.policyDenied.stream().anyMatch(e -> e.getTool().equals("edit_file")));
        assertFalse("resolution event must exist", events.resolved.isEmpty());
    }

    @Test
    public void timedOutApproval_failsClosed() throws Exception {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("edit_file", "{}"),
                FakeAgentLlmGateway.ScriptedTurn.text("done"));

        PolicyDenialCollector events = new PolicyDenialCollector();
        PermissionLayer layer = new PermissionLayer(ToolPolicy.interactive(),
                new ApprovalHandler() {
                    @Override
                    public PermissionDecision onRequest(PermissionRequest request) {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                        return PermissionDecision.DENY;
                    }

                    @Override
                    public long timeoutMs() {
                        return 100; // expires before the handler answers
                    }
                }, events.stream);

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .permissions(layer)
                .maxTurns(4)
                .build();
        StubTool tool = new StubTool("edit_file");
        RunResult result = runtime.run(
                Agent.Builder.forName("a", "i").tools(tool).build(), "go", "sc1");

        assertTrue(result.isSuccessful());
        assertFalse("tool must not have executed after timeout", tool.executed);
        assertFalse("timeout must resolve as a recorded denial",
                events.resolved.isEmpty());
        assertFalse(events.resolved.get(0).isAllowed());
    }

    // ------------------------------------------------------------------
    // Host bridge: AgentManager delegates to the runtime (sync UI path)
    // ------------------------------------------------------------------

    @Test
    public void bridge_deliversMessagesAndBlocksForSyncHosts() throws Exception {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.text("legacy-free answer"));
        AgentRuntime runtime = new AgentRuntime.Builder(gateway).build();

        List<ChatMessage> added = new ArrayList<>();
        List<ChatMessage> updated = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        BlockingQueue<Boolean> finished = new LinkedBlockingQueue<>(1);
        AgentManager.HostBridge bridge = new AgentManager.HostBridge(runtime,
                () -> Agent.Builder.forName("a", "i").build(),
                new AgentManager.AgentListener() {
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
                        statuses.add(status);
                    }

                    @Override
                    public void onDebug(String message) {
                    }

                    @Override
                    public void onProcessingFinished() {
                        finished.offer(Boolean.TRUE);
                    }

                    @Override
                    public void onToolExecuted(String toolName, boolean isMutation) {
                    }

                    @Override
                    public void onError(String error) {
                    }
                }, "sc_bridge", Runnable::run);

        bridge.processUserMessage("hello bridge");

        assertTrue("run must finish and unblock the sync host",
                finished.poll(5, TimeUnit.SECONDS) == Boolean.TRUE);
        assertEquals("legacy-free answer", bridge.lastAssistantText());
        assertFalse(added.isEmpty());
        assertFalse("user message must be echoed to the UI", added.get(0).isBot());
        assertTrue("assistant final text must reach the UI",
                updated.stream().anyMatch(ChatMessage::isBot));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static AgentTool approvalTool(String name) {
        return new StubTool(name);
    }


    /** Simple approval-gated tool recording execution. */
    private static final class StubTool implements AgentTool {
        final String toolName;
        boolean executed;

        StubTool(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public JSONObject parameters() {
            return new JSONObject();
        }

        @Override
        public boolean requiresApproval() {
            return true;
        }

        @Override
        public AgentToolResult execute(RunContext context, JSONObject args) {
            executed = true;
            return AgentToolResult.success("ran");
        }
    }

    /** Collector for denial/resolution events backed by a real sync EventStream. */
    private static final class PolicyDenialCollector {
        final List<AgentEvent.PolicyDenied> policyDenied = new ArrayList<>();
        final List<AgentEvent.PermissionResolved> resolved = new ArrayList<>();
        final EventStream stream = new EventStream(Runnable::run, 64);

        PolicyDenialCollector() {
            stream.subscribe(event -> {
                if (event instanceof AgentEvent.PolicyDenied) {
                    policyDenied.add((AgentEvent.PolicyDenied) event);
                } else if (event instanceof AgentEvent.PermissionResolved) {
                    resolved.add((AgentEvent.PermissionResolved) event);
                }
            });
        }
    }
}
