package com.dmzagent.sdk.exceptions;

/**
 * Raised on HTTP 400 or 422 — the server rejected the request body.
 *
 * <p>400 means malformed; 422 means it parsed but failed evaluation.
 * The spec's error taxonomy maps both here, because the caller's remedy
 * is the same: fix the request, do not retry it unchanged.
 * {@link #statusCode()} distinguishes them when that matters.
 *
 * <p>Spec §3, canonical name {@code ValidationError}.
 *
 * <p>NOTE: client-side argument validation (unknown event kind,
 * missing required field, both {@code subjectId} and
 * {@code interactionId} set on {@code check}) raises
 * {@link IllegalArgumentException}, NOT this class. This class is
 * reserved for {@code 400} and {@code 422} responses from the server.
 */
public class DMZAgentValidationException extends DMZAgentException {
    public DMZAgentValidationException(String message, Integer statusCode, Object body) {
        super(message, statusCode, body);
    }
}
