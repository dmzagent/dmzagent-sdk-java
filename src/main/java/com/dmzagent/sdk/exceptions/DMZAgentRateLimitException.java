package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 429 — the caller is being rate limited.
 *
 * <p>Spec §3, canonical name {@code RateLimitError}.
 *
 * <p>{@link #retryAfter()} is the value of the {@code Retry-After}
 * response header in seconds, or {@code null} when the server did not
 * send one. Callers should handle {@code null} rather than assume a
 * default: the contract corpus carries a vector for each case precisely
 * because both occur.
 */
public class DMZAgentRateLimitException extends DMZAgentException {

    private final Integer retryAfter;

    public DMZAgentRateLimitException(String message, Integer statusCode, Object body) {
        this(message, statusCode, body, null);
    }

    public DMZAgentRateLimitException(
            String message, Integer statusCode, Object body, Integer retryAfter) {
        super(message, statusCode, body);
        this.retryAfter = retryAfter;
    }

    /** Seconds to wait before retrying, or {@code null} if the server
     *  sent no {@code Retry-After} header. */
    public Integer retryAfter() { return retryAfter; }
}
