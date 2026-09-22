package com.saaspaymentsolutions.axion.agentsdk.tools;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime guard sitting in front of {@code exec_command}: classifies the
 * requested shell command with {@link ToolSelectionPolicy} and refuses to
 * execute it when a specialized tool clearly covers the same intent.
 *
 * <p>This is what makes "specialized tool &gt; shell" a guardrail rather than
 * a prompt hope. Even if the model ignores {@code
 * ToolSelectionPolicy#TOOL_USAGE_POLICY_PROMPT} and the tool description
 * hints, a command like {@code ls -la} never reaches the shell process.</p>
 *
 * <p>Requirement 26 (no shell -&gt; blocked -&gt; shell -&gt; blocked loops): after
 * a small number of rejections for the same (session, intent) pair within
 * one run, this policy stops re-explaining and just blocks silently-ish
 * (still returns a message, but a terser one) so the model is pushed to
 * actually switch tools instead of retrying the same shell command.</p>
 */
final class ShellFallbackPolicy {

    /** After this many rejections of the same intent in a run, stop re-explaining at length. */
    private static final int MAX_VERBOSE_REJECTIONS = 2;

    private static final Map<String, AtomicInteger> rejectionCounts = new ConcurrentHashMap<>();

    private ShellFallbackPolicy() {
    }

    /** Result of evaluating a proposed exec_command call. */
    static final class Verdict {
        final boolean allowed;
        final String blockedMessage;

        private Verdict(boolean allowed, String blockedMessage) {
            this.allowed = allowed;
            this.blockedMessage = blockedMessage;
        }

        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict block(String message) {
            return new Verdict(false, message);
        }
    }

    /**
     * @param sessionKey a per-run/session identifier (e.g. {@code scId}), used only to scope the
     *                    repeated-rejection counter — never logged with command content.
     * @param command    the raw {@code cmd} argument passed to exec_command.
     */
    static Verdict evaluate(String sessionKey, String command) {
        ToolSelectionPolicy.Decision decision = ToolSelectionPolicy.classifyShellCommand(command);
        if (!decision.hasSpecializedAlternative()) {
            ToolSelectionAudit.log(sessionKey, "exec_command", false, "no_specialized_tool");
            return Verdict.allow();
        }

        ToolSelectionAudit.logViolation(sessionKey, "exec_command", decision.preferredTool());

        String key = normalizeKey(sessionKey, command);
        int count = rejectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

        if (count > MAX_VERBOSE_REJECTIONS) {
            return Verdict.block("Blocked (repeated): use " + decision.preferredTool()
                    + " instead of exec_command for this. This intent has already been rejected "
                    + count + " times in this run; switch tools instead of retrying the shell command.");
        }
        return Verdict.block("A specialized tool is available for this operation. "
                + "Use " + decision.preferredTool() + " instead of exec_command. " + decision.reason());
    }

    /** Clears rejection counters for a finished run/session so state doesn't leak across runs. */
    static void resetSession(String sessionKey) {
        if (sessionKey == null) {
            return;
        }
        String prefix = sessionKey + "::";
        rejectionCounts.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String normalizeKey(String sessionKey, String command) {
        String normalizedCommand = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        // Collapse to the leading token(s) so "ls -la /foo" and "ls -la /bar"
        // count as the same intent rather than resetting the counter per arg.
        String[] parts = normalizedCommand.split("\\s+", 2);
        String head = parts.length > 0 ? parts[0] : normalizedCommand;
        return (sessionKey == null ? "" : sessionKey) + "::" + head;
    }
}
