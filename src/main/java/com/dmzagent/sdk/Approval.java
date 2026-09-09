package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * An action held pending a human decision (spec §7.12).
 *
 * <p>Every field here is something <em>you</em> render. There is no message
 * written for your end user, no copy of ours, and no display string:
 * {@code reason} and each {@code firedPolicies} entry's {@code name} are the
 * words your operator typed when they wrote the policy, and {@code action}
 * is the call your agent was about to make, verbatim. Building display text
 * out of them is your job precisely because a sentence we wrote would read
 * the same in every customer's product.
 *
 * <p>{@code expiresAt} stays the server's ISO-8601 string rather than a
 * parsed countdown. Seconds-remaining computed at parse time is wrong by
 * however long you held the object, and the caller rendering an approval
 * deadline is exactly the caller who holds it.
 *
 * <p>This record is immutable per spec §7.10.
 */
public record Approval(
    @JsonProperty("approval_id")    String approvalId,
    @JsonProperty("status")         String status,
    @JsonProperty("subject_id")     String subjectId,
    @JsonProperty("interaction_id") String interactionId,
    @JsonProperty("frame_id")       String frameId,
    /** The held call, verbatim: {@code {tool, args}}. */
    @JsonProperty("action")         Map<String, Object> action,
    @JsonProperty("reason")         String reason,
    @JsonProperty("fired_policies") List<Map<String, Object>> firedPolicies,
    @JsonProperty("requested_at")   String requestedAt,
    @JsonProperty("expires_at")     String expiresAt,
    /**
     * Always {@code "decline"}. An approval that becomes an allow because
     * nobody looked at it is a delay with extra steps, not a control
     * (spec §2.9).
     */
    @JsonProperty("on_expiry")      String onExpiry,
    @JsonProperty("anchor")         Map<String, Object> anchor,
    @JsonProperty("decision")       ApprovalDecision decision,
    Map<String, Object>             raw
) {

    /** {@code true} while nobody has decided. */
    public boolean isPending() {
        return "pending".equals(status);
    }

    /** The tool this approval is holding, or {@code ""} when absent. */
    public String tool() {
        Object t = action == null ? null : action.get("tool");
        return t instanceof String s ? s : "";
    }

    @SuppressWarnings("unchecked")
    public static Approval fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();

        Object actionRaw = data.get("action");
        Map<String, Object> action = actionRaw instanceof Map<?, ?> m
            ? (Map<String, Object>) actionRaw
            : Map.of();

        Object firedRaw = data.get("fired_policies");
        List<Map<String, Object>> fired = firedRaw instanceof List<?> l
            ? (List<Map<String, Object>>) firedRaw
            : List.of();

        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?> m
            ? (Map<String, Object>) anchorRaw
            : null;

        Object decisionRaw = data.get("decision");
        ApprovalDecision decision = decisionRaw instanceof Map<?, ?> m
            ? ApprovalDecision.fromResponse((Map<String, Object>) decisionRaw)
            : null;

        return new Approval(
            str(data, "approval_id"),
            str(data, "status"),
            str(data, "subject_id"),
            nullableStr(data, "interaction_id"),
            nullableStr(data, "frame_id"),
            action,
            str(data, "reason"),
            fired,
            str(data, "requested_at"),
            str(data, "expires_at"),
            // Not read from the server: expiry declines, and a server that
            // ever sent "approve" would be describing a control this SDK
            // does not implement (spec §2.9).
            "decline",
            anchor,
            decision,
            data
        );
    }

    static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : "";
    }

    static String nullableStr(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : null;
    }
}
