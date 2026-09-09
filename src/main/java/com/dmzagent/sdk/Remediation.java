package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** One thing that was done about an {@link Incident} (spec §7.14). */
public record Remediation(
    @JsonProperty("remediation_id") String remediationId,
    /** {@code approval} | {@code policy_change} | {@code manual} | {@code auto} */
    @JsonProperty("kind")           String kind,
    @JsonProperty("outcome")        String outcome,
    @JsonProperty("approval_id")    String approvalId,
    @JsonProperty("actor_id")       String actorId,
    @JsonProperty("reason")         String reason,
    @JsonProperty("occurred_at")    String occurredAt,
    @JsonProperty("anchor")         Map<String, Object> anchor
) {
    @SuppressWarnings("unchecked")
    public static Remediation fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?> m
            ? (Map<String, Object>) anchorRaw
            : null;
        return new Remediation(
            Approval.str(data, "remediation_id"),
            Approval.str(data, "kind"),
            Approval.str(data, "outcome"),
            Approval.nullableStr(data, "approval_id"),
            Approval.nullableStr(data, "actor_id"),
            Approval.nullableStr(data, "reason"),
            Approval.str(data, "occurred_at"),
            anchor
        );
    }
}
