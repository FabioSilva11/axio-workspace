package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.tools.AxionToolRegistry;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolRegistration;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolName;

import org.json.JSONObject;

import java.util.Collections;

/**
 * Materializes an {@link Agent}'s handoffs as registry tool registrations
 * (Codex / openai-agents parity): declaring handoffs registers a
 * {@code transfer_to_<agent>} FUNCTION per target. The runtime switches the
 * active agent when a routed registration reports
 * {@link ToolRegistration#isHandoff()}.
 *
 * <p>Handoffs are ordinary registry citizens — same catalog, same router, same
 * permission pipeline (always ALLOW: the transferring agents were configured
 * by the host). No {@code AgentTool} participates.</p>
 */
public final class HandoffRegistrations {

    /** Handoff tool names always keep this prefix ({@code transfer_to_<agent>}). */
    public static final String TRANSFER_PREFIX = "transfer_to_";
    /** Matches valid Java identifiers, which is also a safe function-name subset. */
    private static final String NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private HandoffRegistrations() {
    }

    /** Registers a {@code transfer_to_<target>} tool for every declared handoff. */
    public static void registerFor(AxionToolRegistry registry, Agent agent) {
        for (Agent target : agent.handoffs()) {
            String toolName = transferToNameFor(target);
            ToolRegistration registration = ToolRegistration.builder(ToolSpec.function(
                            ToolName.plain(toolName),
                            handoffDescription(target),
                            handoffParameters()))
                    .executor(ctx ->
                            AgentToolResult.success(HandoffProtocol.resultPayload(target)))
                    .source("handoff")
                    .handoffTarget(target.name())
                    .build();
            registry.register(registration);
        }
    }

    /** The model-visible name of a target's handoff tool. */
    public static String transferToNameFor(Agent target) {
        return TRANSFER_PREFIX + safeName(target);
    }

    private static String handoffDescription(Agent target) {
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

    private static JSONObject handoffParameters() {
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

    private static String safeName(Agent agent) {
        String name = agent.name();
        String cleaned = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || !cleaned.matches(NAME_PATTERN)) {
            cleaned = "agent_" + Integer.toHexString(System.identityHashCode(agent));
        }
        return cleaned;
    }
}