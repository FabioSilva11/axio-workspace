package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

/**
 * An {@link AgentTool} that resolves its own {@link ToolPolicy} rule at
 * check time (used by shell and MCP wrappers whose category is intrinsic).
 * The {@link PermissionLayer} consults this before falling back to the
 * interface flags.
 */
public interface SandboxAwareTool extends AgentTool {

    /** The policy rule that governs this tool's execution. */
    ToolPolicy.Rule policyRule();

    /**
     * Hook invoked before execution; return an error result to block the
     * call (sandbox violation). Default: no additional constraints.
     */
    default AgentToolResult preExecute(ToolCall call) {
        return null;
    }
}
