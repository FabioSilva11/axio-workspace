package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

import java.util.Collections;

/**
 * Handoff tool auto-generated from an {@link Agent}'s handoff list,
 * mirroring openai-agents-js: declaring handoffs materializes a
 * {@code transfer_to_<agent>} function the model can call.
 */
public final class HandoffTool implements AgentTool {

    /** Handoff tool names always keep this prefix ({@code transfer_to_<agent>}). */
    public static final String TRANSFER_PREFIX = "transfer_to_";
    /** Matches valid Java identifiers, which is also a safe function-name subset. */
    private static final String NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final Agent target;

    HandoffTool(Agent target) {
        this.target = target;
    }

    Agent target() {
        return target;
    }

    static String toolNameFor(Agent agent) {
        return TRANSFER_PREFIX + safeName(agent);
    }

    private static String safeName(Agent agent) {
        String name = agent.name();
        String cleaned = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || !cleaned.matches(NAME_PATTERN)) {
            cleaned = "agent_" + Integer.toHexString(System.identityHashCode(agent));
        }
        return cleaned;
    }

    @Override
    public String name() {
        return toolNameFor(target);
    }

    @Override
    public String description() {
        String base = "Hand off the conversation to the '" + safeName(target) + "' agent";
        if (target.instructions().isEmpty()) {
            return base + ".";
        }
        return base + ": " + firstSentence(target.instructions())
                + ". Use this when the request matches this agent's specialty.";
    }

    private static String firstSentence(String text) {
        String trimmed = text.trim();
        int cut = trimmed.indexOf('\n');
        if (cut > 0) {
            trimmed = trimmed.substring(0, cut);
        }
        return trimmed.length() > 140 ? trimmed.substring(0, 140) + "…" : trimmed;
    }

    @Override
    public JSONObject parameters() {
        try {
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("reason", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Why the handoff is needed.")))
                    .put("required", Collections.emptyList());
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public AgentToolResult execute(RunContext context, JSONObject args) {
        // The Runner owns handoff bookkeeping (it knows the source agent and
        // the reason argument); this tool only returns the wire marker.
        return AgentToolResult.success(HandoffProtocol.resultPayload(target));
    }
}
