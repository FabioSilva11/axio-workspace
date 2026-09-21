package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.AgentTool;

import org.json.JSONObject;

/**
 * Bridges a legacy {@link AgentTool} into the registry-backed architecture:
 * its schema/description become the {@link ToolSpec}, its placeholder
 * {@link ToolExecutor} delegates to {@code AgentTool.execute} at runtime, and
 * its policy flags flow into the {@link ToolRegistration}. The legacy tool
 * thus becomes a normal registry citizen — the model catalog comes exclusively
 * from {@code modelVisibleTools()} and the {@link AxionToolRouter} executes it.
 */
public final class LegacyToolAdapter {

    private LegacyToolAdapter() {
    }

    /** Builds a DIRECT FUNCTION registration for a legacy agent tool. */
    public static ToolRegistration register(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool is required");
        }
        String name = tool.name() == null ? "" : tool.name();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a legacy tool must have a non-empty name");
        }
        JSONObject parameters = tool.parameters();
        if (parameters == null) {
            parameters = new JSONObject();
        }
        return ToolRegistration.builder(ToolSpec.function(
                        ToolName.plain(name),
                        tool.description() == null ? "" : tool.description(),
                        parameters))
                .executor(new AgentToolExecutor(tool))
                .source("legacy")
                .requiresApproval(tool.requiresApproval())
                .fileMutation(tool.isFileMutation())
                .destructive(tool.isDestructive())
                .build();
    }
}