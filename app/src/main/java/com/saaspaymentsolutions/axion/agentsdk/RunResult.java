package com.saaspaymentsolutions.axion.agentsdk;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a {@link Runner} run, mirroring
 * {@code RunnerResult/RunResult} from openai-agents-js.
 */
public final class RunResult {

    enum Status { SUCCESS, MAX_TURNS, GUARDRAIL_BLOCKED, FAILED }

    private final Status status;
    private final String output;
    private final String failureReason;
    private final GuardrailResult guardrail;
    private final List<String> handoffTrail;

    private RunResult(Status status, String output, String failureReason,
                      GuardrailResult guardrail, List<String> handoffTrail) {
        this.status = status;
        this.output = output == null ? "" : output;
        this.failureReason = failureReason == null ? "" : failureReason;
        this.guardrail = guardrail;
        this.handoffTrail = handoffTrail == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(handoffTrail);
    }

    static RunResult success(String output, RunContext context) {
        return new RunResult(Status.SUCCESS, output, "", null, context.handoffTrail());
    }

    static RunResult maxTurnsReached(RunContext context, String partialOutput) {
        return new RunResult(Status.MAX_TURNS, partialOutput,
                "Max turns reached.", null, context.handoffTrail());
    }

    static RunResult blockedByGuardrail(GuardrailResult result) {
        return new RunResult(Status.GUARDRAIL_BLOCKED, "", "",
                result, Collections.emptyList());
    }

    static RunResult failure(String reason) {
        return new RunResult(Status.FAILED, "", reason, null, Collections.emptyList());
    }

    public boolean isSuccessful() {
        return status == Status.SUCCESS;
    }

    public boolean isBlockedByGuardrail() {
        return status == Status.GUARDRAIL_BLOCKED;
    }

    public boolean isMaxTurnsReached() {
        return status == Status.MAX_TURNS;
    }

    /** Final assistant text (also populated for partial max-turn runs). */
    public String getOutput() {
        return output;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public GuardrailResult getGuardrailResult() {
        return guardrail;
    }

    /** Ordered trail of handoffs that happened during the run. */
    public List<String> getHandoffTrail() {
        return handoffTrail;
    }
}
