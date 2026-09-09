package com.dmzagent.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.dmzagent.sdk.exceptions.CircuitBreakerOpenException;
import com.dmzagent.sdk.exceptions.DMZAgentAuthException;
import com.dmzagent.sdk.exceptions.DMZAgentConflictException;
import com.dmzagent.sdk.exceptions.DMZAgentException;
import com.dmzagent.sdk.exceptions.DMZAgentPermissionException;
import com.dmzagent.sdk.exceptions.DMZAgentRateLimitException;
import com.dmzagent.sdk.exceptions.DMZAgentServerException;
import com.dmzagent.sdk.exceptions.DMZAgentValidationException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Synchronous DMZAgent client — the SDK's main entry point.
 *
 * <p>Per spec §4, the Java class name is {@code DMZAgentClient}
 * (not the bare {@code DMZAgent} that Python and TypeScript use)
 * because Java reserves unqualified type names for value-bearing
 * entities and expects a {@code Client} suffix on HTTP service
 * classes.
 *
 * <p>The client is sync-first. OkHttp's call dispatcher is thread-safe,
 * so a single {@code DMZAgentClient} instance can be shared across
 * threads. Most agent runtimes already manage their own thread/executor
 * model; an async overload returning {@code CompletableFuture<...>}
 * may land in a later spec version when customer demand confirms
 * it's worth the surface-area cost.
 *
 * <p>Quick start:
 *
 * <pre>{@code
 *   try (var cx = new DMZAgentClient("ck_...")) {
 *       cx.subjectSays(
 *           "user:ws:cust",                       // subject_id (speaker)
 *           "I want a refund.",                   // text
 *           "user:ws:bot");                       // agent_subject_id (anchor)
 *
 *       try (var g = cx.guard("user:ws:bot")) {
 *           if (!g.getResult().allow()) {
 *               throw new IllegalStateException(g.getResult().reason());
 *           }
 *           // ... sensitive action
 *       }
 *   }
 * }</pre>
 */
public final class DMZAgentClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DMZAgentClient.class);

    private static final String   DEFAULT_BASE_URL = "https://api.dmzagent.com";
    private static final Duration DEFAULT_TIMEOUT  = Duration.ofMillis(10_000);
    private static final String   DEFAULT_UA       = "dmzagent-java/" + SpecVersion.VALUE;

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String       apiKey;
    private final String       baseUrl;
    private final String       userAgent;
    private final CBStateCache   cbCache;
    private final CbCacheOnError cbCacheOnError;
    private boolean closed = false;

    static final Set<String> VALID_SUBJECT_TYPES = Set.of("chat", "sensor", "lead", "ticket", "journey");

    // ===================================================================== //
    // Construction
    // ===================================================================== //

    /** Construct with defaults: api.dmzagent.com, 10s timeout, default UA. */
    public DMZAgentClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_TIMEOUT, DEFAULT_UA, null);
    }

    /** Builder-style explicit constructor. */
    public DMZAgentClient(
        String   apiKey,
        String   baseUrl,
        Duration timeout,
        String   userAgent
    ) {
        this(apiKey, baseUrl, timeout, userAgent, null);
    }

    /**
     * Full constructor with optional OkHttp {@link Interceptor} for
     * tests. The interceptor is added as an APPLICATION interceptor,
     * which lets contract tests short-circuit network I/O and return
     * canned responses without spinning up a mock server.
     *
     * <p>Per spec §4.1, the only required parameter is {@code apiKey};
     * everything else has a documented default.
     *
     * <p>Per spec §1.2, this constructor rejects empty keys and keys
     * that don't start with {@code ck_}.
     */
    public DMZAgentClient(
        String      apiKey,
        String      baseUrl,
        Duration    timeout,
        String      userAgent,
        Interceptor testInterceptor
    ) {
        this(apiKey, baseUrl, timeout, userAgent, testInterceptor,
             null, CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.RAISE);
    }

    /**
     * Full constructor, adding the circuit-breaker state cache (spec §4.4).
     *
     * <p>{@code cbCacheTtl} turns the cache on; {@code null} or a
     * non-positive duration leaves it off, which is the default.
     *
     * <p>Read the TTL as <b>the longest a newly-opened breaker can go
     * unnoticed by this client</b>. A cached {@code closed} is an allow
     * the server might no longer give, so the number is a risk you are
     * choosing. Every cached result carries {@link CheckResult#cached()}
     * and {@link CheckResult#cacheAge()} so a caller can see what it read.
     *
     * @param cbCacheTtl        cache lifetime; {@code null} or {@code ZERO} is off.
     * @param cbCacheMaxEntries bound on the cache; least-recently-used evicted.
     * @param cbCacheOnError    what a failed check does — see {@link CbCacheOnError}.
     */
    public DMZAgentClient(
        String         apiKey,
        String         baseUrl,
        Duration       timeout,
        String         userAgent,
        Interceptor    testInterceptor,
        Duration       cbCacheTtl,
        int            cbCacheMaxEntries,
        CbCacheOnError cbCacheOnError
    ) {
        if (apiKey == null || apiKey.isEmpty() || !apiKey.startsWith("ck_")) {
            throw new IllegalArgumentException(
                "apiKey must start with 'ck_' — get one from your tenant_admin");
        }
        CbCacheOnError onError =
            (cbCacheOnError != null) ? cbCacheOnError : CbCacheOnError.RAISE;
        boolean ttlSet = cbCacheTtl != null
            && !cbCacheTtl.isNegative() && !cbCacheTtl.isZero();
        if (onError == CbCacheOnError.LAST_KNOWN && !ttlSet) {
            // There is nothing to fall back TO until the caller has opted
            // into the cache. Accepting this pair would leave someone
            // believing they had an outage story that can never fire.
            throw new IllegalArgumentException(
                "cbCacheOnError LAST_KNOWN needs a cbCacheTtl above zero");
        }
        this.cbCache        = new CBStateCache(cbCacheTtl, cbCacheMaxEntries);
        this.cbCacheOnError = onError;
        this.apiKey    = apiKey;
        this.baseUrl   = stripTrailingSlash(
            baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        this.userAgent = userAgent != null ? userAgent : DEFAULT_UA;

        Duration t = (timeout != null) ? timeout : DEFAULT_TIMEOUT;
        OkHttpClient.Builder b = new OkHttpClient.Builder()
            .connectTimeout(t.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout   (t.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout  (t.toMillis(), TimeUnit.MILLISECONDS)
            .callTimeout   (t.toMillis(), TimeUnit.MILLISECONDS);
        if (testInterceptor != null) {
            b.addInterceptor(testInterceptor);
        }
        this.http   = b.build();
        this.mapper = new ObjectMapper()
            // Stable key ordering on the wire — matters for the
            // golden-envelope contract test, which compares the request
            // body after JSON normalization.
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/"))
            ? s.substring(0, s.length() - 1)
            : s;
    }

    // ===================================================================== //
    // Event emission — /v1/agent-stream/event
    // ===================================================================== //

    /**
     * Low-level event emitter — every higher-level helper delegates
     * here. Validates {@code kind} against {@link EventKinds#ALL};
     * throws {@link IllegalArgumentException} for anything else.
     *
     * <p>Parameters mirror the wire body documented in spec §2.1.
     * {@code null} optional parameters are simply omitted from the
     * outgoing JSON.
     */
    /**
     * @deprecated Substitutes {@code subject_type = "sensor"}, which the
     * caller did not choose. Spec §5.2–§5.5 make {@code subject_type}
     * REQUIRED, and it is not cosmetic: traces are grouped by the subject's
     * type (§C.1), so a chat utterance recorded as a sensor reading is
     * routed into the wrong trace and reasoned under the wrong pattern.
     *
     * <p>Use the overload that takes {@code subjectType} explicitly. This
     * one is kept only so existing callers still compile, and will be
     * removed in the next MAJOR.
     */
    @Deprecated(since = "0.8.0", forRemoval = true)
    public EmitResult emitEvent(
        String              kind,
        String              agentSubjectId,
        Map<String, Object> payload,
        String              interactionId,
        String              interactionKind,
        List<Map<String, Object>> subjects,
        String              speakerSubjectId,
        String              speakerRole,
        String              occurredAt,
        Map<String, Object> metadata
    ) {
        return emitEvent(kind, "sensor", agentSubjectId, payload,
            interactionId, interactionKind, subjects,
            speakerSubjectId, speakerRole, occurredAt, metadata);
    }

    public EmitResult emitEvent(
        String              kind,
        String              subjectType,
        String              agentSubjectId,
        Map<String, Object> payload,
        String              interactionId,
        String              interactionKind,
        List<Map<String, Object>> subjects,
        String              speakerSubjectId,
        String              speakerRole,
        String              occurredAt,
        Map<String, Object> metadata
    ) {
        return emitEvent(kind, subjectType, agentSubjectId, payload, interactionId,
                         interactionKind, subjects, speakerSubjectId, speakerRole,
                         occurredAt, metadata, null);
    }

    /**
     * As {@link #emitEvent(String, String, String, Map, String, String, List,
     * String, String, String, Map)}, with a caller-generated
     * {@code Idempotency-Key} (spec §1.8).
     *
     * <p>Overload rather than an extra parameter on the existing method so
     * callers compiled against 0.7.0 keep working.
     *
     * @param idempotencyKey the key, or {@code null} for none. Supply your
     *        own: the SDK never invents one, because a key minted per call
     *        deduplicates nothing and a key derived from the payload would
     *        collapse two genuinely distinct but identical events.
     */
    public EmitResult emitEvent(
        String              kind,
        String              subjectType,
        String              agentSubjectId,
        Map<String, Object> payload,
        String              interactionId,
        String              interactionKind,
        List<Map<String, Object>> subjects,
        String              speakerSubjectId,
        String              speakerRole,
        String              occurredAt,
        Map<String, Object> metadata,
        String              idempotencyKey
    ) {
        if (!EventKinds.ALL.contains(kind)) {
            throw new IllegalArgumentException(
                "kind must be one of " + EventKinds.ALL + ", got '" + kind + "'");
        }
        if (subjectType == null || !VALID_SUBJECT_TYPES.contains(subjectType)) {
            throw new IllegalArgumentException(
                "subjectType must be one of [chat, sensor, lead, ticket, journey], got '" + subjectType + "'");
        }
        if (agentSubjectId == null || agentSubjectId.isEmpty()) {
            throw new IllegalArgumentException("agentSubjectId is required");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind",             kind);
        body.put("subject_type",     subjectType);
        body.put("agent_subject_id", agentSubjectId);
        body.put("payload",          payload != null ? payload : Map.of());
        if (interactionId    != null && !interactionId.isEmpty())    body.put("interaction_id",     interactionId);
        body.put("interaction_kind", interactionKind != null ? interactionKind : "chat_session");
        if (subjects         != null && !subjects.isEmpty())         body.put("subjects",            subjects);
        if (speakerSubjectId != null && !speakerSubjectId.isEmpty()) body.put("speaker_subject_id", speakerSubjectId);
        if (speakerRole      != null && !speakerRole.isEmpty())      body.put("speaker_role",       speakerRole);
        if (occurredAt       != null && !occurredAt.isEmpty())       body.put("occurred_at",        occurredAt);
        if (metadata         != null && !metadata.isEmpty())         body.put("metadata",           metadata);

        Map<String, Object> data = postJson("/v1/agent-stream/event", body, idempotencyKey);
        return EmitResult.fromResponse(data);
    }

    /**
     * A subject in the conversation spoke. Per spec §5.2 the SDK
     * passes {@code speaker_subject_id = subjectId} on the wire.
     *
     * @param subjectId        the speaker — could be agent, human, sensor.
     * @param text             utterance text.
     * @param agentSubjectId   conversation anchor (required by wire protocol).
     */
    public EmitResult subjectSays(String subjectId, String text, String agentSubjectId) {
        return subjectSays(subjectId, text, agentSubjectId, null, null, null);
    }

    /** Full {@code subjectSays} with optional interaction stitching,
     * subject roster, and per-event payload extras. */
    /**
     * @deprecated Substitutes {@code subject_type = "sensor"}, which the
     * caller did not choose. Spec §5.2–§5.5 make {@code subject_type}
     * REQUIRED, and it is not cosmetic: traces are grouped by the subject's
     * type (§C.1), so a chat utterance recorded as a sensor reading is
     * routed into the wrong trace and reasoned under the wrong pattern.
     *
     * <p>Use the overload that takes {@code subjectType} explicitly. This
     * one is kept only so existing callers still compile, and will be
     * removed in the next MAJOR.
     */
    @Deprecated(since = "0.8.0", forRemoval = true)
    public EmitResult subjectSays(
        String                    subjectId,
        String                    text,
        String                    agentSubjectId,
        String                    interactionId,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payloadExtra
    ) {
        return subjectSays(subjectId, text, agentSubjectId, "sensor",
            interactionId, subjects, payloadExtra);
    }

    public EmitResult subjectSays(
        String                    subjectId,
        String                    text,
        String                    agentSubjectId,
        String                    subjectType,
        String                    interactionId,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payloadExtra
    ) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(text, "text");
        if (agentSubjectId == null || agentSubjectId.isEmpty()) {
            throw new IllegalArgumentException(
                "agentSubjectId is required — every event grounds against "
                + "an agent identity (use the Conversation helper to avoid "
                + "passing this on every call)");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        if (payloadExtra != null) payload.putAll(payloadExtra);

        return emitEvent(
            EventKinds.SUBJECT_SAYS,
            subjectType,
            agentSubjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,
            null,
            null, null
        );
    }

    /** The agent invoked a tool. Use BEFORE the tool runs — emit the
     * intent. The result lands separately via {@link #toolResult}. */
    public EmitResult toolCall(String subjectId, String tool, Map<String, Object> args) {
        return toolCall(subjectId, tool, args, null, null);
    }

    /** Full {@code toolCall} with interaction stitching and roster. */
    /**
     * @deprecated Substitutes {@code subject_type = "sensor"}, which the
     * caller did not choose. Spec §5.2–§5.5 make {@code subject_type}
     * REQUIRED, and it is not cosmetic: traces are grouped by the subject's
     * type (§C.1), so a chat utterance recorded as a sensor reading is
     * routed into the wrong trace and reasoned under the wrong pattern.
     *
     * <p>Use the overload that takes {@code subjectType} explicitly. This
     * one is kept only so existing callers still compile, and will be
     * removed in the next MAJOR.
     */
    @Deprecated(since = "0.8.0", forRemoval = true)
    public EmitResult toolCall(
        String                    subjectId,
        String                    tool,
        Map<String, Object>       args,
        String                    interactionId,
        List<Map<String, Object>> subjects
    ) {
        return toolCall(subjectId, tool, args, "sensor", interactionId, subjects);
    }

    public EmitResult toolCall(
        String                    subjectId,
        String                    tool,
        Map<String, Object>       args,
        String                    subjectType,
        String                    interactionId,
        List<Map<String, Object>> subjects
    ) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tool, "tool");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("args", args != null ? args : Map.of());

        return emitEvent(
            EventKinds.TOOL_CALL,
            subjectType,
            subjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,
            "agent",
            null, null
        );
    }

    /** A tool returned a result. Pair with the prior {@link #toolCall}. */
    public EmitResult toolResult(String subjectId, String tool, Object result) {
        return toolResult(subjectId, tool, result, null, null);
    }

    /** Full {@code toolResult} with interaction stitching and roster. */
    /**
     * @deprecated Substitutes {@code subject_type = "sensor"}, which the
     * caller did not choose. Spec §5.2–§5.5 make {@code subject_type}
     * REQUIRED, and it is not cosmetic: traces are grouped by the subject's
     * type (§C.1), so a chat utterance recorded as a sensor reading is
     * routed into the wrong trace and reasoned under the wrong pattern.
     *
     * <p>Use the overload that takes {@code subjectType} explicitly. This
     * one is kept only so existing callers still compile, and will be
     * removed in the next MAJOR.
     */
    @Deprecated(since = "0.8.0", forRemoval = true)
    public EmitResult toolResult(
        String                    subjectId,
        String                    tool,
        Object                    result,
        String                    interactionId,
        List<Map<String, Object>> subjects
    ) {
        return toolResult(subjectId, tool, result, "sensor", interactionId, subjects);
    }

    public EmitResult toolResult(
        String                    subjectId,
        String                    tool,
        Object                    result,
        String                    subjectType,
        String                    interactionId,
        List<Map<String, Object>> subjects
    ) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tool, "tool");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool",   tool);
        payload.put("result", result);

        return emitEvent(
            EventKinds.TOOL_RESULT,
            subjectType,
            subjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,
            null,
            null, null
        );
    }

    /** Structured observation — video keyframes, IoT events, anything
     * not utterance-shaped. */
    public EmitResult observation(
        String                    agentSubjectId,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payload
    ) {
        return observation(agentSubjectId, subjects, payload, null);
    }

    /** Full {@code observation} with interaction stitching. */
    /**
     * @deprecated Substitutes {@code subject_type = "sensor"}, which the
     * caller did not choose. Spec §5.2–§5.5 make {@code subject_type}
     * REQUIRED, and it is not cosmetic: traces are grouped by the subject's
     * type (§C.1), so a chat utterance recorded as a sensor reading is
     * routed into the wrong trace and reasoned under the wrong pattern.
     *
     * <p>Use the overload that takes {@code subjectType} explicitly. This
     * one is kept only so existing callers still compile, and will be
     * removed in the next MAJOR.
     */
    @Deprecated(since = "0.8.0", forRemoval = true)
    public EmitResult observation(
        String                    agentSubjectId,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payload,
        String                    interactionId
    ) {
        return observation(agentSubjectId, "sensor", subjects, payload, interactionId);
    }

    public EmitResult observation(
        String                    agentSubjectId,
        String                    subjectType,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payload,
        String                    interactionId
    ) {
        Objects.requireNonNull(agentSubjectId, "agentSubjectId");
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(payload, "payload");

        return emitEvent(
            EventKinds.OBSERVATION,
            subjectType,
            agentSubjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            null, null, null, null
        );
    }

    // ===================================================================== //
    // Circuit breaker — /v1/cb/check
    // ===================================================================== //

    /**
     * Synchronous CB check. Pass EXACTLY ONE of {@code subjectId} or
     * {@code interactionId}; both null or both set throws
     * {@link IllegalArgumentException}.
     *
     * <p>Per spec §5.6 — the SDK derives {@code scope} ({@code subject}
     * vs {@code interaction}) and {@code scope_ref} from which arg
     * was supplied.
     */
    public CheckResult check(String subjectId, String interactionId) {
        return check(subjectId, interactionId, false);
    }

    /**
     * As {@link #check(String, String)}, with {@code fresh = true}
     * bypassing the state cache (spec §4.4) and refreshing it. With the
     * cache off — the default — {@code fresh} does nothing.
     */
    public CheckResult check(String subjectId, String interactionId, boolean fresh) {
        boolean hasSubject     = subjectId != null && !subjectId.isEmpty();
        boolean hasInteraction = interactionId != null && !interactionId.isEmpty();
        if (hasSubject == hasInteraction) {
            throw new IllegalArgumentException(
                "pass exactly one of subjectId or interactionId");
        }
        String scope    = hasSubject ? "subject" : "interaction";
        String scopeRef = hasSubject ? subjectId : interactionId;
        String cacheKey = CBStateCache.key(scope, scopeRef);

        if (!fresh) {
            CBStateCache.Hit hit = cbCache.get(cacheKey);
            if (hit != null) {
                return hit.result().asCached(hit.age(), false);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope",     scope);
        body.put("scope_ref", scopeRef);

        Map<String, Object> data;
        try {
            data = postJson("/v1/cb/check", body);
        } catch (DMZAgentServerException e) {
            // Network, timeout, or 5xx — the server could not answer.
            // Deliberately NOT the rate-limit exception: a 429 is an
            // answer, and it carries a retryAfter the caller can act on.
            // Hiding it behind a cached state would drop that signal.
            if (cbCacheOnError == CbCacheOnError.LAST_KNOWN) {
                CBStateCache.Hit fallback = cbCache.getAny(cacheKey);
                if (fallback != null) {
                    return fallback.result().asCached(fallback.age(), true);
                }
            }
            throw e;
        }

        CheckResult result = CheckResult.fromResponse(data);
        cbCache.put(cacheKey, result);
        return result;
    }

    /** Convenience: check by subject only. */
    public CheckResult check(String subjectId) {
        return check(subjectId, null, false);
    }

    // ===================================================================== //
    // Guard — context-managed CB check
    // ===================================================================== //

    /**
     * Open a {@link Guard} for try-with-resources use:
     *
     * <pre>{@code
     *   try (var g = cx.guard("user:ws:bot")) {
     *       if (!g.getResult().allow()) return refuse(g.getResult().reason());
     *   }
     * }</pre>
     *
     * <p>This 1-arg overload is equivalent to
     * {@code guard(subjectId, null, false)}.
     */
    public Guard guard(String subjectId) {
        return guard(subjectId, null, false);
    }

    /**
     * Open a {@link Guard}. When {@code raiseOnOpen=true} and the
     * result is {@code open}, throws {@link CircuitBreakerOpenException}
     * BEFORE returning — the try-with-resources body never runs.
     *
     * @param subjectId     pass EXACTLY ONE of {@code subjectId} or
     *                      {@code interactionId}.
     * @param interactionId pass EXACTLY ONE of {@code subjectId} or
     *                      {@code interactionId}.
     * @param raiseOnOpen   if {@code true}, throw {@link CircuitBreakerOpenException}
     *                      when {@code result.allow() == false}.
     */
    public Guard guard(String subjectId, String interactionId, boolean raiseOnOpen) {
        return guard(subjectId, interactionId, raiseOnOpen, false);
    }

    /**
     * As {@link #guard(String, String, boolean)}, with {@code fresh}
     * passed through to {@link #check(String, String, boolean)}.
     */
    public Guard guard(
            String subjectId, String interactionId,
            boolean raiseOnOpen, boolean fresh) {
        CheckResult r = check(subjectId, interactionId, fresh);
        if (raiseOnOpen && !r.allow()) {
            String scopeRef =
                (subjectId     != null && !subjectId.isEmpty())     ? subjectId     :
                (interactionId != null && !interactionId.isEmpty()) ? interactionId :
                "";
            throw new CircuitBreakerOpenException(
                "circuit breaker open: " + r.reason(),
                r.reason(),
                r.firedPolicies(),
                r.anchor(),
                scopeRef
            );
        }
        return new Guard(r);
    }

    // ===================================================================== //
    // Conversation factory
    // ===================================================================== //

    /**
     * Open a {@link Conversation} handle bound to this client. The
     * Conversation tracks the subject roster and stitches every
     * subsequent event into the same {@code interaction_id} returned
     * by the first emit call.
     *
     * <p>Per spec §5.8: {@code participants} MUST be non-empty.
     * {@code agentSubjectId} is derived from participants when
     * omitted (first participant whose role is in
     * {@code {agent, service, system}} wins; otherwise the first
     * participant).
     */
    public Conversation conversation(
        List<Map<String, Object>> participants,
        String                    agentSubjectId,
        String                    kind,
        Map<String, Object>       metadata
    ) {
        return new Conversation(this, participants, agentSubjectId,
            kind != null ? kind : "chat_session", metadata);
    }

    /** Minimal {@link #conversation} overload — participants only. */
    public Conversation conversation(List<Map<String, Object>> participants) {
        return conversation(participants, null, null, null);
    }

    // ===================================================================== //
    // Capture — /v1/agent-stream/event (unvalidated)
    // ===================================================================== //

    public CaptureResult capture(
        String              subjectId,
        String              kind,
        String              subjectType,
        Map<String, Object> payload,
        String              agentSubjectId,
        String              interactionId,
        String              interactionKind,
        List<Map<String, Object>> subjects,
        String              speakerSubjectId,
        String              speakerRole,
        String              occurredAt,
        Map<String, Object> metadata
    ) {
        return capture(subjectId, kind, subjectType, payload, agentSubjectId,
                       interactionId, interactionKind, subjects, speakerSubjectId,
                       speakerRole, occurredAt, metadata, null);
    }

    /**
     * As the 12-argument overload, with a caller-generated
     * {@code Idempotency-Key} (spec §1.8). See
     * {@link #emitEvent(String, String, String, Map, String, String, List,
     * String, String, String, Map, String)} for why the key is never
     * generated by the SDK.
     */
    public CaptureResult capture(
        String              subjectId,
        String              kind,
        String              subjectType,
        Map<String, Object> payload,
        String              agentSubjectId,
        String              interactionId,
        String              interactionKind,
        List<Map<String, Object>> subjects,
        String              speakerSubjectId,
        String              speakerRole,
        String              occurredAt,
        Map<String, Object> metadata,
        String              idempotencyKey
    ) {
        if (kind == null || !EventKinds.ALL.contains(kind)) {
            throw new IllegalArgumentException(
                "kind must be one of " + EventKinds.ALL + ", got '" + kind + "'");
        }
        if (subjectType == null || !VALID_SUBJECT_TYPES.contains(subjectType)) {
            throw new IllegalArgumentException(
                "subjectType must be one of [chat, sensor, lead, ticket, journey], got '" + subjectType + "'");
        }
        if (subjectId == null || subjectId.isEmpty()) {
            throw new IllegalArgumentException("subjectId is required");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("subject_id", subjectId);
        body.put("subject_type", subjectType);
        body.put("payload", payload != null ? payload : Map.of());
        if (agentSubjectId != null && !agentSubjectId.isEmpty()) body.put("agent_subject_id", agentSubjectId);
        if (interactionId != null && !interactionId.isEmpty()) body.put("interaction_id", interactionId);
        body.put("interaction_kind", interactionKind != null ? interactionKind : "chat_session");
        if (subjects != null && !subjects.isEmpty()) body.put("subjects", subjects);
        if (speakerSubjectId != null && !speakerSubjectId.isEmpty()) body.put("speaker_subject_id", speakerSubjectId);
        if (speakerRole != null && !speakerRole.isEmpty()) body.put("speaker_role", speakerRole);
        if (occurredAt != null && !occurredAt.isEmpty()) body.put("occurred_at", occurredAt);
        if (metadata != null && !metadata.isEmpty()) body.put("metadata", metadata);

        Map<String, Object> data = postJson("/v1/agent-stream/event", body, idempotencyKey);
        return CaptureResult.fromResponse(data);
    }

    // ===================================================================== //
    // Await outcome — /v1/frames/{id}/story
    // ===================================================================== //

    public OutcomeResult awaitOutcome(String frameId) {
        return awaitOutcome(frameId, 30.0);
    }

    /**
     * Poll the frame story endpoint until reasoning completes
     * (sdk-spec.md §5.10).
     *
     * <p>Terminates on {@code summary.complete} — every workspace the frame
     * fanned out to has reported, matching the {@code n_workspaces} on the
     * ingest ack. This previously returned the first response that parsed,
     * which is a half-finished story: the endpoint answers 200 all the way
     * through the fan-out, handing back traces as each workspace finishes.
     *
     * <p>No {@code workspace_id} is sent. The story endpoint is
     * division-scoped; naming a workspace narrows the result to 1 of N
     * perspectives and makes completeness mean "that workspace finished".
     */
    public OutcomeResult awaitOutcome(String frameId, double timeoutSeconds) {
        timeoutSeconds = Math.min(timeoutSeconds, 120.0);
        long start = System.currentTimeMillis();
        long end = start + (long)(timeoutSeconds * 1000);
        long delay = 100;
        Exception lastError = null;
        String path = "/v1/frames/" + URLEncoder.encode(frameId, StandardCharsets.UTF_8) + "/story";

        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DMZAgentServerException("awaitOutcome interrupted", null, null, e);
            }
            try {
                Map<String, Object> data = getJson(path);
                OutcomeResult result = OutcomeResult.fromResponse(data);
                if (result.complete()) return result;
            } catch (DMZAgentValidationException | DMZAgentAuthException | DMZAgentPermissionException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
            }
            delay = Math.min(delay * 2, 2000);
        }
        throw new DMZAgentServerException(
            "await_outcome timed out after " + timeoutSeconds + "s for frame " + frameId,
            null, lastError != null ? lastError.getMessage() : null, lastError);
    }

    // ===================================================================== //
    // Notification prefs — /v1/settings/notifications
    // ===================================================================== //

    public NotificationPrefs getNotificationPrefs() {
        Map<String, Object> data = getJson("/v1/settings/notifications");
        return NotificationPrefs.fromResponse(data);
    }

    public NotificationPrefs updateNotificationPrefs(Map<String, Object> prefs) {
        Map<String, Object> data = putJson("/v1/settings/notifications", prefs);
        return NotificationPrefs.fromResponse(data);
    }

    // ===================================================================== //
    // Division config — /v1/divisions/{id}/config
    // ===================================================================== //

    public DivisionConfig getDivisionConfig(String divisionId) {
        String path = "/v1/divisions/" + URLEncoder.encode(divisionId, StandardCharsets.UTF_8) + "/config";
        Map<String, Object> data = getJson(path);
        return DivisionConfig.fromResponse(data);
    }

    public DivisionConfig updateDivisionConfig(String divisionId, Map<String, Object> config) {
        String path = "/v1/divisions/" + URLEncoder.encode(divisionId, StandardCharsets.UTF_8) + "/config";
        Map<String, Object> data = putJson(path, config);
        return DivisionConfig.fromResponse(data);
    }

    // ===================================================================== //
    // Human-in-the-loop approvals (spec §2.8–§2.9, §5.16–§5.18)
    // ===================================================================== //

    /**
     * One page of approvals awaiting a human decision.
     *
     * <p>This is the read half of the white-label control: you render these
     * in your own product, with your own words. Nothing in an
     * {@link Approval} is display text we wrote.
     *
     * <pre>{@code
     * ApprovalPage page = cx.listApprovals("pending", "subject:dv:bot", null, null);
     * for (Approval a : page) renderMyOwnCard(a.action(), a.reason());
     * }</pre>
     *
     * <p>Does not follow {@code nextCursor}. A caller who asked for 25 got
     * 25, and a method that quietly walked every page would turn one bounded
     * request into an unbounded one against a record that only grows. Use
     * {@link #iterApprovals} when you want the walk.
     *
     * @param status    {@code pending} (default when null) | {@code approved}
     *                  | {@code declined} | {@code expired}
     * @param subjectId restrict to one subject, or null
     * @param limit     1–100, or null for the server's 25
     * @param cursor    from a previous page's {@code nextCursor}, or null
     */
    public ApprovalPage listApprovals(
        String status, String subjectId, Integer limit, String cursor
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("status", status != null ? status : "pending");
        if (subjectId != null) params.put("subject_id", subjectId);
        if (limit != null) {
            requirePageLimit(limit);
            params.put("limit", String.valueOf(limit));
        }
        if (cursor != null) params.put("cursor", cursor);
        return ApprovalPage.fromResponse(getJson("/v1/approvals" + query(params)));
    }

    /** {@code listApprovals("pending", null, null, null)}. */
    public ApprovalPage listApprovals() {
        return listApprovals(null, null, null, null);
    }

    /**
     * Lazily walk every page of {@link #listApprovals}.
     *
     * <p>Fetches a page only when the consumer asks for an item past the
     * ones it holds. A short-circuiting terminal operation — {@code findFirst},
     * {@code limit} — never requests the next page, which is the whole reason
     * this is a {@link Stream} and not a {@link List}.
     */
    public Stream<Approval> iterApprovals(
        String status, String subjectId, Integer limit
    ) {
        Spliterator<Approval> pages = new Spliterators.AbstractSpliterator<>(
            Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL
        ) {
            private Iterator<Approval> current = null;
            private String cursor = null;
            private boolean exhausted = false;

            @Override
            public boolean tryAdvance(Consumer<? super Approval> action) {
                while (current == null || !current.hasNext()) {
                    if (exhausted) return false;
                    ApprovalPage page =
                        listApprovals(status, subjectId, limit, cursor);
                    cursor = page.nextCursor();
                    if (cursor == null || cursor.isEmpty()) exhausted = true;
                    current = page.approvals().iterator();
                    // An empty page with a cursor is legal; loop rather than
                    // reporting the walk finished.
                    if (!current.hasNext() && exhausted) return false;
                }
                action.accept(current.next());
                return true;
            }
        };
        return StreamSupport.stream(pages, false);
    }

    /** {@code iterApprovals("pending", null, null)}. */
    public Stream<Approval> iterApprovals() {
        return iterApprovals(null, null, null);
    }

    /**
     * Approve or decline a held action, on behalf of a named human.
     *
     * <p>{@code actorId} is required and is <em>your</em> identifier for the
     * person who decided. It is never defaulted and never derived from the
     * API key: the key identifies your integration, and an approval whose
     * actor is the integration that requested it has recorded nobody. We
     * resolve it against no directory, so your users never need an account
     * here.
     *
     * <pre>{@code
     * cx.decideApproval("apr_7f3c9a1b", "approve", "acct_4471",
     *                   "Dana R.", "verified the order by phone");
     * }</pre>
     *
     * @throws IllegalArgumentException locally — with no round trip — when
     *         {@code actorId} is blank or {@code decision} is not
     *         approve/decline, because a caller who has not got a human's
     *         identity at this point does not have a human, and the failure
     *         belongs where the mistake is.
     * @throws com.dmzagent.sdk.exceptions.DMZAgentConflictException when the
     *         approval was already decided or has expired. That is not a
     *         transient fault to retry: someone else decided, or the window
     *         closed.
     */
    public Approval decideApproval(
        String approvalId, String decision, String actorId,
        String actorLabel, String reason
    ) {
        if (!"approve".equals(decision) && !"decline".equals(decision)) {
            throw new IllegalArgumentException(
                "decision must be approve or decline, got " + decision);
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException(
                "actorId is required: a human-in-the-loop decision has to "
                + "record which human made it");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("actor_id", actorId);
        if (actorLabel != null) body.put("actor_label", actorLabel);
        if (reason != null) body.put("reason", reason);

        String path = "/v1/approvals/"
            + URLEncoder.encode(approvalId, StandardCharsets.UTF_8)
            + "/decision";
        return Approval.fromResponse(postJson(path, body));
    }

    /** {@code decideApproval(id, "approve", ...)}. {@code actorId} stays required. */
    public Approval approveApproval(
        String approvalId, String actorId, String actorLabel, String reason
    ) {
        return decideApproval(approvalId, "approve", actorId, actorLabel, reason);
    }

    /** {@code approveApproval(id, actorId, null, null)}. */
    public Approval approveApproval(String approvalId, String actorId) {
        return approveApproval(approvalId, actorId, null, null);
    }

    /** {@code decideApproval(id, "decline", ...)}. {@code actorId} stays required. */
    public Approval declineApproval(
        String approvalId, String actorId, String actorLabel, String reason
    ) {
        return decideApproval(approvalId, "decline", actorId, actorLabel, reason);
    }

    /** {@code declineApproval(id, actorId, null, null)}. */
    public Approval declineApproval(String approvalId, String actorId) {
        return declineApproval(approvalId, actorId, null, null);
    }

    // ===================================================================== //
    // The incident and remediation ledger (spec §2.10, §5.19–§5.21)
    // ===================================================================== //

    /**
     * One page of the incident and remediation ledger.
     *
     * <p>Every breaker that opened, every approval decided, every
     * remediation that ran — newest ledger entry first. This is the readable
     * form of the {@code anchor} that {@link #check} hands back: record it at
     * check time, find that {@code ledger_index} here, and compare hashes. A
     * mismatch is the alarm the ledger exists for.
     *
     * <p>{@code since} and {@code until} are ISO-8601 strings; the window is
     * half-open, {@code since} inclusive and {@code until} exclusive.
     *
     * <p>Does not follow {@code nextCursor} — see {@link #iterIncidents}.
     */
    public IncidentPage getIncidents(
        String status, String subjectId, String since, String until,
        Integer limit, String cursor
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("status", status != null ? status : "all");
        if (subjectId != null) params.put("subject_id", subjectId);
        if (since != null) params.put("since", since);
        if (until != null) params.put("until", until);
        if (limit != null) {
            requirePageLimit(limit);
            params.put("limit", String.valueOf(limit));
        }
        if (cursor != null) params.put("cursor", cursor);
        return IncidentPage.fromResponse(getJson("/v1/incidents" + query(params)));
    }

    /** {@code getIncidents("all", null, null, null, null, null)}. */
    public IncidentPage getIncidents() {
        return getIncidents(null, null, null, null, null, null);
    }

    /** Lazily walk every page of {@link #getIncidents}, on §5.17's terms. */
    public Stream<Incident> iterIncidents(
        String status, String subjectId, String since, String until, Integer limit
    ) {
        Spliterator<Incident> pages = new Spliterators.AbstractSpliterator<>(
            Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL
        ) {
            private Iterator<Incident> current = null;
            private String cursor = null;
            private boolean exhausted = false;

            @Override
            public boolean tryAdvance(Consumer<? super Incident> action) {
                while (current == null || !current.hasNext()) {
                    if (exhausted) return false;
                    IncidentPage page = getIncidents(
                        status, subjectId, since, until, limit, cursor);
                    cursor = page.nextCursor();
                    if (cursor == null || cursor.isEmpty()) exhausted = true;
                    current = page.incidents().iterator();
                    if (!current.hasNext() && exhausted) return false;
                }
                action.accept(current.next());
                return true;
            }
        };
        return StreamSupport.stream(pages, false);
    }

    /** {@code iterIncidents("all", null, null, null, null)}. */
    public Stream<Incident> iterIncidents() {
        return iterIncidents(null, null, null, null, null);
    }

    // There is deliberately no closeIncident() / resolveIncident(). The
    // ledger is append-only and has no endpoint for one: an incident reaches
    // "remediated" because a remediation was appended to it, and a
    // convenience method that read as closing one would describe a ledger
    // this is not (spec §5.21).

    /**
     * The approval status carried by a 409 body, or null.
     *
     * <p>How a caller tells the two 409s apart (spec §3): a settled approval
     * says what it had already become; an idempotency conflict says nothing.
     */
    @SuppressWarnings("unchecked")
    private static String settledApprovalStatus(Object body) {
        if (body instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("status");
            if (v instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    /** Reject a page size the server would reject, before the round trip. */
    private static void requirePageLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                "limit must be an integer in 1..100, got " + limit);
        }
    }

    /** Build a query string from already-decoded values. */
    private static String query(Map<String, String> params) {
        if (params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ===================================================================== //
    // Resource lifecycle
    // ===================================================================== //

    /** Release the underlying OkHttp dispatcher + connection pool.
     * Idempotent per spec §4.3. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    // ===================================================================== //
    // Internal — HTTP plumbing
    // ===================================================================== //

    private Map<String, Object> postJson(String path, Map<String, Object> body) {
        return postJson(path, body, null);
    }

    /**
     * @param idempotencyKey caller-generated key making a retry of this
     *        request safe (spec §1.8), or {@code null} for none. The SDK
     *        never generates one: a key minted per call is unique per call
     *        and deduplicates nothing, and a key derived from the payload
     *        would collapse two genuinely distinct but identical events.
     */
    private Map<String, Object> postJson(
        String path, Map<String, Object> body, String idempotencyKey
    ) {
        String url = baseUrl + path;
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            // Should never happen — every value the SDK puts in the
            // map is JSON-encodable by construction. If a customer
            // hands us a non-serializable payload extra, surface it.
            throw new IllegalArgumentException(
                "could not serialize request body to JSON: " + e.getMessage(), e);
        }

        Request.Builder builder = new Request.Builder()
            .url(url)
            .post(RequestBody.create(json, JSON_MEDIA))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type",  "application/json")
            .header("User-Agent",    userAgent);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        Request req = builder.build();

        try (Response resp = http.newCall(req).execute()) {
            return handle(resp, path);
        } catch (SocketTimeoutException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (InterruptedIOException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (IOException e) {
            throw new DMZAgentServerException(
                "network error calling " + path + ": " + e.getMessage(),
                null, null, e);
        }
    }

    private Map<String, Object> getJson(String path) {
        String url = baseUrl + path;
        Request req = new Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return handle(resp, path);
        } catch (SocketTimeoutException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (InterruptedIOException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (IOException e) {
            throw new DMZAgentServerException(
                "network error calling " + path + ": " + e.getMessage(),
                null, null, e);
        }
    }

    private Map<String, Object> putJson(String path, Map<String, Object> body) {
        String url = baseUrl + path;
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "could not serialize request body to JSON: " + e.getMessage(), e);
        }

        Request req = new Request.Builder()
            .url(url)
            .put(RequestBody.create(json, JSON_MEDIA))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return handle(resp, path);
        } catch (SocketTimeoutException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (InterruptedIOException e) {
            throw new DMZAgentServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (IOException e) {
            throw new DMZAgentServerException(
                "network error calling " + path + ": " + e.getMessage(),
                null, null, e);
        }
    }

    /**
     * Seconds from a {@code Retry-After} header, or {@code null}.
     *
     * <p>Only the delta-seconds form is understood. RFC 9110 also permits an
     * HTTP-date, and a caller handed a wrong number is worse off than one
     * handed {@code null}, so anything non-numeric returns {@code null} rather
     * than guessing. The corpus carries a vector for the header-absent case,
     * which lands here too.
     */
    private static Integer parseRetryAfter(String raw) {
        if (raw == null) return null;
        try {
            int seconds = Integer.parseInt(raw.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> handle(Response resp, String path) throws IOException {
        int status = resp.code();
        ResponseBody rb = resp.body();
        String text = (rb != null) ? rb.string() : "";

        if (status >= 200 && status < 300) {
            if (text.isEmpty()) return Map.of();
            try {
                return mapper.readValue(text, MAP_TYPE);
            } catch (IOException e) {
                // 2xx with a non-JSON body — surface an empty map
                // rather than crash; callers can still see status==2xx
                // and assume success.
                LOG.warn("non-JSON 2xx body from {}: {}", path, e.getMessage());
                return Map.of();
            }
        }

        Object body = text;
        try {
            body = mapper.readValue(text, MAP_TYPE);
        } catch (IOException ignored) {
            // not JSON — body stays as raw string
        }

        switch (status) {
            // 400 and 422 both mean "fix the request" — malformed vs
            // parsed-but-rejected. The spec taxonomy maps both here;
            // statusCode tells them apart for callers that care.
            case 400, 422 -> throw new DMZAgentValidationException(
                "server rejected request to " + path + ": " + body,
                status, body);
            // 409 has two causes and one type (spec §3). Either the
            // caller's own earlier request is still in flight under this
            // Idempotency-Key (§1.8), or an approval was already decided or
            // has expired (§2.9). Neither is transient — the call did not
            // fail, it lost — so this stays off the 5xx branch below, and
            // the message names which one it was rather than asserting the
            // older cause on every path.
            case 409 -> throw new DMZAgentConflictException(
                settledApprovalStatus(body) != null
                    ? "approval already " + settledApprovalStatus(body) + " on " + path
                    : "a request with this Idempotency-Key is already in flight on " + path,
                status, body);
            case 429 -> throw new DMZAgentRateLimitException(
                "rate limited on " + path,
                status, body, parseRetryAfter(resp.header("Retry-After")));
            case 401 -> throw new DMZAgentAuthException(
                "invalid or revoked API key",
                status, body);
            case 403 -> throw new DMZAgentPermissionException(
                "API key lacks required scope for this operation",
                status, body);
        }
        if (status >= 500) {
            throw new DMZAgentServerException(
                "server error from " + path + " (" + status + ")",
                status, body);
        }
        throw new DMZAgentException(
            "unexpected status " + status + " from " + path,
            status, body);
    }
}
