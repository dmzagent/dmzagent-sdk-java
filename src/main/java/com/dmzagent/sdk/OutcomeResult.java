package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record OutcomeResult(
    @JsonProperty("frame_id")      String frameId,
    @JsonProperty("outcome")       String outcome,
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
        return new OutcomeResult(
            (String) data.getOrDefault("frame_id", ""),
            (String) data.getOrDefault("outcome", "no_change"),
            (Map<String, Object>) data.get("error"),
            (List<Map<String, Object>>) data.get("tags_fired"),
            (List<Map<String, Object>>) data.get("reasoning"),
            data.get("soul_version") instanceof Number n ? n.intValue() : null,
            (String) data.getOrDefault("finished_at", ""),
            data
        );
    }
}
