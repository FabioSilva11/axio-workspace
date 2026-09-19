package com.saaspaymentsolutions.axion.agentsdk;

/**
 * Per-category tool execution policy, modeled after Codex's
 * {@code sandbox-mode + approval-policy} split: the model never executes
 * anything that policy or the user did not allow.
 *
 * <p>Categories follow the registry flags ({@code isFileMutation},
 * {@code isDestructive}, shell/network tools): shell covers terminal tools,
 * network covers remote MCP tools, mutation covers file edits, and
 * destructive overrides mutation for irreversible actions.</p>
 */
public final class ToolPolicy {

    /** What happens when a tool in this category is invoked. */
    public enum Rule {
        /** Execute without asking. */
        ALLOW,
        /** Ask the user via the {@link ApprovalHandler}. */
        ASK_USER,
        /** Deny before reaching the user; the model gets an error result. */
        DENY
    }

    private final Rule mutation;
    private final Rule destructive;
    private final Rule shell;
    private final Rule network;
    private final Rule unknown;

    private ToolPolicy(Builder b) {
        this.mutation = b.mutation;
        this.destructive = b.destructive;
        this.shell = b.shell;
        this.network = b.network;
        this.unknown = b.unknown;
    }

    public Rule mutation() {
        return mutation;
    }

    public Rule destructive() {
        return destructive;
    }

    public Rule shell() {
        return shell;
    }

    public Rule network() {
        return network;
    }

    /** Rule for tools that match no known category. */
    public Rule unknown() {
        return unknown;
    }

    /** Maximum-permissive policy (CI, evals, trusted automations). */
    public static ToolPolicy permissive() {
        return builder()
                .mutation(Rule.ALLOW)
                .destructive(Rule.ALLOW)
                .shell(Rule.ALLOW)
                .network(Rule.ALLOW)
                .unknown(Rule.ALLOW)
                .build();
    }

    /** Everything sensitive asks the user (default for agent mode). */
    public static ToolPolicy interactive() {
        return builder()
                .mutation(Rule.ASK_USER)
                .destructive(Rule.ASK_USER)
                .shell(Rule.ASK_USER)
                .network(Rule.ASK_USER)
                .unknown(Rule.ASK_USER)
                .build();
    }

    /** Read-only turns: mutations, destructive and shell denied outright. */
    public static ToolPolicy readOnly() {
        return builder()
                .mutation(Rule.DENY)
                .destructive(Rule.DENY)
                .shell(Rule.DENY)
                .network(Rule.ASK_USER)
                .unknown(Rule.ASK_USER)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link ToolPolicy}. */
    public static final class Builder {
        private Rule mutation = Rule.ASK_USER;
        private Rule destructive = Rule.ASK_USER;
        private Rule shell = Rule.ASK_USER;
        private Rule network = Rule.ALLOW;
        private Rule unknown = Rule.ASK_USER;

        public Builder mutation(Rule rule) {
            this.mutation = rule;
            return this;
        }

        public Builder destructive(Rule rule) {
            this.destructive = rule;
            return this;
        }

        public Builder shell(Rule rule) {
            this.shell = rule;
            return this;
        }

        public Builder network(Rule rule) {
            this.network = rule;
            return this;
        }

        public Builder unknown(Rule rule) {
            this.unknown = rule;
            return this;
        }

        public ToolPolicy build() {
            return new ToolPolicy(this);
        }
    }
}
