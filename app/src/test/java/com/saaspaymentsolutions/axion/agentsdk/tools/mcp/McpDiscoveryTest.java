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
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpecSerializer;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.Type;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP discovery parity: raw tools/list definitions fold into the registry as
 * {@code mcp__server.tool} FUNCTION tools whose executor bridges through the
 * McpInvoker; namespaced (mcp__server) tools are grouped under a namespace
 * declaration by the serializer.
 */
public class McpDiscoveryTest {

    private static JSONObject tool(String name, String description) {
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("inputSchema", new JSONObject().put("type", "object"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject toolWithSchema(String name, String description, JSONObject schema) {
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("inputSchema", schema);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Test
    public void registerToolsFoldsServerToolsIntoRegistry() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");
        mcp.addTool("github", tool("search", "Search code"));
        mcp.addTool("github", tool("create_issue", "Create an issue"));
        mcp.registerTools(registry, fakeInvoker());

        assertTrue(registry.contains("mcp__github.search"));
        assertTrue(registry.contains("mcp__github.create_issue"));
        assertEquals("mcp:github", registry.get("mcp__github.search").source());
        assertEquals(Type.FUNCTION, registry.get("mcp__github.search").spec().type());
    }

    @Test
    public void invokerBridgeDeliversArguments() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");
        mcp.addTool("github", tool("search", "Search code"));
        AtomicInteger calls = new AtomicInteger();
        McpToolAdapter.McpInvoker invoker =
                (server, toolName, args, scId) -> {
                    calls.incrementAndGet();
                    assertEquals("github", server);
                    assertEquals("search", toolName);
                    assertEquals("code", args.optString("query"));
                    return "[{\"repo\":\"r\"}]";
                };
        mcp.registerTools(registry, invoker);

        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("call_s", "mcp__github.search", "{\"query\":\"code\"}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertEquals(1, calls.get());
        assertFalse(routed.result().isError());
        assertEquals("[{\"repo\":\"r\"}]", routed.result().output());
    }

    @Test
    public void namespacedToolsGroupUnderNamespaceDeclaration() throws Exception {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");
        mcp.addTool("github", tool("search", "Search"));
        mcp.registerTools(registry, fakeInvoker());
        mcp.registerNamespaces(registry);

        assertEquals(1, registry.namespaceTools().size());
        JSONArray catalog = ToolSpecSerializer.toCatalog(registry.modelVisibleTools());
        assertEquals(1, catalog.length());
        assertEquals("namespace", catalog.getJSONObject(0).getString("type"));
        assertEquals("mcp__github", catalog.getJSONObject(0).getString("name"));
        assertEquals(1, catalog.getJSONObject(0).getJSONArray("tools").length());
    }

    @Test
    public void missingInputSchemaDefaultsToEmptyObject() throws Exception {
        JSONObject noSchema = new JSONObject().put("name", "bare").put("description", "no schema");
        ToolRegistration reg = McpToolAdapter.toRegistration(
                McpToolIdentity.of("github", "bare"), "no schema", null,
                fakeInvoker(), "");
        JSONObject params = reg.spec().parameters();
        assertEquals("object", params.optString("type"));
        assertNotNull(params.optJSONObject("properties"));
        assertFalse(params.optBoolean("additionalProperties", true));
    }

    private static McpToolAdapter.McpInvoker fakeInvoker() {
        return (server, toolName, args, scId) -> "{}";
    }
}