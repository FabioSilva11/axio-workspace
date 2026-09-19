package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.AiChatSettingsHelper;
import com.saaspaymentsolutions.axion.AiOperationContext;
import com.saaspaymentsolutions.axion.AiProviderService;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.ContextBuilder;
import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolManager;
import com.saaspaymentsolutions.axion.toolcalling.DefaultToolCallDetector;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;
import com.saaspaymentsolutions.axion.toolcalling.ToolCallResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production {@link AgentLlmGateway}: one streaming provider request per turn
 * through {@link AiProviderService#sendStreamingMessage}, using the shared
 * {@link ContextBuilder} (provider-aware history, budgets, compaction) and
 * the provider's native tool-call envelope when available.
 *
 * <p>This is what lets the {@link AgentRuntime} fully replace the legacy
 * AgentManager loop: the runtime now sits on the exact same transport the
 * legacy chat used — streamed deltas, reasoning, structured tool calls —
 * with the SDK loop on top.</p>
 */
public final class AxionAgentGateway implements AgentLlmGateway {

    private final AiProviderService aiService;
    private final String chatMode;
    private final DefaultToolCallDetector textToolDetector = new DefaultToolCallDetector();
    private final java.util.function.Consumer<String> deltaListenerHook;
    private volatile boolean cancelRequested;

    public AxionAgentGateway(AiProviderService aiService, String chatMode) {
        this(aiService, chatMode, null);
    }

    /** Host-provided XML-fallback tool definitions for this mode. */
    public void setXmlFallbackTools(List<Tool> tools) {
        this.xmlFallbackTools = tools;
    }

    private List<Tool> xmlFallbackTools;

    /**
     * @param deltaListenerHook optional bridge from provider content deltas to
     *                          the host UI while the request is in flight
     *                          (called on provider threads).
     */
    public AxionAgentGateway(AiProviderService aiService, String chatMode,
                             java.util.function.Consumer<String> deltaListenerHook) {
        this.aiService = aiService;
        this.chatMode = chatMode == null ? "agent" : chatMode;
        this.deltaListenerHook = deltaListenerHook;
    }

    @Override
    public LlmTurnOutput completeTurn(String systemPrompt,
                                      JSONArray tools,
                                      List<ChatMessage> messages,
                                      AiOperationContext operationContext) throws Exception {
        cancelRequested = false;

        ContextBuilder builder = new ContextBuilder(null, messages, null)
                .setAgentGuidance("")
                .setFinalResponseOnly(false)
                .setIncludeNativeReferences(true);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.setExternalSystemPrompt(systemPrompt);
        }
        if (xmlFallbackTools != null) {
            builder.setExternalTools(xmlFallbackTools);
        }
        String providerId = com.saaspaymentsolutions.axion.SketchApplication.getContext()
                .getSharedPreferences(com.saaspaymentsolutions.axion.port.VoidPortSettings.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE)
                .getString(AiChatSettingsHelper.PREF_CURRENT_PROVIDER, "");
        ContextBuilder.Result request = builder.build(latestUserText(messages), chatMode, providerId);

        final CountDownLatch done = new CountDownLatch(1);
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final AtomicReference<String> finishReason = new AtomicReference<>("");
        final List<ToolCall> toolCalls = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<String> error = new AtomicReference<>(null);

        AiProviderService.StreamListener listener = new AiProviderService.StreamListener() {
            @Override
            public void onContent(String delta) {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                content.append(delta);
                if (deltaListenerHook != null) {
                    deltaListenerHook.accept(delta);
                }
            }

            @Override
            public void onReasoning(String delta) {
                if (delta != null) {
                    reasoning.append(delta);
                }
            }

            @Override
            public void onToolCall(String name, String arguments, String id) {
                if (name != null && !name.isEmpty()) {
                    toolCalls.add(new ToolCall(name, arguments == null ? "{}" : arguments, id));
                }
            }

            @Override
            public void onFinalMessage(String fullContent, String fullReasoning, String msgFinishReason) {
                if (fullContent != null && fullContent.length() > content.length()) {
                    // Providers that deliver one final message instead of deltas.
                    content.setLength(0);
                    content.append(fullContent);
                }
                finishReason.set(msgFinishReason == null ? "" : msgFinishReason);
                done.countDown();
            }

            @Override
            public void onDebug(String message) {
            }

            @Override
            public void onError(String message, Throwable t) {
                error.set(message == null ? "Unknown streaming error" : message);
                done.countDown();
            }
        };

        aiService.sendStreamingMessage(request, tools, chatMode, operationContext, listener);
        try {
            if (!done.await(10, TimeUnit.MINUTES)) {
                return timeoutTurn("Streaming turn timed out.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return timeoutTurn("Streaming turn interrupted.");
        }
        if (error.get() != null) {
            return timeoutTurn(error.get());
        }
        if (cancelRequested) {
            return timeoutTurn("Turn cancelled by user.");
        }

        // Native tool calls win; otherwise parse text-embedded calls
        // (XML/DSML/JSON) exactly like the legacy chat fallback.
        List<ToolCall> parsed = toolCalls;
        if (parsed.isEmpty()) {
            parsed = textToolDetector
                    .detect(new ToolCallResponse(content.toString(), reasoning.toString(), null))
                    .getToolCalls();
        }
        return new LlmTurnOutput(content.toString(), reasoning.toString(),
                finishReason.get(), parsed);
    }

    private static LlmTurnOutput timeoutTurn(String message) {
        return new LlmTurnOutput("Error: " + message, "", "error", null);
    }

    @Override
    public void cancel() {
        cancelRequested = true;
    }

    private static String latestUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && message.getType() == ChatMessage.TYPE_USER) {
                return message.getLlmContent();
            }
        }
        return "";
    }
}
