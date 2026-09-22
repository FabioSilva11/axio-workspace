package com.saaspaymentsolutions.axion.agentsdk;

import android.content.Context;

/**
 * Assembly of the production chat runtime (item 1 of the migration):
 *
 * <pre>
 * ChatActivity
 *     ↓ AgentManager (UI controller only)
 *     ↓ AgentRuntime  ← AgentRuntimeFactory.createForChat(context)
 *     ↓ AxionAgentGateway
 *     ↓ AiProviderService
 * </pre>
 *
 * <p>The factory is the ONLY place the UI's runtime is built, guaranteeing:</p>
 * <ul>
 *   <li>a real streaming gateway ({@link AxionAgentGateway}) declaring
 *       {@code NativeToolCallsOnly};</li>
 *   <li>a MANDATORY {@link PermissionLayer} — the {@link ApprovalHandler}
 *       interactive resolver parks requests until the UI resolves them by
 *       {@code requestId}; mutation/shell NEVER execute merely because the
 *       host forgot a policy (safe default = WORKSPACE + ON_REQUEST);</li>
 *   <li>the runtime's own {@link EventStream} — the {@code HostBridge}
 *       subscribes to THIS stream, not to a second one.</li>
 * </ul>
 */
public final class AgentRuntimeFactory {

    private AgentRuntimeFactory() {
    }

    /** Production runtime for the chat UI with host-driven approvals. */
    public static AgentRuntime createForChat(Context context) {
        com.saaspaymentsolutions.axion.AiProviderService aiService =
                com.saaspaymentsolutions.axion.AiProviderService.getInstance();
        AxionAgentGateway gateway = new AxionAgentGateway(aiService, "agent");
        android.content.SharedPreferences prefs =
                context == null ? null : com.saaspaymentsolutions.axion.port.VoidPortSettings.prefs(context);
        return createForChatWithGateway(gateway, prefs);
    }

    /**
     * Production assembly over an explicit {@link AgentLlmGateway}. The memory
     * owner is always constructed here — never by the caller — so the UI
     * runtime, its registry, its permission layer, its approval input channel
     * and its event stream are built exactly once and exactly the same way for
     * every gateway. Tests drive the REAL factory with a scripted gateway and
     * assert on the assembly's own pieces (no wiring duplication in tests).
     *
     * <p>{@code context} is deliberately not passed further: the whole
     * assembly after the gateway is pure memory and runs on the JVM, which is
     * what makes {@code ChatActivity → AgentManager → AgentRuntime} provably
     * buildable and runnable under unit tests.</p>
     */
    public static AgentRuntime createForChatWithGateway(
            com.saaspaymentsolutions.axion.agentsdk.AgentLlmGateway gateway) {
        return createForChatWithGateway(gateway, null);
    }

    /**
     * Production assembly over an explicit {@link AgentLlmGateway} and the
     * optional MCP preferences. When {@code prefs == null} (JVM tests) no MCP
     * server is reachable and none is registered.
     */
    public static AgentRuntime createForChatWithGateway(
            com.saaspaymentsolutions.axion.agentsdk.AgentLlmGateway gateway,
            android.content.SharedPreferences mcpPrefs) {
        // Item 16: the interactive resolver is the explicit approval
        // protocol — requests are resolved by requestId from the UI thread.
        ApprovalHandler.Resolver approvals = new ApprovalHandler.Resolver();
        EventStream events = new EventStream();
        // Item (registry-backed catalog): the SINGLE model-facing tool source.
        // The Codex-parity core tools and the remaining workspace read tools
        // (read_file, ls_dir, search* ...) are registered once here; MCP
        // servers follow as registry citizens. Nothing is adapted at run time,
        // so legacy names with a replacement never reach the model.
        com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry registry =
                new com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry();
        com.saaspaymentsolutions.axion.agentsdk.tools.WorkspaceToolProvider.registerCoreTools(
                registry, approvals);
        com.saaspaymentsolutions.axion.agentsdk.tools.WorkspaceToolProvider.registerWorkspaceReadTools(
                registry);
        com.saaspaymentsolutions.axion.agentsdk.tools.McpToolSource.discover(mcpPrefs, registry);
        // Item (permission model): the safe default WORKSPACE + ON_REQUEST.
        // Reads run automatically; workspace writes / shell / network ask the
        // user before executing. The host UI switches the profile via
        // AgentRuntime#updatePermissionConfig.
        return new AgentRuntime.Builder(gateway)
                .events(events)
                .permissions(new PermissionLayer(approvals, events))
                .toolRegistry(registry)
                // Removed: expectFileMutations(true) - Codex alignment
                // The runtime no longer forces mutations for all chats.
                // Text-only responses are valid for read-only queries.
                .build();
    }
}
