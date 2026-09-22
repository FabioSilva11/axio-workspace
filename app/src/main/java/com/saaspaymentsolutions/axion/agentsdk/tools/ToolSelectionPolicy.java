package com.saaspaymentsolutions.axion.agentsdk.tools;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Answers exactly one question: <b>which tool should be used for this
 * intent?</b>
 *
 * <p>This is deliberately NOT {@code PermissionEvaluator}. Permission
 * answers "can this run?"; {@code ToolSelectionPolicy} answers "which tool is
 * the right one, before permission is even considered?" The intended flow
 * is:</p>
 *
 * <pre>
 * model chooses a tool
 *   -> ToolSelectionPolicy   (is there a better/specialized tool for this?)
 *   -> PermissionEvaluator   (ALLOW / PROMPT / DENY)
 *   -> AxionToolRouter
 *   -> executor
 * </pre>
 *
 * <p>The FUNDAMENTAL RULE this policy encodes: SPECIALIZED TOOL &gt; SHELL.
 * {@code exec_command} is only correct when no specialized {@code
 * ToolRegistration} can perform the requested operation. This class is the
 * single place that rule lives in code — the system prompt text in {@link
 * WorkspaceToolProvider}'s tool descriptions and {@code
 * TOOL_USAGE_POLICY_PROMPT} restate the same rule for the model, but the
 * guardrail here is what actually stops a misrouted {@code exec_command}
 * call from running (see {@link ShellFallbackPolicy}).</p>
 */
public final class ToolSelectionPolicy {

    private ToolSelectionPolicy() {
    }

    /** The permanent system-prompt instruction (Requirement 9). Kept here, not as a loose string. */
    public static final String TOOL_USAGE_POLICY_PROMPT =
            "TOOL USAGE POLICY\n\n"
            + "Always prefer a specialized workspace tool over exec_command.\n\n"
            + "Use:\n"
            + "- read_file for reading files\n"
            + "- ls_dir/get_dir_tree for directory inspection\n"
            + "- search_pathnames_only/search_for_files/search_in_file for searching\n"
            + "- create_file_or_folder for creation\n"
            + "- edit_file/apply_patch for edits\n"
            + "- rewrite_file for full replacement\n"
            + "- delete_file_or_folder for deletion\n"
            + "- move_file/rename_file/copy_file for filesystem operations\n\n"
            + "Use exec_command only when no specialized tool can perform the requested operation.\n\n"
            + "Never replace a specialized tool with an equivalent shell command.\n\n"
            + "Use apply_patch for file edits. Do not reproduce apply_patch through exec_command. "
            + "Do not invoke shell commands that emulate apply_patch.";

    /** One classified outcome: the preferred tool name, or {@code null} if shell is legitimate. */
    public static final class Decision {
        private final String preferredTool;
        private final String reason;

        private Decision(String preferredTool, String reason) {
            this.preferredTool = preferredTool;
            this.reason = reason;
        }

        /** {@code null} means: no specialized tool applies, exec_command is a legitimate fallback. */
        public String preferredTool() {
            return preferredTool;
        }

        /** Human-readable reason, safe to surface back to the model. */
        public String reason() {
            return reason;
        }

        public boolean hasSpecializedAlternative() {
            return preferredTool != null;
        }

        static Decision specialized(String tool, String reason) {
            return new Decision(tool, reason);
        }

        static Decision shellAllowed() {
            return new Decision(null, "no specialized tool matches this command");
        }
    }

    // Ordered rules: first match wins. Patterns intentionally look for
    // WHOLE-WORD command names at the start of a (possibly compound) shell
    // statement so "git status" isn't mistaken for anything and "pythons"
    // isn't mistaken for "python". Conservative by design: an unmatched
    // command is left to shell (Requirement 12 — no aggressive false positives).
    private static final Pattern LS_LIKE = Pattern.compile("(^|[;&|]\\s*)(ls|dir|tree)\\b");
    private static final Pattern CAT_LIKE = Pattern.compile("(^|[;&|]\\s*)(cat|head|tail|sed\\s+-n)\\b");
    private static final Pattern GREP_LIKE = Pattern.compile("(^|[;&|]\\s*)(grep|rg|ag|find)\\b");
    private static final Pattern MKDIR_LIKE = Pattern.compile("(^|[;&|]\\s*)(mkdir|touch)\\b");
    private static final Pattern RM_LIKE = Pattern.compile("(^|[;&|]\\s*)(rm|rmdir)\\b");
    private static final Pattern MV_LIKE = Pattern.compile("(^|[;&|]\\s*)mv\\b");
    private static final Pattern CP_LIKE = Pattern.compile("(^|[;&|]\\s*)cp\\b");
    private static final Pattern DESTRUCTIVE_EDIT_LIKE = Pattern.compile(
            "(^|[;&|]\\s*)(sed\\s+-i|perl\\s+-i)\\b|(>>?\\s*\\S)|<<\\s*['\"]?EOF");
    private static final Pattern APPLY_PATCH_LIKE = Pattern.compile("(^|[;&|]\\s*)apply_patch\\b");
    private static final Pattern PYTHON_READ_LIKE = Pattern.compile(
            "python[0-9.]*\\s+-c\\s+.*open\\([^)]*['\"]r['\"]?");
    private static final Pattern PYTHON_WRITE_LIKE = Pattern.compile(
            "python[0-9.]*\\s+-c\\s+.*open\\([^)]*['\"][wa][+b]?['\"]");

    /**
     * Classifies a shell command the model wants to run through {@code
     * exec_command}. Returns the specialized tool that should be used
     * instead, or {@link Decision#shellAllowed()} when the command has no
     * safe, unambiguous specialized equivalent (Requirement 12: when in
     * doubt, permit the shell rather than blocking legitimate build/VCS/
     * runtime commands like {@code git status}, {@code ./gradlew test},
     * {@code npm test}, {@code python build.py}, {@code adb ...}).
     */
    public static Decision classifyShellCommand(String rawCommand) {
        if (rawCommand == null) {
            return Decision.shellAllowed();
        }
        String cmd = rawCommand.trim().toLowerCase(Locale.ROOT);
        if (cmd.isEmpty()) {
            return Decision.shellAllowed();
        }

        if (APPLY_PATCH_LIKE.matcher(cmd).find()) {
            return Decision.specialized("apply_patch", "apply_patch must be called directly, not through exec_command/shell.");
        }
        if (PYTHON_WRITE_LIKE.matcher(cmd).find() || DESTRUCTIVE_EDIT_LIKE.matcher(cmd).find()) {
            return Decision.specialized("apply_patch, edit_file or rewrite_file",
                    "This command edits a file's contents; use apply_patch, edit_file or rewrite_file instead.");
        }
        if (PYTHON_READ_LIKE.matcher(cmd).find() || CAT_LIKE.matcher(cmd).find()) {
            return Decision.specialized("read_file", "Use read_file instead of exec_command with cat/head/tail/sed.");
        }
        if (LS_LIKE.matcher(cmd).find()) {
            return Decision.specialized("ls_dir or get_dir_tree", "Use ls_dir or get_dir_tree instead of exec_command with ls/dir/tree.");
        }
        if (GREP_LIKE.matcher(cmd).find()) {
            return Decision.specialized("search_pathnames_only, search_for_files or search_in_file",
                    "Use search_pathnames_only, search_for_files or search_in_file instead of grep/rg/find.");
        }
        if (MKDIR_LIKE.matcher(cmd).find()) {
            return Decision.specialized("create_file_or_folder", "Use create_file_or_folder instead of mkdir/touch.");
        }
        if (RM_LIKE.matcher(cmd).find()) {
            return Decision.specialized("delete_file_or_folder", "Use delete_file_or_folder instead of rm/rmdir.");
        }
        if (MV_LIKE.matcher(cmd).find()) {
            return Decision.specialized("move_file or rename_file", "Use move_file or rename_file instead of mv.");
        }
        if (CP_LIKE.matcher(cmd).find()) {
            return Decision.specialized("copy_file", "Use copy_file instead of cp.");
        }
        return Decision.shellAllowed();
    }
}
