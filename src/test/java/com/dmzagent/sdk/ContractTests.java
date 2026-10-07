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
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test harness — drives the JSON corpus from the
 * {@code dmzagent-sdk-spec} repo against the Java SDK build.
 *
 * <p>Four corpora per {@code runner-spec.md}:
 *
 * <ol>
 *   <li>{@code golden-envelopes.json} — serialization parity.
 *       The runner installs an OkHttp {@link Interceptor} that
 *       captures the outgoing request body, calls the SDK method,
 *       and asserts the captured body matches the expected envelope
 *       after JSON normalization.</li>
 *   <li>{@code signature-vectors.json} — webhook signature
 *       verifier parity. The runner computes the {@code <COMPUTE>}
 *       placeholders at runtime and asserts
 *       {@link WebhookSignature#verify} returns the expected verdict.</li>
 *   <li>{@code error-mapping.json} — exception-mapping parity.
 *       The runner installs an interceptor that returns a fixed
 *       status + body, calls the SDK method, and asserts the
 *       canonical exception type matches.</li>
 *   <li>{@code step-vectors.json} — agent mode (spec §1.9, §2.11).
 *       The runner serves each fixture's scripted responses, calls
 *       {@code agentStep}, and asserts the request and every listed
 *       field of the {@link StepResult} — {@code runs} on every
 *       vector, because the vectors that matter most are the ones
 *       where it must be {@code false}.</li>
 * </ol>
 *
 * <p>Each corpus is run as JUnit 5 {@link DynamicTest}s so a failure
 * names the specific fixture that broke.
 */
class ContractTests {

    private static final String API_KEY = "ck_test_xxxxxxxxxxxxxxxxxxxxx";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        // Sort map keys on serialization so the captured request body
        // we re-serialize matches the expected envelope after normalization.
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE =
        new TypeReference<>() {};

    /** A minimal, readable answer to a step, for fixtures that assert only the request. */
    private static final String STEP_ANSWER =
        "{\"frame_id\":\"fr_0\",\"interaction_id\":\"sess_1\",\"directive\":\"proceed\","
        + "\"scope\":null,\"reason\":\"\",\"approval_id\":null,\"settled\":true,"
        + "\"behaviors\":[],\"anchor\":null,\"livemode\":false}";

    // ===================================================================== //
    // Locate the spec repo
    // ===================================================================== //

    /**
     * The spec corpus lives in a sibling repo. Resolution order:
     *
     * <ol>
     *   <li>{@code DMZAGENT_SPEC_PATH} env var (CI sets this).</li>
     *   <li>The {@code spec.path.default} from
     *       {@code spec-version.properties}.</li>
     * </ol>
     */
    private static Path specRoot() {
        String env = System.getenv("DMZAGENT_SPEC_PATH");
        if (env != null && !env.isEmpty()) return Paths.get(env);

        try (InputStream in = ContractTests.class.getClassLoader()
                .getResourceAsStream("spec-version.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            String def = p.getProperty("spec.path.default", "../dmzagent-sdk-spec");
            return Paths.get(def);
        } catch (IOException e) {
            throw new RuntimeException("could not load spec-version.properties", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadCorpus(String name) throws IOException {
        Path file = specRoot().resolve("contract-tests").resolve(name);
        return MAPPER.readValue(Files.readAllBytes(file), MAP_TYPE);
    }

    // ===================================================================== //
    // Helpers — capture HTTP and serve canned responses
    // ===================================================================== //

    /** Captures the outgoing request and returns a fixed 200 response. */
    private static final class Capture {
        String      path;
        String      verb;
        String      query;
        Map<String, Object> body;
        String      okJson = "{\"interaction_id\":\"int_test\",\"queued\":false}";

        /** The query string as a decoded map, or empty when there was none. */
        Map<String, String> queryMap() {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            if (query == null || query.isEmpty()) return out;
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                out.put(java.net.URLDecoder.decode(pair.substring(0, i),
                            java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(i + 1),
                            java.nio.charset.StandardCharsets.UTF_8));
            }
            return out;
        }

        Interceptor interceptor() {
            return chain -> {
                Request req = chain.request();
                path = req.url().encodedPath();
                verb = req.method();
                query = req.url().query();
                String text = readBody(req);
                if (!text.isEmpty()) body = MAPPER.readValue(text, MAP_TYPE);
                return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(
                        okJson, MediaType.get("application/json")))
                    .build();
            };
        }

        private static String readBody(Request req) throws IOException {
            if (req.body() == null) return "";
            okio.Buffer buf = new okio.Buffer();
            req.body().writeTo(buf);
            return buf.readString(StandardCharsets.UTF_8);
        }
    }

    /** Returns a fixed {@code (status, body)} response without writing
     * to the network — used for error-mapping fixtures. */
    private static Interceptor cannedResponse(int status, Object body) {
        return cannedResponse(status, body, Map.of());
    }

    /**
     * The corpus attaches response headers to some fixtures (429 carries
     * {@code Retry-After}); forward them, or the SDK never sees what it is
     * meant to parse and the vector passes for the wrong reason.
     */
    private static Interceptor cannedResponse(
            int status, Object body, Map<String, Object> headers) {
        return chain -> {
            Request req = chain.request();
            String json = (body instanceof String s)
                ? "\"" + s.replace("\"", "\\\"") + "\""
                : MAPPER.writeValueAsString(body);
            Response.Builder rb = new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 400 ? "Error" : "OK")
                .body(ResponseBody.create(
                    json, MediaType.get("application/json")));
            headers.forEach((k, v) -> rb.header(k, String.valueOf(v)));
            return rb.build();
        };
    }

    // ===================================================================== //
    // golden-envelopes.json
    // ===================================================================== //

    @TestFactory
    @SuppressWarnings("unchecked")
    Iterable<DynamicTest> goldenEnvelopes() throws IOException {
        Map<String, Object> corpus = loadCorpus("golden-envelopes.json");
        List<DynamicTest> tests = new ArrayList<>();

        // ----- happy-path fixtures -----
        List<Map<String, Object>> fixtures =
            (List<Map<String, Object>>) corpus.getOrDefault("fixtures", List.of());
        for (Map<String, Object> f : fixtures) {
            String name = (String) f.get("name");
            tests.add(DynamicTest.dynamicTest("envelope/" + name, () -> {
                Capture cap = new Capture();
                // A step answered without a directive raises (spec §1.9), so
                // a step fixture is served a minimal readable answer. The
                // fixture asserts the request; the answer is not under test.
                if ("agent_step".equals(f.get("method"))) cap.okJson = STEP_ANSWER;
                try (DMZAgentClient cx = new DMZAgentClient(
                        API_KEY, "http://contract.invalid", null, null,
                        cap.interceptor())) {
                    callMethod(cx,
                        (String) f.get("method"),
                        (Map<String, Object>) f.get("args"));
                }
                assertThat(cap.path).isEqualTo(f.get("expected_path"));

                // A read vector pins its verb and its query string. Asserting
                // only the body would let a GET that sent every filter as
                // nothing at all pass, since a GET has no body to be wrong
                // about.
                Object wantVerb = f.getOrDefault("expected_method", "POST");
                assertThat(cap.verb)
                    .as("verb mismatch for " + name)
                    .isEqualTo(wantVerb);

                Object wantQuery = f.get("expected_query");
                if (wantQuery != null) {
                    assertThat(cap.queryMap())
                        .as("query mismatch for " + name)
                        .isEqualTo(wantQuery);
                }

                Map<String, Object> expected =
                    (Map<String, Object>) f.get("expected_body");
                if (expected == null) {
                    assertThat(cap.body)
                        .as(name + ": expected no request body")
                        .isNull();
                } else {
                    assertThat(normalize(cap.body))
                        .as("envelope mismatch for " + name)
                        .isEqualTo(normalize(expected));
                }
            }));
        }

        // ----- validation-failure fixtures -----
        List<Map<String, Object>> failures =
            (List<Map<String, Object>>) corpus.getOrDefault(
                "validation_failures", List.of());
        for (Map<String, Object> f : failures) {
            String name        = (String) f.get("name");
            String method      = (String) f.get("method");
            Map<String, Object> args = (Map<String, Object>) f.get("args");
            String wantContain = (String) f.get("expected_message_contains");

            tests.add(DynamicTest.dynamicTest("envelope/" + name, () -> {
                if ("construct".equals(method)) {
                    String key = (String) args.get("api_key");
                    assertThatThrownBy(() -> new DMZAgentClient(key))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(wantContain);
                    return;
                }
                Capture cap = new Capture();
                try (DMZAgentClient cx = new DMZAgentClient(
                        API_KEY, "http://contract.invalid", null, null,
                        cap.interceptor())) {
                    assertThatThrownBy(() -> callMethod(cx, method, args))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(wantContain);
                }
            }));
        }

        return tests;
    }

    // ===================================================================== //
    // signature-vectors.json
    // ===================================================================== //

    @TestFactory
    @SuppressWarnings("unchecked")
    Iterable<DynamicTest> signatureVectors() throws IOException {
        Map<String, Object> corpus = loadCorpus("signature-vectors.json");
        List<DynamicTest> tests = new ArrayList<>();

        List<Map<String, Object>> fixtures =
            (List<Map<String, Object>>) corpus.getOrDefault("fixtures", List.of());

        for (Map<String, Object> f : fixtures) {
            String name = (String) f.get("name");
            tests.add(DynamicTest.dynamicTest("signature/" + name, () -> {
                String payload   = (String) f.get("payload");
                String secret    = (String) f.get("secret");
                String headerTpl = (String) f.get("header");
                int tolerance    = ((Number) f.get("tolerance_seconds")).intValue();
                long nowUnix     = ((Number) f.get("now_unix")).longValue();
                boolean expected = (Boolean) f.get("valid");

                String header = headerTpl;
                if (header.contains("<COMPUTE>")) {
                    long t = parseT(header);
                    String mac = hmacHex(secret, t + "." + payload);
                    header = header.replace("<COMPUTE>", mac);
                } else if (header.contains("<COMPUTE_WITH_OTHER>")) {
                    long t = parseT(header);
                    String other = (String) f.get("header_signed_with");
                    String mac = hmacHex(other, t + "." + payload);
                    header = header.replace("<COMPUTE_WITH_OTHER>", mac);
                }

                boolean got = WebhookSignature.verify(
                    payload, header, secret, tolerance, nowUnix);
                assertThat(got)
                    .as("signature verdict for " + name)
                    .isEqualTo(expected);
            }));
        }
        return tests;
    }

    private static long parseT(String header) {
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if ("t".equals(part.substring(0, eq).trim())) {
                return Long.parseLong(part.substring(eq + 1).trim());
            }
        }
        throw new IllegalArgumentException("no t= in header: " + header);
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===================================================================== //
    // error-mapping.json
    // ===================================================================== //

    @TestFactory
    @SuppressWarnings("unchecked")
    Iterable<DynamicTest> errorMapping() throws IOException {
        Map<String, Object> corpus = loadCorpus("error-mapping.json");
        List<DynamicTest> tests = new ArrayList<>();

        List<Map<String, Object>> fixtures =
            (List<Map<String, Object>>) corpus.getOrDefault("fixtures", List.of());

        for (Map<String, Object> f : fixtures) {
            String name = (String) f.get("name");
            tests.add(DynamicTest.dynamicTest("error/" + name, () -> {
                int    status  = ((Number) f.get("status")).intValue();
                Object body    = f.get("body");
                String method  = (String) f.get("method");
                Map<String, Object> args = (Map<String, Object>) f.get("args");
                String wantExc = (String) f.get("expected_exception");
                Map<String, Object> fxHeaders =
                    (Map<String, Object>) f.getOrDefault("headers", Map.of());

                try (DMZAgentClient cx = new DMZAgentClient(
                        API_KEY, "http://contract.invalid", null, null,
                        cannedResponse(status, body, fxHeaders))) {

                    if ("guard_with_raise_on_open".equals(method)) {
                        boolean raise = Boolean.TRUE.equals(args.get("raise_on_open"));
                        String  sid   = (String) args.get("subject_id");
                        if (wantExc == null) {
                            // expect no exception; verify result fields
                            try (Guard g = cx.guard(sid, null, raise)) {
                                Map<String, Object> wantFields =
                                    (Map<String, Object>) f.get("expected_result_fields");
                                if (wantFields != null) {
                                    assertThat(g.getResult().allow())
                                        .isEqualTo(wantFields.get("allow"));
                                    assertThat(g.getResult().state())
                                        .isEqualTo(wantFields.get("state"));
                                }
                            }
                        } else {
                            AtomicReference<Throwable> caught = new AtomicReference<>();
                            try (Guard g = cx.guard(sid, null, raise)) {
                                // body shouldn't run
                            } catch (CircuitBreakerOpenException e) {
                                caught.set(e);
                            }
                            assertThat(caught.get())
                                .isInstanceOf(CircuitBreakerOpenException.class);
                            CircuitBreakerOpenException e =
                                (CircuitBreakerOpenException) caught.get();
                            Map<String, Object> wantFields =
                                (Map<String, Object>) f.get("expected_fields");
                            if (wantFields != null) {
                                if (wantFields.containsKey("reason"))
                                    assertThat(e.reason()).isEqualTo(wantFields.get("reason"));
                                if (wantFields.containsKey("scope_ref"))
                                    assertThat(e.scopeRef()).isEqualTo(wantFields.get("scope_ref"));
                            }
                        }
                        return;
                    }

                    Class<? extends Throwable> expected = mapException(wantExc);
                    assertThatThrownBy(() -> callMethod(cx, method, args))
                        .as("error mapping for " + name)
                        .isInstanceOf(expected)
                        .satisfies(t -> {
                            DMZAgentException ce = (DMZAgentException) t;
                            Integer wantStatus = (Integer) f.get("expected_status_code");
                            if (wantStatus != null) {
                                assertThat(ce.statusCode())
                                    .isEqualTo(wantStatus);
                            }
                            // containsKey, not get() != null: the corpus has an
                            // explicit null case for a 429 sent without a
                            // Retry-After header, and a plain null check would
                            // make that indistinguishable from absence.
                            if (f.containsKey("expected_retry_after")) {
                                assertThat(ce)
                                    .isInstanceOf(DMZAgentRateLimitException.class);
                                assertThat(((DMZAgentRateLimitException) ce).retryAfter())
                                    .isEqualTo((Integer) f.get("expected_retry_after"));
                            }
                        });
                }
            }));
        }
        return tests;
    }

    // ===================================================================== //
    // step-vectors.json
    // ===================================================================== //

    /**
     * Serves scripted {@code (status, body)} responses in order and records
     * every request. A request past the script is an {@link IOException},
     * which the SDK surfaces as a server error — never as an answer.
     */
    private static final class Scripted implements Interceptor {
        final List<Map<String, Object>> responses;
        final List<Request> requests = new ArrayList<>();

        Scripted(List<Map<String, Object>> responses) { this.responses = responses; }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request req = chain.request();
            requests.add(req);
            if (requests.size() > responses.size()) {
                throw new IOException("request " + requests.size()
                    + " is past the fixture's " + responses.size() + " scripted response(s)");
            }
            Map<String, Object> r = responses.get(requests.size() - 1);
            int status = ((Number) r.get("status")).intValue();
            return new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 400 ? "Error" : "OK")
                .body(ResponseBody.create(
                    MAPPER.writeValueAsString(r.get("body")),
                    MediaType.get("application/json")))
                .build();
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Iterable<DynamicTest> stepVectors() throws IOException {
        Map<String, Object> corpus = loadCorpus("step-vectors.json");
        List<DynamicTest> tests = new ArrayList<>();

        List<Map<String, Object>> fixtures =
            (List<Map<String, Object>>) corpus.getOrDefault("fixtures", List.of());
        for (Map<String, Object> f : fixtures) {
            String name = (String) f.get("name");
            tests.add(DynamicTest.dynamicTest("step/" + name, () -> {
                Map<String, Object> want =
                    (Map<String, Object>) f.get("expected_result");
                // runner-spec.md: runs MUST be asserted on every vector. A
                // vector that forgot it would pass here without saying so.
                assertThat(want)
                    .as(name + ": the vector must assert runs")
                    .containsKey("runs");

                Scripted t = new Scripted(
                    (List<Map<String, Object>>) f.get("responses"));
                StepResult r;
                try (DMZAgentClient cx = new DMZAgentClient(
                        API_KEY, "http://contract.invalid", null, null, t)) {
                    r = (StepResult) callMethod(cx, (String) f.get("method"),
                        (Map<String, Object>) f.get("args"));
                }

                Map<String, Object> wantReq =
                    (Map<String, Object>) f.get("expected_request");
                assertThat(t.requests).hasSize(1);
                assertThat(t.requests.get(0).method())
                    .isEqualTo(wantReq.get("method"));
                assertThat(t.requests.get(0).url().encodedPath())
                    .isEqualTo(wantReq.get("path"));

                for (Map.Entry<String, Object> e : want.entrySet()) {
                    if ("behaviors".equals(e.getKey())) {
                        List<Map<String, Object>> wantB =
                            (List<Map<String, Object>>) e.getValue();
                        assertThat(r.behaviors())
                            .as(name + ": behaviors")
                            .hasSize(wantB.size());
                        for (int i = 0; i < wantB.size(); i++) {
                            Behavior b = r.behaviors().get(i);
                            for (Map.Entry<String, Object> be : wantB.get(i).entrySet()) {
                                assertThat(behaviorField(b, be.getKey()))
                                    .as(name + ": behaviors[" + i + "]." + be.getKey())
                                    .isEqualTo(be.getValue());
                            }
                        }
                    } else {
                        assertThat(stepField(r, e.getKey()))
                            .as(name + ": " + e.getKey())
                            .isEqualTo(e.getValue());
                    }
                }
            }));
        }

        List<Map<String, Object>> failures =
            (List<Map<String, Object>>) corpus.getOrDefault("failures", List.of());
        for (Map<String, Object> f : failures) {
            String name = (String) f.get("name");
            tests.add(DynamicTest.dynamicTest("step/" + name, () -> {
                Scripted t = new Scripted(
                    (List<Map<String, Object>>) f.get("responses"));
                Class<? extends Throwable> expected =
                    mapException((String) f.get("expected_exception"));
                AtomicReference<Object> returned = new AtomicReference<>();
                try (DMZAgentClient cx = new DMZAgentClient(
                        API_KEY, "http://contract.invalid", null, null, t)) {
                    assertThatThrownBy(() -> returned.set(callMethod(cx,
                            (String) f.get("method"),
                            (Map<String, Object>) f.get("args"))))
                        .as(name)
                        .isInstanceOf(expected);
                }
                assertThat(returned.get())
                    .as(name + ": an unanswered step must not return a result")
                    .isNull();
            }));
        }
        return tests;
    }

    /** A {@link StepResult} field by its canonical wire name (spec §8.4). */
    private static Object stepField(StepResult r, String key) {
        return switch (key) {
            case "frame_id"       -> r.frameId();
            case "interaction_id" -> r.interactionId();
            case "directive"      -> r.directive();
            case "scope"          -> r.scope();
            case "reason"         -> r.reason();
            case "approval_id"    -> r.approvalId();
            case "settled"        -> r.settled();
            case "behaviors"      -> r.behaviors();
            case "anchor"         -> r.anchor();
            case "livemode"       -> r.livemode();
            case "runs"           -> r.runs();
            default -> throw new IllegalArgumentException(
                "step vector names a StepResult field this runner does not map: " + key);
        };
    }

    /** A {@link Behavior} field by its canonical wire name (spec §8.4). */
    private static Object behaviorField(Behavior b, String key) {
        return switch (key) {
            case "tag"            -> b.tag();
            case "polarity"       -> b.polarity();
            case "strength"       -> b.strength();
            case "source"         -> b.source();
            case "evidence"       -> b.evidence();
            case "calls"          -> b.calls();
            case "behavior_id"    -> b.behaviorId();
            case "subject_id"     -> b.subjectId();
            case "interaction_id" -> b.interactionId();
            case "observed_at"    -> b.observedAt();
            case "anchor"         -> b.anchor();
            default -> throw new IllegalArgumentException(
                "step vector names a Behavior field this runner does not map: " + key);
        };
    }

    private static Class<? extends Throwable> mapException(String canonical) {
        return switch (canonical) {
            case "AuthError"       -> DMZAgentAuthException.class;
            case "PermissionError" -> DMZAgentPermissionException.class;
            case "ValidationError" -> DMZAgentValidationException.class;
            case "ServerError"     -> DMZAgentServerException.class;
            case "RateLimitError"  -> DMZAgentRateLimitException.class;
            case "ConflictError"   -> DMZAgentConflictException.class;
            case "DMZAgentError"  -> DMZAgentException.class;
            case "CBOpenError"     -> CircuitBreakerOpenException.class;
            default -> throw new IllegalArgumentException(
                "unknown canonical exception: " + canonical);
        };
    }

    // ===================================================================== //
    // Dispatch by method name — the corpus uses canonical snake_case
    // names; we translate to the Java surface here.
    // ===================================================================== //

    @SuppressWarnings("unchecked")
    private static Object callMethod(DMZAgentClient cx, String method,
                                     Map<String, Object> args) {
        // subject_type is REQUIRED by spec §5.2-5.5. The runner used to call
        // the short overloads that omit it, which silently substitute
        // "sensor" — and every fixture happened to use "sensor", so the
        // substitution was invisible. Always pass the fixture's value
        // through the explicit overloads so the corpus actually controls it.
        String subjectType = (String) args.get("subject_type");
        return switch (method) {
            case "subject_says" -> cx.subjectSays(
                (String) args.get("subject_id"),
                (String) args.get("text"),
                (String) args.get("agent_subject_id"),
                subjectType,
                (String) args.get("interaction_id"),
                (List<Map<String, Object>>) args.get("subjects"),
                (Map<String, Object>)       args.get("payload_extra"));

            case "tool_call" -> cx.toolCall(
                (String) args.get("subject_id"),
                (String) args.get("tool"),
                (Map<String, Object>) args.get("args"),
                subjectType,
                (String) args.get("interaction_id"),
                (List<Map<String, Object>>) args.get("subjects"));

            case "tool_result" -> cx.toolResult(
                (String) args.get("subject_id"),
                (String) args.get("tool"),
                args.get("result"),
                subjectType,
                (String) args.get("interaction_id"),
                (List<Map<String, Object>>) args.get("subjects"));

            case "observation" -> cx.observation(
                (String) args.get("agent_subject_id"),
                subjectType,
                (List<Map<String, Object>>) args.get("subjects"),
                (Map<String, Object>) args.get("payload"),
                (String) args.get("interaction_id"));

            case "check" -> cx.check(
                (String) args.get("subject_id"),
                (String) args.get("interaction_id"));

            case "emit_event" -> cx.emitEvent(
                (String) args.get("kind"),
                (String) args.get("agent_subject_id"),
                (Map<String, Object>) args.get("payload"),
                (String) args.get("interaction_id"),
                (String) args.get("interaction_kind"),
                (List<Map<String, Object>>) args.get("subjects"),
                (String) args.get("speaker_subject_id"),
                (String) args.get("speaker_role"),
                (String) args.get("occurred_at"),
                (Map<String, Object>) args.get("metadata"));

            // 0.10.0 — the white-label approval control and the readable ledger.
            case "list_approvals" -> cx.listApprovals(
                (String) args.get("status"),
                (String) args.get("subject_id"),
                args.get("limit") instanceof Number n ? n.intValue() : null,
                (String) args.get("cursor"));

            case "decide_approval" -> cx.decideApproval(
                (String) args.get("approval_id"),
                (String) args.get("decision"),
                (String) args.get("actor_id"),
                (String) args.get("actor_label"),
                (String) args.get("reason"));

            case "get_incidents" -> cx.getIncidents(
                (String) args.get("status"),
                (String) args.get("subject_id"),
                (String) args.get("since"),
                (String) args.get("until"),
                args.get("limit") instanceof Number n ? n.intValue() : null,
                (String) args.get("cursor"));

            // 0.11.0 — agent mode.
            case "agent_step" -> cx.agentStep(
                (String) args.get("agent_subject_id"),
                (String) args.get("interaction_id"),
                (String) args.get("phase"),
                (String) args.get("call_id"),
                (String) args.get("tool"),
                (Map<String, Object>) args.get("args"),
                (String) args.get("status"),
                args.get("result"),
                (String) args.get("refused_by"),
                (String) args.get("reason"),
                (String) args.get("attempt_of"),
                (Map<String, Object>) args.get("intent"),
                (String) args.get("occurred_at"),
                (Map<String, Object>) args.get("metadata"),
                (String) args.get("idempotency_key"));

            case "list_behaviors" -> cx.listBehaviors(
                (String) args.get("subject_id"),
                (String) args.get("polarity"),
                (String) args.get("interaction_id"),
                (String) args.get("since"),
                (String) args.get("until"),
                args.get("limit") instanceof Number n ? n.intValue() : null,
                (String) args.get("cursor"));

            case "get_approval" -> cx.getApproval(
                (String) args.get("approval_id"));

            default -> throw new IllegalArgumentException(
                "unknown corpus method: " + method);
        };
    }

    // ===================================================================== //
    // JSON normalization — deep-sort maps so the assertion compares
    // structures, not insertion order.
    // ===================================================================== //

    @SuppressWarnings("unchecked")
    private static Object normalize(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return sorted;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(normalize(x));
            return out;
        }
        return v;
    }
}
