package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.AgentTool;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges a legacy {@link AgentTool} into the registry-backed architecture:
 * its schema/description become the {@link ToolSpec}, its placeholder
 * {@link ToolExecutor} delegates to {@code AgentTool.execute} at runtime, and
 * its policy flags flow into the {@link ToolRegistration}. The legacy tool
 * thus becomes a normal registry citizen — the model catalog comes exclusively
 * from {@link AxionToolRegistry#modelVisibleTools()} and the
 * {@link AxionToolRouter} executes it.
 *
 * <p><b>Migration guard.</b> No legacy tool is registered blindly as
 * model-visible. Every tool is classified ({@link #classify(String)}) before
 * it may enter the registry: names superseded by a newer contract (e.g.
 * {@code run_command} → {@code exec_command}, {@code edit_file} →
 * {@code apply_patch}) are REJECTED from {@link #register(AgentTool)} so the
 * old name can never surface next to the new one. Hosts keep legacy tools the
 * model may still use by passing the explicit {@code LEGACY_MODEL_COMPATIBLE}
 * classification; internal machinery is registered with
 * {@link #registerHidden(AgentTool)}.</p>
 */
public final class LegacyToolAdapter {

    /**
     * Explicit classification legacy tools receive on the way into the
     * registry. The classification makes the migration deliberate instead of
     * implicit: no legacy name enters the model catalog by accident.
     */
    public enum LegacyKind {
        /** No newer contract exists — keep it model-visible via an explicit registration. */
        LEGACY_MODEL_COMPATIBLE,
        /** Internal implementation detail, never meant for the model. */
        LEGACY_INTERNAL,
        /** Superseded by a newer contract — the OLD name must not surface. */
        REPLACED,
        /** Intentionally never model-visible; an internal executor only. */
        HIDDEN
    }

    private LegacyToolAdapter() {
    }

    /**
     * Legacy names superseded by the Codex-parity contracts, mapped to the
     * registration that must be offered to the model instead.
     */
    private static final Map<String, String> REPLACED = new HashMap<>();

    static {
        REPLACED.put("run_command", "exec_command");
        REPLACED.put("run_persistent_command", "write_stdin");
        REPLACED.put("open_persistent_terminal", "exec_command");
        REPLACED.put("kill_persistent_terminal", "exec_command");
        REPLACED.put("edit_file", "apply_patch");
        REPLACED.put("rewrite_file", "apply_patch");
        REPLACED.put("create_file_or_folder", "apply_patch");
        REPLACED.put("delete_file_or_folder", "apply_patch");
        REPLACED.put("update_plan", "update_plan");
        REPLACED.put("request_user_input", "request_user_input");
        REPLACED.put("get_context_remaining", "get_context_remaining");
        REPLACED.put("apply_patch", "apply_patch");
        REPLACED.put("exec_command", "exec_command");
        REPLACED.put("write_stdin", "write_stdin");
        REPLACED.put("new_context", "new_context");
        REPLACED.put("tool_search", "tool_search");
        REPLACED.put("curr_time", "clock.curr_time");
        REPLACED.put("sleep", "clock.sleep");
    }

    /** Classifies a legacy tool by name (unknown names opt in explicitly). */
    public static LegacyKind classify(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return LegacyKind.HIDDEN;
        }
        if (toolName.startsWith("transfer_to_")) {
            return LegacyKind.LEGACY_INTERNAL;
        }
        return REPLACED.containsKey(toolName) ? LegacyKind.REPLACED : LegacyKind.LEGACY_MODEL_COMPATIBLE;
    }

    /** The canonical replacement for a {@link LegacyKind#REPLACED} name, or {@code null}. */
    public static String replacementFor(String toolName) {
        return toolName == null ? null : REPLACED.get(toolName);
    }

    /**
     * Builds a DIRECT (model-visible) FUNCTION registration for a legacy
     * agent tool. Names classified {@link LegacyKind#REPLACED} are rejected so
     * the old contract can never shadow the new one; internal legacy tools
     * must go through {@link #registerHidden(AgentTool)}.
     */
    public static ToolRegistration register(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool is required");
        }
        String name = tool.name() == null ? "" : tool.name();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a legacy tool must have a non-empty name");
        }
        LegacyKind kind = classify(name);
        if (kind == LegacyKind.REPLACED) {
            throw new IllegalArgumentException(
                    "legacy tool '" + name + "' is REPLACED by '" + replacementFor(name)
                            + "': register the new contract instead of the legacy name");
        }
        if (kind != LegacyKind.LEGACY_MODEL_COMPATIBLE) {
            throw new IllegalArgumentException(
                    "legacy tool '" + name + "' is " + kind + ": use registerHidden(...) for internal executors");
        }
        return build(tool, ToolExposure.direct());
    }

    /** Builds a HIDDEN registration (internal executor, never model-visible). */
    public static ToolRegistration registerHidden(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool is required");
        }
        String name = tool.name() == null ? "" : tool.name();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a legacy tool must have a non-empty name");
        }
        return build(tool, ToolExposure.hidden());
    }

    private static ToolRegistration build(AgentTool tool, ToolExposure exposure) {
        JSONObject parameters = tool.parameters();
        if (parameters == null) {
            parameters = new JSONObject();
        }
        return ToolRegistration.builder(ToolSpec.function(
                        ToolName.plain(tool.name()),
                        tool.description() == null ? "" : tool.description(),
                        parameters))
                .exposure(exposure)
                .executor(new AgentToolExecutor(tool))
                .source("legacy")
                .requiresApproval(tool.requiresApproval())
                .fileMutation(tool.isFileMutation())
                .destructive(tool.isDestructive())
                .build();
    }
}