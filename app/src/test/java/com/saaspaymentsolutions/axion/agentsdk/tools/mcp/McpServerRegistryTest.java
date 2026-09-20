package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * MCP server-registry parity: servers hold raw tool/resource definitions, a
 * duplicate tool name WITHIN one server is a malformed catalog (Codex errors),
 * the same name across two servers stays distinct, and counts are faithful.
 */
public class McpServerRegistryTest {

    private static JSONObject tool(String name) {
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("description", "tool " + name)
                    .put("inputSchema", new JSONObject().put("type", "object"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Test
    public void addServerAndCount() {
        McpServerRegistry registry = new McpServerRegistry();
        registry.addServer("github", "GitHub tools");
        assertFalse(registry.isEmpty());
        assertEquals(1, registry.servers().size());
        assertEquals("github", registry.servers().get(0).name());
    }

    @Test
    public void addToolAndResource() throws Exception {
        McpServerRegistry registry = new McpServerRegistry();
        registry.addServer("ticketing", "tickets");
        assertTrue(registry.addTool("ticketing", tool("create_ticket")));
        assertTrue(registry.addTool("ticketing", tool("read_ticket")));
        assertTrue(registry.addResource("ticketing",
                new JSONObject().put("uri", "ticket://1")));
        assertEquals(2, registry.toolCount());
        assertEquals(2, registry.servers().get(0).tools().size());
        assertEquals(1, registry.servers().get(0).resources().size());
    }

    @Test
    public void addToolToUnknownServerFails() {
        McpServerRegistry registry = new McpServerRegistry();
        assertFalse(registry.addTool("missing", tool("x")));
    }

    @Test
    public void duplicateToolWithinServerThrows() {
        McpServerRegistry registry = new McpServerRegistry();
        registry.addServer("ticketing", "tickets");
        registry.addTool("ticketing", tool("create_ticket"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.addTool("ticketing", tool("create_ticket")));
    }

    @Test
    public void toolWithoutNameThrows() {
        McpServerRegistry registry = new McpServerRegistry();
        registry.addServer("ticketing", "tickets");
        assertThrows(IllegalArgumentException.class,
                () -> registry.addTool("ticketing", new JSONObject().put("description", "no name")));
    }

    @Test
    public void serverNameEmptyRejected() {
        McpServerRegistry registry = new McpServerRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.addServer("  ", "x"));
    }

    @Test
    public void namespaceForSanitizesServerName() {
        McpServerRegistry registry = new McpServerRegistry();
        registry.addServer("My Ticket App", "src");
        // Codex parity: sanitization collapses invalid characters without
        // lowercasing (qualified_mcp_tool_name_prefix, mcp.rs).
        assertEquals("mcp__My_Ticket_App", registry.namespaceFor("My Ticket App"));
    }
}