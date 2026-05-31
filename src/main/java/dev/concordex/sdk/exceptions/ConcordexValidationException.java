package dev.concordex.sdk.exceptions;

/**
 * Raised on HTTP 400 — the server rejected the request body as
 * malformed.
 *
 * <p>Spec §3, canonical name {@code ValidationError}.
 *
 * <p>NOTE: client-side argument validation (unknown event kind,
 * missing required field, both {@code subjectId} and
 * {@code interactionId} set on {@code check}) raises
 * {@link IllegalArgumentException}, NOT this class. This class is
 * reserved for {@code 400} responses from the server.
 */
public class ConcordexValidationException extends ConcordexException {
    public ConcordexValidationException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }
}
