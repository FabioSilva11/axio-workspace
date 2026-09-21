package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP lifecycle (audit item): syncTools/removeServer keep the live
 * {@link AxionToolRegistry} reconciled with the server's tools/list — tools
 * added, updated and removed on refresh, and a removed tool stops routing —
 * plus the sanitized-alias index never pollutes the distinct-server iteration.
 */
public class McpLifecycleTest {

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

    private static List<String> serverToolNames(McpServerRegistry.McpServer server) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JSONObject raw : server.tools()) {
            names.add(raw.optString("name", ""));
        }
        Collections.sort(names);
        return names;
    }

    @Test
    public void refreshAddsUpdatesAndRemovesTools() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");

        assertTrue(mcp.syncTools(registry, fakeInvoker(), "github", Arrays.asList(
                tool("search", "Search code"),
                tool("create_issue", "Create an issue"))));
        assertTrue(registry.contains("mcp__github.search"));
        assertTrue(registry.contains("mcp__github.create_issue"));

        // Second tools/list: create_issue removed, list_pulls added, search kept.
        assertTrue(mcp.syncTools(registry, fakeInvoker(), "github", Arrays.asList(
                tool("search", "Search code"),
                tool("list_pulls", "List pull requests"))));

        assertTrue("search survives the refresh", registry.contains("mcp__github.search"));
        assertTrue("list_pulls added", registry.contains("mcp__github.list_pulls"));
        assertFalse("create_issue removed from the live registry",
                registry.contains("mcp__github.create_issue"));

        McpServerRegistry.McpServer server = mcp.getServer("github");
        assertNotNull(server);
        assertEquals(Arrays.asList("list_pulls", "search"), serverToolNames(server));
        assertEquals(2, mcp.toolCount());
        assertEquals(1, mcp.servers().size());
    }

    @Test
    public void removedToolIsNoLongerRoutable() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");
        AtomicInteger calls = new AtomicInteger();
        McpToolAdapter.McpInvoker invoker = (server, tool, args, scId) -> {
            calls.incrementAndGet();
            return "{}";
        };

        mcp.syncTools(registry, fakeInvoker(),
                "github", Arrays.asList(tool("search", "Search"), tool("create_issue", "Create")));
        mcp.syncTools(registry, invoker, "github",
                Collections.singletonList(tool("search", "Search")));

        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);
        AxionToolRouter.Routed stale = router.route(
                new AxionToolRouter.Route("call_old", "mcp__github.create_issue", "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertTrue("removed tool must fail closed", stale.result().isError());
        assertEquals("removed tool must never reach the invoker",
                0, calls.get());

        AxionToolRouter.Routed live = router.route(
                new AxionToolRouter.Route("call_new", "mcp__github.search", "{}"),
                "sc", RunContext.bare("sc", "assistant", null), null);
        assertEquals(1, calls.get());
        assertFalse(live.result().isError());
    }

    @Test
    public void removeServerDropsToolsAndNamespace() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("github", "GitHub");
        mcp.syncTools(registry, fakeInvoker(), "github", Arrays.asList(
                tool("search", "S"), tool("create_issue", "C")));
        assertTrue(registry.contains("mcp__github.search"));
        assertNotNull("namespace entry registered after sync",
                registry.get(McpToolIdentity.NAMESPACE_PREFIX + "github"));

        assertTrue(mcp.removeServer("github", registry));
        assertFalse(registry.contains("mcp__github.search"));
        assertFalse(registry.contains("mcp__github.create_issue"));
        assertNull("namespace removed with the server",
                registry.get(McpToolIdentity.NAMESPACE_PREFIX + "github"));
        assertFalse(mcp.hasServer("github"));
        assertEquals(0, mcp.toolCount());
        assertTrue(mcp.isEmpty());
    }

    @Test
    public void unknownServerOpsAreNoops() {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        assertFalse(mcp.syncTools(registry, fakeInvoker(), "missing",
                Collections.emptyList()));
        assertFalse(mcp.removeServer("missing", registry));
        assertFalse(mcp.hasServer("missing"));
    }

    @Test
    public void sanitizedAliasIsLookupOnlyAndNeverDuplicatesServers() {
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("My Ticket App", "tickets");
        // Lookup works through the sanitized name…
        assertTrue(mcp.hasServer("My_Ticket_App"));
        assertNotNull(mcp.getServer("My_Ticket_App"));
        // …but iteration stays distinct (no mcp__index duplicate object).
        assertEquals(1, mcp.servers().size());
        assertEquals("My Ticket App", mcp.servers().get(0).name());

        AxionToolRegistry registry = new AxionToolRegistry();
        mcp.addTool("My_Ticket_App", tool("create", "Create"));
        mcp.registerTools(registry, fakeInvoker());
        assertTrue(registry.contains("mcp__My_Ticket_App.create"));
        assertEquals("single server, single registration of its tools",
                1, mcp.toolCount());
    }

    private static McpToolAdapter.McpInvoker fakeInvoker() {
        return (server, tool, args, scId) -> "{}";
    }
}