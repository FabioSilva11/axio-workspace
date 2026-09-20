package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolExposure;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;

import org.json.JSONObject;
import org.junit.Test;

/**
 * MCP dynamic-registration parity: server refresh re-registers tools
 * atomically (replace), and discovered MCP tools can be exposed as DEFERRED
 * then activated through the same tool_search path as any registry tool.
 */
public class McpDynamicRegistrationTest {

    private static JSONObject tool(String name) {
        try {
            return new JSONObject().put("name", name)
                    .put("description", "desc")
                    .put("inputSchema", new JSONObject().put("type", "object"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Test
    public void serverRefreshReplacesRegistrationAtomically() throws Exception {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("ticketing", "tickets");
        mcp.addTool("ticketing", tool("create_ticket"));
        mcp.registerTools(registry, (s, t, a, sc) -> "v1");

        // Refresh: same server now exposes a NEW tool set; the old tool is
        // replaced, the new one added — no DuplicateToolException.
        McpServerRegistry refreshed = new McpServerRegistry();
        refreshed.addServer("ticketing", "tickets");
        refreshed.addTool("ticketing", tool("create_ticket"));
        refreshed.addTool("ticketing", tool("read_ticket"));
        ToolRegistration replaced = registry.registerOrReplace(McpToolAdapter.toRegistration(
                McpToolIdentity.of("ticketing", "create_ticket"), "desc",
                new JSONObject().put("type", "object"), (s, t, a, sc) -> "v2", ""));
        assertNotNull("old create_ticket must have existed", replaced);
        assertEquals("v2", executeViaRouter(registry, "mcp__ticketing.create_ticket"));
    }

    @Test
    public void deferredMcpToolsDiscoverableThenCallable() {
        AxionToolRegistry registry = new AxionToolRegistry();
        // Core provider includes tool_search; register it too.
        com.saaspaymentsolutions.axion.agentsdk.tools.WorkspaceToolProvider
                .registerCoreTools(registry, null);

        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("ticketing", "tickets");
        mcp.addTool("ticketing", tool("create_ticket"));
        McpToolAdapter.McpInvoker invoker = (s, t, a, scId) -> "ticket-created";
        for (McpServerRegistry.McpServer server : mcp.servers()) {
            for (JSONObject raw : server.tools()) {
                McpToolIdentity identity = McpToolIdentity.of(server.name(), raw.optString("name"));
                ToolRegistration deferredReg = ToolRegistration.builder(ToolSpec.function(
                                ToolName.namespaced(identity.namespace(), identity.toolName()),
                                raw.optString("description"),
                                McpToolAdapter.normalizeParameters(raw.optJSONObject("inputSchema"))))
                        .executor(ctx -> {
                            com.saaspaymentsolutions.axion.agentsdk.AgentToolResult out =
                                    McpToolAdapter.toRegistration(identity,
                                            raw.optString("description"),
                                            raw.optJSONObject("inputSchema"),
                                            invoker, "").executor().execute(ctx);
                            return out;
                        })
                        .exposure(ToolExposure.deferred())
                        .source("mcp:" + server.name())
                        .build();
                registry.register(deferredReg);
            }
        }

        ToolRegistration deferred = registry.get("mcp__ticketing.create_ticket");
        assertEquals(ToolExposure.Kind.DEFERRED, deferred.exposure().kind());
        assertFalse(registry.modelVisibleTools().stream()
                .anyMatch(r -> r.spec().qualifiedName().equals("mcp__ticketing.create_ticket")));

        // tool_search discovers + activates the deferred MCP tool.
        ToolRegistration search = registry.get("tool_search");
        String result = executeViaRouter(registry, "tool_search",
                "{\"query\":\"create_ticket\"}");
        assertFalse(result.startsWith("Error"));
        assertTrue(registry.isDeferredActivated(
                com.saaspaymentsolutions.axion.agentsdk.tools.ToolName
                        .parse("mcp__ticketing.create_ticket")));

        // Now callable via the router.
        assertEquals("ticket-created", executeViaRouter(registry, "mcp__ticketing.create_ticket"));
    }

    private static String executeViaRouter(AxionToolRegistry registry, String toolName) {
        AxionToolRouter router = new AxionToolRouter(registry, null,
                new EventStream(r -> r.run(), 8));
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("call", toolName, "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        return routed.result().output();
    }

    private static String executeViaRouter(AxionToolRegistry registry, String toolName, String args) {
        AxionToolRouter router = new AxionToolRouter(registry, null,
                new EventStream(r -> r.run(), 8));
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("call", toolName, args),
                "sc", RunContext.bare("sc", "assistant", null), null);
        return routed.result().output();
    }
}