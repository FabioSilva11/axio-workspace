package com.saaspaymentsolutions.axion.agentsdk.schema;

import java.util.List;

/**
 * Exception thrown when tool schema validation fails, providing clear
 * information about which tool and which part of the schema is invalid.
 *
 * <p>This exception is used to FAIL FAST before any HTTP request is made
 * to the provider, ensuring that invalid schemas never reach the LLM API.</p>
 */
public class ToolSchemaValidationException extends Exception {

    private final String toolName;
    private final String schemaPath;
    private final String reason;

    public ToolSchemaValidationException(String toolName, String schemaPath, String reason) {
        super(formatMessage(toolName, schemaPath, reason));
        this.toolName = toolName;
        this.schemaPath = schemaPath;
        this.reason = reason;
    }

    public ToolSchemaValidationException(List<String> validationErrors) {
        super(formatMultipleErrors(validationErrors));
        this.toolName = null;
        this.schemaPath = null;
        this.reason = null;
    }

    public String getToolName() {
        return toolName;
    }

    public String getSchemaPath() {
        return schemaPath;
    }

    public String getReason() {
        return reason;
    }

    private static String formatMessage(String toolName, String schemaPath, String reason) {
        return String.format(
                "Invalid tool schema:\n  tool=%s\n  path=%s\n  reason=%s",
                toolName, schemaPath, reason);
    }

    private static String formatMultipleErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder("Tool schema validation failed:\n");
        for (String error : errors) {
            sb.append(error).append("\n");
        }
        return sb.toString().trim();
    }
}
