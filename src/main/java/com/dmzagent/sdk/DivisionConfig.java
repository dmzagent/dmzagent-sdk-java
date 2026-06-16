package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record DivisionConfig(
    @JsonProperty("config")  Map<String, Object> config,
    Map<String, Object>      raw
) {
    @SuppressWarnings("unchecked")
    public static DivisionConfig fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Map<String, Object> cfg = (Map<String, Object>) data.getOrDefault("config", Map.of());
        return new DivisionConfig(cfg, data);
    }
}
