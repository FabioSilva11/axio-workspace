package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.AiOperationContext;
import com.saaspaymentsolutions.axion.AiProviderService;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.ContextBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production {@link AgentLlmGateway}: talks to the configured provider through
 * {@link AiProviderService#sendTextMessage} (blocking, off-main-thread) and
 * converts the SDK's {@link ChatMessage} history into
 * system/user/assistant turns.
 *
 * <p>Because {@code sendTextMessage} carries no native tool-call envelope,
 * the app's text-embedded detectors (XML/JSON/DSML) parse tool calls from the
 * assistant text — the same mechanism the app's XML-fallback chat mode uses.</p>
 */
public final class AxionAgentGateway implements AgentLlmGateway {

    private static final ExecutorService GATEWAY_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agentsdk-gateway");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    private final AiProviderService aiService;

    public AxionAgentGateway(AiProviderService aiService) {
        this.aiService = aiService;
    }

    @Override
    public LlmTurnOutput completeTurn(String systemPrompt,
                                      JSONArray tools,
                                      List<ChatMessage> messages,
                                      AiOperationContext operationContext) throws Exception {
        return GATEWAY_EXECUTOR.submit(() -> doCompleteTurn(systemPrompt, messages)).get();
    }

    private LlmTurnOutput doCompleteTurn(String systemPrompt, List<ChatMessage> messages)
            throws Exception {
        StringBuilder userTurn = new StringBuilder();
        for (ChatMessage message : messages) {
            switch (message.getType()) {
                case ChatMessage.TYPE_USER -> userTurn.append(message.getLlmContent()).append("\n\n");
                case ChatMessage.TYPE_BOT -> userTurn.append("[assistant]: ")
                        .append(message.getLlmContent()).append("\n\n");
                case ChatMessage.TYPE_TOOL -> userTurn.append("[tool ").append(message.getToolName())
                        .append(" result]: ").append(message.getToolResult() == null
                                ? "" : message.getToolResult()).append("\n\n");
                default -> {
                }
            }
        }

        String raw = aiService.sendTextMessage(systemPrompt, userTurn.toString().trim());
        return new LlmTurnOutput(raw, "", "", java.util.Collections.emptyList());
    }

    @Override
    public void cancel() {
        // sendTextMessage has no handle; per-run cancellation is out of scope here.
    }
}
