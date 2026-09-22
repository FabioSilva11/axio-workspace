package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.ChatToolLog;

/**
 * Structured, secret-free logging for tool-selection decisions (Requirement
 * 27/60). Never logs tokens, Authorization headers, API keys, cookies or
 * other secrets — only the classification metadata needed to spot new
 * shell-vs-specialized-tool cases.
 */
final class ToolSelectionAudit {

    private ToolSelectionAudit() {
    }

    /** Logs a routine tool_selection decision (shell allowed as a legitimate fallback). */
    static void log(String scId, String selectedTool, boolean specializedAvailable, String fallbackReason) {
        ChatToolLog.d("tool_selection", "selected=" + selectedTool
                + " specialized_available=" + specializedAvailable
                + " fallback_reason=" + fallbackReason
                + " workspace_id=" + safe(scId));
    }

    /** Logs a tool_selection_violation: exec_command was requested despite a specialized tool existing. */
    static void logViolation(String scId, String requestedTool, String preferredTool) {
        ChatToolLog.w("tool_selection_violation", "requested=" + requestedTool
                + " preferred=" + preferredTool
                + " workspace_id=" + safe(scId));
    }

    private static String safe(String scId) {
        return scId == null || scId.isEmpty() ? "unknown" : scId;
    }
}
