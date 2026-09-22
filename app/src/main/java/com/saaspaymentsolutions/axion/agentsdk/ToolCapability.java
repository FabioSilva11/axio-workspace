package com.saaspaymentsolutions.axion.agentsdk;

/**
 * What a tool may do. Capabilities are DECLARED on
 * {@code ToolRegistration} metadata — the single source of truth — and the
 * {@link PermissionEvaluator} never out-guesses the registry (names are only
 * a defensive fallback).
 */
public enum ToolCapability {

    /** Reads content inside the workspace (files, search, context, clocks). */
    READ,

    /** Writes/creates/modifies files inside the logical workspace. */
    WORKSPACE_WRITE,

    /** Irreversible actions (deletes, overwrites that cannot be undone). */
    DESTRUCTIVE,

    /** Executes commands / drives terminals. */
    SHELL,

    /** Talks to a remote endpoint (MCP servers). */
    NETWORK,

    /** Reaches beyond the logical workspace (host, other roots, external storage). */
    EXTERNAL_ACCESS
}