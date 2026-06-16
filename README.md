# DMZAgent SDK — Java

[![Maven Central](https://img.shields.io/maven-central/v/com.dmzagent/dmzagent-sdk.svg)](https://search.maven.org/artifact/com.dmzagent/dmzagent-sdk)
[![spec](https://img.shields.io/badge/spec-0.5.0-blue)](https://github.com/dmzagent/dmzagent-sdk-spec/tree/v0.5.0)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Official Java SDK for [DMZAgent](https://dmzagent.com) — emit
agent-stream events, run circuit-breaker checks, verify outbound
webhook signatures.

The surface is defined by the language-agnostic
[`dmzagent-sdk-spec`](https://github.com/dmzagent/dmzagent-sdk-spec)
and is identical across the Python, TypeScript, C#, and Java SDKs —
same constructor shape, same methods (under each language's
idiomatic naming), same return types, same error hierarchy, same
wire protocol. This SDK pins to **spec version 0.5.0**.

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
| `userAgent` | `dmzagent-java/0.5.0`           |

For staging or self-hosted:

```java
var cx = new DMZAgentClient(
    "ck_…",
    "https://staging.api.eastern-shore-solutions.com",
    Duration.ofSeconds(30),
    "my-app/1.2.3");
```

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
A spec tag `v0.5.0` corresponds 1:1 to release tags in this and
every other DMZAgent SDK repo. No SDK ships a version the spec
hasn't blessed.

Pre-1.0 (current): MINOR bumps MAY include breaking wire changes;
PATCH bumps MUST remain backwards-compatible.

---

## License

[Apache-2.0](LICENSE).
