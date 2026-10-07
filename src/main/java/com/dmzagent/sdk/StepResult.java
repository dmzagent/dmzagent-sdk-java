package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The answer to one agent-mode step (spec §7.16).
 *
 * <p>The one question a harness asks — may this call run? — is
 * {@link #runs()}: {@code true} exactly for {@code proceed} and
 * {@code warn}. Branch on it rather than on {@link #directive()}, because
 * {@code directive} carries whatever word the server sent, including one
 * this SDK does not know (Appendix B), and an unknown word from the
 * governor is read as {@code block} (spec §1.9).
 *
 * <pre>{@code
 * StepResult r = session.call("call_7", "Bash", Map.of("command", "git push"), null, null);
 * if (r.runs()) {
 *     runTheTool();
 * } else {
 *     session.refused("call_7", "Bash", "governor", r.reason(), null, null);
 * }
 * }</pre>
 *
 * <p>{@code reason} is the operator's own policy words and MAY be empty;
 * {@code approvalId} is non-null exactly when the directive is
 * {@code hold}, and {@link DMZAgentClient#getApproval} is how a caller
 * learns whether it was approved.
 *
 * <p>This record is immutable per spec §7.15.
 */
public record StepResult(
    @JsonProperty("frame_id")       String frameId,
    @JsonProperty("interaction_id") String interactionId,
    /** One of {@link Directives#ALL}, or the raw string when unknown. */
    @JsonProperty("directive")      String directive,
    /** {@code subject} | {@code interaction} | {@code null} on {@code proceed}. */
    @JsonProperty("scope")          String scope,
    @JsonProperty("reason")         String reason,
    @JsonProperty("approval_id")    String approvalId,
    /** {@code false} while reasoning over this step is still running. */
    @JsonProperty("settled")        boolean settled,
    @JsonProperty("behaviors")      List<Behavior> behaviors,
    @JsonProperty("anchor")         Map<String, Object> anchor,
    /** As on {@link EmitResult}; {@code null} when the server did not say. */
    @JsonProperty("livemode")       Boolean livemode,
    Map<String, Object>             raw
) {

    /**
     * May the call this step reported run?
     *
     * <p>{@code true} exactly for {@code proceed} and {@code warn}; {@code false}
     * for {@code hold}, {@code block}, {@code shutdown} and every directive
     * this SDK does not know. Derived from {@link #directive()} rather than
     * stored, so no {@code StepResult} can say {@code block} and
     * {@code runs == true} at once. It has no counterpart on the wire.
     */
    public boolean runs() {
        return Directives.PROCEED.equals(directive) || Directives.WARN.equals(directive);
    }

    @SuppressWarnings("unchecked")
    public static StepResult fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        Object anchorRaw = data.get("anchor");
        Map<String, Object> anchor = anchorRaw instanceof Map<?, ?>
            ? (Map<String, Object>) anchorRaw
            : null;
        Object livemodeRaw = data.get("livemode");
        return new StepResult(
            Approval.str(data, "frame_id"),
            Approval.str(data, "interaction_id"),
            Approval.str(data, "directive"),
            Approval.nullableStr(data, "scope"),
            Approval.str(data, "reason"),
            Approval.nullableStr(data, "approval_id"),
            Boolean.TRUE.equals(data.get("settled")),
            behaviors(data.get("behaviors")),
            anchor,
            livemodeRaw instanceof Boolean b ? b : null,
            data
        );
    }

    @SuppressWarnings("unchecked")
    static List<Behavior> behaviors(Object raw) {
        if (!(raw instanceof List<?> l)) return List.of();
        List<Behavior> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof Map<?, ?>) out.add(Behavior.fromResponse((Map<String, Object>) o));
        }
        return List.copyOf(out);
    }
}
