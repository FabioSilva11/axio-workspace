package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

/**
 * Observability callbacks for a {@link Runner} run. All methods are optional;
 * a no-op default instance is used when none is supplied.
 */
public interface RunListeners {

    /** A new loop iteration started for this agent. */
    default void onTurnStart(Agent agent, int turn) {
    }

    /** The host should approve this tool call; return false to reject. */
    default boolean onToolApproval(AgentTool tool, ToolCall call) {
        return true;
    }

    /** A tool execution is about to start. */
    default void onToolStart(AgentTool tool, ToolCall call) {
    }

    /** A tool finished (success or typed error). */
    default void onToolFinish(AgentTool tool, ToolCall call, AgentToolResult result) {
    }

    /** An input guardrail blocked the run before the first LLM call. */
    default void onGuardrailBlocked(Agent agent, GuardrailResult result) {
    }

    /** An output guardrail passed for this final output. */
    default void onOutputGuardrailPassed(Agent agent, GuardrailResult result) {
    }
}
