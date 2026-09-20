package com.saaspaymentsolutions.axion.agentsdk.tools.mcp;

/**
 * Canonical identity of one MCP-exposed tool, ported from Codex
 * {@code McpToolContext} / {@code ToolInfo}: a server name plus the raw tool
 * name, producing the fully-qualified model name
 * {@code mcp__<server>.<tool>}.
 *
 * <p>Naming rules (Codex parity):</p>
 * <ul>
 *   <li>the namespace is {@code mcp__}<i>sanitizedServerName</i>;</li>
 *   <li>the tool name is the <i>sanitized</i> raw MCP tool name;</li>
 *   <li>sanitization maps any character outside {@code [a-zA-Z0-9_-]} to
 *       {@code _} and collapses runs, so {@code "My Ticket App"} becomes
 *       {@code My_Ticket_App};</li>
 *   <li>the fully-qualified name is {@code mcp__server.tool}, which keeps two
 *       servers exposing the same tool name distinct (collision parity:
 *       {@code mcp__server_a.search} vs {@code mcp__server_b.search}).</li>
 * </ul>
 */
public final class McpToolIdentity {

    /** Prefix of every MCP namespace (Codex {@code LEGACY_MCP_TOOL_NAME_PREFIX}). */
    public static final String NAMESPACE_PREFIX = "mcp__";

    /** Pattern every sanitised identity component must match. */
    public static final String SAFE_PATTERN = "^[a-zA-Z0-9_-]+$";

    private final String serverName;
    private final String toolName;

    private McpToolIdentity(String serverName, String toolName) {
        this.serverName = serverName;
        this.toolName = toolName;
    }

    /** Builds an identity from a raw server and tool name (sanitised). */
    public static McpToolIdentity of(String serverName, String toolName) {
        String server = sanitize(serverName);
        String tool = sanitize(toolName);
        if (server.isEmpty()) {
            throw new IllegalArgumentException("MCP server name must not be empty");
        }
        if (tool.isEmpty()) {
            throw new IllegalArgumentException("MCP tool name must not be empty");
        }
        return new McpToolIdentity(server, tool);
    }

    /** The sanitised server name (namespace suffix). */
    public String serverName() {
        return serverName;
    }

    /** The sanitised tool name. */
    public String toolName() {
        return toolName;
    }

    /** The namespace {@code mcp__<server>}. */
    public String namespace() {
        return NAMESPACE_PREFIX + serverName;
    }

    /** The fully-qualified model name {@code mcp__<server>.<tool>}. */
    public String qualifiedName() {
        return namespace() + "." + toolName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpToolIdentity)) {
            return false;
        }
        McpToolIdentity that = (McpToolIdentity) o;
        return serverName.equals(that.serverName) && toolName.equals(that.toolName);
    }

    @Override
    public int hashCode() {
        return qualifiedName().hashCode();
    }

    @Override
    public String toString() {
        return qualifiedName();
    }

    /**
     * Sanitises a raw MCP name for the model-visible identifier: characters
     * outside {@code [a-zA-Z0-9_-]} become {@code _}; leading/trailing runs
     * are trimmed. Empty result stays empty (callers reject it).
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim()
                .replaceAll("[^A-Za-z0-9_-]+", "_");
        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) == '_') {
            start++;
        }
        while (end > start && cleaned.charAt(end - 1) == '_') {
            end--;
        }
        return cleaned.substring(start, end);
    }
}