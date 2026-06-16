package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record NotificationPrefs(
    @JsonProperty("email_cadence")      String emailCadence,
    @JsonProperty("email_paused_until") String emailPausedUntil,
    @JsonProperty("push_enabled")       boolean pushEnabled,
    @JsonProperty("phone")              String phone,
    @JsonProperty("sms_enabled")        boolean smsEnabled,
    @JsonProperty("whatsapp_enabled")   boolean whatsappEnabled,
    @JsonProperty("webhook_url")        String webhookUrl,
    Map<String, Object>                 raw
) {
    public static NotificationPrefs fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        return new NotificationPrefs(
            (String) data.getOrDefault("email_cadence", "off"),
            (String) data.get("email_paused_until"),
            (Boolean) data.getOrDefault("push_enabled", false),
            (String) data.get("phone"),
            (Boolean) data.getOrDefault("sms_enabled", false),
            (Boolean) data.getOrDefault("whatsapp_enabled", false),
            (String) data.get("webhook_url"),
            data
        );
    }
}
