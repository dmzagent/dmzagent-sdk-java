# Changelog

## [0.6.0] — 2026-06-02

### Added
- `com.dmzagent.sdk.concordia` — Java client for the Concordia MCP 1.0
  governance server at `/mcp/v1`. New `ConcordiaClient` class wraps
  the four MCP tools (`enforceCovenant`, `recordDecision`,
  `queryCorpus`, `getSubjectSoul`) and the three resources
  (`workspacePolicies`, `workspaceCanons`, `recentLedger`) so callers
  don't write JSON-RPC envelopes by hand.
- Typed result records: `EnforceCovenantResult`, `CbStateChange`,
  `RecordDecisionResult`, `CorpusMatch`, `QueryCorpusResult`, `SoulTag`,
  `SoulTrace`, `SubjectSoul`, `PolicySummary`, `InstalledCanon`,
  `LedgerEntry`, `LedgerPage`.
- Typed exception hierarchy with 1:1 mapping to MCP spec §8 error
  codes: `ConcordiaAuthException`, `ConcordiaQuotaExceededException`,
  `ConcordiaPolicyEngineUnavailableException`,
  `ConcordiaCanonNotInstalledException`,
  `ConcordiaSubjectNotFoundException`, `ConcordiaCircuitOpenException`,
  `ConcordiaPermissionDeniedException`. Plus base `ConcordiaException`
  and transport-level `ConcordiaProtocolException`.
- `ConcordiaClient.iterLedger()` returns an `Iterable<LedgerEntry>` that
  pages through `recentLedger()` transparently for streaming through
  the full chain.
- `ConcordiaClient.Builder` — fluent construction (`apiKey`, `baseUrl`,
  `timeout`, `userAgent`, `objectMapper`, `httpClient`) alongside the
  single-arg `ConcordiaClient(apiKey)` convenience constructor.

### Changed
- Package version bumped to `0.6.0` (minor — additive, no breaking
  changes to the existing 0.5.0 surface). The agent-stream surface
  (`DMZAgentClient`, `Conversation`, `WebhookSignature`) remains at
  spec v0.5.0.

## [0.5.0] — 2026-05-30

First lockstep release — supersedes the unilateral `0.1.0` line. This
version aligns the Java SDK with the Python, TypeScript, and C#
implementations under `dmzagent-sdk-spec` v0.5.0.

### Added
- `EmitResult` surfaces the rich synchronous envelope: `frameId`,
  `subjectId`, `outcome`, `triageDecision`, `tagsFired`,
  `scoredByCanons`, `soulVersion`, `ledgerIndex`, `followMyData`.
- `DMZAgentClient` overloaded constructors (`apiKey`; `apiKey, baseUrl,
  timeout, userAgent`) and the full emit-method surface
  (`subjectSays`, `toolCall`, `toolResult`, `observation`, `capture`,
  `check`, `guard`) with required `subjectType` per spec §5.1–5.5.
- `Conversation` handle (`client.conversation(participants, ...)`)
  wrapping the single-agent-one-customer case.
- `WebhookSignature.verify()` — HMAC-SHA256 signature verification per
  spec §9, symmetric with the Python/TypeScript/C# reference
  implementations (returns `false` rather than throwing on any
  verification failure).
- Full exception hierarchy under `com.dmzagent.sdk.exceptions`:
  `DMZAgentException`, `DMZAgentAuthException`,
  `DMZAgentPermissionException`, `DMZAgentValidationException`,
  `DMZAgentServerException`, `CircuitBreakerOpenException`.

### Changed
- Rebranded from `Concordex` to `DMZAgent` — package remains
  `com.dmzagent.sdk` (unchanged from the `0.1.0` line); only
  user-visible strings (User-Agent, docs) moved.
