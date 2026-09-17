package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import java.util.Collections;
import java.util.List;

/**
 * Shared primitives for the agentsdk gateway contract. Mirrors the
 * {@code Model/ModelResponse} split of openai-agents-js: the gateway returns a
 * raw turn ({@link LlmTurn}) and the host converts it to typed output.
 */
final class AgenticPrimitives {

    private AgenticPrimitives() {}

    /** Raw LLM turn as returned by a gateway implementation. */
    static final class LlmTurn {
        final String content;
        final String reasoning;
        final String finishReason;
        final List<ToolCall> toolCalls;

        LlmTurn(String content, String reasoning, String finishReason, List<ToolCall> toolCalls) {
            this.content = content == null ? "" : content;
            this.reasoning = reasoning == null ? "" : reasoning;
            this.finishReason = finishReason == null ? "" : finishReason;
            this.toolCalls = toolCalls == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(toolCalls);
        }
    }

    /** Reference wrapper for agent maps (keeps Runner API surface small). */
    static final class AgentEntry {
        final Agent agent;

        AgentEntry(Agent agent) {
            this.agent = agent;
        }

        Agent agent() {
            return agent;
        }
    }
}
