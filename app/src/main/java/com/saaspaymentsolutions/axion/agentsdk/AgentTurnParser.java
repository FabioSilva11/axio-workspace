package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.DefaultToolCallDetector;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;
import com.saaspaymentsolutions.axion.toolcalling.ToolCallDetector;
import com.saaspaymentsolutions.axion.toolcalling.ToolCallParseResult;
import com.saaspaymentsolutions.axion.toolcalling.ToolCallResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts a normalized LLM turn into visible text + structured tool calls.
 *
 * <p><b>Contract (tool-call execution architecture):</b> the v2 path NEVER
 * derives tool calls from assistant text. Only the provider's structured
 * envelope (native tool calls, already accumulated by id during streaming)
 * reaches the {@link AgentRuntime}'s {@link com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRouter}.
 * Assistant text
 * is displayable content and stays displayable — a JSON/XML/DSML block in
 * the text is text, not a tool call.</p>
 *
 * <p>The app's text-embedded protocol detectors (XML/JSON/DSML) remain
 * available ONLY through {@link #parseLegacyTextEmbedded} for explicitly
 * legacy hosts (the retired AgentManager loop must enable it deliberately).
 * They never feed the v2 runtime silently.</p>
 */
final class AgentTurnParser {

    private final ToolCallDetector detector;
    private final boolean legacyTextEmbeddedEnabled;

    AgentTurnParser() {
        this(new DefaultToolCallDetector(), false);
    }

    /**
     * @param legacyTextEmbeddedEnabled when {@code true}, text-embedded
     *        protocols are parsed as a LEGACY compatibility path; the v2
     *        runtime uses the default constructor, which keeps them OFF.
     */
    AgentTurnParser(ToolCallDetector detector, boolean legacyTextEmbeddedEnabled) {
        this.detector = detector == null ? new DefaultToolCallDetector() : detector;
        this.legacyTextEmbeddedEnabled = legacyTextEmbeddedEnabled;
    }

    static final class ParsedTurn {
        final String content;
        final String reasoning;
        final String finishReason;
        final List<ToolCall> toolCalls;

        ParsedTurn(String content, String reasoning, String finishReason, List<ToolCall> toolCalls) {
            this.content = content == null ? "" : content;
            this.reasoning = reasoning == null ? "" : reasoning;
            this.finishReason = finishReason == null ? "" : finishReason;
            this.toolCalls = Collections.unmodifiableList(toolCalls);
        }

        boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        String content() {
            return content == null ? "" : content;
        }

        String reasoning() {
            return reasoning == null ? "" : reasoning;
        }

        String finishReason() {
            return finishReason == null ? "" : finishReason;
        }

        List<ToolCall> toolCalls() {
            return toolCalls;
        }
    }

    /**
     * v2 conversion: native/structured calls pass through (validated,
     * deduplicated by callId); text is kept as text — always.
     */
    ParsedTurn parse(String rawContent,
                     String reasoning,
                     String finishReason,
                     List<ToolCall> nativeToolCalls) {
        return parseInternal(rawContent, reasoning, finishReason, nativeToolCalls, false);
    }

    /**
     * LEGACY compatibility path for retired hosts that relied on
     * text-embedded protocols (XML/JSON/DSML) from providers without
     * function calling. Must be enabled explicitly; never used by the v2
     * runtime.
     */
    ParsedTurn parseLegacyTextEmbedded(String rawContent,
                                       String reasoning,
                                       String finishReason,
                                       List<ToolCall> nativeToolCalls) {
        return parseInternal(rawContent, reasoning, finishReason, nativeToolCalls, true);
    }

    private ParsedTurn parseInternal(String rawContent,
                                     String reasoning,
                                     String finishReason,
                                     List<ToolCall> nativeToolCalls,
                                     boolean allowTextEmbedded) {
        List<ToolCall> calls = new ArrayList<>();
        if (nativeToolCalls != null) {
            for (ToolCall call : nativeToolCalls) {
                if (call != null && call.isValid() && !containsCallId(calls, call)) {
                    calls.add(call);
                }
            }
        }

        if (allowTextEmbedded && legacyTextEmbeddedEnabled) {
            ToolCallParseResult parsed = detector.detect(
                    new ToolCallResponse(rawContent, reasoning, Collections.emptyList()));
            for (ToolCall call : parsed.getToolCalls()) {
                if (call != null && call.isValid() && !containsCallId(calls, call)) {
                    calls.add(call);
                }
            }
            return new ParsedTurn(
                    parsed.getRemainingContent(),
                    parsed.getRemainingReasoning(),
                    finishReason,
                    calls);
        }

        // v2: text is content, verbatim.
        return new ParsedTurn(rawContent, reasoning, finishReason, calls);
    }

    /**
     * Deduplication by callId (never by name): two apply_patch calls with
     * different ids are two legitimate calls; the same id twice is one call.
     */
    private static boolean containsCallId(List<ToolCall> calls, ToolCall candidate) {
        for (ToolCall call : calls) {
            if (call.getId().equals(candidate.getId())) {
                return true;
            }
        }
        return false;
    }
}
