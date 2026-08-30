package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Return type of every event-emit method ({@code subjectSays},
 * {@code toolCall}, {@code toolResult}, {@code observation}).
 *
 * <p>In synchronous mode (the default), the server returns the full
 * envelope after reasoning completes — frame_id, outcome, tags fired,
 * and so on. In async mode (header {@code X-DMZAgent-Async: true})
 * only {@code interactionId}, {@code subjects}, and {@code queued=true}
 * are populated; every other component is {@code Optional.empty()}.
 *
 * <p>Per spec §2.1, when the server can't determine a value (e.g. no
 * tags fired) it omits the field rather than returning {@code null}
 * or an empty array. Consumers MUST treat empty/missing as "unknown",
 * not as "empty result".
 *
 * <p>The {@code raw} component carries the full server JSON response
 * as a {@code Map<String, Object>} for callers who want to read fields
 * this SDK version doesn't surface explicitly yet.
 *
 * <p>This record is immutable per spec §7.10.
 */
public record EmitResult(
    @JsonProperty("interaction_id")    String interactionId,
    @JsonProperty("subjects")          List<String> subjects,
    @JsonProperty("queued")            boolean queued,
    /** True when the frame was stored and enqueued (spec §7.1). Not
     *  "reasoning finished" — see §1.6. Was reachable only through
     *  {@code raw()} before 0.8.0. */
    @JsonProperty("accepted")          Optional<Boolean> accepted,
    /** Number of workspaces the frame fanned out to (spec §7.1). Also
     *  previously reachable only through {@code raw()}. */
    @JsonProperty("n_workspaces")      Optional<Integer> nWorkspaces,
    @JsonProperty("frame_id")          Optional<String> frameId,
    @JsonProperty("subject_id")        Optional<String> subjectId,
    @JsonProperty("outcome")           Optional<String> outcome,
    @JsonProperty("triage_decision")   Optional<String> triageDecision,
    @JsonProperty("tags_fired")        Optional<List<String>> tagsFired,
    @JsonProperty("scored_by_canons")  Optional<List<String>> scoredByCanons,
    @JsonProperty("soul_version")      Optional<Integer> soulVersion,
    @JsonProperty("ledger_index")      Optional<Long> ledgerIndex,
    @JsonProperty("follow_my_data")    Optional<String> followMyData,
    /** {@code true} for a live key, {@code false} for a test key
     *  ({@code ck_test_…}), empty when the server omitted it (spec §1.2,
     *  §2.1). Empty rather than {@code false}: {@code false} is the
     *  positive claim "this is test data", and asserting that about a
     *  response that never carried the field is the confusion this signal
     *  exists to prevent. */
    @JsonProperty("livemode")          Optional<Boolean> livemode,
    Map<String, Object>                raw
) {

    /**
     * Build an EmitResult from a parsed server response. Missing
     * components are populated as {@code Optional.empty()}; the raw
     * map is retained verbatim.
     */
    @SuppressWarnings("unchecked")
    public static EmitResult fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        return new EmitResult(
            (String) data.getOrDefault("interaction_id", ""),
            (List<String>) data.getOrDefault("subjects", List.of()),
            ((Boolean) data.getOrDefault("queued", false)),
            Optional.ofNullable(data.get("accepted"))
                    .map(v -> v instanceof Boolean b ? b : null),
            Optional.ofNullable(data.get("n_workspaces"))
                    .map(v -> v instanceof Number n ? n.intValue() : null),
            Optional.ofNullable((String) data.get("frame_id")),
            Optional.ofNullable((String) data.get("subject_id")),
            Optional.ofNullable((String) data.get("outcome")),
            Optional.ofNullable((String) data.get("triage_decision")),
            Optional.ofNullable((List<String>) data.get("tags_fired")),
            Optional.ofNullable((List<String>) data.get("scored_by_canons")),
            // Jackson parses integer JSON into Integer or Long depending
            // on magnitude; normalize both to Integer/Long respectively.
            Optional.ofNullable(data.get("soul_version"))
                    .map(v -> v instanceof Number n ? n.intValue() : null),
            Optional.ofNullable(data.get("ledger_index"))
                    .map(v -> v instanceof Number n ? n.longValue() : null),
            Optional.ofNullable((String) data.get("follow_my_data")),
            Optional.ofNullable(data.get("livemode"))
                    .map(v -> v instanceof Boolean b ? b : null),
            data
        );
    }
}
