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
 *       host forgot a policy (safe default = interactive);</li>
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
        // Item 16: the interactive resolver is the explicit approval
        // protocol — requests are resolved by requestId from the UI thread.
        ApprovalHandler.Resolver approvals = new ApprovalHandler.Resolver();
        EventStream events = new EventStream();
        // Item (registry-backed catalog): the SINGLE model-facing tool source.
        // The Codex-parity core tools are registered once here; legacy void
        // tools that still have no newer contract (read_file, ls_dir, search*
        // ...) follow as classification-guarded registry citizens. The
        // coordinator agent carries no AgentTool[] — nothing is adapted at run
        // time, so legacy names with a replacement never reach the model.
        com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry registry =
                new com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry();
        com.saaspaymentsolutions.axion.agentsdk.tools.WorkspaceToolProvider.registerCoreTools(
                registry, approvals);
        com.saaspaymentsolutions.axion.ToolManager legacy = new com.saaspaymentsolutions.axion.ToolManager();
        com.saaspaymentsolutions.axion.port.VoidToolWrapper.registerAllVoidTools(legacy);
        WorkspaceAgents.registerModelCompatibleTools(registry, legacy);
        return new AgentRuntime.Builder(gateway)
                .events(events)
                .permissions(new PermissionLayer(ToolPolicy.interactive(), approvals, events))
                .inputChannel(approvals)
                .toolRegistry(registry)
                // Removed: expectFileMutations(true) - Codex alignment
                // The runtime no longer forces mutations for all chats.
                // Text-only responses are valid for read-only queries.
                .build();
    }
}
