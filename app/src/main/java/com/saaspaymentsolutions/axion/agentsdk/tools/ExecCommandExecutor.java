package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.port.VoidPortToolsService;

import org.json.JSONObject;

/**
 * Core {@code exec_command} + {@code write_stdin} executors implementing the
 * Codex {@code shell_spec.rs} contract:
 *
 * <ul>
 *   <li>{@code exec_command}: {@code cmd} required + {@code workdir},
 *       {@code tty}, {@code yield_time_ms} (Windows default 10000, effective
 *       10000-30000 on Windows / 250-30000 elsewhere), {@code max_output_tokens}. </li>
 *   <li>{@code write_stdin}: {@code session_id} + {@code chars},
 *       {@code yield_time_ms}, {@code max_output_tokens}.</li>
 * </ul>
 *
 * <p>Execution is delegated to the internal Android terminal/process
 * machinery ({@link VoidPortToolsService}) so the model-facing contract is
 * Codex-shaped while the executor stays a thin adapter — the processors
 * remain the authoritative runtime.</p>
 */
final class ExecCommandExecutor implements ToolExecutor {

    private static final String RUN_COMMAND = "run_command";
    private static final String RUN_PERSISTENT_COMMAND = "run_persistent_command";

    @Override
    public AgentToolResult execute(ToolExecutionContext ctx) {
        JSONObject args = ctx.functionArguments();
        if (args == null || !args.has("cmd")) {
            return AgentToolResult.error(
                    "Error: invalid exec_command arguments; the required 'cmd' string is missing.");
        }
        String cmd = args.optString("cmd", "").trim();
        if (cmd.isEmpty()) {
            return AgentToolResult.error("Error: 'cmd' must not be empty.");
        }
        // Guardrail (not just prompt hope): reject commands that a
        // specialized workspace tool already covers, per ToolSelectionPolicy.
        ShellFallbackPolicy.Verdict verdict = ShellFallbackPolicy.evaluate(ctx.scId(), cmd);
        if (!verdict.allowed) {
            return AgentToolResult.error(verdict.blockedMessage);
        }
        // Legacy internal bridging: the Void service understands command/cwd/
        // timeout_seconds; map the Codex keys onto it.
        try {
            JSONObject internal = new JSONObject();
            internal.put("command", cmd);
            if (args.has("workdir")) {
                internal.put("cwd", args.optString("workdir"));
            }
            String output = VoidPortToolsService.executeTool(ctx.scId(), RUN_COMMAND, internal);
            return AgentToolResult.success(output);
        } catch (Throwable t) {
            // Fail-closed: a missing/unstarted host channel (or an Android
            // static-init error under tests) must surface as an error result,
            // never crash the model loop.
            return AgentToolResult.error("Error: failed to execute command: "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    /** write_stdin executor: bridged to the persistent terminal writer. */
    static final ToolExecutor WRITE_STDIN = ctx -> {
        JSONObject args = ctx.functionArguments();
        if (args == null || !args.has("session_id")) {
            return AgentToolResult.error(
                    "Error: invalid write_stdin arguments; the required 'session_id' number is missing.");
        }
        String sessionId = String.valueOf(args.opt("session_id"));
        String chars = args.optString("chars", "");
        // Codex-style contract: empty `chars` means "poll session output
        // without writing", not an error. Do not reject it.
        try {
            JSONObject internal = new JSONObject();
            internal.put("command", chars);
            internal.put("persistent_terminal_id", sessionId);
            internal.put("poll_only", chars.isEmpty());
            String output = VoidPortToolsService.executeTool(
                    ctx.scId(), RUN_PERSISTENT_COMMAND, internal);
            return AgentToolResult.success(output);
        } catch (Throwable t) {
            return AgentToolResult.error(
                    "Error: failed to write stdin to session " + sessionId + ": "
                            + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    };
}