package com.dmzagent.sdk;

/**
 * What {@link DMZAgentClient#check} does when the request itself fails —
 * network error, timeout, or 5xx. Spec §4.4.
 *
 * <p>An enum rather than a string, because the whole point of validating
 * this value in the other bindings is that a typo must not silently
 * become a different safety posture. Here the compiler does it.
 */
public enum CbCacheOnError {

    /**
     * Propagate the error. This is what a client with no cache does, and
     * it is the default.
     */
    RAISE,

    /**
     * Serve the last cached state for that subject even if it has
     * expired, marked {@code cached} and {@code stale}. When nothing is
     * known for that subject, propagate the error instead — never an
     * invented state.
     *
     * <p>Cannot be set without a cache TTL above zero: there is nothing
     * to fall back to until the caller has opted into the cache.
     */
    LAST_KNOWN
}
