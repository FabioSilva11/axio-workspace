package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

/**
 * Codex parity: {@code get_context_remaining}. Lets the model see how much
 * of the run's token budget remains before it is exhausted, so it can finish
 * early, summarize, or stop calling tools instead of being cut mid-task by
 * the {@link RunBudget} gate.
 *
 * <p>Stateless by design: the per-run {@link ContextTracker} is attached to
 * the {@link RunContext} by the {@link AgentRuntime}, so every call reports
 * the numbers of the run actually in progress.</p>
 */
public final class ContextRemainingTool implements AgentTool {

    @Override
    public String name() {
        return "get_context_remaining";
    }

    @Override
    public String description() {
        return "Reports how much of the current run's context/token budget has been used and how much remains. "
                + "Call this when a long task makes you unsure whether you can finish within the limit: "
                + "if remaining is low, wrap up, summarize the state, and tell the user what is left instead of continuing to call tools.";
    }

    @Override
    public JSONObject parameters() {
        try {
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject())
                    .put("required", new org.json.JSONArray())
                    .put("additionalProperties", false);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public AgentToolResult execute(RunContext context, JSONObject args) {
        ContextTracker tracker = context == null ? null : context.contextTracker();
        if (tracker == null) {
            try {
                return AgentToolResult.success(new JSONObject()
                        .put("budget_enforced", false)
                        .put("note", "Context accounting is not available in this run. "
                                + "Wrap up when the task is complete.")
                        .toString());
            } catch (org.json.JSONException e) {
                return AgentToolResult.error("Error: could not build the context report.");
            }
        }
        return AgentToolResult.success(tracker.report().toString());
    }
}
