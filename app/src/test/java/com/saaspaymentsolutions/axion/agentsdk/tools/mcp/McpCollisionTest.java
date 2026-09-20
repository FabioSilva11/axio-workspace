package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;

import org.json.JSONObject;
import org.junit.Test;

/**
 * MCP collision parity: two servers exposing the same tool name are TWO
 * distinct model tools ({@code mcp__server_a.search} vs
 * {@code mcp__server_b.search}); both stay registered and route independently.
 */
public class McpCollisionTest {

    private static JSONObject tool(String name) {
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("description", "desc")
                    .put("inputSchema", new JSONObject().put("type", "object"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Test
    public void sameToolNameAcrossServersIsDistinct() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("server_a", "a");
        mcp.addServer("server_b", "b");
        mcp.addTool("server_a", tool("search"));
        mcp.addTool("server_b", tool("search"));
        mcp.registerTools(registry, (server, toolName, args, scId) -> "RESULT:" + server);

        assertTrue(registry.contains("mcp__server_a.search"));
        assertTrue(registry.contains("mcp__server_b.search"));
        assertEquals(2, registry.size());
        assertEquals("mcp:server_a", registry.get("mcp__server_a.search").source());
        assertEquals("mcp:server_b", registry.get("mcp__server_b.search").source());
    }

    @Test
    public void bothCollidingToolsRouteToTheirOwnServer() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("server_a", "a");
        mcp.addServer("server_b", "b");
        mcp.addTool("server_a", tool("search"));
        mcp.addTool("server_b", tool("search"));
        mcp.registerTools(registry, (server, toolName, args, scId) -> "RESULT:" + server);

        AxionToolRouter router = new AxionToolRouter(registry, null,
                new EventStream(r -> r.run(), 8));
        String fromA = router.route(new AxionToolRouter.Route("c_a", "mcp__server_a.search", "{}"),
                "sc", RunContext.bare("sc", "a", null), null).result().output();
        String fromB = router.route(new AxionToolRouter.Route("c_b", "mcp__server_b.search", "{}"),
                "sc", RunContext.bare("sc", "b", null), null).result().output();
        assertEquals("RESULT:server_a", fromA);
        assertEquals("RESULT:server_b", fromB);
    }

    @Test
    public void sanitizedNamesStayDistinctWhenServerNamesDiffOnlyInCase() {
        assertEquals("mcp__github", McpToolIdentity.of("github", "x").namespace());
        assertEquals("mcp__GITHUB", McpToolIdentity.of("GITHUB", "x").namespace());
        assertFalse(McpToolIdentity.of("github", "x")
                .qualifiedName().equals(McpToolIdentity.of("GITHUB", "x").qualifiedName()));
    }
}