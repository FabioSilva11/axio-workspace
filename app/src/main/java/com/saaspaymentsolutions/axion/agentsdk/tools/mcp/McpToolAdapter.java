package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolExecutor;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;

import org.json.JSONObject;

/**
 * Adapts one raw MCP tool definition (as returned by {@code tools/list}:
 * {@code name}, {@code description}, {@code inputSchema}) into a
 * {@link ToolRegistration} whose {@link ToolSpec} is a FUNCTION tool named
 * {@code mcp__<server>.<tool>} (Codex {@code mcp_tool_to_responses_api_tool}
 * parity).
 *
 * <p>The executor bridges to the host channel (an Android-safe HTTP MCP
 * client) through a {@link McpInvoker}; the adapter itself stays pure so the
 * same registration builder works for tests, legacy bridge and future
 * stdio/SSE clients.</p>
 */
public final class McpToolAdapter {

    /** Functional bridge executing one MCP tool call. */
    public interface McpInvoker {
        /**
         * Returns the raw {@code tools/call} result, or an error-prefixed
         * string. Never throws for call failures.
         */
        String invoke(String serverName, String toolName, JSONObject arguments, String scId);
    }

    private McpToolAdapter() {
    }

    /**
     * Builds a FUNCTION registration for a raw MCP tool definition.
     *
     * @param identity   resolved model identity ({@code mcp__server.tool})
     * @param description raw tool description ({@code ""} allowed)
     * @param inputSchema raw MCP input schema; {@code null} → default empty object
     * @param invoker    bridge to the MCP transport
     * @param scId       session id bound to the executor (may be {@code ""})
     */
    public static ToolRegistration toRegistration(McpToolIdentity identity,
                                                  String description,
                                                  JSONObject inputSchema,
                                                  McpInvoker invoker,
                                                  String scId) {
        ToolName name = ToolName.namespaced(identity.namespace(), identity.toolName());
        JSONObject parameters = normalizeParameters(inputSchema);
        ToolExecutor executor = ctx -> {
            JSONObject args = ctx.functionArguments();
            String result = invoker.invoke(
                    identity.serverName(), identity.toolName(),
                    args == null ? new JSONObject() : args, ctx.scId());
            return result != null && result.startsWith("Error")
                    ? AgentToolResult.error(result)
                    : AgentToolResult.success(result);
        };
        return ToolRegistration.builder(ToolSpec.function(
                        name, description, parameters))
                .executor(executor)
                .source("mcp:" + identity.serverName())
                .build();
    }

    /** Normalises an MCP input schema into a valid json-schema parameter object. */
    public static JSONObject normalizeParameters(JSONObject inputSchema) {
        if (inputSchema == null || inputSchema.length() == 0) {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject())
                        .put("additionalProperties", false);
            } catch (org.json.JSONException e) {
                return new JSONObject();
            }
        }
        try {
            JSONObject copy = new JSONObject(inputSchema.toString());
            if (!copy.has("type")) {
                copy.put("type", "object");
            }
            if (!copy.has("properties")) {
                copy.put("properties", new JSONObject());
            }
            if (!copy.has("additionalProperties")) {
                // MCP servers routinely omit this; default to false like Codex.
                copy.put("additionalProperties", false);
            }
            return copy;
        } catch (org.json.JSONException e) {
            return inputSchema;
        }
    }
}