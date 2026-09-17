package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.AiOperationContext;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.ContextBuilder;

import org.json.JSONArray;

import java.util.List;

/**
 * LLM boundary of the agentsdk, mirroring the model interface of
 * openai-agents-js: the {@link Runner} only talks to an LLM through this
 * port, which makes the whole run loop unit-testable with fakes.
 *
 * <p>Turn state lives in the {@code messages} list — the gateway appends the
 * assistant turn (including tool calls) and tool results as {@link ChatMessage}s,
 * so the next call carries full loop history and the {@code ContextBuilder}
 * can produce provider-native envelopes (OpenAI/Anthropic/Gemini/XML).</p>
 */
public interface AgentLlmGateway {

    /**
     * Runs one LLM turn for the active agent and returns the parsed turn.
     *
     * @param systemPrompt      resolved system instruction for the active agent
     * @param tools             OpenAI-style function schemas for the active agent's tools
     * @param messages          mutable conversation history; the gateway appends this turn
     * @param operationContext  frozen provider/model selection (may be {@code null})
     */
    LlmTurnOutput completeTurn(String systemPrompt,
                               JSONArray tools,
                               List<ChatMessage> messages,
                               AiOperationContext operationContext) throws Exception;

    /** Cancels the in-flight request, if any. */
    default void cancel() {
    }
}
