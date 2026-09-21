package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolExecResult;
import com.saaspaymentsolutions.axion.ToolManager;
import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.LegacyToolAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the existing Void-ported tool registry ({@link ToolManager}) into
 * {@link AgentTool}s.
 *
 * <p>Migration: this class is NOT a model-facing catalog source anymore. The
 * production coordinator agent carries no {@code AgentTool[]}; hosts that want
 * a legacy Tool model-visible must register it into the
 * {@link AxionToolRegistry} via {@link #registerModelCompatibleTools(...)},
 * where {@link LegacyToolAdapter} applies the replacement classification.</p>
 */
public final class WorkspaceAgents {

    private WorkspaceAgents() {}

    /** Adapts a registered {@link Tool} (Void-ported registry) to {@link AgentTool}. */
    public static AgentTool fromRegistryTool(Tool tool, ToolManager manager) {
        return new RegistryToolAdapter(tool, manager);
    }

    /**
     * Registers the legacy {@link ToolManager} tools the model may KEEP using
     * into the registry — classification-guarded by {@link LegacyToolAdapter}.
     * Only {@link LegacyToolAdapter.LegacyKind#LEGACY_MODEL_COMPATIBLE} tools
     * become model-visible DIRECT registrations; names superseded by a newer
     * contract (run_command, edit_file, rewrite_file, ...) are skipped, so the
     * old and the new contract never coexist in the model catalog.
     */
    public static void registerModelCompatibleTools(AxionToolRegistry registry, ToolManager manager) {
        if (registry == null || manager == null) {
            return;
        }
        for (Tool tool : manager.getToolsForChatMode("agent")) {
            if (LegacyToolAdapter.classify(tool.getName()) != LegacyToolAdapter.LegacyKind.LEGACY_MODEL_COMPATIBLE) {
                continue;
            }
            registry.register(LegacyToolAdapter.register(fromRegistryTool(tool, manager)));
        }
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
