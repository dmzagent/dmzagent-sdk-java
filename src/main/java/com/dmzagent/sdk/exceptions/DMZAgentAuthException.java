package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 401 — the API key is missing, invalid, or revoked.
 *
 * <p>Spec §3, canonical name {@code AuthError}.
 */
public class DMZAgentAuthException extends DMZAgentException {
    public DMZAgentAuthException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }
}
