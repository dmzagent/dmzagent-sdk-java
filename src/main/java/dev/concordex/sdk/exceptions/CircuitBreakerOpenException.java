package dev.concordex.sdk.exceptions;

import java.util.List;
import java.util.Map;

/**
 * Raised by {@code Guard} when {@code raiseOnOpen=true} and the
 * circuit-breaker check returned {@code allow=false}.
 *
 * <p>Spec §3 / §8.5, canonical name {@code CBOpenError}.
 *
 * <p>This is intentionally an exception (not just a return-value
 * flag) so that production code paths wrapping CB checks can use
 * {@code try / catch (CircuitBreakerOpenException e)} as a natural
 * control-flow seam — same idiom as other authorization-failure
 * exceptions.
 *
 * <p>NOTE: this exception is NOT raised from the HTTP layer. The
 * underlying {@code /v1/cb/check} call always returns 200; the
 * exception is synthesized by {@code Guard.close()} (and the
 * try-with-resources statement) when policy says block.
 *
 * <p>Beyond the base {@code message}, {@code statusCode}, and
 * {@code body}, this exception exposes the policy-decision context
 * required to log or report the block:
 *
 * <ul>
 *   <li>{@link #reason} — human-readable rationale.
 *   <li>{@link #firedPolicies} — list of {@code {cb_policy_id, name, action}}.
 *   <li>{@link #anchor} — ledger anchor (auditable proof), or {@code null}.
 *   <li>{@link #scopeRef} — the subject_id or interaction_id that was blocked.
 * </ul>
 */
public class CircuitBreakerOpenException extends ConcordexException {

    private final String                    reason;
    private final List<Map<String, Object>> firedPolicies;
    private final Map<String, Object>       anchor;
    private final String                    scopeRef;

    public CircuitBreakerOpenException(
        String message,
        String reason,
        List<Map<String, Object>> firedPolicies,
        Map<String, Object> anchor,
        String scopeRef
    ) {
        super(message, null, null);
        this.reason         = reason == null ? "" : reason;
        this.firedPolicies  = firedPolicies == null ? List.of() : firedPolicies;
        this.anchor         = anchor;
        this.scopeRef       = scopeRef == null ? "" : scopeRef;
    }

    public String                    reason()        { return reason; }
    public List<Map<String, Object>> firedPolicies() { return firedPolicies; }
    public Map<String, Object>       anchor()        { return anchor; }
    public String                    scopeRef()      { return scopeRef; }
}
