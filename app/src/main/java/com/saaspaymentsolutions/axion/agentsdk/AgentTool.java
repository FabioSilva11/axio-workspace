package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

/**
 * A tool owned by an {@link Agent}, mirroring {@code AgentTool} from
 * openai-agents-js. Tools are declared per-agent (not globally), are described
 * by a JSON schema and receive a typed result.
 *
 * <p>Adapters that expose existing {@code com.saaspaymentsolutions.axion.Tool}
 * instances (the Void-ported registry) live in {@code WorkspaceAgents}.</p>
 */
public interface AgentTool {

    /** Stable tool name as the model sees it (function name). */
    String name();

    /** Human-readable description sent to the model. */
    String description();

    /**
     * JSON schema ({@code {"type":"object","properties":{...}}} envelope) for
     * the tool arguments. An empty object means "no parameters".
     */
    JSONObject parameters();

    /**
     * Executes the tool. Implementations must never throw for model-caused
     * input problems — return {@link AgentToolResult#error(String)} instead;
     * unexpected crashes are converted by the {@link Runner}.
     */
    AgentToolResult execute(RunContext context, JSONObject args) throws Exception;

    /** True when the host should ask the user before running (mutation tools). */
    default boolean requiresApproval() {
        return false;
    }

    /** True when the tool writes/deletes workspace files (policy category). */
    default boolean isFileMutation() {
        return false;
    }

    /** True when the tool's effect is irreversible (policy category). */
    default boolean isDestructive() {
        return false;
    }
}
