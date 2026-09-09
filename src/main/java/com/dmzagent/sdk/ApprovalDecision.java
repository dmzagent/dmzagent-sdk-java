package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The human half of an {@link Approval} — who decided, and why.
 *
 * <p>{@code actorId} is the <em>caller's</em> identifier for a person, not
 * ours. We resolve it against no directory and store it as given, which is
 * what lets a customer's own users decide without ever holding an account
 * here.
 */
public record ApprovalDecision(
    @JsonProperty("decision")    String decision,
    @JsonProperty("actor_id")    String actorId,
    @JsonProperty("actor_label") String actorLabel,
    @JsonProperty("reason")      String reason,
    @JsonProperty("decided_at")  String decidedAt
) {
    public static ApprovalDecision fromResponse(Map<String, Object> data) {
        if (data == null) return null;
        return new ApprovalDecision(
            Approval.str(data, "decision"),
            Approval.str(data, "actor_id"),
            Approval.nullableStr(data, "actor_label"),
            Approval.nullableStr(data, "reason"),
            Approval.str(data, "decided_at")
        );
    }
}
