package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.AgentTool;
import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.HandoffTool;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;

import org.json.JSONObject;

/**
 * Function-exposing {@link ToolExecutor} around a legacy {@link AgentTool}.
 * Registered by {@link LegacyToolAdapter}; delegates execution straight to
 * {@link AgentTool#execute} with the run-scoped context and parsed arguments.
 */
public final class AgentToolExecutor implements ToolExecutor {

    private final AgentTool tool;

    public AgentToolExecutor(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool is required");
        }
        this.tool = tool;
    }

    /** The wrapped legacy tool (identity for handoff detection). */
    public AgentTool wrappedTool() {
        return tool;
    }

    public boolean isHandoff() {
        return tool instanceof HandoffTool;
    }

    @Override
    public AgentToolResult execute(ToolExecutionContext ctx) throws Exception {
        RunContext runContext = ctx.runContext();
        JSONObject args = ctx.functionArguments();
        if (args == null) {
            args = new JSONObject();
        }
        return tool.execute(runContext, args);
    }
}