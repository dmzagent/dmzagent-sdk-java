package dev.concordex.sdk.exceptions;

/**
 * Base class for every error raised by the Concordex SDK.
 *
 * <p>The hierarchy is deliberately shallow — most consumers only need
 * to catch {@code ConcordexException} to bail out gracefully, or
 * {@link CircuitBreakerOpenException} specifically when they want to
 * handle a blocked subject differently from other failures.
 *
 * <pre>
 *   ConcordexException                       base
 *     ├── ConcordexAuthException             API key invalid / revoked
 *     ├── ConcordexPermissionException       API key lacks scope
 *     ├── ConcordexValidationException       server rejected payload (400)
 *     ├── ConcordexServerException           5xx / network / timeout
 *     └── CircuitBreakerOpenException        cb.check() returned open
 * </pre>
 *
 * <p>{@code ConcordexException} extends {@link RuntimeException} so
 * SDK calls don't force {@code throws} declarations through the
 * caller's code. This mirrors Java's HTTP-client and JDBC-driver
 * conventions for transport-level errors.
 *
 * <p>Per spec §3, every exception MUST expose {@code message},
 * {@code statusCode}, and {@code body}.
 */
public class ConcordexException extends RuntimeException {

    private final Integer statusCode;
    private final Object  body;

    public ConcordexException(String message) {
        this(message, null, null, null);
    }

    public ConcordexException(String message, Integer statusCode, Object body) {
        this(message, statusCode, body, null);
    }

    public ConcordexException(String message, Integer statusCode, Object body, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.body       = body;
    }

    /** HTTP status code that triggered this exception, or {@code null}
     * if not raised from an HTTP response (validation, network). */
    public Integer statusCode() { return statusCode; }

    /** Parsed response body — a {@code Map<String, Object>} on JSON
     * responses, a {@code String} on text bodies, or {@code null}. */
    public Object body() { return body; }
}
