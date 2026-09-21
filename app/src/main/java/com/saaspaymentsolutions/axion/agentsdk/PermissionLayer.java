package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

/**
 * Enforces the {@link ToolPolicy} on every tool call before execution,
 * porting Codex's decision model (Skip / NeedsApproval / Forbidden) to the
 * Axion runtime: DENY short-circuits, ASK_USER parks the request with the
 * {@link ApprovalHandler} until the host resolves it explicitly
 * ({@code resolve(requestId, decision)} / {@code cancel(requestId)}) or it
 * times out (fail closed). The model never decides.
 *
 * <p>The layer is part of the runtime's MANDATORY contract (item 15): a
 * {@code null} policy layer is replaced by a safe-default
 * {@link ToolPolicy#interactive()} — mutation/shell never execute merely
 * because the host forgot to configure the policy.</p>
 *
 * <p>The layer classifies a {@link ToolRegistration} from its metadata — the
 * single source of truth: shell name → shell policy, MCP source → network
 * policy, destructive/file-mutation flags → destructive/mutation policy,
 * everything else → unknown. No {@code AgentTool} participates.</p>
 */
public final class PermissionLayer {

    private final ToolPolicy policy;
    private final ApprovalHandler approvalHandler;
    private final EventStream events;

    /** Snapshot of the last resolved request's lifecycle (UI/debug). */
    private volatile ApprovalHandler.ApprovalRecord lastRecord;
    /** Requests this layer raised and that are still PENDING (layer-owned
     *  registry — works with ANY ApprovalHandler implementation). */
    private final java.util.List<PermissionRequest> layerPending =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Resolution slots per requestId (the layer owns the channel). */
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<PermissionDecision>> pendingFutures =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Request ids cancelled before any decision (state classification). */
    private final java.util.Set<String> cancelledIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PermissionLayer(ToolPolicy policy, ApprovalHandler approvalHandler, EventStream events) {
        this.policy = policy == null ? ToolPolicy.interactive() : policy;
        this.approvalHandler = approvalHandler;
        this.events = events;
    }

    /** Outcome of a policy check for one tool call. */
    public enum Outcome {
        /** Execute the tool. */
        PROCEED,
        /** Do not execute; the model receives an error result. */
        BLOCKED
    }

    /**
     * Decides whether {@code call} may run. Blocking when the policy asks
     * the user; emits {@code ApprovalRequired} / {@code PermissionResolved}
     * / {@code PolicyDenied} events.
     */
    public Outcome check(ToolRegistration registration, ToolCall call, String scId) {
        if (registration == null) {
            return Outcome.PROCEED; // unknown tool handled by the caller
        }
        String toolName = registration.spec().name().name();
        ToolPolicy.Rule rule = ruleFor(registration);
        if (rule == ToolPolicy.Rule.DENY) {
            String reason = "Policy denies tool '" + toolName + "' in this mode.";
            emitEvent(new AgentEvent.PolicyDenied(scId, toolName, reason));
            return Outcome.BLOCKED;
        }
        if (rule == ToolPolicy.Rule.ALLOW) {
            return Outcome.PROCEED;
        }
        // ASK_USER
        if (approvalHandler == null) {
            // No host to ask: fail closed instead of silently executing.
            String reason = "Tool '" + toolName + "' requires approval but no approval handler is configured.";
            emitEvent(new AgentEvent.PolicyDenied(scId, toolName, reason));
            return Outcome.BLOCKED;
        }
        PermissionRequest request = new PermissionRequest(
                "perm_" + java.util.UUID.randomUUID(), toolName, call,
                "The policy requires user confirmation to execute '" + toolName + "'.");
        // Track state transitions for the UI/telemetry.
        approvalHandler.addStateListener(this::recordState);
        // The layer owns its pending registry and the resolution channel:
        // currentPendingRequest()/resolve()/cancel() work for EVERY handler
        // (anonymous, resolver-based, test doubles).
        layerPending.add(request);
        java.util.concurrent.CompletableFuture<PermissionDecision> slot =
                new java.util.concurrent.CompletableFuture<>();
        pendingFutures.put(request.getId(), slot);
        emitEvent(new AgentEvent.ApprovalRequired(scId, toolName, call, request));
        // The handler answers ASYNCHRONOUSLY: its decision races the host's
        // explicit resolve(requestId, decision). Whoever completes the slot
        // first decides — the run thread never parks on an uncancellable
        // callback beyond the timeout.
        Thread responder = new Thread(() -> {
            try {
                PermissionDecision handlerDecision = approvalHandler.decideNow(request);
                slot.complete(handlerDecision == null ? PermissionDecision.DENY : handlerDecision);
            } catch (Exception broken) {
                slot.complete(PermissionDecision.DENY);
            }
        }, "axion-approval-" + request.getId());
        responder.setDaemon(true);
        responder.start();
        PermissionDecision decision;
        boolean allowed;
        boolean timedOut = false;
        boolean cancelled = false;
        try {
            decision = slot.get(approvalHandler.timeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            allowed = decision == PermissionDecision.ALLOW || decision == PermissionDecision.ALLOW_ONCE;
        } catch (java.util.concurrent.TimeoutException e) {
            decision = PermissionDecision.DENY;
            allowed = false;
            timedOut = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = PermissionDecision.DENY;
            allowed = false;
            cancelled = true;
        } catch (Exception e) {
            decision = PermissionDecision.DENY;
            allowed = false;
            cancelled = true;
        } finally {
            pendingFutures.remove(request.getId());
            layerPending.remove(request);
            responder.interrupt();
        }
        if (!allowed && !timedOut && cancelledIds.remove(request.getId())) {
            cancelled = true;
        }
        ApprovalHandler.ApprovalState state = allowed
                ? ApprovalHandler.ApprovalState.ALLOWED
                : timedOut ? ApprovalHandler.ApprovalState.TIMED_OUT
                : cancelled ? ApprovalHandler.ApprovalState.CANCELLED
                : ApprovalHandler.ApprovalState.DENIED;
        emitEvent(new AgentEvent.PermissionResolved(scId, toolName, decision, allowed, state));
        return allowed ? Outcome.PROCEED : Outcome.BLOCKED;
    }

    /**
     * Explicit host resolution (item 16): completes the pending request's
     * slot from any thread. Works with every {@link ApprovalHandler}; when
     * the handler also supports the resolver protocol it is forwarded for
     * its own bookkeeping.
     */
    public boolean resolve(String requestId, PermissionDecision decision) {
        java.util.concurrent.CompletableFuture<PermissionDecision> slot =
                requestId == null ? null : pendingFutures.get(requestId);
        if (slot == null || decision == null) {
            return false;
        }
        boolean completed = slot.complete(decision);
        if (approvalHandler != null) {
            approvalHandler.resolve(requestId, decision);
        }
        return completed;
    }

    /** Cancels a pending request (user dismissed the dialog / run ended). */
    public boolean cancel(String requestId) {
        java.util.concurrent.CompletableFuture<PermissionDecision> slot =
                requestId == null ? null : pendingFutures.get(requestId);
        if (slot == null) {
            return false;
        }
        cancelledIds.add(requestId);
        boolean completed = slot.complete(PermissionDecision.DENY);
        if (approvalHandler != null) {
            approvalHandler.cancel(requestId);
        }
        return completed;
    }

    /** The currently pending request of the layer, or {@code null}. */
    public PermissionRequest currentPendingRequest() {
        return layerPending.isEmpty() ? null : layerPending.get(layerPending.size() - 1);
    }

    /** Cancels every PENDING approval of this layer (run cancelled/ended). */
    public void cancelPendingApprovals() {
        for (PermissionRequest request : layerPending) {
            cancel(request.getId());
        }
        if (approvalHandler != null) {
            approvalHandler.cancelAll();
        }
    }

    /** The explicit resolver when the handler supports it (null otherwise). */
    public ApprovalHandler approvalResolver() {
        return approvalHandler;
    }

    /** Advisory view of the last recorded lifecycle (null while pending). */
    public ApprovalHandler.ApprovalRecord lastApprovalRecord() {
        return lastRecord;
    }

    private ApprovalHandler.ApprovalRecord lastRecordFor(PermissionRequest request) {
        ApprovalHandler.ApprovalRecord record = lastRecord;
        if (record != null && record.getRequest() == request) {
            return record;
        }
        return null;
    }

    private void recordState(ApprovalHandler.ApprovalRecord record) {
        this.lastRecord = record;
    }

    private ToolPolicy.Rule ruleFor(ToolRegistration registration) {
        if (registration.isHandoff()) {
            // Handoffs are always allowed: the model transfers control
            // between agents already configured by the host.
            return ToolPolicy.Rule.ALLOW;
        }
        String toolName = registration.spec().name().name();
        if (isShellTool(toolName)) {
            return policy.shell();
        }
        if (isNetworkTool(registration)) {
            return policy.network();
        }
        // ToolRegistration metadata (the single source of truth) is what
        // drives mutation/destructive classification.
        if (registration.isDestructive()) {
            return policy.destructive();
        }
        if (registration.isFileMutation()) {
            return policy.mutation();
        }
        // Name-based fallback only covers known mutating tool names whose
        // registration lost the original metadata; everything else stays
        // unknown.
        if (isKnownMutatingToolName(toolName)) {
            return toolName.startsWith("delete")
                    ? policy.destructive()
                    : policy.mutation();
        }
        return policy.unknown();
    }

    /** Package-private test hook exposing the classification of a tool. */
    ToolPolicy.Rule ruleForPublicForTest(ToolRegistration registration) {
        return ruleFor(registration);
    }

    /**
     * Mutating tool names from the Void registry. Used as a safety net when a
     * {@code Tool} implementation does not carry the metadata flags, so
     * mutating tools are never classified as {@code unknown} by accident.
     */
    public static boolean isKnownMutatingToolName(String toolName) {
        return "edit_file".equals(toolName)
                || "rewrite_file".equals(toolName)
                || "create_file_or_folder".equals(toolName)
                || "delete_file_or_folder".equals(toolName)
                || "apply_patch".equals(toolName);
    }

    /** Terminal/persistent-terminal tool names used by the Void registry. */
    public static boolean isShellTool(String toolName) {
        return "run_command".equals(toolName)
                || "run_persistent_command".equals(toolName)
                || "open_persistent_terminal".equals(toolName)
                || "kill_persistent_terminal".equals(toolName);
    }

    /** Remote MCP tools are treated as network tools (source-based). */
    public static boolean isNetworkTool(ToolRegistration registration) {
        String source = registration == null ? "" : registration.source();
        return source != null && source.startsWith("mcp:");
    }

    private void emitEvent(AgentEvent event) {
        if (events != null) {
            events.emit(event);
        }
    }
}
