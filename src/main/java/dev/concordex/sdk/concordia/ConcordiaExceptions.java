package dev.concordex.sdk.concordia;

import java.util.Collections;
import java.util.Map;

/**
 * Exception hierarchy for the Concordia MCP client.
 *
 * <p>The base class is {@link ConcordiaException}; the seven typed
 * subclasses map 1:1 to MCP spec §8 application error codes (-32001
 * through -32007). The transport / envelope variant
 * {@link ConcordiaProtocolException} covers non-200 HTTP responses,
 * timeouts, and parse errors.
 *
 * <pre>
 *   ConcordiaException                              base
 *     ├── ConcordiaProtocolException                non-200 / timeout / parse
 *     ├── ConcordiaAuthException                    -32001 auth_expired
 *     ├── ConcordiaQuotaExceededException           -32002 tenant_quota_exceeded
 *     ├── ConcordiaPolicyEngineUnavailableException -32003 policy_engine_unavailable
 *     ├── ConcordiaCanonNotInstalledException       -32004 canon_not_installed
 *     ├── ConcordiaSubjectNotFoundException         -32005 subject_not_found
 *     ├── ConcordiaCircuitOpenException             -32006 circuit_open
 *     └── ConcordiaPermissionDeniedException        -32007 permission_denied
 * </pre>
 *
 * <p>Callers usually catch the base {@link ConcordiaException};
 * specific subclasses are useful when the caller wants to branch
 * (e.g. fall back gracefully on {@code subject_not_found}, retry
 * with backoff on {@code circuit_open}).
 *
 * <p>All exceptions extend {@link RuntimeException} so SDK calls
 * don't require {@code throws} declarations to propagate through
 * customer code — matching the agent-stream surface convention.
 */
public final class ConcordiaExceptions {
    private ConcordiaExceptions() {}

    /** Base type. */
    public static class ConcordiaException extends RuntimeException {
        private final Integer code;
        private final String  errorId;
        private final Map<String, Object> errorData;

        public ConcordiaException(String message) {
            this(message, null, null, Collections.emptyMap(), null);
        }
        public ConcordiaException(String message, Throwable cause) {
            this(message, null, null, Collections.emptyMap(), cause);
        }
        public ConcordiaException(String message, Integer code, String errorId,
                                  Map<String, Object> errorData) {
            this(message, code, errorId, errorData, null);
        }
        public ConcordiaException(String message, Integer code, String errorId,
                                  Map<String, Object> errorData, Throwable cause) {
            super(message, cause);
            this.code      = code;
            this.errorId   = errorId;
            this.errorData = errorData == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(errorData);
        }

        /** JSON-RPC error code, or {@code null} for transport-level
         * failures. */
        public Integer code() { return code; }

        /** Concordex error slug from {@code error.data.error_id}
         * (e.g. {@code "auth_expired"}). */
        public String errorId() { return errorId; }

        /** Raw {@code data} object from the JSON-RPC error envelope.
         * Named {@code errorData} so it doesn't collide with anything
         * customers may add via standard {@code Throwable} APIs. */
        public Map<String, Object> errorData() { return errorData; }
    }

    /** Non-200 HTTP, timeout, or parse error. */
    public static final class ConcordiaProtocolException extends ConcordiaException {
        public ConcordiaProtocolException(String message)                { super(message); }
        public ConcordiaProtocolException(String message, Throwable c)   { super(message, c); }
        public ConcordiaProtocolException(String message, Integer code,
                                          Map<String, Object> data)      { super(message, code, null, data); }
    }

    /** -32001 — API key rejected. */
    public static final class ConcordiaAuthException extends ConcordiaException {
        public ConcordiaAuthException(String message, Map<String, Object> data) {
            super(message, -32001, "auth_expired", data);
        }
    }

    /** -32002 — workspace hit a billing cap. */
    public static final class ConcordiaQuotaExceededException extends ConcordiaException {
        public ConcordiaQuotaExceededException(String message, Map<String, Object> data) {
            super(message, -32002, "tenant_quota_exceeded", data);
        }
    }

    /** -32003 — CB substrate degraded; agent should default to
     * "review" and defer. */
    public static final class ConcordiaPolicyEngineUnavailableException extends ConcordiaException {
        public ConcordiaPolicyEngineUnavailableException(String message, Map<String, Object> data) {
            super(message, -32003, "policy_engine_unavailable", data);
        }
    }

    /** -32004 — {@code canonFilter} referenced a Canon not in the
     * workspace's corpus. */
    public static final class ConcordiaCanonNotInstalledException extends ConcordiaException {
        public ConcordiaCanonNotInstalledException(String message, Map<String, Object> data) {
            super(message, -32004, "canon_not_installed", data);
        }
    }

    /** -32005 — {@code getSubjectSoul} against an unknown subject. */
    public static final class ConcordiaSubjectNotFoundException extends ConcordiaException {
        public ConcordiaSubjectNotFoundException(String message, Map<String, Object> data) {
            super(message, -32005, "subject_not_found", data);
        }
    }

    /** -32006 — per-tool rate limit. Retry with exponential backoff. */
    public static final class ConcordiaCircuitOpenException extends ConcordiaException {
        public ConcordiaCircuitOpenException(String message, Map<String, Object> data) {
            super(message, -32006, "circuit_open", data);
        }
    }

    /** -32007 — role gate refusal. */
    public static final class ConcordiaPermissionDeniedException extends ConcordiaException {
        public ConcordiaPermissionDeniedException(String message, Map<String, Object> data) {
            super(message, -32007, "permission_denied", data);
        }
    }
}
