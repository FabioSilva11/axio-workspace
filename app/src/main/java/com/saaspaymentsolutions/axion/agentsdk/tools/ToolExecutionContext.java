package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.RunContext;

import org.json.JSONObject;

/**
 * One execution request resolved by the {@link AxionToolRouter}: the
 * registration identity, the run-scoped {@link RunContext} and either the
 * parsed FUNCTION arguments OR the raw FREEFORM input — never both.
 */
public final class ToolExecutionContext {

    private final ToolRegistration registration;
    private final String scId;
    private final String callId;
    private final RunContext runContext;
    private final JSONObject functionArguments;
    private final String freeformInput;
    private final String rawArguments;

    ToolExecutionContext(ToolRegistration registration, String scId, String callId,
                         RunContext runContext, JSONObject functionArguments,
                         String freeformInput, String rawArguments) {
        this.registration = registration;
        this.scId = scId == null ? "" : scId;
        this.callId = callId;
        this.runContext = runContext;
        this.functionArguments = functionArguments;
        this.freeformInput = freeformInput;
        this.rawArguments = rawArguments;
    }

    /** The registration being executed. */
    public ToolRegistration registration() {
        return registration;
    }

    /** The session/workspace id of the run. */
    public String scId() {
        return scId;
    }

    /** The provider tool-call id (dedupe/audit). */
    public String callId() {
        return callId;
    }

    /** The run-scoped context (workspace, tracker, task memory). */
    public RunContext runContext() {
        return runContext;
    }

    /** Parsed FUNCTION arguments; {@code null} for freeform tools. */
    public JSONObject functionArguments() {
        return functionArguments;
    }

    /** Raw FREEFORM input; {@code null} for function tools. */
    public String freeformInput() {
        return freeformInput;
    }

    /** The untrimmed wire arguments string (diagnostics). */
    public String rawArguments() {
        return rawArguments;
    }
}