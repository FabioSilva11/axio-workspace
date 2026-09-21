package com.saaspaymentsolutions.axion.agentsdk;

/**
 * Wire protocol for handoffs: the {@link Runner} recognizes the structured
 * payload emitted by a handoff {@code ToolRegistration} and switches the active
 * agent for the next turn, mirroring how openai-agents-js threads handoffs
 * through the tool-call layer.
 */
final class HandoffProtocol {

    private HandoffProtocol() {}

    static final String HANDOFF_MARKER = "__axion_handoff__";

    static String resultPayload(Agent target) {
        try {
            return new org.json.JSONObject()
                    .put(HANDOFF_MARKER, target.name())
                    .toString();
        } catch (org.json.JSONException e) {
            // Cannot happen with a fixed key + String value; keep a safe fallback.
            return "{" + HANDOFF_MARKER + ":" + target.name() + "}";
        }
    }

    /** Returns the target agent name if {@code output} is a handoff payload. */
    static String targetAgentName(String output) {
        if (output == null || !output.contains(HANDOFF_MARKER)) {
            return null;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(output);
            String name = json.optString(HANDOFF_MARKER, "");
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }
}
