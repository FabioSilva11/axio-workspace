package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * MCP + tool_search integration parity: deferred MCP tools are discovered by
 * tool_search (query matches namespace + tool), activated, and then callable,
 * while still excluded from the model catalog.
 */
public class McpToolSearchTest {

    private static JSONObject tool(String name, String description) {
        try {
            return new JSONObject().put("name", name)
                    .put("description", description)
                    .put("inputSchema", new JSONObject().put("type", "object"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private AxionToolRegistry buildDeferredMcp() {
        AxionToolRegistry registry = new AxionToolRegistry();
        com.saaspaymentsolutions.axion.agentsdk.tools.WorkspaceToolProvider
                .registerCoreTools(registry, null);
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("ticketing", "Ticketing");
        mcp.addTool("ticketing", tool("create_ticket", "Create a new support ticket."));
        mcp.addTool("ticketing", tool("list_tickets", "List existing support tickets."));
        for (McpServerRegistry.McpServer server : mcp.servers()) {
            for (JSONObject raw : server.tools()) {
                McpToolIdentity identity = McpToolIdentity.of(server.name(), raw.optString("name"));
                ToolRegistration deferredReg = ToolRegistration.builder(ToolSpec.function(
                                com.saaspaymentsolutions.axion.agentsdk.tools.ToolName.namespaced(
                                        identity.namespace(), identity.toolName()),
                                raw.optString("description"),
                                McpToolAdapter.normalizeParameters(raw.optJSONObject("inputSchema"))))
                        .executor(McpToolAdapter.toRegistration(identity,
                                raw.optString("description"), raw.optJSONObject("inputSchema"),
                                (s, t, a, scId) -> "handled:" + t, "").executor())
                        .exposure(com.saaspaymentsolutions.axion.agentsdk.tools.ToolExposure.deferred())
                        .source("mcp:" + server.name())
                        .build();
                registry.register(deferredReg);
            }
        }
        return registry;
    }

    @Test
    public void deferredMcpToolsAreAbsentFromCatalog() {
        AxionToolRegistry registry = buildDeferredMcp();
        assertFalse(registry.modelVisibleTools().stream()
                .anyMatch(r -> r.spec().qualifiedName().startsWith("mcp__")));
        assertEquals(2, registry.deferredTools().size());
    }

    @Test
    public void toolSearchFindsMcpToolByNamespaceAndTool() throws Exception {
        AxionToolRegistry registry = buildDeferredMcp();
        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);

        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("s1", "tool_search",
                        "{\"query\":\"mcp__ticketing.create_ticket\"}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertFalse(routed.result().isError());
        JSONObject body = new JSONObject(routed.result().output());
        JSONArray tools = body.getJSONArray("tools");
        assertTrue(tools.length() >= 1);
        String found = tools.getJSONObject(0).getString("name");
        assertEquals("mcp__ticketing.create_ticket", found);
        assertTrue(registry.isDeferredActivated(ToolName.parse(found)));
    }

    @Test
    public void activatedMcpToolRoutesThroughRouter() {
        AxionToolRegistry registry = buildDeferredMcp();
        AxionToolRouter router = new AxionToolRouter(registry, null,
                new EventStream(r -> r.run(), 8));
        router.route(new AxionToolRouter.Route("s2", "tool_search",
                        "{\"query\":\"list_tickets\"}"),
                "sc", RunContext.bare("sc", "assistant", null), null);

        AxionToolRouter.Routed call = router.route(
                new AxionToolRouter.Route("c3", "mcp__ticketing.list_tickets", "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertFalse(call.result().isError());
        assertEquals("handled:list_tickets", call.result().output());
    }

    @Test
    public void unactivatedMcpToolIsRejectedByRouter() {
        AxionToolRegistry registry = buildDeferredMcp();
        AxionToolRouter router = new AxionToolRouter(registry, null,
                new EventStream(r -> r.run(), 8));
        AxionToolRouter.Routed call = router.route(
                new AxionToolRouter.Route("c4", "mcp__ticketing.create_ticket", "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertTrue(call.result().isError());
        assertTrue(call.result().output().contains("deferred"));
    }
}