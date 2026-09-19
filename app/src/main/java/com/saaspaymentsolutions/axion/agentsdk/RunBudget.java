package com.saaspaymentsolutions.axion.agentsdk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-run token budget with the reserve/settle/block discipline of the
 * openai-cookbook spending controller, ported to tokens (the common currency
 * of the Axion multi-provider runtime):
 *
 * <ul>
 *   <li>reserve the worst-case cost before each LLM turn;</li>
 *   <li>settle with the actual usage when the response arrives;</li>
 *   <li>block the run permanently when the cost cannot be confirmed
 *       (missing/implausible usage report) — fail closed, as the Cookbook
 *       does for uncertain charges.</li>
 * </ul>
 *
 * Thread-safe. Pure JVM, unit-testable without Android or network.
 */
public final class RunBudget {

    /** Thrown when the remaining budget cannot cover the next request. */
    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    /** Thrown when actual spend exceeds the reservation; the run must halt. */
    public static final class UncertainChargeException extends RuntimeException {
        public UncertainChargeException(String message) {
            super(message);
        }
    }

    /** Opaque handle returned by {@link #reserve}; pass it to {@link #settle}. */
    public static final class Handle {
        private final long reserved;

        private Handle(long reserved) {
            this.reserved = reserved;
        }

        long reserved() {
            return reserved;
        }
    }

    private final long maximum;
    private long spent;
    private long pending;
    private boolean blocked;
    private final Map<Handle, Long> holds = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public RunBudget(long maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Budget must be positive");
        }
        this.maximum = maxTokens;
    }

    public long maximum() {
        return maximum;
    }

    public long spent() {
        lock.lock();
        try {
            return spent;
        } finally {
            lock.unlock();
        }
    }

    public long remaining() {
        lock.lock();
        try {
            return Math.max(0, maximum - spent - pending);
        } finally {
            lock.unlock();
        }
    }

    public boolean isBlocked() {
        lock.lock();
        try {
            return blocked;
        } finally {
            lock.unlock();
        }
    }

    /** Cheap pre-check: can the run afford at least {@code minimum} more tokens? */
    public void ensureActive(long minimum) {
        lock.lock();
        try {
            if (blocked || spent + pending + minimum > maximum) {
                throw new BudgetExceededException(
                        "Run budget exhausted: " + (spent + pending) + "/" + maximum + " tokens");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves the worst-case cost of the next turn. The reservation counts
     * against the budget until {@link #settle} returns the unused part.
     */
    public Handle reserve(long worstCaseTokens) {
        if (worstCaseTokens <= 0) {
            throw new IllegalArgumentException("Reservation must be positive");
        }
        lock.lock();
        try {
            if (blocked || spent + pending + worstCaseTokens > maximum) {
                throw new BudgetExceededException(
                        "Run budget exhausted: " + (spent + pending) + "/" + maximum
                                + " tokens; next turn needs " + worstCaseTokens);
            }
            Handle handle = new Handle(worstCaseTokens);
            holds.put(handle, worstCaseTokens);
            pending += worstCaseTokens;
            return handle;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Settles a reservation with the actual token usage. If the actual spend
     * exceeds the reservation the run is blocked permanently (uncertain
     * charge), mirroring the Cookbook's fail-closed rule.
     */
    public void settle(Handle handle, long actualTokens) {
        if (handle == null || actualTokens < 0) {
            block();
            throw new UncertainChargeException("Invalid spend settlement");
        }
        lock.lock();
        try {
            Long held = holds.remove(handle);
            if (held == null || held > pending) {
                block();
                throw new UncertainChargeException("Reservation is unknown or already settled");
            }
            pending -= held;
            spent += actualTokens;
            if (actualTokens > held) {
                block();
                throw new UncertainChargeException(
                        "Actual spend (" + actualTokens + ") exceeded the reservation (" + held + ")");
            }
        } finally {
            lock.unlock();
        }
    }

    /** Permanently stops the run: no further reservations are accepted. */
    public void block() {
        lock.lock();
        try {
            blocked = true;
        } finally {
            lock.unlock();
        }
    }
}
