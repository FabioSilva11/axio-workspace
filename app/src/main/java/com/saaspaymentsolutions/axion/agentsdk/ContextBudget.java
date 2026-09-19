package com.saaspaymentsolutions.axion.agentsdk;

/**
 * Explicit per-category budgets for the agent context, extracted from the
 * hardcoded constants of {@code ContextBuilder} so they can become
 * model-aware (fed by {@code VoidPortProviderMaxTokens}) instead of magical.
 *
 * <p>Semantics mirror the real {@code ContextBuilder}: {@code totalTokens}
 * is the overall envelope and each category is a per-section cap. When the
 * caps sum above the envelope, contention is resolved by priority
 * (system → recent turns → tool results → relevant files → summary),
 * mirroring {@code composePrioritizedSections} — it is not a build error.</p>
 */
public final class ContextBudget {

    private final int totalTokens;
    private final int systemTokens;
    private final int relevantFilesTokens;
    private final int recentHistoryTokens;
    private final int toolResultTokens;
    private final int summaryTokens;

    private ContextBudget(Builder b) {
        this.totalTokens = b.totalTokens;
        this.systemTokens = b.systemTokens;
        this.relevantFilesTokens = b.relevantFilesTokens;
        this.recentHistoryTokens = b.recentHistoryTokens;
        this.toolResultTokens = b.toolResultTokens;
        this.summaryTokens = b.summaryTokens;
    }

    public int totalTokens() {
        return totalTokens;
    }

    public int systemTokens() {
        return systemTokens;
    }

    public int relevantFilesTokens() {
        return relevantFilesTokens;
    }

    public int recentHistoryTokens() {
        return recentHistoryTokens;
    }

    public int toolResultTokens() {
        return toolResultTokens;
    }

    public int summaryTokens() {
        return summaryTokens;
    }

    /** Sums every category; useful for invariant checks and telemetry. */
    public int allocatedTokens() {
        return systemTokens + relevantFilesTokens + recentHistoryTokens
                + toolResultTokens + summaryTokens;
    }

    /** Default budget mirroring today's ContextBuilder constants. */
    public static ContextBudget defaults() {
        return builder().build();
    }

    /**
     * Scales every category proportionally to {@code maxModelTokens} while
     * keeping the current category ratios at the reference budget (6k total).
     */
    public static ContextBudget forModel(int maxModelTokens) {
        ContextBudget reference = defaults();
        if (maxModelTokens <= 0) {
            return reference;
        }
        int ceiling = Math.min(maxModelTokens, 128_000);
        if (ceiling <= reference.totalTokens) {
            return reference;
        }
        double scale = (double) ceiling / reference.totalTokens();
        // Cap growth so a big-context model never drowns the prompt in stale history.
        double bounded = Math.min(scale, 8.0);
        return builder()
                .totalTokens((int) (reference.totalTokens * bounded))
                .systemTokens((int) (reference.systemTokens * bounded))
                .relevantFilesTokens((int) (reference.relevantFilesTokens * bounded))
                .recentHistoryTokens((int) (reference.recentHistoryTokens * bounded))
                .toolResultTokens((int) (reference.toolResultTokens * bounded))
                .summaryTokens((int) (reference.summaryTokens * bounded))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int totalTokens = 6_000;
        private int systemTokens = 2_400;
        private int relevantFilesTokens = 1_500;
        private int recentHistoryTokens = 3_000;
        private int toolResultTokens = 1_000;
        private int summaryTokens = 500;

        public Builder totalTokens(int v) {
            this.totalTokens = v;
            return this;
        }

        public Builder systemTokens(int v) {
            this.systemTokens = v;
            return this;
        }

        public Builder relevantFilesTokens(int v) {
            this.relevantFilesTokens = v;
            return this;
        }

        public Builder recentHistoryTokens(int v) {
            this.recentHistoryTokens = v;
            return this;
        }

        public Builder toolResultTokens(int v) {
            this.toolResultTokens = v;
            return this;
        }

        public Builder summaryTokens(int v) {
            this.summaryTokens = v;
            return this;
        }

        public ContextBudget build() {
            if (totalTokens <= 0 || systemTokens < 0 || relevantFilesTokens < 0
                    || recentHistoryTokens < 0 || toolResultTokens < 0 || summaryTokens < 0) {
                throw new IllegalArgumentException(
                        "Budgets must be positive (total) and non-negative (categories)");
            }
            return new ContextBudget(this);
        }
    }
}
