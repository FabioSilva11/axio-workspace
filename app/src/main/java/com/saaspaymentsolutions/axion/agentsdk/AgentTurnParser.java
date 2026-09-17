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
 * Parses a completed LLM turn into visible text + tool calls.
 *
 * <p>Native tool calls from the gateway pass through; the remaining text is
 * additionally scanned by the app's text-embedded protocol detectors
 * (XML/JSON/DSML) so providers without function calling still work.</p>
 */
final class AgentTurnParser {

    private final ToolCallDetector detector;

    AgentTurnParser() {
        this(new DefaultToolCallDetector());
    }

    AgentTurnParser(ToolCallDetector detector) {
        this.detector = detector == null ? new DefaultToolCallDetector() : detector;
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

    ParsedTurn parse(String rawContent,
                     String reasoning,
                     String finishReason,
                     List<ToolCall> nativeToolCalls) {
        List<ToolCall> calls = new ArrayList<>();
        if (nativeToolCalls != null) {
            for (ToolCall call : nativeToolCalls) {
                if (call != null && call.isValid()) {
                    calls.add(call);
                }
            }
        }

        ToolCallParseResult parsed = detector.detect(
                new ToolCallResponse(rawContent, reasoning, Collections.emptyList()));
        for (ToolCall call : parsed.getToolCalls()) {
            if (call != null && call.isValid() && !containsCall(calls, call)) {
                calls.add(call);
            }
        }

        return new ParsedTurn(
                parsed.getRemainingContent(),
                parsed.getRemainingReasoning(),
                finishReason,
                calls);
    }

    private static boolean containsCall(List<ToolCall> calls, ToolCall candidate) {
        for (ToolCall call : calls) {
            if (call.getId().equals(candidate.getId())
                    && call.getName().equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }
}
