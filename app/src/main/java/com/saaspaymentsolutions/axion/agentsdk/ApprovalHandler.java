package com.saaspaymentsolutions.axion.agentsdk;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Host boundary for human-in-the-loop approvals, replacing the Runner's
 * blocking {@code boolean onToolApproval} callback.
 *
 * <p>The run thread calls {@link #awaitDecision} and parks until the UI
 * resolves the pending {@link PermissionRequest} via {@link #resolve} —
 * from any thread, seconds or hours later. Unit tests can resolve instantly;
 * production hosts wire this to a dialog on the main thread.</p>
 *
 * <p>Hosts that prefer the old synchronous style can implement
 * {@link #decideNow} instead (or use {@link RunListenersAdapter}).</p>
 */
public interface ApprovalHandler {

    /** Default maximum time a pending approval may block the run thread. */
    long DEFAULT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * Called on the run thread when a tool needs approval. Implementations
     * must either block (queue/CompletableFuture style) or answer directly
     * via {@link #decideNow}.
     */
    PermissionDecision onRequest(PermissionRequest request);

    /** Default approval timeout; override to allow longer waits. */
    default long timeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Waits for the decision, honoring {@link #timeoutMs}. Default
     * implementation parks on a one-slot queue; override only for special
     * synchronization needs.
     */
    default PermissionDecision awaitDecision(PermissionRequest request) throws InterruptedException, TimeoutException {
        long timeout = timeoutMs();
        if (timeout <= 0) {
            return onRequest(request);
        }
        BlockingQueue<PermissionDecision> slot = new LinkedBlockingQueue<>(1);
        Thread responder = new Thread(() -> {
            PermissionDecision decision = onRequest(request);
            if (decision == null) {
                decision = PermissionDecision.DENY;
            }
            slot.offer(decision);
        }, "axion-approval-" + request.getId());
        responder.setDaemon(true);
        responder.start();
        try {
            PermissionDecision decision = slot.poll(timeout, TimeUnit.MILLISECONDS);
            if (decision == null) {
                throw new TimeoutException("Approval timed out for tool '" + request.getTool() + "'");
            }
            return decision;
        } finally {
            responder.interrupt();
        }
    }

    /** Non-blocking answer used by synchronous hosts and tests. */
    default PermissionDecision decideNow(PermissionRequest request) {
        return onRequest(request);
    }

    /**
     * Free-text answer typed by the user for the most recent request — used by
     * the {@code request_user_input} tool. Hosts that support typed answers
     * store the text before resolving the decision; the default returns null
     * (interpreted as "no answer provided").
     */
    default String lastResponseText() {
        return null;
    }

    /** Convenience: a future-based variant for hosts that resolve later. */
    static ApprovalHandler fromFuture(java.util.function.Function<PermissionRequest, CompletableFuture<PermissionDecision>> resolver,
                                      long timeoutMs) {
        return new ApprovalHandler() {
            @Override
            public PermissionDecision onRequest(PermissionRequest request) {
                try {
                    PermissionDecision decision = resolver.apply(request).get(timeoutMs, TimeUnit.MILLISECONDS);
                    return decision == null ? PermissionDecision.DENY : decision;
                } catch (Exception e) {
                    return PermissionDecision.DENY;
                }
            }
        };
    }
}
