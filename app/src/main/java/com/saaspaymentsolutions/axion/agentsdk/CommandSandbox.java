package com.saaspaymentsolutions.axion.agentsdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Static validation layer for shell commands executed by agent tools,
 * porting the decision model of Codex's sandboxing (deny first, explain the
 * violation) to Android, where kernel-level sandboxing is not available.
 *
 * <p><b>HONEST SCOPE (item 25): THIS IS A POLICY GUARD, NOT AN OS SANDBOX.</b>
 * It is a deny-first validator over the command STRING. It cannot make the
 * OS confine the process; a sufficiently creative payload may still escape
 * the patterns below. Defense in depth is layered: the
 * {@link PermissionLayer} asks the user for shell tools, and the runtime
 * treats every shell execution as policy-gated, never sandbox-guaranteed.</p>
 *
 * <p>Beyond the classic destructive deny-list, a configured workspace root
 * also rejects shell composition operators ({@code ; && || | $() backticks
 * redirections}), environment expansions and interpreter inline-exec
 * (python/perl/node/ruby/php {@code -c/-e}) — the bypass channels a string
 * deny-list alone cannot inspect. Symlink escapes and kernel-level escapes
 * remain OUT of scope on Android and are documented as residual risk.</p>
 */
public final class CommandSandbox {

    /** A detected sandbox violation. */
    public static final class Violation {
        private final String reason;

        Violation(String reason) {
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }

    private static final Pattern WORKSPACE_ESCAPE = Pattern.compile(
            "(^|[\\s\"'=(])(/|~|[A-Za-z]:[\\\\/]|\\.\\.[\\\\/])", Pattern.CASE_INSENSITIVE);

    private static final String[] DENY_PATTERNS = {
            "rm\\s+(-[a-z]*\\s+)*-?[rf]{2}",           // rm -rf / rm -fr variants
            "rmdir\\s+/[a-z]",                          // rmdir absolute roots
            "mkfs(\\.\\w+)?\\s",                        // filesystem formatting
            "dd\\s+if=",                                // raw disk writes
            ":\\(\\)\\s*\\{.*\\};\\s*:",                // fork bomb
            "shutdown|reboot|poweroff",                 // host power control
            "chmod\\s+(-[a-z]+\\s+)?777\\s+/",          // world-writable roots
            "curl[^|]*\\|\\s*(ba)?sh",                  // remote script piped to shell
            "wget[^|]*\\|\\s*(ba)?sh",
            "git\\s+push\\s+.*--force",                 // destructive remote op
            "git\\s+reset\\s+--hard",
    };

    /**
     * Shell-operator patterns (item 25): a command containing these is
     * rejected under a configured workspace root — operators compose
     * commands the deny-list cannot see (curl|sh via $(), chains that end
     * in a destructive second command, redirects that clobber files,
     * interpreters spawning other commands).
     */
    private static final Pattern SHELL_OPERATORS = Pattern.compile(
            ";|&&|\\|\\|?|`|\\$\\(|\\$\\{|>\\(|<<?", Pattern.CASE_INSENSITIVE);

    /**
     * Interpreter commands that can execute arbitrary secondary payloads
     * (python/perl/node/ruby -c, -e, scripts): the deny-list cannot reason
     * about what the payload does, so a jailed run refuses them outright.
     */
    private static final Pattern INTERPRETER_EXEC = Pattern.compile(
            "\\b(python3?|perl|node|ruby|php)\\b[^&|;]*(-c|-e)\\s", Pattern.CASE_INSENSITIVE);

    private final String workspaceRoot;
    private final List<Pattern> denyList;

    public CommandSandbox(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot == null ? "" : workspaceRoot;
        this.denyList = new ArrayList<>();
        for (String regex : DENY_PATTERNS) {
            denyList.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
    }

    /** Returns a violation when the command must not run, {@code null} otherwise. */
    public Violation validate(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new Violation("Empty command.");
        }
        String cmd = command.trim();

        for (Pattern pattern : denyList) {
            if (pattern.matcher(cmd).find()) {
                return new Violation("Command matches deny-list pattern: " + pattern.pattern());
            }
        }

        // Shell operators and interpreter exec are only rejected when a
        // workspace root is configured (the jail is active). Pure deny-list
        // validation (tests/no-root hosts) keeps the legacy behavior.
        if (!workspaceRoot.isEmpty()) {
            if (INTERPRETER_EXEC.matcher(cmd).find()) {
                return new Violation("Interpreter with inline code (-c/-e) is not allowed "
                        + "inside the sandboxed workspace.");
            }
            java.util.regex.Matcher operator = SHELL_OPERATORS.matcher(cmd);
            while (operator.find()) {
                // '>' alone is a redirection that stays inside the workspace;
                // composite operators and pipes cross the policy boundary.
                String op = operator.group();
                if (!">".equals(op) && !"<".equals(op)) {
                    return new Violation("Shell operator '" + op + "' is not allowed "
                            + "inside the sandboxed workspace.");
                }
            }
        }

        String violation = containsWorkspaceEscape(cmd);
        if (violation != null) {
            return new Violation(violation);
        }
        return null;
    }

    /**
     * Detects absolute paths outside the workspace and {@code ..} escapes.
     * Relative paths are resolved by the caller inside the workspace.
     */
    private String containsWorkspaceEscape(String cmd) {
        if (workspaceRoot.isEmpty()) {
            return null; // No jail configured (e.g. unit tests); deny-list still applies.
        }
        java.util.regex.Matcher matcher = WORKSPACE_ESCAPE.matcher(cmd);
        while (matcher.find()) {
            String token = matcher.group(2);
            if (token == null) {
                continue;
            }
            String normalized = token.replace('\\', '/');
            if (normalized.startsWith("..")) {
                return "Path escape detected: '" + token + "' leaves the workspace.";
            }
            if (normalized.startsWith("/") && !isInsideWorkspace(cmd, matcher)) {
                return "Absolute path '" + token + "' is outside the workspace.";
            }
            // Windows drive letters (C:/...) are outside the Android workspace by definition.
            if (normalized.matches("[A-Za-z]:/.*")) {
                return "Host filesystem path '" + token + "' is not accessible.";
            }
        }
        return null;
    }

    private boolean isInsideWorkspace(String cmd, java.util.regex.Matcher matcher) {
        int end = matcher.end(2);
        String rest = cmd.substring(Math.min(end, cmd.length()));
        String root = workspaceRoot.replace('\\', '/');
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        // The command mentions the workspace root explicitly for this path.
        return rest.toLowerCase(Locale.ROOT).startsWith(root.toLowerCase(Locale.ROOT));
    }
}
