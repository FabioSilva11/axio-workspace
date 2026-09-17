package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import java.util.Collections;
import java.util.List;

/** Immutable parsed output of one LLM turn. */
public final class LlmTurnOutput {

    private final String content;
    private final String reasoning;
    private final String finishReason;
    private final List<ToolCall> toolCalls;

    public LlmTurnOutput(String content, String reasoning, String finishReason,
                         List<ToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.reasoning = reasoning == null ? "" : reasoning;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.toolCalls = toolCalls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(toolCalls);
    }

    public String content() {
        return content;
    }

    public String reasoning() {
        return reasoning;
    }

    public String finishReason() {
        return finishReason;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }
}
