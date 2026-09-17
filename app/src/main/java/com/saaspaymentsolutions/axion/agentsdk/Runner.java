package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes {@link Agent}s in a run loop, mirroring {@code Runner.run} from
 * openai-agents-js: guardrails, LLM turns, tool execution, handoffs and a
 * max-turn ceiling. Provider-agnostic: it only talks to an
 * {@link AgentLlmGateway} and {@link AgentTool}s.
 */
public final class Runner {

    public static final int DEFAULT_MAX_TURNS = 16;

    private final AgentLlmGateway gateway;
    private final AgentTurnParser turnParser;
    private final ToolExecutor toolExecutor;
    private final List<Guardrail> inputGuardrails;
    private final int maxTurns;
    private final RunListeners listeners;

    private Runner(Builder builder) {
        this.gateway = builder.gateway;
        this.turnParser = new AgentTurnParser();
        this.toolExecutor = new ToolExecutor();
        this.inputGuardrails = Collections.unmodifiableList(new ArrayList<>(builder.inputGuardrails));
        this.maxTurns = Math.max(1, builder.maxTurns);
        this.listeners = builder.listeners;
    }

    /** Single-shot run: user input in, final assistant text out. */
    public RunResult run(Agent agent, String userInput, String scId) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(userInput, ChatMessage.TYPE_USER, System.currentTimeMillis()));
        return run(agent, history, scId);
    }

    /** Resumable run over caller-owned history (appended with this run's turns). */
    public RunResult run(Agent agent, List<ChatMessage> history, String scId) {
        if (agent == null) {
            return RunResult.failure("No agent was provided.");
        }
        if (history == null || history.isEmpty()) {
            return RunResult.failure("No user input was provided.");
        }

        RunContext context = new RunContext(scId, agent.name());
        String latestUserText = latestUserText(history);
        for (Guardrail guardrail : inputGuardrails) {
            GuardrailResult result = guardrail.checkInput(latestUserText);
            if (result.isTripwireTriggered()) {
                notify(() -> listeners.onGuardrailBlocked(agent, result));
                return RunResult.blockedByGuardrail(result);
            }
        }

        Agent activeAgent = agent;
        int turns = 0;
        while (turns++ < maxTurns) {
            final Agent turnAgent = activeAgent;
            final int turnNumber = turns;
            notify(() -> listeners.onTurnStart(turnAgent, turnNumber));

            final LlmTurnOutput turn;
            try {
                turn = gateway.completeTurn(
                        activeAgent.instructions(),
                        toolSchemasFor(activeAgent),
                        history,
                        null);
            } catch (Exception e) {
                return RunResult.failure("LLM turn failed: " + e.getMessage());
            }
            context.incrementLlmCalls();

            AgentTurnParser.ParsedTurn parsed = turnParser.parse(
                    turn.content(), turn.reasoning(), turn.finishReason(), turn.toolCalls());

            if (!parsed.hasToolCalls()) {
                String output = parsed.content().trim();
                for (Guardrail guardrail : activeAgent.outputGuardrails()) {
                    final GuardrailResult result = guardrail.checkOutput(output);
                    if (result.isTripwireTriggered()) {
                        final Agent guardrailAgent = activeAgent;
                        notify(() -> listeners.onGuardrailBlocked(guardrailAgent, result));
                        return RunResult.blockedByGuardrail(result);
                    }
                }
                return RunResult.success(output, context);
            }

            boolean handedOff = false;
            for (ToolCall call : parsed.toolCalls()) {
                AgentTool tool = findTool(activeAgent, call.getName());
                if (tool == null) {
                    appendToolResult(history, call,
                            "Error: unknown tool '" + call.getName() + "'.");
                    continue;
                }

                context.incrementToolCalls();
                if (tool.requiresApproval() && listeners != null) {
                    boolean approved = listeners.onToolApproval(tool, call);
                    if (!approved) {
                        appendToolResult(history, call,
                                "Error: user rejected execution of '" + tool.name() + "'.");
                        notify(() -> listeners.onToolFinish(tool, call,
                                AgentToolResult.error("rejected by user")));
                        continue;
                    }
                }

                notify(() -> listeners.onToolStart(tool, call));
                JSONObject args = toolExecutor.parseArguments(call.getArguments());
                AgentToolResult result = args == null
                        ? AgentToolResult.error("Error: invalid JSON arguments for '" + call.getName() + "'.")
                        : toolExecutor.run(tool, context, args);
                appendToolResult(history, call, result.output());
                notify(() -> listeners.onToolFinish(tool, call, result));

                if (tool instanceof HandoffTool) {
                    Agent target = findHandoffTarget(activeAgent, call.getName());
                    if (target != null && target != activeAgent) {
                        final Agent fromAgent = activeAgent;
                        final Agent toAgent = target;
                        context.recordHandoff(fromAgent, toAgent, "");
                        activeAgent = target;
                    }
                    handedOff = true;
                    break;
                }
            }

            if (!handedOff && turns >= maxTurns) {
                return RunResult.maxTurnsReached(context, lastAssistantText(history));
            }
            // With tools executed (or a handoff), loop for the next LLM turn.
        }
        return RunResult.maxTurnsReached(context, lastAssistantText(history));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JSONArray toolSchemasFor(Agent agent) {
        JSONArray schemas = new JSONArray();
        for (AgentTool tool : agent.tools()) {
            try {
                schemas.put(new JSONObject()
                        .put("type", "function")
                        .put("function", new JSONObject()
                                .put("name", tool.name())
                                .put("description", tool.description())
                                .put("parameters", tool.parameters())));
            } catch (org.json.JSONException e) {
                // A malformed tool schema must not kill the run; skip the tool.
            }
        }
        return schemas;
    }

    private static AgentTool findTool(Agent agent, String toolName) {
        for (AgentTool tool : agent.tools()) {
            if (tool.name().equals(toolName)) {
                return tool;
            }
        }
        return null;
    }

    private static Agent findHandoffTarget(Agent activeAgent, String toolName) {
        for (Agent candidate : activeAgent.handoffs()) {
            if (HandoffTool.toolNameFor(candidate).equals(toolName)) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendToolResult(List<ChatMessage> history, ToolCall call, String output) {
        ChatMessage toolMessage = new ChatMessage("", ChatMessage.TYPE_TOOL, System.currentTimeMillis());
        toolMessage.setToolName(call.getName());
        toolMessage.setToolArgs(call.getArguments());
        toolMessage.setToolId(call.getId());
        toolMessage.setToolRunning(false);
        toolMessage.setToolResult(output);
        toolMessage.setToolError(output != null && output.startsWith("Error"));
        history.add(toolMessage);
    }

    private static String latestUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getType() == ChatMessage.TYPE_USER) {
                return message.getMessage();
            }
        }
        return "";
    }

    private static String lastAssistantText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getType() == ChatMessage.TYPE_BOT) {
                return message.getMessage();
            }
        }
        return "";
    }

    private void notify(Runnable action) {
        if (listeners == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception ignored) {
            // Listener failures must never break the run loop.
        }
    }

    /** Fluent builder for {@link Runner}. */
    public static final class Builder {
        private final AgentLlmGateway gateway;
        private final List<Guardrail> inputGuardrails = new ArrayList<>();
        private int maxTurns = DEFAULT_MAX_TURNS;
        private RunListeners listeners;

        public Builder(AgentLlmGateway gateway) {
            if (gateway == null) {
                throw new IllegalArgumentException("gateway is required");
            }
            this.gateway = gateway;
        }

        public Builder inputGuardrails(Guardrail... guardrails) {
            for (Guardrail guardrail : guardrails) {
                if (guardrail != null) {
                    inputGuardrails.add(guardrail);
                }
            }
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder listeners(RunListeners listeners) {
            this.listeners = listeners;
            return this;
        }

        public Runner build() {
            return new Runner(this);
        }
    }
}
