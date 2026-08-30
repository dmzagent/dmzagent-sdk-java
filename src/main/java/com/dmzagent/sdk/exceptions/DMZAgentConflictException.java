package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 409 — a request carrying this {@code Idempotency-Key}
 * is already in flight.
 *
 * <p>Spec §3, canonical name {@code ConflictError}.
 *
 * <p>Deliberately not a {@link DMZAgentServerException}: this is not a
 * transient fault. The duplicate is the caller's <em>own</em> earlier
 * request, still running. Retrying the same key after a short pause
 * replays that request's stored response rather than producing a second
 * side effect, so the caller can safely wait and retry — but the SDK
 * never does so on its own (spec §1.8).
 */
public class DMZAgentConflictException extends DMZAgentException {
    public DMZAgentConflictException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }

    public DMZAgentConflictException(String message, Integer statusCode, Object body, Throwable cause) {
        super(message, statusCode, body, cause);
    }
}
