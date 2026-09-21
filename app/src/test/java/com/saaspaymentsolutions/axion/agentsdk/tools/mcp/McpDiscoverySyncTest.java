package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.EventStream;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolCatalog;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpecSerializer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP discovery → live registry (audit item): a real-shaped
 * {@code tools/list} payload (per the MCP spec each tool carries
 * {@code name}/{@code description}/{@code inputSchema}) flows through
 * {@code McpServerRegistry.syncTools} into the {@link AxionToolRegistry} and
 * the {@link ToolCatalog}, then executes through the {@link AxionToolRouter}
 * against the invoker — the same single path any host channel would use.
 */
public class McpDiscoverySyncTest {

    /** Real-shaped tools/list result for a minimal files server. */
    private static JSONArray toolsList() throws Exception {
        return new JSONArray()
                .put(new JSONObject()
                        .put("name", "read_file")
                        .put("description", "Read a file from the workspace")
                        .put("inputSchema", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("path", new JSONObject()
                                                .put("type", "string")
                                                .put("description", "File path")))
                                .put("required", new JSONArray().put("path"))))
                .put(new JSONObject()
                        .put("name", "list_directory")
                        .put("description", "List a directory")
                        .put("inputSchema", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("dir", new JSONObject()
                                                .put("type", "string")))));
    }

    @Test
    public void toolsListFlowsIntoRegistryCatalogAndRouter() throws Exception {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("files", "Workspace files");

        AtomicReference<String> lastCall = new AtomicReference<>();
        McpToolAdapter.McpInvoker invoker = (server, tool, args, scId) -> {
            assertEquals("files", server);
            lastCall.set(tool + ":" + args.optString("path"));
            return "file contents";
        };

        // Discovery writes straight into the live registry (no addTool loop,
        // no separate registerTools step — the sync IS the registration).
        assertTrue(mcp.syncTools(registry, invoker, "files", toolsList()));
        assertTrue(registry.contains("mcp__files.read_file"));
        assertTrue(registry.contains("mcp__files.list_directory"));
        assertTrue(registry.contains(ToolName.plain("mcp__files")));

        // Catalog: the two tools sit INSIDE the mcp__files namespace entry.
        ToolCatalog catalog = ToolCatalog.from(registry);
        String kinds = ToolSpecSerializer.catalogTypes(
                ToolSpecSerializer.toCatalog(catalog.registrations()));
        assertTrue("namespace grouped: " + kinds, kinds.contains("namespace:mcp__files"));
        assertFalse("children never flattened to roots: " + kinds,
                kinds.contains("function:mcp__files.read_file"));

        // Router executes the namespaced tool through the invoker.
        EventStream events = new EventStream(r -> r.run(), 8);
        AxionToolRouter router = new AxionToolRouter(registry, null, events);
        AxionToolRouter.Routed routed = router.route(
                new AxionToolRouter.Route("call_1", "mcp__files.read_file",
                        "{\"path\":\"README.md\"}"),
                "sc-files", RunContext.bare("sc-files", "assistant", null), null);
        assertFalse(routed.result().isError());
        assertEquals("read_file:README.md", lastCall.get());
        assertEquals("file contents", routed.result().output());
    }

    @Test
    public void parametersRenamedInCatalogPreserveAcrossBoundary() throws Exception {
        AxionToolRegistry registry = new AxionToolRegistry();
        McpServerRegistry mcp = new McpServerRegistry();
        mcp.addServer("db", "Database");

        McpToolAdapter.McpInvoker invoker = (server, tool, args, scId) -> "ok";
        mcp.syncTools(registry, invoker, "db", toolsList());
        mcp.syncTools(registry, invoker, "db",
                new JSONArray().put(new JSONObject()
                        .put("name", "read_file")
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("path", new JSONObject()
                                                .put("type", "string"))))));

        assertNotNull("updated description applies on re-sync",
                registry.get("mcp__db.read_file"));
        assertFalse("old list_directory gone after re-sync",
                registry.contains("mcp__db.list_directory"));
    }
}