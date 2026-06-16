package com.dmzagent.sdk.concordia;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Result records returned by {@link ConcordiaClient}. Twelve nested
 * records, one per tool / resource response shape, all backed by
 * Jackson's {@link JsonProperty} annotations to map the snake_case
 * wire field names to Java's camelCase.
 *
 * <p>Per spec §8 the Java binding uses {@code record} types for all
 * result shapes — they're immutable, ergonomic, and serialize cleanly
 * with Jackson. Construct directly is rare; usually you'll receive
 * them from one of the client's {@code …()} methods.
 */
public final class ConcordiaTypes {
    private ConcordiaTypes() {}

    /** Verdict constants for {@link EnforceCovenantResult#verdict}. */
    public static final class Verdict {
        private Verdict() {}
        public static final String ALLOW    = "allow";
        public static final String REVIEW   = "review";
        public static final String BLOCK    = "block";
        public static final String ESCALATE = "escalate";
    }

    /** Circuit-breaker state at the moment of an enforce_covenant call. */
    public record CbStateChange(
        @JsonProperty("state")        String  state,
        @JsonProperty("warning")      boolean warning,
        @JsonProperty("soul_version") Integer soulVersion
    ) {}

    /** Result of {@code enforceCovenant}. */
    public record EnforceCovenantResult(
        @JsonProperty("verdict")          String        verdict,
        @JsonProperty("policy_ids")       List<String>  policyIds,
        @JsonProperty("rationale")        String        rationale,
        @JsonProperty("cb_state_change")  CbStateChange cbStateChange,
        @JsonProperty("ledger_entry_id")  String        ledgerEntryId
    ) {
        /** True iff {@link #verdict()} is {@code "allow"}. */
        public boolean allow()   { return Verdict.ALLOW.equals(verdict); }
        /** True iff {@link #verdict()} is {@code "block"}. */
        public boolean blocked() { return Verdict.BLOCK.equals(verdict); }
    }

    /** Result of {@code recordDecision}. */
    public record RecordDecisionResult(
        @JsonProperty("ledger_entry_id") String  ledgerEntryId,
        @JsonProperty("chain_head_hash") String  chainHeadHash,
        @JsonProperty("index")           Integer index
    ) {}

    /** One match from {@code queryCorpus}. */
    public record CorpusMatch(
        @JsonProperty("canon_id")      String canonId,
        @JsonProperty("canon_version") String canonVersion,
        @JsonProperty("section")       String section,
        @JsonProperty("excerpt")       String excerpt,
        @JsonProperty("relevance")     double relevance
    ) {}

    /** Result of {@code queryCorpus}. */
    public record QueryCorpusResult(
        @JsonProperty("matches")     List<CorpusMatch> matches,
        @JsonProperty("scope")       String            scope,
        @JsonProperty("canon_count") int               canonCount
    ) {}

    /** One tag on a subject's soul snapshot. */
    public record SoulTag(
        @JsonProperty("tag_id")             String tagId,
        @JsonProperty("score")              double score,
        @JsonProperty("raw_strength")       Double rawStrength,
        @JsonProperty("evidence_count")     int    evidenceCount,
        @JsonProperty("first_observed_at")  String firstObservedAt,
        @JsonProperty("last_reinforced_at") String lastReinforcedAt
    ) {}

    /** One recent reasoning trace on a subject. */
    public record SoulTrace(
        @JsonProperty("trace_id")    String traceId,
        @JsonProperty("frame_id")    String frameId,
        @JsonProperty("verdict")     String verdict,
        @JsonProperty("outcome")     String outcome,
        @JsonProperty("started_at")  String startedAt,
        @JsonProperty("finished_at") String finishedAt
    ) {}

    /** Result of {@code getSubjectSoul}. */
    public record SubjectSoul(
        @JsonProperty("subject_id")    String          subjectId,
        @JsonProperty("snapshot_at")   String          snapshotAt,
        @JsonProperty("soul_version")  int             soulVersion,
        @JsonProperty("tags")          List<SoulTag>   tags,
        @JsonProperty("recent_traces") List<SoulTrace> recentTraces,
        @JsonProperty("retired_count") int             retiredCount
    ) {}

    /** One CB policy from {@code concordia:/workspace/policies}. */
    public record PolicySummary(
        @JsonProperty("cb_policy_id") String                     cbPolicyId,
        @JsonProperty("name")         String                     name,
        @JsonProperty("description")  String                     description,
        @JsonProperty("scope")        String                     scope,
        @JsonProperty("action")       String                     action,
        @JsonProperty("enabled")      boolean                    enabled,
        @JsonProperty("rules")        List<Map<String, Object>>  rules,
        @JsonProperty("updated_at")   String                     updatedAt
    ) {}

    /** One installed Canon from {@code concordia:/workspace/canons}. */
    public record InstalledCanon(
        @JsonProperty("canon_id")          String  canonId,
        @JsonProperty("name")              String  name,
        @JsonProperty("category")          String  category,
        @JsonProperty("author_name")       String  authorName,
        @JsonProperty("latest_version")    String  latestVersion,
        @JsonProperty("short_description") String  shortDescription,
        @JsonProperty("icon_url")          String  iconUrl,
        @JsonProperty("enabled")           boolean enabled,
        @JsonProperty("installed_at")      String  installedAt
    ) {}

    /** One ledger row from {@code concordia:/workspace/recent-ledger}. */
    public record LedgerEntry(
        @JsonProperty("event_id")     String              eventId,
        @JsonProperty("index")        int                 index,
        @JsonProperty("prev_hash")    String              prevHash,
        @JsonProperty("payload_hash") String              payloadHash,
        @JsonProperty("hash")         String              hash,
        @JsonProperty("payload")      Map<String, Object> payload,
        @JsonProperty("recorded_at")  String              recordedAt
    ) {}

    /** Paginated ledger page. When {@link #nextSince()} is non-null,
     * call {@code recentLedger(since: nextSince)} for the next page. */
    public record LedgerPage(
        @JsonProperty("entries")    List<LedgerEntry> entries,
        @JsonProperty("since")      int               since,
        @JsonProperty("limit")      int               limit,
        @JsonProperty("count")      int               count,
        @JsonProperty("next_since") Integer           nextSince
    ) {}
}
