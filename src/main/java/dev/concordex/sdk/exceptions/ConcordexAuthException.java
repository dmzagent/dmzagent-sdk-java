package dev.concordex.sdk.exceptions;

/**
 * Raised on HTTP 401 — the API key is missing, invalid, or revoked.
 *
 * <p>Spec §3, canonical name {@code AuthError}.
 */
public class ConcordexAuthException extends ConcordexException {
    public ConcordexAuthException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }
}
