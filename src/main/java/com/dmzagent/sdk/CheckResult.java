package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Return type of {@link DMZAgentClient#check}.
 *
 * <p>Per spec §7.2: {@code allow} is {@code false} only when
 * {@code state} is {@code "open"}. {@code warning} is {@code true}
 * when {@code state} is {@code "half_open"} — proceed but flag for
 * review.
 *
 * <p>{@code anchor} is {@code null} when no transition has been
 * anchored; when present it is a {@code {ledger_index, hash}} pair.
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
     * <p>Non-null only alongside {@code allow == false}. It is a field
     * rather than a fourth breaker state so that code reading {@code allow}
     * alone still refuses: a client that has never heard of approvals must
     * not start allowing what it used to deny.
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
        return new CheckResult(
            (String) data.getOrDefault("state", "closed"),
            ((Boolean) data.getOrDefault("allow", Boolean.TRUE)),
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
