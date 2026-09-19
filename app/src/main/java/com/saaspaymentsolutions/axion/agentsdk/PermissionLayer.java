package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

/**
 * Enforces the {@link ToolPolicy} on every tool call before execution,
 * porting Codex's decision model (Skip / NeedsApproval / Forbidden) to the
 * Axion runtime: DENY short-circuits, ASK_USER parks the run on the
 * {@link ApprovalHandler}, ALLOW passes through. The model never decides.
 */
public final class PermissionLayer {

    private final ToolPolicy policy;
    private final ApprovalHandler approvalHandler;
    private final EventStream events;

    /** Currently parked request (null when no approval is pending). */
    private volatile PermissionRequest pendingRequest;
    private volatile PermissionDecision lastDecision;

    /** The request awaiting the user, or {@code null}. */
    public PermissionRequest currentPendingRequest() {
        return pendingRequest;
    }

    /** Advisory view of the decision for {@code request}: null while pending. */
    public PermissionDecision lastDecisionFor(PermissionRequest request) {
        return request != null && request == pendingRequest ? null : lastDecision;
    }

    public PermissionLayer(ToolPolicy policy, ApprovalHandler approvalHandler, EventStream events) {
        this.policy = policy == null ? ToolPolicy.permissive() : policy;
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
    public Outcome check(AgentTool tool, ToolCall call, String scId) {
        if (tool == null) {
            return Outcome.PROCEED; // unknown tool handled by the caller
        }
        ToolPolicy.Rule rule = ruleFor(tool);
        if (rule == ToolPolicy.Rule.DENY) {
            String reason = "Policy denies tool '" + tool.name() + "' in this mode.";
            emitEvent(new AgentEvent.PolicyDenied(scId, tool.name(), reason));
            return Outcome.BLOCKED;
        }
        if (rule == ToolPolicy.Rule.ALLOW) {
            return Outcome.PROCEED;
        }
        // ASK_USER
        if (approvalHandler == null) {
            // No host to ask: fail closed instead of silently executing.
            String reason = "Tool '" + tool.name() + "' requires approval but no approval handler is configured.";
            emitEvent(new AgentEvent.PolicyDenied(scId, tool.name(), reason));
            return Outcome.BLOCKED;
        }
        PermissionRequest request = new PermissionRequest(
                "perm_" + java.util.UUID.randomUUID(), tool.name(), call,
                "The policy requires user confirmation to execute '" + tool.name() + "'.");
        pendingRequest = request;
        lastDecision = null;
        emitEvent(new AgentEvent.ApprovalRequired(scId, tool.name(), call, request));
        PermissionDecision decision;
        boolean allowed;
        try {
            decision = approvalHandler.awaitDecision(request);
            allowed = decision == PermissionDecision.ALLOW || decision == PermissionDecision.ALLOW_ONCE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = PermissionDecision.DENY;
            allowed = false;
        } catch (Exception e) {
            decision = PermissionDecision.DENY;
            allowed = false;
        }
        lastDecision = decision;
        pendingRequest = null;
        emitEvent(new AgentEvent.PermissionResolved(scId, tool.name(),
                allowed ? PermissionDecision.ALLOW_ONCE : PermissionDecision.DENY, allowed));
        if (!allowed) {
            return Outcome.BLOCKED;
        }
        return Outcome.PROCEED;
    }

    private ToolPolicy.Rule ruleFor(AgentTool tool) {
        if (tool instanceof SandboxAwareTool) {
            return ((SandboxAwareTool) tool).policyRule();
        }
        if (isShellTool(tool.name())) {
            return policy.shell();
        }
        if (isNetworkTool(tool)) {
            return policy.network();
        }
        // Tool metadata (from the registry wrapper or the SDK tool) is the
        // single source of truth for mutation/destructive classification.
        if (tool.isDestructive()) {
            return policy.destructive();
        }
        if (tool.isFileMutation()) {
            return policy.mutation();
        }
        // Name-based fallback only covers known mutating tool names whose
        // adapter lost the original metadata; everything else stays unknown.
        if (isKnownMutatingToolName(tool.name())) {
            return tool.name().startsWith("delete")
                    ? policy.destructive()
                    : policy.mutation();
        }
        return policy.unknown();
    }

    /** Package-private test hook exposing the classification of a tool. */
    ToolPolicy.Rule ruleForPublicForTest(AgentTool tool) {
        return ruleFor(tool);
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

    /** Remote MCP tools are treated as network tools. */
    public static boolean isNetworkTool(AgentTool tool) {
        return tool instanceof McpAgentTool;
    }

    private void emitEvent(AgentEvent event) {
        if (events != null) {
            events.emit(event);
        }
    }
}
