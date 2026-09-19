package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Execution-time filesystem binding for one agent run (item 14 of the
 * context-model migration): the {@link AgentRuntime} pins the
 * {@link WorkspaceIdentity}/{@link WorkspaceFileSystem} resolved by the
 * {@link RunContextFactory} at run start, and every tool that still resolves
 * paths through static helpers (the Void-ported {@code VoidPortToolsService}
 * registry, {@code ContextBuilder}, {@code ApplyPatchTool}) reads THIS
 * binding instead of the global active workspace.
 *
 * <p>This is the mechanism that makes {@code scId = A} + {@code
 * activeWorkspace = B} impossible for an executing tool: the global
 * selection may change mid-run (it is a UI choice), but the binding pinned
 * at run start keeps every prompt, tool call and mutation on the run's
 * workspace.</p>
 *
 * <p>Two layers:</p>
 * <ul>
 *   <li><b>Pinned (run-wide)</b> — installed once by the runtime when the
 *       run starts, visible from ANY thread while the run is active. The
 *       tool loop runs on the loop thread, streaming callbacks may run on
 *       other threads, and {@code ContextBuilder} assembles the prompt on a
 *       background thread: all of them must see the same filesystem.</li>
 *   <li><b>Thread stack (nested)</b> — explicit push/pop for callers that
 *       enter the runtime from an unmanaged thread; restores the outer
 *       binding exactly on pop.</li>
 * </ul>
 */
public final class RuntimeFileContext {

    /** Identity + filesystem pinned for one run. */
    public static final class Binding {
        private final WorkspaceIdentity identity;
        private final WorkspaceFileSystem filesystem;

        Binding(WorkspaceIdentity identity, WorkspaceFileSystem filesystem) {
            this.identity = identity;
            this.filesystem = filesystem;
        }

        public WorkspaceIdentity identity() {
            return identity;
        }

        public WorkspaceFileSystem filesystem() {
            return filesystem;
        }
    }

    /** The single pinned binding for the run in flight (run-wide scope). */
    private static volatile Binding pinned;

    private static final ThreadLocal<Deque<Binding>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private RuntimeFileContext() {
    }

    /**
     * Pins the run's binding for the whole duration of the run (cross-thread).
     * Returns a token that restores the previous pinned binding exactly —
     * {@code close()} MUST be called in the run's {@code finally} block.
     */
    public static AutoCloseable pin(WorkspaceIdentity identity, WorkspaceFileSystem filesystem) {
        Binding previous = pinned;
        pinned = new Binding(identity, filesystem);
        return () -> pinned = previous;
    }

    /** Installs a thread-local binding; returns a token to pop it precisely. */
    public static AutoCloseable install(WorkspaceIdentity identity, WorkspaceFileSystem filesystem) {
        Deque<Binding> stack = STACK.get();
        stack.push(new Binding(identity, filesystem));
        return () -> {
            Deque<Binding> current = STACK.get();
            if (!current.isEmpty()) {
                current.pop();
            }
        };
    }

    /**
     * The filesystem bound to the running execution: the thread's own
     * binding when installed (nested/legacy push), else the run-wide pinned
     * binding, else {@code null} — in which case callers keep the legacy
     * {@code WorkspaceManager.getActiveFileSystem()} contract (UI paths that
     * legitimately reflect the user's selection).
     */
    public static WorkspaceFileSystem effectiveFileSystem() {
        Binding threadTop = STACK.get().peek();
        if (threadTop != null && threadTop.filesystem() != null) {
            return threadTop.filesystem();
        }
        Binding runWide = pinned;
        return runWide == null ? null : runWide.filesystem();
    }

    /** The identity bound to the running execution, or {@code null}. */
    public static WorkspaceIdentity effectiveIdentity() {
        Binding threadTop = STACK.get().peek();
        if (threadTop != null && threadTop.identity() != null) {
            return threadTop.identity();
        }
        Binding runWide = pinned;
        return runWide == null ? null : runWide.identity();
    }

    /**
     * The filesystem bound to THIS thread only (no run-wide fallback) — for
     * code that must distinguish an explicit nested installation.
     */
    public static WorkspaceFileSystem currentFileSystem() {
        Binding top = STACK.get().peek();
        return top == null ? null : top.filesystem();
    }

    /** The identity bound to THIS thread only, or {@code null}. */
    public static WorkspaceIdentity currentIdentity() {
        Binding top = STACK.get().peek();
        return top == null ? null : top.identity();
    }

    /** True when a thread-local binding is installed on this thread. */
    public static boolean isInstalled() {
        return !STACK.get().isEmpty();
    }

    /** Test hook: clears every binding (thread-local of the caller + pin). */
    static void clearForTest() {
        STACK.get().clear();
        pinned = null;
    }
}
