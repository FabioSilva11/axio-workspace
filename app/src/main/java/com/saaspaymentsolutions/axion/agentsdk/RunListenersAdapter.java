package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import java.util.function.Consumer;

/**
 * Bridges {@link AgentEvent}s from the {@link AgentRuntime} onto the legacy
 * {@link RunListeners} interface so existing hosts keep working while the UI
 * migrates to the event stream. Direction: events → listeners.
 */
public final class RunListenersAdapter implements Consumer<AgentEvent> {

    private final RunListeners listeners;
    private final Agent agentFallback;

    public RunListenersAdapter(RunListeners listeners, Agent agentFallback) {
        this.listeners = listeners;
        this.agentFallback = agentFallback;
    }

    @Override
    public void accept(AgentEvent event) {
        if (listeners == null || event == null) {
            return;
        }
        if (event instanceof AgentEvent.TurnStarted) {
            AgentEvent.TurnStarted turn = (AgentEvent.TurnStarted) event;
            listeners.onTurnStart(resolveAgent(turn.getAgentName()), turn.getTurn());
        } else if (event instanceof AgentEvent.ToolCallStarted) {
            AgentEvent.ToolCallStarted started = (AgentEvent.ToolCallStarted) event;
            AgentTool tool = resolveTool(started.getTool());
            if (tool != null) {
                listeners.onToolStart(tool, started.getCall());
            }
        } else if (event instanceof AgentEvent.ToolCallCompleted) {
            AgentEvent.ToolCallCompleted completed = (AgentEvent.ToolCallCompleted) event;
            AgentTool tool = resolveTool(completed.getTool());
            if (tool != null) {
                listeners.onToolFinish(tool, completed.getCall(), completed.getResult());
            }
        } else if (event instanceof AgentEvent.PolicyDenied) {
            AgentEvent.PolicyDenied denied = (AgentEvent.PolicyDenied) event;
            AgentTool tool = resolveTool(denied.getTool());
            if (tool != null) {
                ToolCall synthetic = new ToolCall(denied.getTool(), "{}", null);
                listeners.onToolFinish(tool, synthetic,
                        AgentToolResult.error(denied.getReason()));
            }
        }
        // ApprovalRequired/PermissionResolved intentionally not forwarded:
        // RunListeners.onToolApproval is synchronous and cannot represent
        // the async human-in-the-loop flow.
    }

    private Agent resolveAgent(String agentName) {
        if (agentFallback != null && agentFallback.name().equals(agentName)) {
            return agentFallback;
        }
        // Handoff targets are unknown to the adapter; the fallback keeps
        // listener signatures working without inventing Agent instances.
        return agentFallback;
    }

    private AgentTool resolveTool(String toolName) {
        if (agentFallback == null) {
            return null;
        }
        for (AgentTool tool : agentFallback.tools()) {
            if (tool.name().equals(toolName)) {
                return tool;
            }
        }
        return null;
    }
}
