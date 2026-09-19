package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Stateful agent run loop (v2), porting the Codex session/turn cycle to the
 * Axion stack on top of the {@link Runner} concepts:
 *
 * <pre>Session → Turn → Model → Tool calls → Permission/Sandbox → Result → next Turn</pre>
 *
 * <p>Differences from {@link Runner}: emits typed {@link AgentEvent}s to an
 * {@link EventStream}, consults the {@link PermissionLayer} before every
 * tool (async human-in-the-loop instead of a blocking boolean callback),
 * keeps an {@link AgentSession} audit trail, and supports cooperative
 * cancellation. Legacy hosts keep using {@link Runner}; new hosts and the
 * migrating UI use this class.</p>
 */
public final class AgentRuntime {

    public static final int DEFAULT_MAX_TURNS = 16;

    private static final long DIRECTORY_CACHE_TTL_MS = 5000L; // parity with ContextBuilder

    private final AgentLlmGateway gateway;
    private final EventStream events;
    private final PermissionLayer permissions;
    private final List<Guardrail> inputGuardrails;
    private final int maxTurns;
    private final RunBudget budget;
    private final ApprovalHandler inputChannel;
    private final int maxOutputTokensPerTurn;
    private final boolean includeProjectInstructions;
    private final AgentToolRouter toolRouter;
    private final boolean expectFileMutations;
    private volatile boolean cancelRequested;

    private AgentRuntime(Builder builder) {
        this.gateway = builder.gateway;
        this.events = builder.events;
        this.permissions = builder.permissions;
        this.toolRouter = new AgentToolRouter(permissions, events);
        this.inputGuardrails = Collections.unmodifiableList(new ArrayList<>(builder.inputGuardrails));
        this.maxTurns = Math.max(1, builder.maxTurns);
        this.budget = builder.budget;
        this.inputChannel = builder.inputChannel;
        this.maxOutputTokensPerTurn = builder.maxOutputTokensPerTurn;
        this.includeProjectInstructions = builder.includeProjectInstructions;
        this.expectFileMutations = builder.expectFileMutations;
    }

    /** Single-shot run: user input in, final assistant text out. */
    public RunResult run(Agent agent, String userInput, String scId) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(userInput, ChatMessage.TYPE_USER, System.currentTimeMillis()));
        return run(agent, history, scId);
    }

    /** Resumable run over caller-owned history (appended with this run's turns). */
    public RunResult run(Agent agent, List<ChatMessage> history, String scId) {
        if (agent == null) {
            return RunResult.failure("No agent was provided.");
        }
        if (history == null || history.isEmpty()) {
            return RunResult.failure("No user input was provided.");
        }
        cancelRequested = false;

        AgentSession session = new AgentSession(scId, agent.name(), history);
        session.setStatus(AgentSession.Status.RUNNING);
        lastSession = session;

        emit(new AgentEvent.RunStarted(scId));

        // Input guardrails run before the first LLM call (openai-agents semantics).
        String latestUserText = latestUserText(history);
        for (Guardrail guardrail : inputGuardrails) {
            GuardrailResult result = guardrail.checkInput(latestUserText);
            if (result.isTripwireTriggered()) {
                session.setStatus(AgentSession.Status.FAILED);
                emit(new AgentEvent.RunCompleted(scId, false, "Blocked by input guardrail"));
                return RunResult.blockedByGuardrail(result);
            }
        }

        // ---- Single execution identity (item 2/3 of the migration) ----
        // The workspace of this run is resolved ONCE from the scId — never
        // from the global active workspace, which is a UI selection that may
        // point anywhere. Prompt, AGENTS.md, snapshot, tools and mutations
        // all derive from this RunContext.
        final RunContextFactory.Resolved resolved = RunContextFactory.resolve(scId);
        RunContext context = new RunContext(
                scId,
                resolved.workspace(),
                resolved.filesystem(),
                includeProjectInstructions
                        ? ProjectInstructions.load(resolved.filesystem(), cwdOf(resolved), PROJECT_INSTRUCTIONS_MAX_CHARS)
                        : "",
                includeProjectInstructions
                        ? ProjectDiscovery.discover(resolved.filesystem(), cwdOf(resolved))
                        : null,
                new TaskMemory(scId, environmentIdOf(resolved), latestUserText(history)),
                new ContextTracker(budget),
                agent.name());

        // Pin the run's filesystem for the whole run (cross-thread): tools,
        // ContextBuilder and ApplyPatchTool read RuntimeFileContext instead
        // of the global active workspace while this run is in flight.
        final AutoCloseable pinned = RuntimeFileContext.pin(resolved.workspace(), resolved.filesystem());

        // Compaction-proof handoff (item 7 of the migration): the previous
        // run's durable task state (objective, relevant files, progress) is
        // restored from the task store, so a compacted history never erases
        // where the task stands.
        TaskMemoryStore.restoreInto(scId, context.taskMemory());

        Agent activeAgent = agent;
        int turns = 0;
        int recoveryNudges = 0;
        toolRouter.resetForNewRun();
        try {
            while (turns++ < maxTurns) {
                if (cancelRequested) {
                    session.setStatus(AgentSession.Status.CANCELLED);
                    emit(new AgentEvent.RunCompleted(scId, false, "Run cancelled"));
                    return RunResult.failure("Run cancelled.");
                }

                final String activeAgentName = activeAgent.name();
                final int turnNumber = turns;
                emit(new AgentEvent.TurnStarted(scId, activeAgentName, turnNumber));

                // M3: reserve the worst-case cost of this turn before the request.
                RunBudget.Handle reservation = null;
                if (budget != null) {
                    try {
                        long estimate = estimatedInputTokens(history) + maxOutputTokensPerTurn;
                        budget.ensureActive(estimate);
                        reservation = budget.reserve(estimate);
                    } catch (RunBudget.BudgetExceededException e) {
                        String reason = e.getMessage();
                        emit(new AgentEvent.Error(scId, reason));
                        session.setStatus(AgentSession.Status.FAILED);
                        emit(new AgentEvent.RunCompleted(scId, false, reason));
                        return RunResult.failure(reason);
                    }
                }

                final LlmTurnOutput turn;
                try {
                    gateway.setDeltaListener(delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            emit(new AgentEvent.AssistantMessageDelta(scId, delta));
                        }
                    });
                    turn = gateway.completeTurn(
                            resolveSystemPrompt(activeAgent, context),
                            toolSchemasFor(withParityTools(activeAgent)),
                            history,
                            null);
                } catch (Exception e) {
                    String reason = "LLM turn failed: " + e.getMessage();
                    emit(new AgentEvent.Error(scId, reason));
                    session.setStatus(AgentSession.Status.FAILED);
                    emit(new AgentEvent.RunCompleted(scId, false, reason));
                    return RunResult.failure(reason);
                } finally {
                    gateway.setDeltaListener(null);
                    if (reservation != null) {
                        // No usage report available yet: settle with the full
                        // estimate so the budget reflects it (conservative).
                        try {
                            budget.settle(reservation, reservation.reserved());
                            context.contextTracker().recordSettled(reservation.reserved());
                        } catch (RunBudget.UncertainChargeException ignored) {
                            // Estimate >= reserved by construction; cannot happen here.
                        }
                    }
                }
                // Input as the model will see it on the NEXT turn: the
                // history now includes this turn's tool results.
                context.contextTracker().recordInputEstimate(estimatedInputTokens(history));
                context.incrementLlmCalls();

                // ---- Source of truth (Codex ResponseItem parity) ----------
                // structuredToolCalls = executable facts from the provider
                // envelope; assistantText = displayable message. The runtime
                // NEVER re-derives tool calls from the text.
                List<ToolCall> structuredCalls = turn.toolCalls() == null
                        ? Collections.emptyList()
                        : turn.toolCalls();
                String assistantText = turn.content() == null ? "" : turn.content().trim();

                if (structuredCalls.isEmpty()) {
                    if (assistantText.isEmpty()) {
                        String reason = "LLM returned an empty turn.";
                        emit(new AgentEvent.Error(scId, reason));
                        session.setStatus(AgentSession.Status.FAILED);
                        emit(new AgentEvent.RunCompleted(scId, false, reason));
                        return RunResult.failure(reason);
                    }
                    // Recovery (item 15/test 15): the host declared this task
                    // must end with a file mutation, and the model answered in
                    // plain text without applying any. ONE nudge, then accept
                    // the answer — never a loop.
                    if (expectFileMutations
                            && context.taskMemory() != null
                            && context.taskMemory().appliedChanges().isEmpty()
                            && recoveryNudges < 1) {
                        recoveryNudges++;
                        history.add(new ChatMessage(RECOVERY_NUDGE, ChatMessage.TYPE_USER,
                                System.currentTimeMillis()));
                        continue;
                    }
                    emit(new AgentEvent.AssistantMessage(scId, assistantText));
                    for (Guardrail guardrail : activeAgent.outputGuardrails()) {
                        GuardrailResult result = guardrail.checkOutput(assistantText);
                        if (result.isTripwireTriggered()) {
                            session.setStatus(AgentSession.Status.FAILED);
                            emit(new AgentEvent.RunCompleted(scId, false, "Blocked by output guardrail"));
                            return RunResult.blockedByGuardrail(result);
                        }
                    }
                    session.setStatus(AgentSession.Status.COMPLETED);
                    emit(new AgentEvent.RunCompleted(scId, true, ""));
                    return RunResult.success(assistantText, context);
                }

                // Assistant text is display-only when tools also arrived: it
                // stays OUT of the execution path (semantic separation).
                if (!assistantText.isEmpty()) {
                    emit(new AgentEvent.AssistantMessageDelta(scId, assistantText));
                }

                boolean handedOff = false;
                for (ToolCall legacyCall : structuredCalls) {
                    if (cancelRequested) {
                        session.setStatus(AgentSession.Status.CANCELLED);
                        emit(new AgentEvent.RunCompleted(scId, false, "Run cancelled"));
                        return RunResult.failure("Run cancelled.");
                    }
                    AgentToolRouter.StructuredToolCall call = new AgentToolRouter.StructuredToolCall(
                            legacyCall.getId(), legacyCall.getName(), legacyCall.getArguments());
                    List<AgentTool> toolset = withParityTools(activeAgent);
                    AgentToolRouter.RoutedCall routed = toolRouter.route(
                            toolset, call, scId, context, new AgentToolRouter.LoopHooks() {
                                @Override
                                public void onToolCallStarted(AgentToolRouter.StructuredToolCall c, AgentTool t) {
                                    context.incrementToolCalls();
                                    emit(new AgentEvent.ToolCallStarted(scId, t.name(), legacyCall));
                                }

                                @Override
                                public void onToolCallCompleted(AgentToolRouter.StructuredToolCall c, AgentTool t, AgentToolResult r) {
                                    if (r != null) {
                                        // Unknown tools arrive with t == null:
                                        // the call's own name is the identity.
                                        emit(new AgentEvent.ToolCallCompleted(scId,
                                                t != null ? t.name() : c.toolName(), legacyCall, r));
                                    }
                                }
                            });
                    if (routed.wasDeduplicated()) {
                        continue;
                    }
                    AgentToolResult result = routed.result();
                    if (result != null) {
                        appendToolResult(history, legacyCall, result.output());
                    }
                    if (routed.handedOff()) {
                        Agent target = findHandoffTarget(activeAgent, call.toolName());
                        if (target != null && target != activeAgent) {
                            context.recordHandoff(activeAgent, target, "");
                            activeAgent = target;
                        }
                        handedOff = true;
                        break;
                    }
                }
                // With tools executed (or a handoff), loop for the next LLM turn.
            }
            session.setStatus(AgentSession.Status.FAILED);
            emit(new AgentEvent.RunCompleted(scId, false, "Max turns reached"));
            return RunResult.maxTurnsReached(context, "");
        } finally {
            sessionSnapshot = session;
            // Durable task memory: the NEXT run (and process restart) starts
            // from this state even when the history gets compacted.
            TaskMemoryStore.save(scId, context.taskMemory());
            try {
                pinned.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Cooperative cancellation: checked between turns and tool calls. */
    public void cancel() {
        cancelRequested = true;
        gateway.cancel();
    }

    private AgentSession lastSession;
    private AgentSession sessionSnapshot;

    /** Session of the most recent run on this runtime (audit/telemetry). */
    public AgentSession lastSession() {
        return sessionSnapshot != null ? sessionSnapshot : lastSession;
    }

    // ------------------------------------------------------------------
    // Host-visible pending approval (Codex ReviewDecision parity)
    // ------------------------------------------------------------------

    /**
     * A tool call currently parked waiting for a human decision. Host UIs
     * query it to render the confirmation dialog and can observe the final
     * decision once resolved.
     */
    public static final class PendingApproval {
        private final PermissionRequest request;
        private final java.util.function.Supplier<PermissionDecision> decisionView;

        PendingApproval(PermissionRequest request,
                        java.util.function.Supplier<PermissionDecision> decisionView) {
            this.request = request;
            this.decisionView = decisionView;
        }

        public String getTool() {
            return request.getTool();
        }

        public PermissionRequest getRequest() {
            return request;
        }

        /** Current decision: {@code null} while still pending. */
        public PermissionDecision peekDecision() {
            return decisionView.get();
        }
    }

    /** The approval awaiting the user, or {@code null} when nothing is parked. */
    public PendingApproval currentPendingApproval() {
        if (permissions == null) {
            return null;
        }
        PermissionRequest request = permissions.currentPendingRequest();
        return request == null
                ? null
                : new PendingApproval(request, () -> permissions.lastDecisionFor(request));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Codex context assembly (item 12 of the migration): structured
     * fragments — agent instructions, AGENTS.md hierarchy, workspace
     * snapshot and task memory — rendered in a fixed order from THIS run's
     * {@link RunContext}. Never reads the global active workspace.
     */
    private String resolveSystemPrompt(Agent agent, RunContext context) {
        if (!includeProjectInstructions) {
            return agent.instructions();
        }
        return RunContextAssembler.assemble(agent, context).renderPrompt();
    }

    /** ~4 chars per token, same estimate used by AgentManager. */
    private static long estimatedInputTokens(List<ChatMessage> history) {
        long chars = 0;
        for (ChatMessage message : history) {
            String content = message.getLlmContent();
            if (content != null) {
                chars += content.length();
            }
        }
        return chars / 4 + 1;
    }

    /**
     * Codex parity: every agent implicitly gains {@code get_context_remaining}
     * and — when an input channel is configured — {@code request_user_input},
     * without hosts having to remember to add them.
     */
    private List<AgentTool> withParityTools(Agent agent) {
        List<AgentTool> tools = new ArrayList<>(agent.tools());
        if (findNamedTool(tools, "get_context_remaining") == null) {
            tools.add(new ContextRemainingTool());
        }
        if (inputChannel != null && findNamedTool(tools, "request_user_input") == null) {
            tools.add(new RequestUserInputTool(inputChannel));
        }
        return tools;
    }

    private static AgentTool findNamedTool(List<AgentTool> tools, String toolName) {
        for (AgentTool tool : tools) {
            if (tool.name().equals(toolName)) {
                return tool;
            }
        }
        return null;
    }

    /** Lookup across the agent's own tools plus the implicit parity tools. */
    private AgentTool findParityTool(String toolName) {
        if ("get_context_remaining".equals(toolName)) {
            return new ContextRemainingTool();
        }
        if (inputChannel != null && "request_user_input".equals(toolName)) {
            return new RequestUserInputTool(inputChannel);
        }
        return null;
    }

    /** Upper bound for AGENTS.md injection (~600 tokens). */
    private static final int PROJECT_INSTRUCTIONS_MAX_CHARS = 2400;

    /** Workspace-relative cwd of the resolved run (root when absent). */
    private static String cwdOf(RunContextFactory.Resolved resolved) {
        return resolved != null
                && resolved.workspace() != null
                && resolved.workspace().cwd() != null
                ? resolved.workspace().cwd()
                : "";
    }

    /** Stable environment id of the resolved run (workspace id, else scId). */
    private static String environmentIdOf(RunContextFactory.Resolved resolved) {
        if (resolved != null && resolved.workspace() != null
                && !resolved.workspace().workspaceId().isEmpty()) {
            return resolved.workspace().workspaceId();
        }
        return "";
    }

    private void emit(AgentEvent event) {
        events.emit(event);
    }

    // ------------------------------------------------------------------
    // Recovery (item 15): plain-text answer while a mutation is expected
    // ------------------------------------------------------------------

    /** Recovery nudge appended as a user message; at most ONE per run. */
    static final String RECOVERY_NUDGE =
            "[system] Sua resposta anterior foi apenas texto, mas a tarefa exige uma "
                    + "alteração real de arquivo. Execute a mutation com a tool apropriada "
                    + "(por exemplo apply_patch) em uma chamada estruturada de ferramenta. "
                    + "Se já não houver nada a alterar, responda apenas com o texto final.";



    private JSONArray toolSchemasFor(Agent agent) {
        return toolSchemasFor(agent.tools());
    }

    private JSONArray toolSchemasFor(List<AgentTool> tools) {
        JSONArray schemas = new JSONArray();
        for (AgentTool tool : tools) {
            try {
                schemas.put(new JSONObject()
                        .put("type", "function")
                        .put("function", new JSONObject()
                                .put("name", tool.name())
                                .put("description", tool.description())
                                .put("parameters", tool.parameters())));
            } catch (org.json.JSONException e) {
                // A malformed tool schema must not kill the run; skip the tool.
            }
        }
        return schemas;
    }

    private static Agent findHandoffTarget(Agent activeAgent, String toolName) {
        for (Agent candidate : activeAgent.handoffs()) {
            if (HandoffTool.toolNameFor(candidate).equals(toolName)) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendToolResult(List<ChatMessage> history, ToolCall call, String output) {
        ChatMessage toolMessage = new ChatMessage("", ChatMessage.TYPE_TOOL, System.currentTimeMillis());
        toolMessage.setToolName(call.getName());
        toolMessage.setToolArgs(call.getArguments());
        toolMessage.setToolId(call.getId());
        toolMessage.setToolRunning(false);
        toolMessage.setToolResult(output);
        toolMessage.setToolError(output != null && output.startsWith("Error"));
        history.add(toolMessage);
    }

    private static String latestUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getType() == ChatMessage.TYPE_USER) {
                return message.getMessage();
            }
        }
        return "";
    }

    /** Fluent builder for {@link AgentRuntime}. */
    public static final class Builder {
        private final AgentLlmGateway gateway;
        private EventStream events = new EventStream();
        private PermissionLayer permissions;
        private final List<Guardrail> inputGuardrails = new ArrayList<>();
        private int maxTurns = DEFAULT_MAX_TURNS;
        private RunBudget budget;
        private ApprovalHandler inputChannel;
        private int maxOutputTokensPerTurn = 2048;
        private boolean includeProjectInstructions = true;
        private boolean expectFileMutations = false;

        public Builder(AgentLlmGateway gateway) {
            if (gateway == null) {
                throw new IllegalArgumentException("gateway is required");
            }
            this.gateway = gateway;
        }

        public Builder events(EventStream events) {
            this.events = events == null ? new EventStream() : events;
            return this;
        }

        public Builder permissions(PermissionLayer permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder inputGuardrails(Guardrail... guardrails) {
            for (Guardrail guardrail : guardrails) {
                if (guardrail != null) {
                    inputGuardrails.add(guardrail);
                }
            }
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /** M3: per-run token budget (reserve/settle/block). */
        public Builder budget(RunBudget budget) {
            this.budget = budget;
            return this;
        }

        /** M3: worst-case output reservation per turn. */
        public Builder maxOutputTokensPerTurn(int maxOutputTokensPerTurn) {
            this.maxOutputTokensPerTurn = Math.max(16, maxOutputTokensPerTurn);
            return this;
        }

        /** M7: inject workspace AGENTS.md into the system prompt (default true). */
        public Builder includeProjectInstructions(boolean include) {
            this.includeProjectInstructions = include;
            return this;
        }

        /**
         * Declares that this task is expected to end with at least one file
         * mutation (item 15 recovery): when the model finishes with plain
         * text and nothing was mutated, the runtime sends ONE recovery nudge
         * asking for a structured tool call, then accepts the answer. Default
         * {@code false} — read-only flows never get nudged.
         */
        public Builder expectFileMutations(boolean expect) {
            this.expectFileMutations = expect;
            return this;
        }

        /**
         * Codex parity: human-in-the-loop channel used by the implicit
         * {@code request_user_input} tool (and surfaced in the tool catalog).
         */
        public Builder inputChannel(ApprovalHandler channel) {
            this.inputChannel = channel;
            return this;
        }

        public AgentRuntime build() {
            return new AgentRuntime(this);
        }
    }
}
