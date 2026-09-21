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
    // Sanitized-name → canonical server name. Lookup-only index: aliases never
    // pollute the servers map, so servers.values() stays a faithful, unordered
    // view of DISTINCT servers (the old mcp__index:<sanitized> entry caused
    // the same McpServer object to iterate twice in toolCount()/registerTools()).
    private final Map<String, String> aliasIndex = new LinkedHashMap<>();

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
        String canonical = name.trim();
        servers.put(canonical, new McpServer(canonical, description));
        // Sanitized-name lookup index (never stored as a second server entry).
        String sanitized = McpToolIdentity.sanitize(canonical);
        if (!sanitized.equals(canonical)) {
            aliasIndex.put(sanitized, canonical);
        }
    }

    /** The canonical server under the raw or sanitized name, or {@code null}. */
    public McpServer getServer(String name) {
        if (name == null) {
            return null;
        }
        McpServer direct = servers.get(name);
        if (direct != null) {
            return direct;
        }
        String canonical = aliasIndex.get(name);
        return canonical == null ? null : servers.get(canonical);
    }

    /** Whether a server with the given raw or sanitized name exists. */
    public boolean hasServer(String name) {
        return getServer(name) != null;
    }

    /** Adds a tool definition to an existing server (returns false if unknown). */
    public boolean addTool(String serverName, JSONObject toolDefinition) {
        McpServer server = getServer(serverName);
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
                        "Duplicate MCP tool '" + toolName + "' in server '" + server.name() + "'");
            }
        }
        server.addTool(toolDefinition);
        return true;
    }

    /** Adds a resource definition to an existing server. */
    public boolean addResource(String serverName, JSONObject resourceDefinition) {
        McpServer server = getServer(serverName);
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
            registry.registerOrReplace(com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration
                    .builder(ns)
                    .source("mcp:" + server.name)
                    .build());
        }
    }

    /**
     * Reconciles ONE server against a fresh {@code tools/list} payload: tools
     * added, updated AND removed both in the live {@link AxionToolRegistry}
     * and in the server's raw definitions. Discovery hosts call this on every
     * refresh so stale MCP tools can never survive a re-list — the registry is
     * the single model-facing source. Returns {@code false} for an unknown
     * server (callers should addServer() first).
     */
    public boolean syncTools(AxionToolRegistry registry, McpToolAdapter.McpInvoker invoker,
                             String serverName, List<JSONObject> toolDefinitions) {
        McpServer server = getServer(serverName);
        if (server == null) {
            return false;
        }
        Map<String, JSONObject> live = new LinkedHashMap<>();
        if (toolDefinitions != null) {
            for (JSONObject def : toolDefinitions) {
                if (def == null) {
                    continue;
                }
                String toolName = def.optString("name", "").trim();
                if (toolName.isEmpty()) {
                    throw new IllegalArgumentException("MCP tool definition has no 'name'");
                }
                if (live.containsKey(toolName)) {
                    throw new IllegalArgumentException(
                            "Duplicate MCP tool '" + toolName + "' in the tools/list payload");
                }
                live.put(toolName, def);
            }
        }
        // Removed tools vanish from BOTH the registry and the raw definitions.
        Map<String, JSONObject> previous = new LinkedHashMap<>();
        for (JSONObject raw : new ArrayList<>(server.tools)) {
            String name = raw.optString("name", "").trim();
            previous.put(name, raw);
            if (!live.containsKey(name)) {
                McpToolIdentity identity = McpToolIdentity.of(server.name, name);
                registry.remove(identity.qualifiedName());
                server.tools.remove(raw);
            }
        }
        // Added/updated tools replace the raw definition and (re)register the
        // model-facing FUNCTION tool (registerOrReplace = refresh-safe).
        for (Map.Entry<String, JSONObject> entry : live.entrySet()) {
            JSONObject stale = previous.get(entry.getKey());
            if (stale != null) {
                server.tools.remove(stale);
            }
            server.tools.add(entry.getValue());
            McpToolIdentity identity = McpToolIdentity.of(server.name, entry.getKey());
            String description = entry.getValue().optString("description",
                    "MCP tool '" + entry.getKey() + "' exposed by server '" + server.name + "'.");
            JSONObject inputSchema = entry.getValue().optJSONObject("inputSchema");
            if (inputSchema == null) {
                inputSchema = entry.getValue().optJSONObject("parameters");
            }
            registry.registerOrReplace(McpToolAdapter.toRegistration(
                    identity, description, inputSchema, invoker, ""));
        }
        reconcileNamespace(registry, server);
        return true;
    }

    /** Convenience overload: feeds a raw {@code tools/list} JSON array. */
    public boolean syncTools(AxionToolRegistry registry, McpToolAdapter.McpInvoker invoker,
                             String serverName, JSONArray toolDefinitions) {
        List<JSONObject> defs = new ArrayList<>();
        if (toolDefinitions != null) {
            for (int i = 0; i < toolDefinitions.length(); i++) {
                defs.add(toolDefinitions.optJSONObject(i));
            }
        }
        return syncTools(registry, invoker, serverName, defs);
    }

    /**
     * Removes a server and every model-facing artifact it contributed: each
     * {@code mcp__<server>.<tool>} registration and its namespace declaration.
     * The raw definitions are dropped too, so a later registerTools cannot
     * resurrect a disabled server's tools.
     */
    public boolean removeServer(String serverName, AxionToolRegistry registry) {
        McpServer server = getServer(serverName);
        if (server == null) {
            return false;
        }
        for (JSONObject raw : new ArrayList<>(server.tools)) {
            McpToolIdentity identity = McpToolIdentity.of(
                    server.name, raw.optString("name", "").trim());
            registry.remove(identity.qualifiedName());
            server.tools.remove(raw);
        }
        com.saaspaymentsolutions.axion.agentsdk.tools.ToolName nsName =
                com.saaspaymentsolutions.axion.agentsdk.tools.ToolName.plain(namespaceFor(server.name));
        if (registry.contains(nsName)) {
            registry.remove(nsName);
        }
        String canonical = server.name;
        servers.remove(canonical);
        aliasIndex.values().remove(canonical);
        return true;
    }

    /** Keeps the server's namespace declaration in sync with its tool set. */
    private void reconcileNamespace(AxionToolRegistry registry, McpServer server) {
        com.saaspaymentsolutions.axion.agentsdk.tools.ToolName nsName =
                com.saaspaymentsolutions.axion.agentsdk.tools.ToolName.plain(namespaceFor(server.name));
        boolean hasNamespace = registry.get(nsName) != null
                && registry.get(nsName).spec().type()
                == com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.Type.NAMESPACE;
        if (server.tools.isEmpty()) {
            if (hasNamespace) {
                registry.remove(nsName);
            }
            return;
        }
        if (!hasNamespace) {
            com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec ns =
                    com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.namespace(
                            nsName,
                            String.format(NAMESPACE_DESCRIPTION, server.name),
                            Collections.emptyList());
            registry.registerOrReplace(com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration
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