package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 429 — the API key's rate cap was reached for the
 * current window.
 *
 * <p>Spec §3, canonical name {@code RateLimitError}.
 *
 * <p>{@link #retryAfter()} carries the server's {@code Retry-After}
 * response header parsed as delta-seconds, or {@code null} when the
 * header is absent or unparseable. Per spec §3 the SDK MUST NOT sleep
 * or retry automatically — the value is surfaced so the caller can
 * decide when (and whether) to retry.
 */
public class DMZAgentRateLimitException extends DMZAgentException {

    private final Integer retryAfter;

    public DMZAgentRateLimitException(String message, Integer statusCode, Object body,
                                      Integer retryAfter) {
        super(message, statusCode, body);
        this.retryAfter = retryAfter;
    }

    /** Seconds until retrying can succeed, parsed from the response's
     * {@code Retry-After} header (delta-seconds form), or {@code null}
     * when the header was absent or unparseable. */
    public Integer retryAfter() { return retryAfter; }

    /** JavaBeans-style alias for {@link #retryAfter()}. */
    public Integer getRetryAfter() { return retryAfter; }
}
