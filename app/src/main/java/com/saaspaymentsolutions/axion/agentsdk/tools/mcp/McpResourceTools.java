package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolExecutor;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;

import org.json.JSONObject;

/**
 * Registers the three Codex MCP resource tools
 * ({@code mcp_resource_spec.rs} contracts), bridging resources/read +
 * resources/list + resources/templates/list through the same
 * {@link McpToolAdapter.McpInvoker} used for tool calls.
 *
 * <pre>
 * list_mcp_resources         → {server?, cursor?}
 * list_mcp_resource_templates→ {server?, cursor?}
 * read_mcp_resource          → {server, uri}   (both required)
 * </pre>
 */
public final class McpResourceTools {

    private McpResourceTools() {
    }

    /** Registers the three resource tools into the registry (source "core:mcp"). */
    public static void register(AxionToolRegistry registry, McpToolAdapter.McpInvoker invoker) {
        registry.register(listResourcesTool(invoker));
        registry.register(listResourceTemplatesTool(invoker));
        registry.register(readResourceTool(invoker));
    }

    private static ToolRegistration listResourcesTool(McpToolAdapter.McpInvoker invoker) {
        return ToolRegistration.builder(ToolSpec.function(
                        ToolName.plain("list_mcp_resources"),
                        "Lists resources provided by MCP servers. Resources allow servers to share data "
                                + "that provides context to language models, such as files, database schemas, "
                                + "or application-specific information. Prefer resources over web search when possible.",
                        params(
                                new String[]{"server", "MCP server name. Omit to list resources from every configured server."},
                                new String[]{"cursor", "Opaque cursor from a previous list_mcp_resources call; omit for the first page."})))
                .executor(resourceExecutor(invoker, "resources/list"))
                .source("core:mcp")
                .build();
    }

    private static ToolRegistration listResourceTemplatesTool(McpToolAdapter.McpInvoker invoker) {
        return ToolRegistration.builder(ToolSpec.function(
                        ToolName.plain("list_mcp_resource_templates"),
                        "Lists resource templates provided by MCP servers. Parameterized resource templates "
                                + "allow servers to share data that takes parameters and provides context to "
                                + "language models, such as files, database schemas, or application-specific "
                                + "information. Prefer resource templates over web search when possible.",
                        params(
                                new String[]{"server", "MCP server name. Omit to list resource templates from every configured server."},
                                new String[]{"cursor", "Opaque cursor from a previous list_mcp_resource_templates call; omit for the first page."})))
                .executor(resourceExecutor(invoker, "resources/templates/list"))
                .source("core:mcp")
                .build();
    }

    private static ToolRegistration readResourceTool(McpToolAdapter.McpInvoker invoker) {
        return ToolRegistration.builder(ToolSpec.function(
                        ToolName.plain("read_mcp_resource"),
                        "Read a specific resource from an MCP server given the server name and resource URI.",
                        params(true,
                                new String[]{"server", "MCP server name exactly as configured. Must match the 'server' field returned by list_mcp_resources."},
                                new String[]{"uri", "Resource URI to read. Must be one of the URIs returned by list_mcp_resources."})))
                .executor(resourceExecutor(invoker, "resources/read"))
                .source("core:mcp")
                .build();
    }

    private static ToolExecutor resourceExecutor(McpToolAdapter.McpInvoker invoker, String method) {
        return ctx -> {
            JSONObject args = ctx.functionArguments() == null
                    ? new JSONObject() : ctx.functionArguments();
            String server = args.optString("server", "").trim();
            String result;
            if ("resources/read".equals(method) || "resources/list".equals(method)
                    || "resources/templates/list".equals(method)) {
                // Route through the invoker bridge (server-scoped MCP method).
                result = invoker.invoke(server, method, args, ctx.scId());
            } else {
                result = "MCP error: unsupported resource method " + method + ".";
            }
            return result != null && result.startsWith("Error")
                    ? AgentToolResult.error(result)
                    : AgentToolResult.success(result);
        };
    }

    private static JSONObject params(String[]... entries) {
        return params(false, entries);
    }

    private static JSONObject params(boolean allRequired, String[]... entries) {
        try {
            JSONObject properties = new JSONObject();
            for (String[] entry : entries) {
                properties.put(entry[0], new JSONObject()
                        .put("type", "string")
                        .put("description", entry[1]));
            }
            JSONObject obj = new JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("additionalProperties", false);
            if (allRequired && entries.length > 0) {
                org.json.JSONArray required = new org.json.JSONArray();
                for (String[] entry : entries) {
                    required.put(entry[0]);
                }
                obj.put("required", required);
            }
            return obj;
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }
}