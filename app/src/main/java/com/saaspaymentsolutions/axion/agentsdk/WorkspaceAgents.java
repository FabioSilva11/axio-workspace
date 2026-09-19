package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolExecResult;
import com.saaspaymentsolutions.axion.ToolManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the existing Void-ported tool registry ({@link ToolManager}) into
 * {@link AgentTool}s and exposes ready-made {@link Agent} factories.
 */
public final class WorkspaceAgents {

    private WorkspaceAgents() {}

    /** Adapts a registered {@link Tool} (Void-ported registry) to {@link AgentTool}. */
    public static AgentTool fromRegistryTool(Tool tool, ToolManager manager) {
        return new RegistryToolAdapter(tool, manager);
    }

    /** All Void-ported tools available in agent mode, as {@link AgentTool}s. */
    public static List<AgentTool> defaultWorkspaceTools(ToolManager manager) {
        return defaultWorkspaceTools(manager, null, "", null);
    }

    /**
     * Codex parity: the default toolset gains {@code get_context_remaining}
     * and {@code request_user_input} (the latter only when an input channel
     * is available).
     */
    public static List<AgentTool> defaultWorkspaceTools(ToolManager manager, ApprovalHandler inputChannel) {
        return defaultWorkspaceTools(manager, inputChannel, "", null);
    }

    /**
     * Full default toolset. {@code apply_patch} (Codex's canonical edit tool)
     * is registered here so every agent mutates files through the same
     * {@link com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem}
     * infrastructure, approval flow and {@code FileChanged} events as the
     * rest of the toolset — a single mutation runtime, no parallel path.
     */
    public static List<AgentTool> defaultWorkspaceTools(ToolManager manager, ApprovalHandler inputChannel,
                                                        String scId, EventStream events) {
        List<AgentTool> tools = new ArrayList<>();
        for (Tool tool : manager.getToolsForChatMode("agent")) {
            tools.add(fromRegistryTool(tool, manager));
        }
        tools.add(new ApplyPatchTool(scId, events));
        tools.add(new ContextRemainingTool());
        if (inputChannel != null) {
            tools.add(new RequestUserInputTool(inputChannel));
        }
        return tools;
    }

    /** Read-only toolset: only tools that never mutate files. */
    public static List<AgentTool> readOnlyTools(ToolManager manager) {
        List<AgentTool> tools = new ArrayList<>();
        for (Tool tool : manager.getToolsForChatMode("agent")) {
            if (!tool.isFileMutation()) {
                tools.add(fromRegistryTool(tool, manager));
            }
        }
        return tools;
    }

    /**
     * Standard topology mirroring the app's multi-agent flow: a coordinator
     * agent with full tools handing off to a reviewer agent (read-only).
     */
    public static Agent workspaceCoordinator(ToolManager manager, String scId) {
        return workspaceCoordinator(manager, scId, null);
    }

    /**
     * Same topology with a human-in-the-loop input channel: the coordinator
     * gains {@code request_user_input} for decisions that belong to the user
     * and {@code get_context_remaining} for long tasks.
     */
    public static Agent workspaceCoordinator(ToolManager manager, String scId, ApprovalHandler inputChannel) {
        Agent reviewer = Agent.Builder.forName("reviewer",
                        "You are a code reviewer. Inspect the provided files and report issues. "
                                + "Do not modify anything.")
                .tools(readOnlyTools(manager).toArray(new AgentTool[0]))
                .build();

        AgentTool reviewerHandoff = new HandoffTool(reviewer);

        return Agent.Builder.forName("coordinator",
                        "You are the workspace coordinator. Use the available tools to "
                                + "explore, read, and modify project files to complete the user's task. "
                                + "Delegate reviews to the reviewer agent when work is complete. "
                                + "For multi-step tasks keep the plan tool updated as steps finish. "
                                + "When a requirement is ambiguous and the decision belongs to the user, "
                                + "ask once via request_user_input instead of guessing.")
                .tools(defaultWorkspaceTools(manager, inputChannel, scId, null).toArray(new AgentTool[0]))
                .tools(reviewerHandoff)
                .handoffs(reviewer)
                .build();
    }

    /**
     * Wraps a legacy {@link Tool} into the SDK interface. Execution goes
     * through {@link ToolManager#executeTool} so host policies (read-only
     * turns, explicit error protocol) keep applying.
     */
    private static final class RegistryToolAdapter implements AgentTool {
        private final Tool delegate;
        private final ToolManager manager;

        RegistryToolAdapter(Tool delegate, ToolManager manager) {
            this.delegate = delegate;
            this.manager = manager;
        }

        @Override
        public String name() {
            return delegate.getName();
        }

        @Override
        public String description() {
            return delegate.getDescription();
        }

        @Override
        public JSONObject parameters() {
            JSONObject schema = delegate.getParameters();
            return schema == null ? new JSONObject() : schema;
        }

        @Override
        public boolean requiresApproval() {
            return delegate.requiresApproval();
        }

        @Override
        public boolean isFileMutation() {
            return delegate.isFileMutation();
        }

        @Override
        public boolean isDestructive() {
            return delegate.isDestructive();
        }

        @Override
        public AgentToolResult execute(RunContext context, JSONObject args) {
            ToolExecResult result = manager.executeTool(context.scId(), name(), args.toString());
            return result.ok
                    ? AgentToolResult.success(result.output)
                    : AgentToolResult.error(result.output);
        }
    }
}
