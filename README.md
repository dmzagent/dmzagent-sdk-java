# DMZAgent SDK — Java

[![Maven Central](https://img.shields.io/maven-central/v/com.dmzagent/dmzagent-sdk.svg)](https://search.maven.org/artifact/com.dmzagent/dmzagent-sdk)
[![spec](https://img.shields.io/badge/spec-0.9.0-blue)](https://github.com/dmzagent/dmzagent-sdk-spec/tree/v0.9.0)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Official Java SDK for [DMZAgent](https://dmzagent.com) — emit
agent-stream events, run circuit-breaker checks, verify outbound
webhook signatures.

The surface is defined by the language-agnostic
[`dmzagent-sdk-spec`](https://github.com/dmzagent/dmzagent-sdk-spec)
and is identical across the Python, TypeScript, C#, and Java SDKs —
same constructor shape, same methods (under each language's
idiomatic naming), same return types, same error hierarchy, same
wire protocol. This SDK pins to **spec version 0.9.0** — the value in
`pom.xml`'s `<dmzagent.spec.version>`, which `VersionMarkerTest` holds
the User-Agent to.

---

## Install

### Maven

```xml
<dependency>
  <groupId>com.dmzagent</groupId>
  <artifactId>dmzagent-sdk</artifactId>
  <version>0.5.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.dmzagent:dmzagent-sdk:0.5.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'com.dmzagent:dmzagent-sdk:0.5.0'
}
```

Requires **Java 17+**.

---

## Quick start

```java
import com.dmzagent.sdk.DMZAgentClient;

try (var cx = new DMZAgentClient("ck_...")) {

    cx.subjectSays(
        "user:ws:cust",          // subject_id (the speaker)
        "I want a refund.",      // text
        "user:ws:bot");          // agent_subject_id (conversation anchor)

    var g = cx.check("user:ws:bot");
    if (!g.allow()) {
        throw new IllegalStateException("blocked: " + g.reason());
    }
}
```

Get an API key from your tenant admin. Keys start with `ck_` —
the SDK rejects empty keys or keys without that prefix at
construction time.

---

## The four event helpers

```java
// 1. A subject said something.
cx.subjectSays(
    "user:ws:cust",         // speaker
    "I want a refund.",     // utterance
    "user:ws:bot");         // conversation anchor

// 2. The agent invoked a tool — emit BEFORE running.
cx.toolCall(
    "user:ws:bot",
    "refund.issue",
    Map.of("amount", 9900, "currency", "usd"));

// 3. The tool returned a result — pair with the prior toolCall.
cx.toolResult(
    "user:ws:bot",
    "refund.issue",
    Map.of("refund_id", "re_123", "status", "succeeded"));

// 4. Structured observation — video keyframe, IoT, sensor reading.
cx.observation(
    "user:ws:sensor",
    List.of(Map.of("subject_id", "user:ws:sensor",
                   "role",       "service",
                   "kind",       "sensor")),
    Map.of("kind", "video_keyframe", "frame_index", 42));
```

For richer flows, the `emitEvent` low-level method takes the full
parameter set documented in spec §2.1.

---

## Circuit-breaker checks

```java
// One-shot check — read the result manually.
var r = cx.check("user:ws:bot");
if (!r.allow()) {
    return refuse(r.reason(), r.firedPolicies());
}
```

### try-with-resources `Guard`

For control-flow seams that mirror try/catch on authorization failure:

```java
try (var g = cx.guard("user:ws:bot")) {
    if (!g.getResult().allow()) {
        return refuse(g.getResult().reason());
    }
    // ... sensitive action runs only when allowed
}
```

Add `raiseOnOpen=true` to throw `CircuitBreakerOpenException` when
policy says block — useful for wrapping a sensitive code path that
must not proceed:

```java
try (var g = cx.guard("user:ws:bot", null, true)) {
    issueRefund();
} catch (CircuitBreakerOpenException e) {
    log.warn("blocked: {} policies={}", e.reason(), e.firedPolicies());
}
```

---

## Conversations

For the common single-agent-with-customer case (and generalizations
to multi-agent / multi-subject conversations), use `Conversation`
to track the subject roster and stitch every event into the same
`interaction_id`:

```java
var participants = List.<Map<String,Object>>of(
    Map.of("subject_id", "user:ws:bot",  "role", "agent",    "kind", "agent"),
    Map.of("subject_id", "user:ws:cust", "role", "customer", "kind", "human"));

try (var conv = cx.conversation(participants)) {
    conv.says("user:ws:cust", "I want a refund.");
    conv.says("user:ws:bot",  "I can help with that.");

    try (var g = conv.guard("user:ws:bot")) {
        if (!g.getResult().allow()) {
            throw new IllegalStateException(g.getResult().reason());
        }
    }
    conv.toolCall("user:ws:bot", "refund.issue", Map.of("amount", 9900));
}
```

The `agentSubjectId` the wire protocol requires is derived from the
first participant whose role is in `{agent, service, system}`. Pass
it explicitly when your conversation has no agent-shaped
participant.

Add participants mid-conversation with `conv.addSubject(id, role, kind)`.

---

## Webhook signature verification

DMZAgent signs outbound webhooks with HMAC-SHA256. Use
`WebhookSignature.verify` to check the `DMZAgent-Signature` header
before trusting a payload:

```java
import com.dmzagent.sdk.WebhookSignature;

boolean valid = WebhookSignature.verify(
    rawRequestBody,                                // payload (UTF-8 string)
    request.getHeader("DMZAgent-Signature"),      // "t=<unix>,v1=<hex>"
    System.getenv("DMZAGENT_WEBHOOK_SECRET"));    // whsec_…

if (!valid) {
    response.setStatus(401);
    return;
}
```

The default tolerance is 300 seconds; override with the four-arg
form. For testing, the five-arg overload accepts a fixed `nowUnix`
to make replay-window tests deterministic.

The verifier returns `false` for every failure mode (missing
fields, malformed timestamp, wrong secret, replay-window breach,
digest mismatch) — it does **not** throw on bad input.

---

## Human-in-the-loop approvals

A circuit-breaker policy can fire with action `require_approval`, which
**holds** the action instead of refusing it. `check()` then hands back a
denial that names what it is waiting on:

```java
CheckResult g = cx.check("subject:dv:checkout-bot");

if (g.awaitingApproval()) {
    showMyOwnApprovalScreen(g.pendingApprovalId());   // asked
} else if (!g.allow()) {
    return refuse(g.reason());                        // refused
}
```

That is the whole difference between a breaker and a human-in-the-loop
control, and it is one field because you have to branch on it.

### You render it. All of it.

```java
cx.iterApprovals("pending", null, null).forEach(a -> {
    System.out.println(a.tool());        // the held call, verbatim
    System.out.println(a.reason());      // your operator's policy words
    System.out.println(a.expiresAt());   // decide before this
});
```

Nothing in an `Approval` is display text we wrote. `reason()` and each
`firedPolicies()` entry's `name` are the words your operator typed when
they wrote the policy, and `action()` is the call your agent was about to
make. There is no message for your end user, no copy of ours, and no
branding — because a sentence we wrote would read identically in every
customer's product, which is the thing this is designed to avoid.

### A decision records which human made it

```java
cx.approveApproval("apr_7f3c9a1b", "acct_4471",
                   "Dana R.", "verified the order by phone");
```

`actorId` is required, never defaulted, and never derived from the API
key — the key identifies your integration, and an approval whose actor is
the integration that requested it has recorded nobody. We resolve it
against no directory, so your users never need an account here. A blank
one throws `IllegalArgumentException` before any request goes out.

Two operators who click at the same moment produce one decision and one
`DMZAgentConflictException`; the body carries the status the approval had
already reached. That is not a retry — the call did not fail, it lost.

**An approval that nobody answers declines.** `onExpiry()` is always
`"decline"` and there is no setting that changes it: an approval that
becomes an allow because nobody looked at it is not a human-in-the-loop
control, it is a delay with extra steps.

## The incident and remediation ledger

`anchor()` has been on `CheckResult` for several releases, pointing into a
ledger nothing could open. Now it opens:

```java
CheckResult g = cx.check("subject:dv:checkout-bot");
Map<String, Object> recorded = g.anchor();

cx.iterIncidents("open", null, "2026-09-01T00:00:00Z", null, null)
  .filter(inc -> Objects.equals(inc.anchor(), recorded))
  .forEach(inc -> {
      // this is the entry your check was told about
  });
```

Every breaker that opened, every approval decided, every remediation that
ran — newest ledger entry first, in the order the ledger recorded them
rather than by timestamp, because two entries written in the same second
still have an order.

The ledger is **append-only**. There is no `closeIncident()` and no method
that edits an entry: an incident reaches `remediated` because a
remediation was appended to it, and `status()` is a fold over what has
been appended. An incident with no remediations is the normal shape of
something nobody has answered yet.

### Paging

`listApprovals()` and `getIncidents()` return one page and do not follow
`nextCursor()`. You asked for 25 and you get 25 — a method that quietly
walked every page would turn one bounded request into an unbounded one
against a record that only grows. `iterApprovals()` and `iterIncidents()`
return a lazy `Stream`: a short-circuiting terminal operation never
requests the next page.

## Exception hierarchy

```
DMZAgentException                         base (RuntimeException)
  ├── DMZAgentAuthException               401
  ├── DMZAgentPermissionException         403
  ├── DMZAgentValidationException         400
  ├── DMZAgentServerException             5xx / network / timeout
  └── CircuitBreakerOpenException          guard(raiseOnOpen=true) + allow=false
```

Every exception exposes `message`, `statusCode()`, and `body()`.
`CircuitBreakerOpenException` additionally exposes `reason()`,
`firedPolicies()`, `anchor()`, and `scopeRef()`.

Client-side argument validation (unknown event kind, both /
neither of `subjectId`/`interactionId` on `check`, missing `ck_`
prefix on the API key) raises `IllegalArgumentException`, not
`DMZAgentValidationException` — the latter is reserved for `400`
responses from the server.

---

## Configuration

Every constructor parameter has a documented default:

| Parameter   | Default                          |
|-------------|----------------------------------|
| `apiKey`    | (required — must start `ck_`)    |
| `baseUrl`   | `https://api.dmzagent.com`      |
| `timeout`   | `Duration.ofMillis(10_000)`      |
| `userAgent` | `dmzagent-java/<spec version>`   |
| `cbCacheTtl` | `null` (state cache off)        |
| `cbCacheMaxEntries` | `1024`                   |
| `cbCacheOnError` | `CbCacheOnError.RAISE`      |

For staging or self-hosted:

```java
var cx = new DMZAgentClient(
    "ck_…",
    "https://staging.api.eastern-shore-solutions.com",
    Duration.ofSeconds(30),
    "my-app/1.2.3");
```

### Circuit-breaker state cache

`check()` is a network round trip, and it usually sits in front of the
sensitive action. A per-client cache removes it for repeated checks on
the same subject. It is off unless you pass a TTL:

```java
var cx = new DMZAgentClient(
    "ck_…", null, null, null, null,
    Duration.ofSeconds(5),          // cbCacheTtl — null or ZERO is off
    1024,                           // cbCacheMaxEntries, LRU evicted
    CbCacheOnError.LAST_KNOWN);     // or RAISE (default)

CheckResult r = cx.check("user:ws:bot");
r.cached();     // served from memory?
r.cacheAge();   // how old it was
r.stale();      // served because the check itself failed

cx.check("user:ws:bot", null, true);   // fresh — skip the cache, refresh it
```

Read the TTL as **the longest a newly-opened breaker can go unnoticed by
this client**. A cached `closed` is an allow the server might no longer
give, which is why the cache is opt-in and why every result says whether
it came from memory and how old it was.

One TTL covers every state. Holding a deny longer than an allow is a
safety policy, and it is yours to make with the number you pass.

`CbCacheOnError.LAST_KNOWN` serves the last state for that subject —
marked `stale()` — when the check cannot reach the server. With no entry
for that subject it throws, and it needs a TTL above zero to be set at
all. A `429` is not covered: that is the server answering, and it carries
a `retryAfter` worth acting on.

---

## Async / non-blocking

This SDK exposes blocking methods that return `EmitResult` and
`CheckResult` directly — appropriate for the agent-runtime
contexts where consumers already manage their own thread or
executor model. A `CompletableFuture<EmitResult>` overload may
land in a later spec version when customer demand confirms it's
worth the surface-area cost.

In the meantime, callers who need non-blocking semantics should
dispatch SDK calls into an `ExecutorService` of their choice. The
underlying OkHttp client is thread-safe and supports concurrent
calls from a single `DMZAgentClient` instance.

---

## Versioning

This SDK follows the language-agnostic spec at
[dmzagent-sdk-spec](https://github.com/dmzagent/dmzagent-sdk-spec).
A spec tag `v0.9.0` corresponds 1:1 to release tags in this and
every other DMZAgent SDK repo. No SDK ships a version the spec
hasn't blessed.

Pre-1.0 (current): MINOR bumps MAY include breaking wire changes;
PATCH bumps MUST remain backwards-compatible.

---

## License

[Apache-2.0](LICENSE).
