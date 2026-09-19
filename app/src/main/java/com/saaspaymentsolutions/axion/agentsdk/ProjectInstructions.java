package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem;
import com.saaspaymentsolutions.axion.workspace.WorkspaceManager;

/**
 * Durable project memory (M7, Codex convention): reads {@code AGENTS.md} from
 * the workspace root and renders it for the system prompt within a token
 * budget. Missing files yield an empty string — never an error. Cached per
 * workspace path with a 5s TTL (same pattern as the ContextBuilder caches).
 */
public final class ProjectInstructions {

    public static final String FILE_NAME = "AGENTS.md";
    private static final long CACHE_TTL_MS = 5000L;

    private static final Object CACHE_LOCK = new Object();
    private static volatile String cachedPath = "";
    private static volatile long cachedAtMs;
    private static volatile String cachedValue = "";
    /** Test/multi-host override; when set, bypasses WorkspaceManager. */
    private static volatile WorkspaceFileSystem overrideFs;

    private ProjectInstructions() {
    }

    /** Forces reads from an injected filesystem (tests, evals). */
    public static void setOverrideFileSystem(WorkspaceFileSystem fs) {
        overrideFs = fs;
        invalidate();
    }

    /** Reads AGENTS.md from the active workspace, truncated to the budget. */
    public static String load(int maxChars) {
        try {
            WorkspaceFileSystem fs = overrideFs != null
                    ? overrideFs
                    : WorkspaceManager.getActiveFileSystem();
            if (fs == null || !fs.exists(FILE_NAME) || fs.isDirectory(FILE_NAME)) {
                return "";
            }
            String path = String.valueOf(fs);
            long now = System.currentTimeMillis();
            synchronized (CACHE_LOCK) {
                if (path.equals(cachedPath) && now - cachedAtMs < CACHE_TTL_MS) {
                    return truncate(cachedValue, maxChars);
                }
            }
            String raw = fs.readText(FILE_NAME);
            synchronized (CACHE_LOCK) {
                cachedPath = path;
                cachedAtMs = now;
                cachedValue = raw == null ? "" : raw;
            }
            return truncate(cachedValue, maxChars);
        } catch (Exception e) {
            return ""; // instructions are optional; never break a run over them
        }
    }

    /** Test overload: read from an injected filesystem (no Android deps). */
    public static String load(WorkspaceFileSystem fs, int maxChars) {
        try {
            if (fs == null || !fs.exists(FILE_NAME) || fs.isDirectory(FILE_NAME)) {
                return "";
            }
            return truncate(fs.readText(FILE_NAME), maxChars);
        } catch (Exception e) {
            return "";
        }
    }

    /** Clears the memoization cache (tests, workspace switches). */
    public static void invalidate() {
        synchronized (CACHE_LOCK) {
            cachedPath = "";
            cachedAtMs = 0;
            cachedValue = "";
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n… (AGENTS.md truncado)";
    }
}
