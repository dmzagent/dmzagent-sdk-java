package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ReviewEvent(
    @JsonProperty("event_id")      String eventId,
    @JsonProperty("type")          String type,
    @JsonProperty("review_id")     String reviewId,
    @JsonProperty("subject_id")    String subjectId,
    @JsonProperty("tag_id")        String tagId,
    @JsonProperty("level")         String level,
    @JsonProperty("status")        String status,
    @JsonProperty("tier")          String tier,
    @JsonProperty("decision")      String decision,
    @JsonProperty("workspace_id")  String workspaceId,
    @JsonProperty("division_id")   String divisionId,
    @JsonProperty("frame_id")      String frameId,
    @JsonProperty("occurred_at")   String occurredAt,
    Map<String, Object>            raw
) {
    public static ReviewEvent fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        return new ReviewEvent(
            (String) data.getOrDefault("event_id", ""),
            (String) data.getOrDefault("type", ""),
            (String) data.getOrDefault("review_id", ""),
            (String) data.getOrDefault("subject_id", ""),
            (String) data.getOrDefault("tag_id", ""),
            (String) data.getOrDefault("level", "review"),
            (String) data.getOrDefault("status", "open"),
            (String) data.getOrDefault("tier", "workspace"),
            (String) data.get("decision"),
            (String) data.getOrDefault("workspace_id", ""),
            (String) data.get("division_id"),
            (String) data.get("frame_id"),
            (String) data.getOrDefault("occurred_at", ""),
            data
        );
    }
}
