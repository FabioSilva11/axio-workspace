package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

/**
 * Per-run context accounting shared with the {@link ContextRemainingTool}.
 * The runtime records the real input size after every turn and the settled
 * token cost after every turn, so the tool reports measured numbers — not
 * model-visible guesses. Mirrors the intent of Codex's
 * {@code get_context_remaining}: transparency about context consumption.
 */
public final class ContextTracker {

    private final RunBudget budget;
    private volatile long lastInputTokens;
    private volatile long settledTokens;

    public ContextTracker(RunBudget budget) {
        this.budget = budget;
    }

    /** Called by the runtime after each LLM turn (measured input size). */
    public void recordInputEstimate(long tokens) {
        if (tokens > 0) {
            lastInputTokens = tokens;
        }
    }

    /** Called by the runtime after each settled turn (real token cost). */
    public void recordSettled(long tokens) {
        if (tokens > 0) {
            settledTokens += tokens;
        }
    }

    public long lastInputTokens() {
        return lastInputTokens;
    }

    public long settledTokens() {
        return settledTokens;
    }

    /** Builds the JSON report consumed by {@link ContextRemainingTool}. */
    public JSONObject report() {
        JSONObject report = new JSONObject();
        try {
            if (budget != null) {
                report.put("budget_enforced", true)
                        .put("used_tokens", budget.spent())
                        .put("maximum_tokens", budget.maximum())
                        .put("remaining_tokens", budget.remaining())
                        .put("blocked", budget.isBlocked())
                        .put("last_input_tokens", lastInputTokens);
            } else {
                report.put("budget_enforced", false)
                        .put("settled_tokens_this_run", settledTokens)
                        .put("last_input_tokens", lastInputTokens)
                        .put("note", "No hard budget is configured for this run. "
                                + "Summarize and wrap up when the task is complete.");
            }
            return report;
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }
}
