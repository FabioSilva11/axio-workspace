package com.saaspaymentsolutions.axion.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Evidence-based completion validation.
 *
 * The host only blocks completion for concrete, objectively verifiable gaps.
 * Heuristic plans and broad regex classifications are never treated as an
 * authoritative obligation, because that can trap the model in impossible or
 * contradictory loops.
 */
public final class FinishChecker {

    private FinishChecker() {}

    public static final class ValidationResult {
        private final boolean canFinish;
        private final String reason;
        private final String feedbackPrompt;
        private final List<String> missingActions;

        private ValidationResult(boolean canFinish, String reason, String feedbackPrompt,
                                 @Nullable List<String> missingActions) {
            this.canFinish = canFinish;
            this.reason = reason == null ? "" : reason;
            this.feedbackPrompt = feedbackPrompt == null ? "" : feedbackPrompt;
            this.missingActions = missingActions == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(missingActions));
        }

        @NonNull public static ValidationResult allowed() {
            return new ValidationResult(true, "Completion requirements satisfied", "", null);
        }

        @NonNull public static ValidationResult cannotFinish(@NonNull String reason,
                                                             @NonNull String feedbackPrompt,
                                                             @Nullable List<String> missingActions) {
            return new ValidationResult(false, reason, feedbackPrompt, missingActions);
        }

        public boolean canFinish() { return canFinish; }
        @NonNull public String getReason() { return reason; }
        @NonNull public String getFeedbackPrompt() { return feedbackPrompt; }
        @NonNull public List<String> getMissingActions() { return missingActions; }
    }

    @NonNull
    public static ValidationResult validate(@Nullable AgentMemory memory,
                                            @Nullable PatternMatcher.Result pattern,
                                            @Nullable TaskPlanner.Plan ignoredPlan,
                                            @NonNull List<ToolSequenceValidator.ToolUsage> currentRunTools,
                                            @Nullable String lastAssistantResponse,
                                            @NonNull String chatMode) {
        if (!"agent".equalsIgnoreCase(chatMode)) {
            return ValidationResult.allowed();
        }

        PatternMatcher.Result effectivePattern = pattern;
        if (effectivePattern == null && memory != null) {
            effectivePattern = PatternMatcher.analyze(
                    memory.getOriginalUserMessage(), null, memory.getOriginalSelections());
        }
        if (effectivePattern == null || effectivePattern.isChatOnly()) {
            return ValidationResult.allowed();
        }

        // A read-only turn is allowed to finish after explanation/inspection. The
        // actual mutation block is enforced in ToolManager, not by this heuristic.
        if (effectivePattern.isReadOnly()) {
            return ValidationResult.allowed();
        }

        // Only concrete tool requirements produced for explicit file operations
        // are blocking. Broad requests such as "fix my app" or "create an app"
        // intentionally carry no required tools and are left to the model.
        List<String> missing = unusedRequiredTools(effectivePattern.getRequiredTools(), currentRunTools);
        if (!missing.isEmpty()) {
            return ValidationResult.cannotFinish(
                    "Concrete requested action was not executed",
                    listFeedback("Complete the explicitly requested workspace action before finishing:", missing),
                    missing);
        }

        if (memory != null && !effectivePattern.getExtractedFilePaths().isEmpty()) {
            List<String> unaccessed = unaccessedKeyFiles(effectivePattern.getExtractedFilePaths(), currentRunTools);
            if (!unaccessed.isEmpty()) {
                return ValidationResult.cannotFinish(
                        "Explicitly referenced files were not accessed",
                        listFeedback("Access the explicitly referenced files before finishing:", unaccessed),
                        unaccessed);
            }
        }

        if (effectivePattern.hasRequiredTools()
                && !hasSuccessfulTool(currentRunTools)
                && describesFutureAction(lastAssistantResponse)) {
            return ValidationResult.cannotFinish(
                    "The assistant promised work instead of executing it",
                    "Execute the concrete requested action with the available tools, then report the verified result.",
                    Collections.singletonList("Execute the requested action"));
        }

        return ValidationResult.allowed();
    }

    @NonNull
    private static List<String> unusedRequiredTools(@NonNull List<String> requiredTools,
                                                    @NonNull List<ToolSequenceValidator.ToolUsage> usages) {
        List<String> missing = new ArrayList<>();
        for (String required : requiredTools) {
            if (!isRequirementSatisfied(required, usages)) {
                missing.add(describeRequirement(required));
            }
        }
        return missing;
    }

    @NonNull
    private static String describeRequirement(@NonNull String required) {
        if (PatternMatcher.PROJECT_DISCOVERY_REQUIREMENT.equals(required)) {
            return "Inspect the project";
        }
        if (PatternMatcher.WORKSPACE_MUTATION_REQUIREMENT.equals(required)) {
            return "Modify the workspace";
        }
        return required;
    }

    private static boolean isRequirementSatisfied(@NonNull String required,
                                                  @NonNull List<ToolSequenceValidator.ToolUsage> usages) {
        for (ToolSequenceValidator.ToolUsage usage : usages) {
            if (usage == null || !usage.wasSuccessful()) continue;
            String used = usage.getToolName();
            if (required.equals(used)) return true;
            if (PatternMatcher.PROJECT_DISCOVERY_REQUIREMENT.equals(required)
                    && PatternMatcher.isProjectDiscoveryTool(used)) return true;
            if (PatternMatcher.WORKSPACE_MUTATION_REQUIREMENT.equals(required)
                    && PatternMatcher.isWorkspaceMutationTool(used)) return true;
            if ("search_for_files".equals(required)
                    && ("search_pathnames_only".equals(used) || "get_dir_tree".equals(used))) return true;
            if ("edit_file".equals(required) && "rewrite_file".equals(used)) return true;
        }
        return false;
    }

    private static boolean hasSuccessfulTool(@NonNull List<ToolSequenceValidator.ToolUsage> usages) {
        for (ToolSequenceValidator.ToolUsage usage : usages) {
            if (usage != null && usage.wasSuccessful()) return true;
        }
        return false;
    }

    @NonNull
    private static List<String> unaccessedKeyFiles(@NonNull List<String> keyFiles,
                                                   @NonNull List<ToolSequenceValidator.ToolUsage> usages) {
        List<String> result = new ArrayList<>();
        for (String keyFile : keyFiles) {
            if (keyFile == null || keyFile.trim().isEmpty()) continue;
            String needle = keyFile.replace('\\', '/').toLowerCase(Locale.ROOT);
            boolean accessed = false;
            for (ToolSequenceValidator.ToolUsage usage : usages) {
                if (usage == null || !usage.wasSuccessful()) continue;
                String args = usage.getArgs() == null ? "" : usage.getArgs().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (args.contains(needle)) {
                    accessed = true;
                    break;
                }
            }
            if (!accessed) result.add(keyFile);
        }
        return result;
    }

    private static boolean describesFutureAction(@Nullable String response) {
        if (response == null) return false;
        String lower = response.toLowerCase(Locale.ROOT);
        return lower.contains("vou ") || lower.contains("irei ")
                || lower.contains("i will ") || lower.contains("i'll ")
                || lower.contains("next i will") || lower.contains("em seguida vou");
    }

    @NonNull
    private static String listFeedback(@NonNull String prefix, @NonNull List<String> items) {
        StringBuilder out = new StringBuilder(prefix);
        for (String item : items) out.append("\n- ").append(item);
        return out.toString();
    }
}
