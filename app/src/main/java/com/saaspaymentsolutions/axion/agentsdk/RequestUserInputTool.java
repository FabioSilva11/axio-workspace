package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.schema.ToolJsonSchema;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Map;

/**
 * Codex parity: {@code request_user_input}. When the task is ambiguous or a
 * decision belongs to the user, the model asks a structured question instead
 * of guessing. The pending question is routed through the {@link ApprovalHandler}
 * (the same human-in-the-loop boundary used for tool approvals) and the
 * answer is returned to the model as the tool result.
 *
 * <p>Fail-closed: with no handler attached, the tool returns an error telling
 * the model to proceed with the best default — it never blocks or crashes.</p>
 *
 * <p>This implementation uses {@link ToolJsonSchema} to construct the parameter
 * schema, following the Codex pattern from
 * {@code codex-rs/core/src/tools/handlers/request_user_input_spec.rs}.
 * The critical fix: {@code items} is always a single schema object, never an array.</p>
 */
public final class RequestUserInputTool implements AgentTool {

    private final ApprovalHandler handler;

    public RequestUserInputTool(ApprovalHandler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "request_user_input";
    }

    @Override
    public String description() {
        return "Asks the user a structured question when the task is ambiguous or a decision belongs to them. "
                + "Provide a short question, up to 4 concrete options, and whether free-form input is allowed. "
                + "Use it sparingly: prefer proceeding with a sensible default when the choice is reversible and low-impact.";
    }

    @Override
    public JSONObject parameters() {
        // Build the option schema: object with label (required) and description (optional)
        Map<String, ToolJsonSchema> optionProps = ToolJsonSchema.properties()
                .put("label", ToolJsonSchema.string("Short option name shown as a clickable choice."))
                .put("description", ToolJsonSchema.string("Optional. One line explaining the option."))
                .build();

        ToolJsonSchema optionSchema = ToolJsonSchema.object(
                optionProps,
                Arrays.asList("label"),
                false
        );

        // Build the main schema
        Map<String, ToolJsonSchema> mainProps = ToolJsonSchema.properties()
                .put("question", ToolJsonSchema.string("The question to show the user. One or two sentences."))
                .put("options", ToolJsonSchema.array(
                        optionSchema,
                        "Up to 4 concrete options. May be empty when free-form input is expected."))
                .put("allow_free_text", ToolJsonSchema.bool(
                        "True when the user may answer with their own text instead of an option."))
                .build();

        ToolJsonSchema schema = ToolJsonSchema.object(
                mainProps,
                Arrays.asList("question"),
                false
        );

        return schema.toJson();
    }

    @Override
    public boolean requiresApproval() {
        // The tool's whole purpose is to reach the user; it is itself the
        // consent boundary and needs no separate approval round-trip.
        return false;
    }

    @Override
    public AgentToolResult execute(RunContext context, JSONObject args) {
        String question = args == null ? "" : args.optString("question", "").trim();
        if (question.isEmpty()) {
            return AgentToolResult.error("Error: 'question' is required.");
        }
        if (handler == null) {
            return AgentToolResult.error(
                    "Error: no user input channel is available in this run. Proceed with the best safe default and state the assumption in your final answer.");
        }

        // Normalize options so the host receives a predictable payload.
        JSONArray normalized = new JSONArray();
        JSONArray raw = args.optJSONArray("options");
        for (int i = 0; raw != null && i < raw.length() && i < 4; i++) {
            JSONObject option = raw.optJSONObject(i);
            String label = option == null ? "" : option.optString("label", "").trim();
            if (label.isEmpty()) {
                continue;
            }
            try {
                JSONObject clean = new JSONObject().put("label", label);
                String description = option.optString("description", "").trim();
                if (!description.isEmpty()) {
                    clean.put("description", description);
                }
                normalized.put(clean);
            } catch (org.json.JSONException ignored) {
            }
        }

        try {
            JSONObject payload = new JSONObject()
                    .put("question", question)
                    .put("options", normalized)
                    .put("allow_free_text", args.optBoolean("allow_free_text", true));
            PermissionRequest request = new PermissionRequest(
                    "user_input_" + System.currentTimeMillis(),
                    name(),
                    null,
                    payload.toString());
            PermissionDecision decision = handler.awaitDecision(request);
            if (decision == PermissionDecision.DENY) {
                return AgentToolResult.success(
                        "The user dismissed the question. Proceed with the best safe default and state the assumption in your final answer.");
            }
            String answer = handler.lastResponseText();
            if (answer == null || answer.trim().isEmpty()) {
                return AgentToolResult.success(
                        "The user approved without providing an answer. Proceed with the best safe default and state the assumption in your final answer.");
            }
            return AgentToolResult.success("User answer: " + answer.trim());
        } catch (org.json.JSONException e) {
            return AgentToolResult.error("Error: could not build the question payload.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentToolResult.error("Error: the question was interrupted.");
        } catch (java.util.concurrent.TimeoutException e) {
            return AgentToolResult.success(
                    "The question timed out without an answer. Proceed with the best safe default and state the assumption in your final answer.");
        }
    }
}
