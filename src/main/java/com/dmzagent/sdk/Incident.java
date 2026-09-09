package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One entry of the append-only incident ledger (spec §7.14).
 *
 * <p>{@code anchor} is the ledger entry that opened this incident, in the
 * same {@code {ledger_index, hash}} shape {@link CheckResult#anchor} carries.
 * A caller who recorded an anchor at check time can find that entry here and
 * compare hashes; a mismatch is the one alarm the ledger exists to make
 * possible.
 *
 * <p>An incident with no remediations and status {@code open} is the normal
 * shape of something nobody has answered yet — not an error, and not
 * something to collapse to {@code null}.
 */
public record Incident(
    @JsonProperty("incident_id")    String incidentId,
    /** {@code open} | {@code remediated} | {@code accepted} */
    @JsonProperty("status")         String status,
    /** {@code cb_open} | {@code cb_half_open} | {@code policy_fired} | {@code approval_required} */
    @JsonProperty("kind")           String kind,
    @JsonProperty("subject_id")     String subjectId,
    @JsonProperty("frame_id")       String frameId,
    @JsonProperty("opened_at")      String openedAt,
    @JsonProperty("closed_at")      String closedAt,
    @JsonProperty("reason")         String reason,
    @JsonProperty("fired_policies") List<Map<String, Object>> firedPolicies,
    /** Oldest first. MAY be empty. */
    @JsonProperty("remediations")   List<Remediation> remediations,
    @JsonProperty("anchor")         Map<String, Object> anchor,
    Map<String, Object>             raw
) {

    /** {@code true} while nobody has answered this. */
    public boolean isOpen() {
        return "open".equals(status);
    }

    @SuppressWarnings("unchecked")
    public static Incident fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();

        Object firedRaw = data.get("fired_policies");
        List<Map<String, Object>> fired = firedRaw instanceof List<?> l
            ? (List<Map<String, Object>>) firedRaw
            : List.of();

        List<Remediation> rems = new ArrayList<>();
        Object remRaw = data.get("remediations");
        if (remRaw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    rems.add(Remediation.fromResponse((Map<String, Object>) o));
                }
            }
        }

        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?> m
            ? (Map<String, Object>) anchorRaw
            : null;

        return new Incident(
            Approval.str(data, "incident_id"),
            Approval.str(data, "status"),
            Approval.str(data, "kind"),
            Approval.str(data, "subject_id"),
            Approval.nullableStr(data, "frame_id"),
            Approval.str(data, "opened_at"),
            Approval.nullableStr(data, "closed_at"),
            Approval.str(data, "reason"),
            fired,
            List.copyOf(rems),
            anchor,
            data
        );
    }
}
