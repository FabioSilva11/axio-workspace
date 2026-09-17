package com.saaspaymentsolutions.axion.agentsdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable per-run state threaded through every tool call and turn,
 * mirroring {@code RunContext} from openai-agents-js (typed local context +
 * usage tracker). Simplified here to a key-value store with usage counters.
 */
final class RunContext {

    private final String scId;
    private final long startedAt = System.currentTimeMillis();
    private final List<String> handoffTrail = new ArrayList<>();
    private int llmCalls;
    private int toolCalls;
    private String currentAgentName;

    RunContext(String scId, String initialAgentName) {
        this.scId = scId == null ? "" : scId;
        this.currentAgentName = initialAgentName;
    }

    String scId() {
        return scId;
    }

    String currentAgentName() {
        return currentAgentName;
    }

    void setCurrentAgentName(String name) {
        if (name != null && !name.isEmpty()) {
            this.currentAgentName = name;
        }
    }

    void incrementLlmCalls() {
        llmCalls++;
    }

    void incrementToolCalls() {
        toolCalls++;
    }

    int llmCalls() {
        return llmCalls;
    }

    int toolCalls() {
        return toolCalls;
    }

    void recordHandoff(Agent from, Agent to, String reason) {
        handoffTrail.add(from.name() + " -> " + to.name()
                + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
    }

    /** Ordered trail of handoffs in this run (for trace/logs). */
    List<String> handoffTrail() {
        return Collections.unmodifiableList(handoffTrail);
    }
}
