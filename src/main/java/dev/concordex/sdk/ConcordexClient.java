package dev.concordex.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.concordex.sdk.exceptions.CircuitBreakerOpenException;
import dev.concordex.sdk.exceptions.ConcordexAuthException;
import dev.concordex.sdk.exceptions.ConcordexException;
import dev.concordex.sdk.exceptions.ConcordexPermissionException;
import dev.concordex.sdk.exceptions.ConcordexServerException;
import dev.concordex.sdk.exceptions.ConcordexValidationException;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous Concordex client — the SDK's main entry point.
 *
 * <p>Per spec §4, the Java class name is {@code ConcordexClient}
 * (not the bare {@code Concordex} that Python and TypeScript use)
 * because Java reserves unqualified type names for value-bearing
 * entities and expects a {@code Client} suffix on HTTP service
 * classes.
 *
 * <p>The client is sync-first. OkHttp's call dispatcher is thread-safe,
 * so a single {@code ConcordexClient} instance can be shared across
 * threads. Most agent runtimes already manage their own thread/executor
 * model; an async overload returning {@code CompletableFuture<...>}
 * may land in a later spec version when customer demand confirms
 * it's worth the surface-area cost.
 *
 * <p>Quick start:
 *
 * <pre>{@code
 *   try (var cx = new ConcordexClient("ck_...")) {
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
public final class ConcordexClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConcordexClient.class);

    private static final String   DEFAULT_BASE_URL = "https://api.concordex.dev";
    private static final Duration DEFAULT_TIMEOUT  = Duration.ofMillis(10_000);
    private static final String   DEFAULT_UA       = "concordex-java/0.5.0";

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String       apiKey;
    private final String       baseUrl;
    private final String       userAgent;
    private boolean closed = false;

    // ===================================================================== //
    // Construction
    // ===================================================================== //

    /** Construct with defaults: api.concordex.dev, 10s timeout, default UA. */
    public ConcordexClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_TIMEOUT, DEFAULT_UA, null);
    }

    /** Builder-style explicit constructor. */
    public ConcordexClient(
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
    public ConcordexClient(
        String      apiKey,
        String      baseUrl,
        Duration    timeout,
        String      userAgent,
        Interceptor testInterceptor
    ) {
        if (apiKey == null || apiKey.isEmpty() || !apiKey.startsWith("ck_")) {
            throw new IllegalArgumentException(
                "apiKey must start with 'ck_' — get one from your tenant_admin");
        }
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
        if (!EventKinds.ALL.contains(kind)) {
            throw new IllegalArgumentException(
                "kind must be one of " + EventKinds.ALL + ", got '" + kind + "'");
        }
        if (agentSubjectId == null || agentSubjectId.isEmpty()) {
            throw new IllegalArgumentException("agentSubjectId is required");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind",             kind);
        body.put("agent_subject_id", agentSubjectId);
        body.put("payload",          payload != null ? payload : Map.of());
        if (interactionId    != null && !interactionId.isEmpty())    body.put("interaction_id",     interactionId);
        body.put("interaction_kind", interactionKind != null ? interactionKind : "chat_session");
        if (subjects         != null && !subjects.isEmpty())         body.put("subjects",            subjects);
        if (speakerSubjectId != null && !speakerSubjectId.isEmpty()) body.put("speaker_subject_id", speakerSubjectId);
        if (speakerRole      != null && !speakerRole.isEmpty())      body.put("speaker_role",       speakerRole);
        if (occurredAt       != null && !occurredAt.isEmpty())       body.put("occurred_at",        occurredAt);
        if (metadata         != null && !metadata.isEmpty())         body.put("metadata",           metadata);

        Map<String, Object> data = postJson("/v1/agent-stream/event", body);
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
    public EmitResult subjectSays(
        String                    subjectId,
        String                    text,
        String                    agentSubjectId,
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
            agentSubjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,         // speaker_subject_id
            null,              // speaker_role
            null, null
        );
    }

    /** The agent invoked a tool. Use BEFORE the tool runs — emit the
     * intent. The result lands separately via {@link #toolResult}. */
    public EmitResult toolCall(String subjectId, String tool, Map<String, Object> args) {
        return toolCall(subjectId, tool, args, null, null);
    }

    /** Full {@code toolCall} with interaction stitching and roster. */
    public EmitResult toolCall(
        String                    subjectId,
        String                    tool,
        Map<String, Object>       args,
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
            subjectId,         // agent_subject_id = the invoker
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,         // speaker_subject_id
            "agent",           // speaker_role per spec §5.3
            null, null
        );
    }

    /** A tool returned a result. Pair with the prior {@link #toolCall}. */
    public EmitResult toolResult(String subjectId, String tool, Object result) {
        return toolResult(subjectId, tool, result, null, null);
    }

    /** Full {@code toolResult} with interaction stitching and roster. */
    public EmitResult toolResult(
        String                    subjectId,
        String                    tool,
        Object                    result,
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
            subjectId,
            payload,
            interactionId,
            "chat_session",
            subjects,
            subjectId,         // speaker_subject_id
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
    public EmitResult observation(
        String                    agentSubjectId,
        List<Map<String, Object>> subjects,
        Map<String, Object>       payload,
        String                    interactionId
    ) {
        Objects.requireNonNull(agentSubjectId, "agentSubjectId");
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(payload, "payload");

        return emitEvent(
            EventKinds.OBSERVATION,
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
        boolean hasSubject     = subjectId != null && !subjectId.isEmpty();
        boolean hasInteraction = interactionId != null && !interactionId.isEmpty();
        if (hasSubject == hasInteraction) {
            throw new IllegalArgumentException(
                "pass exactly one of subjectId or interactionId");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope",     hasSubject ? "subject" : "interaction");
        body.put("scope_ref", hasSubject ? subjectId : interactionId);

        Map<String, Object> data = postJson("/v1/cb/check", body);
        return CheckResult.fromResponse(data);
    }

    /** Convenience: check by subject only. */
    public CheckResult check(String subjectId) {
        return check(subjectId, null);
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
        CheckResult r = check(subjectId, interactionId);
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

        Request req = new Request.Builder()
            .url(url)
            .post(RequestBody.create(json, JSON_MEDIA))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type",  "application/json")
            .header("User-Agent",    userAgent)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return handle(resp, path);
        } catch (SocketTimeoutException | InterruptedIOException e) {
            throw new ConcordexServerException(
                "timeout calling " + path + ": " + e.getMessage(),
                null, null, e);
        } catch (IOException e) {
            throw new ConcordexServerException(
                "network error calling " + path + ": " + e.getMessage(),
                null, null, e);
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
            case 400 -> throw new ConcordexValidationException(
                "server rejected request to " + path + ": " + body,
                status, body);
            case 401 -> throw new ConcordexAuthException(
                "invalid or revoked API key",
                status, body);
            case 403 -> throw new ConcordexPermissionException(
                "API key lacks required scope for this operation",
                status, body);
        }
        if (status >= 500) {
            throw new ConcordexServerException(
                "server error from " + path + " (" + status + ")",
                status, body);
        }
        throw new ConcordexException(
            "unexpected status " + status + " from " + path,
            status, body);
    }
}
