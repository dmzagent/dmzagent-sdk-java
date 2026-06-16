package com.dmzagent.sdk;

import com.dmzagent.sdk.exceptions.CircuitBreakerOpenException;

/**
 * {@link AutoCloseable} wrapper around a {@link CheckResult},
 * returned from {@code DMZAgentClient.guard(...)} and
 * {@code Conversation.guard(...)} for use with try-with-resources.
 *
 * <p>Per spec §5.7, the Java idiom is:
 *
 * <pre>{@code
 *   try (var g = cx.guard("user:ws:bot")) {
 *       if (!g.getResult().allow()) {
 *           return refuse(g.getResult().reason());
 *       }
 *       // ... sensitive action
 *   }
 * }</pre>
 *
 * <p>When {@code raiseOnOpen=true} and the result is open, the
 * underlying {@code DMZAgentClient.guard(...)} factory throws
 * {@link CircuitBreakerOpenException} BEFORE returning the Guard —
 * so the try-with-resources body never executes.
 *
 * <p>The current close path is a no-op; v1.0 may emit a
 * {@code guard_closed} ledger entry. The shape is reserved.
 */
public final class Guard implements AutoCloseable {

    private final CheckResult result;
    private boolean closed = false;

    Guard(CheckResult result) {
        this.result = result;
    }

    /** The underlying CB check result. */
    public CheckResult getResult() {
        return result;
    }

    /** Idempotent — calling {@code close()} more than once is safe. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // No work to do today. Future versions may emit a
        // "guard_closed" event to anchor the scope in the ledger.
    }
}
