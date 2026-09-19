package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolManager;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONObject;

/**
 * Registers the runtime policy tools into the legacy chat {@link ToolManager}:
 * {@code apply_patch} (new capability) and a sandboxed {@code run_command}
 * that enforces the {@link CommandSandbox} before spawning any process.
 *
 * <p>The chat {@code run_command} keeps its original behavior (the registry
 * wins registration conflicts), while the runtime's {@link AgentRuntime} uses
 * the sandbox-aware variant; {@code apply_patch} is additive in both worlds.</p>
 */
public final class RuntimePolicyTools {

    private RuntimePolicyTools() {
    }

    /** Registers the additive tools; safe to call multiple times. */
    public static void register(ToolManager manager, String scId, EventStream events) {
        if (manager == null) {
            return;
        }
        // apply_patch is additive: no registry tool conflicts with it.
        // The sandboxed run_command lives in the AgentRuntime toolset
        // (SandboxAwareRunCommand is an AgentTool, not a chat Tool).
        manager.registerTool(new ApplyPatchChatTool(scId, events));
    }

    /**
     * Chat-facing adapter of {@link ApplyPatchTool}: implements the legacy
     * {@link Tool} interface, delegates execution to the shared implementation.
     */
    private static final class ApplyPatchChatTool implements Tool {
        private final ApplyPatchTool delegate;

        ApplyPatchChatTool(String scId, EventStream events) {
            this.delegate = new ApplyPatchTool(scId, events);
        }

        @Override
        public String getName() {
            return delegate.name();
        }

        @Override
        public String getDescription() {
            return delegate.description();
        }

        @Override
        public JSONObject getParameters() {
            return delegate.parameters();
        }

        @Override
        public String execute(String scId, JSONObject args) {
            AgentToolResult result = delegate.execute(null, args);
            return result.output();
        }

        @Override
        public boolean requiresApproval() {
            return true;
        }

        @Override
        public boolean isDestructive() {
            return true;
        }

        @Override
        public boolean isFileMutation() {
            return true;
        }
    }

    /**
     * Sandbox-aware shell tool: validates the command against the
     * {@link CommandSandbox} before execution and implements
     * {@link SandboxAwareTool} so the {@link AgentRuntime} policy applies.
     * Delegates the actual process execution to the registry's run_command.
     */
    public static final class SandboxAwareRunCommand implements SandboxAwareTool {
        private final com.saaspaymentsolutions.axion.Tool delegate;
        private final CommandSandbox sandbox;
        private final EventStream events;

        public SandboxAwareRunCommand(EventStream events) {
            this(events, null);
        }

        public SandboxAwareRunCommand(EventStream events, String workspaceRoot) {
            this.events = events;
            this.sandbox = new CommandSandbox(workspaceRoot);
            this.delegate = new com.saaspaymentsolutions.axion.port.VoidToolWrapper(
                    "run_command",
                    "Executes a shell command inside the workspace, subject to the sandbox policy.",
                    defaultParams(),
                    true, true, false);
        }

        @Override
        public String name() {
            return delegate.getName();
        }

        @Override
        public String description() {
            return delegate.getDescription();
        }

        @Override
        public JSONObject parameters() {
            return delegate.getParameters();
        }

        @Override
        public AgentToolResult execute(RunContext context, JSONObject args) {
            try {
                String output = delegate.execute(context == null ? "" : context.scId(), args);
                return AgentToolResult.success(output);
            } catch (Exception e) {
                return AgentToolResult.error("Error: " + e.getMessage());
            }
        }

        @Override
        public ToolPolicy.Rule policyRule() {
            return ToolPolicy.Rule.ASK_USER;
        }

        @Override
        public boolean isFileMutation() {
            return true; // a shell command can mutate files
        }

        @Override
        public AgentToolResult preExecute(ToolCall call) {
            String command = extractCommand(call);
            CommandSandbox.Violation violation = sandbox.validate(command);
            if (violation == null) {
                return null;
            }
            if (events != null) {
                events.emit(new AgentEvent.SandboxViolation(
                        contextScId(), name(), violation.getReason()));
            }
            return AgentToolResult.error("Error: sandbox violation — " + violation.getReason());
        }

        private String contextScId() {
            return ""; // filled by the runtime event stream in practice
        }

        private static String extractCommand(ToolCall call) {
            if (call == null) {
                return "";
            }
            try {
                JSONObject args = new JSONObject(call.getArguments());
                return args.optString("command", args.optString("cmd", ""));
            } catch (Exception e) {
                return "";
            }
        }

        private static JSONObject defaultParams() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("command", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "O comando shell a executar.")))
                        .put("required", new org.json.JSONArray().put("command"));
            } catch (org.json.JSONException e) {
                return new JSONObject();
            }
        }
    }
}
