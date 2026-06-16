package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 5xx, network failures, and timeouts.
 *
 * <p>Spec §3, canonical name {@code ServerError}. Per §3.1, the
 * underlying cause is preserved via Java's exception-chaining
 * mechanism ({@code initCause()} / constructor-based).
 *
 * <p>The contract is: {@code DMZAgentServerException} is safe to
 * retry with backoff. The SDK does not retry automatically; that's
 * the caller's policy decision.
 */
public class DMZAgentServerException extends DMZAgentException {
    public DMZAgentServerException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }

    public DMZAgentServerException(String message, Integer statusCode, Object body, Throwable cause) {
        super(message, statusCode, body, cause);
    }
}
