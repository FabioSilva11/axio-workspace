package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the discovered/enabled MCP servers and folds their tools and
 * resources into an {@link AxionToolRegistry}.
 *
 * <p>Collision policy (Codex parity): every tool is keyed by its full
 * {@code mcp__<server>.<tool>} identity, so two servers exposing the same
 * tool name are DISTINCT tools and both stay registered. Within one server a
 * duplicate tool name is an error (the server catalog is malformed).</p>
 *
 * <p>A logical NAMESPACE entry per {@code mcp__<server>} may also be added so
 * the model catalogue shows the namespace (Codex declares MCP namespaces in
 * the catalog only when the model opened them). By default tools are exposed
 * DIRECT so the model can call them immediately; hosts that prefer discovery
 * register the catalogue as deferred instead.</p>
 */
public final class McpServerRegistry {

    /** Key of the global namespace description for an MCP server. */
    public static final String NAMESPACE_DESCRIPTION = "MCP tools exposed by %s.";

    private final Map<String, McpServer> servers = new LinkedHashMap<>();

    /** A discovered MCP server: name plus its raw tool/resource definitions. */
    public static final class McpServer {
        private final String name;
        private final String description;
        private final List<JSONObject> tools = new ArrayList<>();
        private final List<JSONObject> resources = new ArrayList<>();

        McpServer(String name, String description) {
            this.name = name;
            this.description = description == null ? "" : description;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public List<JSONObject> tools() {
            return Collections.unmodifiableList(tools);
        }

        public List<JSONObject> resources() {
            return Collections.unmodifiableList(resources);
        }

        void addTool(JSONObject tool) {
            if (tool != null) {
                tools.add(tool);
            }
        }

        void addResource(JSONObject resource) {
            if (resource != null) {
                resources.add(resource);
            }
        }
    }

    /** Adds a server (or its tools) to the registry. */
    public void addServer(String name, String description) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP server name must not be empty");
        }
        servers.put(name.trim(), new McpServer(name.trim(), description));
        // Index by sanitized name for collision-safe lookup.
        String sanitized = McpToolIdentity.sanitize(name.trim());
        if (!sanitized.equals(name.trim())) {
            servers.putIfAbsent("mcp__index:" + sanitized, servers.get(name.trim()));
        }
    }

    /** Adds a tool definition to an existing server (returns false if unknown). */
    public boolean addTool(String serverName, JSONObject toolDefinition) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return false;
        }
        String toolName = toolDefinition == null ? "" : toolDefinition.optString("name", "").trim();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("MCP tool definition has no 'name'");
        }
        // Duplicate tool within one server = malformed catalog (Codex errors).
        for (JSONObject existing : server.tools) {
            if (toolName.equals(existing.optString("name", "").trim())) {
                throw new IllegalArgumentException(
                        "Duplicate MCP tool '" + toolName + "' in server '" + serverName + "'");
            }
        }
        server.addTool(toolDefinition);
        return true;
    }

    /** Adds a resource definition to an existing server. */
    public boolean addResource(String serverName, JSONObject resourceDefinition) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return false;
        }
        String uri = resourceDefinition == null ? "" : resourceDefinition.optString("uri", "").trim();
        if (uri.isEmpty() && resourceDefinition != null
                && resourceDefinition.optString("name", "").trim().isEmpty()) {
            throw new IllegalArgumentException("MCP resource definition has no 'uri' or 'name'");
        }
        server.addResource(resourceDefinition);
        return true;
    }

    /** Registers every MCP tool into the registry as {@code mcp__server.tool}. */
    public void registerTools(AxionToolRegistry registry, McpToolAdapter.McpInvoker invoker) {
        for (McpServer server : servers.values()) {
            for (JSONObject rawTool : server.tools) {
                String rawName = rawTool.optString("name", "").trim();
                McpToolIdentity identity = McpToolIdentity.of(server.name, rawName);
                String description = rawTool.optString("description",
                        "MCP tool '" + rawName + "' exposed by server '" + server.name + "'.");
                JSONObject inputSchema = rawTool.optJSONObject("inputSchema");
                if (inputSchema == null) {
                    inputSchema = rawTool.optJSONObject("parameters");
                }
                registry.register(McpToolAdapter.toRegistration(
                        identity, description, inputSchema, invoker, ""));
            }
        }
    }

    /**
     * Registers a NAMESPACE declaration per server so the catalog groups the
     * {@code mcp__<server>} tools under a namespace entry (Codex exposes the
     * namespace when the model opened it). No-op if the server has no tools.
     * The namespace plain name MUST equal the prefix of the child tool
     * namespaces ({@code mcp__server}) for the serializer to group them.
     */
    public void registerNamespaces(AxionToolRegistry registry) {
        for (McpServer server : servers.values()) {
            if (server.tools.isEmpty()) {
                continue;
            }
            String prefix = namespaceFor(server.name);
            com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec ns = 
                    com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.namespace(
                            com.saaspaymentsolutions.axion.agentsdk.tools.ToolName.plain(prefix),
                            String.format(NAMESPACE_DESCRIPTION, server.name),
                            Collections.emptyList());
            registry.register(com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration
                    .builder(ns)
                    .source("mcp:" + server.name)
                    .build());
        }
    }

    /** Resolves the {@code mcp__server} namespace for a server name. */
    public String namespaceFor(String serverName) {
        return "mcp__" + McpToolIdentity.sanitize(serverName);
    }

    /** All registered servers (unmodifiable view). */
    public List<McpServer> servers() {
        return Collections.unmodifiableList(new ArrayList<>(servers.values()));
    }

    /** Whether any server has been added. */
    public boolean isEmpty() {
        return servers.isEmpty();
    }

    /** Count of distinct tools across servers, keyed by full model name. */
    public int toolCount() {
        int count = 0;
        for (McpServer server : servers.values()) {
            count += server.tools.size();
        }
        return count;
    }
}