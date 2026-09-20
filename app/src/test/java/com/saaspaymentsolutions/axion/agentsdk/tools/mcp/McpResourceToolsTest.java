package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.Type;

import org.json.JSONObject;
import org.junit.Test;

/**
 * MCP resource parity (Codex mcp_resource_spec.rs): the three resource tools
 * — list_mcp_resources, list_mcp_resource_templates, read_mcp_resource — are
 * registered as codex-core FUNCTION tools bridging resources/* through the
 * same invoker; read_mcp_resource requires server + uri.
 */
public class McpResourceToolsTest {

    @Test
    public void resourceToolsRegisteredWithCodexNames() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpResourceTools.register(registry, fakeInvoker());

        assertTrue(registry.contains("list_mcp_resources"));
        assertTrue(registry.contains("list_mcp_resource_templates"));
        assertTrue(registry.contains("read_mcp_resource"));
        assertEquals("core:mcp", registry.get("list_mcp_resources").source());
        assertEquals(Type.FUNCTION, registry.get("list_mcp_resources").spec().type());
    }

    @Test
    public void readResourceRequiresServerAndUri() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpResourceTools.register(registry, fakeInvoker());
        JSONObject params = registry.get("read_mcp_resource").spec().parameters();
        org.json.JSONArray required = params.optJSONArray("required");
        assertNotNull(required);
        assertTrue(required.length() == 2);
    }

    @Test
    public void readResourceBridgesResourcesReadThroughInvoker() {
        AxionToolRegistry registry = new AxionToolRegistry();
        final String[] captured = new String[3];
        McpToolAdapter.McpInvoker invoker = (server, toolName, args, scId) -> {
            captured[0] = server;
            captured[1] = toolName;
            captured[2] = args.optString("uri", "");
            return "RESOURCE_CONTENT";
        };
        McpResourceTools.register(registry, invoker);

        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("c", "read_mcp_resource",
                        "{\"server\":\"github\",\"uri\":\"github://readme\"}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertFalse(routed.result().isError());
        assertEquals("github", captured[0]);
        assertEquals("resources/read", captured[1]);
        assertEquals("github://readme", captured[2]);
        assertEquals("RESOURCE_CONTENT", routed.result().output());
    }

    @Test
    public void listResourcesBridgesResourcesList() throws Exception {
        AxionToolRegistry registry = new AxionToolRegistry();
        final java.util.List<String> calls = new java.util.ArrayList<>();
        McpToolAdapter.McpInvoker invoker = (server, toolName, args, scId) -> {
            calls.add(toolName);
            return "[]";
        };
        McpResourceTools.register(registry, invoker);

        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("c", "list_mcp_resources", "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertFalse(routed.result().isError());
        assertEquals(java.util.List.of("resources/list"), calls);
    }

    private static McpToolAdapter.McpInvoker fakeInvoker() {
        return (server, toolName, args, scId) -> "[]";
    }
}