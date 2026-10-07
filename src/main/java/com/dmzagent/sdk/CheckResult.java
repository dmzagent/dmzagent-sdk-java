package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Return type of {@link DMZAgentClient#check}.
 *
 * <p>Per spec §2.2, {@code state} is {@code closed}, {@code half_open},
 * {@code hold} or {@code open}, and {@code allow} is {@code false} when it
 * is {@code hold} or {@code open}. {@code hold} is a subject waiting on a
 * person — a {@code require_approval} policy or an operator holds it — and
 * {@code pendingApprovalId} names the approval when there is one.
 * {@code warning} is {@code true} when {@code state} is {@code half_open}:
 * proceed but flag for review.
 *
 * <p>{@code allow} is read from the wire, with one exception: a
 * {@code state} this SDK does not know denies, whatever {@code allow} says
 * (Appendix B). The raw state is kept in {@code state}.
 *
 * <p>Each {@code firedPolicies} entry is {@code {cb_policy_id, name, action}},
 * where {@code action} is {@code allow}, {@code review}, {@code block} or
 * {@code require_approval} — setting {@code closed}, {@code half_open},
 * {@code open} or {@code hold}; the most restrictive wins. Entries are
 * passed through as the server sent them.
 *
 * <p>{@code anchor} is {@code null} when no transition has been
 * anchored; when present it is a {@code {ledger_index, hash}} pair (the
 * server also sends {@code ledger_event_id}, kept in the map and otherwise
 * unused).
 *
 * <p>{@code cached}, {@code cacheAge} and {@code stale} describe how the
 * caller got this result (spec §4.4) and have no counterpart on the wire.
 * With the state cache off — the default — they are always
 * {@code false}, {@code ZERO}, {@code false}.
 *
 * <p>This record is immutable per spec §7.3.
 */
public record CheckResult(
    @JsonProperty("state")            String state,
    @JsonProperty("allow")            boolean allow,
    @JsonProperty("warning")          boolean warning,
    @JsonProperty("reason")           String reason,
    @JsonProperty("fired_policies")   List<Map<String, Object>> firedPolicies,
    @JsonProperty("anchor")           Map<String, Object> anchor,
    @JsonProperty("checked_at")       String checkedAt,
    @JsonProperty("latency_ms")       double latencyMs,
    @JsonProperty("route_latency_ms") double routeLatencyMs,
    /** Served from the state cache (spec §4.4)? */
    boolean                           cached,
    /** Age of the cache entry when it was served; {@code ZERO} if fresh. */
    Duration                          cacheAge,
    /** The check failed and this is the last known state. */
    boolean                           stale,
    /**
     * The approval this denial is waiting on, or {@code null} (spec §2.2).
     *
     * <p>Non-null only alongside {@code allow == false}, normally with
     * {@code state == "hold"}. Code reading {@code allow} alone still
     * refuses: a client that has never heard of approvals must not start
     * allowing what it used to deny.
     */
    @JsonProperty("pending_approval_id") String pendingApprovalId,
    Map<String, Object>               raw
) {

    /**
     * This is an ask, not a refusal — a human can still clear it.
     *
     * <p>The difference {@code pendingApprovalId} exists to express: branch
     * on it to show your approval UI instead of telling the user no.
     */
    public boolean awaitingApproval() {
        return pendingApprovalId != null;
    }

    /** The breaker states this SDK knows (spec §2.2). */
    static final java.util.Set<String> KNOWN_STATES =
        java.util.Set.of("closed", "half_open", "hold", "open");

    /** Build a CheckResult from a parsed server response. */
    @SuppressWarnings("unchecked")
    public static CheckResult fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Object firedRaw = data.get("fired_policies");
        List<Map<String, Object>> fired = firedRaw instanceof List<?> l
            ? (List<Map<String, Object>>) firedRaw
            : List.of();
        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?> m
            ? (Map<String, Object>) anchorRaw
            : null;
        String state = (String) data.getOrDefault("state", "closed");
        // An unknown state denies (Appendix B): the server may add a state,
        // and a client that does not know what it means must not read the
        // accompanying allow as permission.
        boolean allow = KNOWN_STATES.contains(state)
            && (Boolean) data.getOrDefault("allow", Boolean.TRUE);
        return new CheckResult(
            state,
            allow,
            ((Boolean) data.getOrDefault("warning", Boolean.FALSE)),
            (String) data.getOrDefault("reason", ""),
            fired,
            anchor,
            (String) data.getOrDefault("checked_at", ""),
            toDouble(data.get("latency_ms")),
            toDouble(data.get("route_latency_ms")),
            false,
            Duration.ZERO,
            false,
            data.get("pending_approval_id") instanceof String s ? s : null,
            data
        );
    }

    /**
     * This result, marked as served from the cache at {@code age} old.
     *
     * <p>{@code latencyMs}, {@code routeLatencyMs}, {@code checkedAt} and
     * {@code raw} are left alone: they describe the check that actually
     * happened, and rewriting them to describe the cache hit would erase
     * the only record of when the server was last asked.
     */
    public CheckResult asCached(Duration age, boolean stale) {
        Duration safe = (age == null || age.isNegative()) ? Duration.ZERO : age;
        return new CheckResult(
            state, allow, warning, reason, firedPolicies, anchor, checkedAt,
            latencyMs, routeLatencyMs, true, safe, stale, pendingApprovalId, raw);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
