package dev.concordex.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Return type of {@link ConcordexClient#check}.
 *
 * <p>Per spec §7.2: {@code allow} is {@code false} only when
 * {@code state} is {@code "open"}. {@code warning} is {@code true}
 * when {@code state} is {@code "half_open"} — proceed but flag for
 * review.
 *
 * <p>{@code anchor} is {@code null} when no transition has been
 * anchored; when present it is a {@code {ledger_index, hash}} pair.
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
    Map<String, Object>               raw
) {

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
            data
        );
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
