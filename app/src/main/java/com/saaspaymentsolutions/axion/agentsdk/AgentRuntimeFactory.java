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
        return new AgentRuntime.Builder(gateway)
                .events(events)
                .permissions(new PermissionLayer(ToolPolicy.interactive(), approvals, events))
                .inputChannel(approvals)
                .expectFileMutations(true)
                .build();
    }
}
