package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

/**
 * Executes {@link AgentTool}s with uniform exception handling, mirroring the
 * "tool errors are results" philosophy of openai-agents-js.
 */
final class ToolExecutor {

    AgentToolResult run(AgentTool tool, RunContext context, JSONObject args) {
        try {
            return tool.execute(context, args);
        } catch (Exception e) {
            String message = e.getMessage();
            return AgentToolResult.error("Error: "
                    + (message == null ? e.getClass().getSimpleName() : message));
        }
    }

    /** Parses raw JSON arguments defensively; invalid JSON becomes an error result. */
    JSONObject parseArguments(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value);
        } catch (Exception e) {
            return null;
        }
    }
}
