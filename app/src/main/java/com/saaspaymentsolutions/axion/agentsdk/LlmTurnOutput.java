package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import java.util.Collections;
import java.util.List;

/**
 * Immutable parsed output of one LLM turn (Codex turn-structure parity):
 * assistant message + structured tool calls + finish reason + token usage.
 *
 * <p>Two contracts enforced here:</p>
 * <ul>
 *   <li><b>textStreamed</b> — transport state: {@code content} was ALREADY
 *       delivered to the UI incrementally through the gateway's delta
 *       listener. The runtime then treats the final content as completion
 *       state only — never re-publishing it as a new
 *       {@code AssistantMessageDelta} (single-display rule).</li>
 *   <li><b>usage</b> — real token accounting: provider-reported
 *       {@link TokenUsage} when available, an explicitly
 *       {@code estimated()} usage when not. The {@link RunBudget} settles
 *       with this value.</li>
 * </ul>
 */
public final class LlmTurnOutput {

    private final String content;
    private final String reasoning;
    private final String finishReason;
    private final List<ToolCall> toolCalls;
    private final boolean textStreamed;
    private final TokenUsage usage;

    public LlmTurnOutput(String content, String reasoning, String finishReason,
                         List<ToolCall> toolCalls) {
        this(content, reasoning, finishReason, toolCalls, false);
    }

    public LlmTurnOutput(String content, String reasoning, String finishReason,
                         List<ToolCall> toolCalls, boolean textStreamed) {
        this(content, reasoning, finishReason, toolCalls, textStreamed, null);
    }

    public LlmTurnOutput(String content, String reasoning, String finishReason,
                         List<ToolCall> toolCalls, boolean textStreamed, TokenUsage usage) {
        this.content = content == null ? "" : content;
        this.reasoning = reasoning == null ? "" : reasoning;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.toolCalls = toolCalls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(toolCalls);
        this.textStreamed = textStreamed;
        this.usage = usage;
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

    /** True when {@link #content()} already reached the UI via deltas. */
    public boolean textStreamed() {
        return textStreamed;
    }

    /** Provider usage of this turn, or {@code null} when the gateway did not report it. */
    public TokenUsage usage() {
        return usage;
    }
}
