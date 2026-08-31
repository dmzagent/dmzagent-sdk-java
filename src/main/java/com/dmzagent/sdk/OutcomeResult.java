package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Returned by {@code awaitOutcome()} (sdk-spec.md §7.3).
 *
 * <p>Per-workspace reasoning results for a captured frame. A frame is
 * division-scoped: it fans out to every workspace in its division and
 * produces one trace per workspace, each entry in {@code reasoning} naming
 * the {@code workspace_id} that produced it.
 *
 * <p>{@code outcome} is a fold over {@code reasoning} computed server-side,
 * with precedence failed &gt; held &gt; applied &gt; no_change &gt; skipped
 * (§2.7). It is {@code null} until at least one trace exists — never
 * guessed. This defaulted to {@code "no_change"} when the key was absent,
 * which reported a clean result for a frame nothing had reasoned over yet.
 */
public record OutcomeResult(
    @JsonProperty("frame_id")      String frameId,
    @JsonProperty("outcome")       String outcome,
    @JsonProperty("division_id")   String divisionId,
    @JsonProperty("workspace_ids") List<String> workspaceIds,
    @JsonProperty("complete")      boolean complete,
    @JsonProperty("error")         Map<String, Object> error,
    @JsonProperty("tags_fired")    List<Map<String, Object>> tagsFired,
    @JsonProperty("reasoning")     List<Map<String, Object>> reasoning,
    @JsonProperty("soul_version")  Integer soulVersion,
    @JsonProperty("finished_at")   String finishedAt,
    Map<String, Object>            raw
) {
    @SuppressWarnings("unchecked")
    public static OutcomeResult fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Object summaryRaw = data.get("summary");
        Map<String, Object> summary = summaryRaw instanceof Map
            ? (Map<String, Object>) summaryRaw
            : Map.of();
        return new OutcomeResult(
            (String) data.getOrDefault("frame_id", ""),
            (String) data.get("outcome"),
            (String) data.get("division_id"),
            (List<String>) data.get("workspace_ids"),
            Boolean.TRUE.equals(summary.get("complete")),
            (Map<String, Object>) data.get("error"),
            (List<Map<String, Object>>) data.get("tags_fired"),
            (List<Map<String, Object>>) data.get("reasoning"),
            data.get("soul_version") instanceof Number n ? n.intValue() : null,
            (String) data.getOrDefault("finished_at", ""),
            data
        );
    }
}
