package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A behavior observed in an agent session (spec §7.17).
 *
 * <p>{@code tag} is the installed canon's own word for what was observed,
 * in the words of whoever wrote the canon. This SDK does not map, rename
 * or describe it (spec §1.9), and neither should a caller that renders
 * it on someone else's behalf.
 *
 * <p>{@code polarity} is {@code positive} or {@code negative}, or the raw
 * string when the server sends a value this SDK does not know
 * (Appendix B). It is never coerced to one of the two.
 *
 * <p>{@code behaviorId}, {@code subjectId}, {@code interactionId},
 * {@code observedAt} and {@code anchor} are present when the behavior was
 * read from the conduct record ({@link DMZAgentClient#listBehaviors});
 * on a {@link StepResult} they are {@code null}.
 *
 * <p>This record is immutable per spec §7.15.
 */
public record Behavior(
    @JsonProperty("tag")            String tag,
    @JsonProperty("polarity")       String polarity,
    /** 0–1: what the subject's soul holds for this tag now (spec §2.12). */
    @JsonProperty("strength")       double strength,
    /** {@code logic} | {@code reasoning}, or the raw string. */
    @JsonProperty("source")         String source,
    /** Frame ids that are this behavior's evidence. */
    @JsonProperty("evidence")       List<String> evidence,
    /** The {@code call_id}s it concerns, when the server can name them; MAY be empty. */
    @JsonProperty("calls")          List<String> calls,
    @JsonProperty("behavior_id")    String behaviorId,
    @JsonProperty("subject_id")     String subjectId,
    @JsonProperty("interaction_id") String interactionId,
    @JsonProperty("observed_at")    String observedAt,
    @JsonProperty("anchor")         Map<String, Object> anchor
) {

    @SuppressWarnings("unchecked")
    public static Behavior fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Object strengthRaw = data.get("strength");
        double strength = strengthRaw instanceof Number n ? n.doubleValue() : 0.0;
        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?>
            ? (Map<String, Object>) anchorRaw
            : null;
        return new Behavior(
            Approval.str(data, "tag"),
            Approval.str(data, "polarity"),
            strength,
            Approval.str(data, "source"),
            strings(data.get("evidence")),
            strings(data.get("calls")),
            Approval.nullableStr(data, "behavior_id"),
            Approval.nullableStr(data, "subject_id"),
            Approval.nullableStr(data, "interaction_id"),
            Approval.nullableStr(data, "observed_at"),
            anchor
        );
    }

    /** An immutable list of the strings in {@code raw}; empty when absent. */
    static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof String s) out.add(s);
        }
        return List.copyOf(out);
    }
}
