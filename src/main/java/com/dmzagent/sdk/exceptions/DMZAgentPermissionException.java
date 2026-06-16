package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 403 — the API key is valid but lacks the scope
 * required for this operation.
 *
 * <p>Spec §3, canonical name {@code PermissionError}.
 */
public class DMZAgentPermissionException extends DMZAgentException {
    public DMZAgentPermissionException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }
}
