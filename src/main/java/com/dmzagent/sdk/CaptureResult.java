package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record CaptureResult(
    @JsonProperty("frame_id")         String frameId,
    @JsonProperty("accepted")         boolean accepted,
    @JsonProperty("n_workspaces")     int nWorkspaces,
    @JsonProperty("interaction_id")   String interactionId,
    @JsonProperty("subjects")         List<String> subjects,
    @JsonProperty("follow_my_data")   String followMyData,
    /** See {@link EmitResult#livemode()} (spec §1.2, §2.1). */
    @JsonProperty("livemode")         Optional<Boolean> livemode,
    Map<String, Object>               raw
) {
    @SuppressWarnings("unchecked")
    public static CaptureResult fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        return new CaptureResult(
            (String) data.getOrDefault("frame_id", ""),
            (Boolean) data.getOrDefault("accepted", false),
            ((Number) data.getOrDefault("n_workspaces", 0)).intValue(),
            (String) data.getOrDefault("interaction_id", ""),
            (List<String>) data.getOrDefault("subjects", List.of()),
            (String) data.get("follow_my_data"),
            Optional.ofNullable(data.get("livemode"))
                    .map(v -> v instanceof Boolean b ? b : null),
            data
        );
    }
}
