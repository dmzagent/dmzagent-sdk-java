package com.dmzagent.sdk;

import com.dmzagent.sdk.exceptions.DMZAgentException;
import com.dmzagent.sdk.exceptions.DMZAgentServerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent mode (spec §1.9, §2.11–§2.13, §5.22–§5.25).
 *
 * <p>What these hold, and why each is here rather than an assertion that
 * merely passes:
 *
 * <ul>
 *   <li>A malformed step is refused locally, and the test asserts <em>no
 *       request was made</em> — a server-side rejection would throw too and
 *       would say nothing about where the check lives.
 *   <li>{@code runs} is {@code true} exactly for {@code proceed} and
 *       {@code warn}. An unknown directive, and a 200 that carries none, are
 *       not a yes.
 *   <li>The {@code Idempotency-Key} header travels only when the caller
 *       gave one. The SDK never invents a key.
 *   <li>The session handle holds its two ids and nothing else, so it cannot
 *       infer {@code attemptOf} or remember a refusal.
 *   <li>The conduct record is read a page at a time, and walked only on
 *       request; there is no method that edits it.
 *   <li>An unknown approval id is {@code 404} → the base
 *       {@link DMZAgentException}, not a subclass.
 * </ul>
 */
@DisplayName("agent mode (spec §1.9, §2.11–§2.13)")
class AgentModeTest {

    private static final String KEY = "ck_test_agent_mode";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT = "subject:dv_test:agent-a";
    private static final String SESS  = "sess_1";

    //: How many requests past the last scripted body the stub tolerates
    //: before it calls the walk unbounded (as ApprovalsAndLedgerTest).
    private static final int RUNAWAY_AFTER = 5;

    private static Map<String, Object> answer(String directive) {
        return answer(directive, Map.of());
    }

    private static Map<String, Object> answer(String directive, Map<String, Object> over) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("frame_id", "fr_1");
        d.put("interaction_id", SESS);
        d.put("directive", directive);
        d.put("scope", "proceed".equals(directive) ? null : "interaction");
        d.put("reason", "");
        d.put("approval_id", "hold".equals(directive) ? "apr_9" : null);
        d.put("settled", true);
        d.put("behaviors", List.of());
        d.put("anchor", null);
        d.put("livemode", false);
        d.putAll(over);
        return d;
    }

    private static Map<String, Object> behaviorBody(String id) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("behavior_id", id);
        b.put("subject_id", AGENT);
        b.put("interaction_id", SESS);
        b.put("tag", "circumvention");
        b.put("polarity", "negative");
        b.put("strength", 0.82);
        b.put("source", "reasoning");
        b.put("evidence", List.of("fr_7b90", "fr_7c21"));
        b.put("calls", List.of("call_12", "call_14"));
        b.put("observed_at", "2026-10-07T15:02:11Z");
        b.put("anchor", Map.of("ledger_index", 40312, "hash", "77ab"));
        return b;
    }

    private static Map<String, Object> page(List<?> behaviors, String next) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("behaviors", behaviors);
        p.put("next_cursor", next);
        return p;
    }

    /** One captured request: what the SDK actually put on the wire. */
    private record Seen(String method, String path, String query, String body,
                        String idempotencyKey) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() throws IOException {
            return MAPPER.readValue(body, Map.class);
        }
    }

    /** Serves each scripted body in turn, repeating the last — then refuses. */
    private static final class Transport implements Interceptor {
        final AtomicInteger calls = new AtomicInteger();
        final List<Seen> seen = new ArrayList<>();
        private final List<Object> bodies;
        private final int status;

        Transport(int status, Object... b) {
            this.status = status;
            this.bodies = new ArrayList<>(List.of(b));
            if (this.bodies.isEmpty()) this.bodies.add(Map.of());
        }

        static Transport serving(Object... b) { return new Transport(200, b); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            int n = calls.incrementAndGet();
            if (n > bodies.size() + RUNAWAY_AFTER) {
                throw new IOException(
                    "unbounded pagination: " + n + " requests for " + bodies.size()
                    + " scripted page(s)");
            }
            Request req = chain.request();
            String sent = "";
            if (req.body() != null) {
                okio.Buffer buf = new okio.Buffer();
                req.body().writeTo(buf);
                sent = buf.readUtf8();
            }
            seen.add(new Seen(req.method(), req.url().encodedPath(),
                              req.url().query(), sent, req.header("Idempotency-Key")));
            Object payload = bodies.get(Math.min(n - 1, bodies.size() - 1));
            String text = payload instanceof String s ? s : MAPPER.writeValueAsString(payload);
            return new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 400 ? "Error" : "OK")
                .body(ResponseBody.create(text, MediaType.get("application/json")))
                .build();
        }
    }

    private static DMZAgentClient client(Transport t) {
        return new DMZAgentClient(KEY, null, null, null, t);
    }

    private static Map<String, String> queryOf(Seen s) {
        Map<String, String> out = new HashMap<>();
        if (s.query() == null || s.query().isEmpty()) return out;
        for (String pair : s.query().split("&")) {
            int i = pair.indexOf('=');
            out.put(java.net.URLDecoder.decode(pair.substring(0, i),
                        java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(i + 1),
                        java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    /** A step with every field named; tests change one thing each. */
    private static final class Step {
        String agent = AGENT, interaction = SESS, phase = "call", callId = "call_1",
               tool = "Bash", status, refusedBy, reason, attemptOf, occurredAt, key;
        Map<String, Object> args = Map.of("command", "ls"), intent, metadata;
        Object result;

        Step with(Consumer<Step> c) { c.accept(this); return this; }

        StepResult send(DMZAgentClient cx) {
            return cx.agentStep(agent, interaction, phase, callId, tool, args, status,
                result, refusedBy, reason, attemptOf, intent, occurredAt, metadata, key);
        }
    }

    /** Asserts the step is refused locally, naming {@code word}, with no request. */
    private static void refusedLocally(Step step, String word) {
        Transport t = Transport.serving(answer("proceed"));
        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class, () -> step.send(client(t)));
        assertTrue(e.getMessage().contains(word),
            "message should name '" + word + "': " + e.getMessage());
        assertEquals(0, t.calls.get(), "a malformed step must not reach the wire");
    }

    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("a malformed step is refused before the round trip (§5.22)")
    class LocalValidation {

        @Test
        void anUnknownPhase() {
            refusedLocally(new Step().with(s -> s.phase = "plan"), "phase");
            refusedLocally(new Step().with(s -> s.phase = null), "phase");
        }

        @Test
        void aCallWithoutItsCallId() {
            refusedLocally(new Step().with(s -> s.callId = null), "callId");
            refusedLocally(new Step().with(s -> s.callId = " "), "callId");
        }

        @Test
        void aCallWithoutItsTool() {
            refusedLocally(new Step().with(s -> s.tool = null), "tool");
        }

        @Test
        void aResultWithoutItsCallIdOrTool() {
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "ok"; s.callId = null;
            }), "callId");
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "ok"; s.tool = "";
            }), "tool");
        }

        @Test
        void aResultWithoutAStatus() {
            refusedLocally(new Step().with(s -> s.phase = "result"), "status");
        }

        @Test
        @DisplayName("a refusal that does not say who refused")
        void aRefusalWithoutItsRefuser() {
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "refused";
            }), "refused");
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "refused"; s.refusedBy = "";
            }), "refused");
        }

        @Test
        @DisplayName("a refuser on a call that ran, or on a step with no status")
        void aRefuserWhereNothingWasRefused() {
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "ok"; s.refusedBy = "host";
            }), "refused");
            refusedLocally(new Step().with(s -> s.refusedBy = "harness"), "refused");
            refusedLocally(new Step().with(s -> {
                s.phase = "intent"; s.callId = null; s.tool = null; s.args = null;
                s.intent = Map.of("text", "x"); s.refusedBy = "governor";
            }), "refused");
            refusedLocally(new Step().with(s -> {
                s.phase = "result"; s.status = "error"; s.refusedBy = "host";
            }), "refused");
        }

        @Test
        @DisplayName("status and refusedBy values are the server's to judge (Appendix B)")
        void unknownStatusAndRefuserValuesStillGoOut() {
            Transport t = Transport.serving(answer("proceed"));
            DMZAgentClient cx = client(t);
            new Step().with(s -> { s.phase = "result"; s.status = "timeout"; }).send(cx);
            new Step().with(s -> {
                s.phase = "result"; s.status = "refused"; s.refusedBy = "reviewer";
            }).send(cx);
            assertEquals(2, t.calls.get());
        }

        @Test
        void anIntentStepWithoutItsIntent() {
            refusedLocally(new Step().with(s -> {
                s.phase = "intent"; s.callId = null; s.tool = null; s.args = null;
            }), "intent");
            refusedLocally(new Step().with(s -> {
                s.phase = "intent"; s.callId = null; s.tool = null; s.args = null;
                s.intent = Map.of("paths", List.of("src/"));
            }), "intent");
        }

        @Test
        void aStepWithoutItsSessionOrAgent() {
            refusedLocally(new Step().with(s -> s.interaction = null), "interaction");
            refusedLocally(new Step().with(s -> s.interaction = ""), "interaction");
            refusedLocally(new Step().with(s -> s.agent = null), "agentSubjectId");
        }

        @Test
        void aSessionHandleWithoutItsIds() {
            Transport t = Transport.serving(answer("proceed"));
            DMZAgentClient cx = client(t);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> cx.agentSession(AGENT, " "));
            assertTrue(e.getMessage().contains("interaction"));
            assertThrows(IllegalArgumentException.class, () -> cx.agentSession(null, SESS));
            assertEquals(0, t.calls.get());
        }

        @Test
        @DisplayName("session.result() takes ok or error; a refusal goes through refused()")
        void resultIsNotARefusal() {
            Transport t = Transport.serving(answer("proceed"));
            AgentSession s = client(t).agentSession(AGENT, SESS);
            for (String bad : new String[] { "refused", null, "done" }) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> s.result("call_1", "Bash", bad, null, null));
                assertTrue(e.getMessage().contains("status"), e.getMessage());
            }
            assertThrows(IllegalArgumentException.class,
                () -> s.refused("call_1", "Bash", null, null, null));
            assertEquals(0, t.calls.get());
        }

        @Test
        void wellFormedStepsOfEveryPhaseGoOut() {
            Transport t = Transport.serving(answer("proceed"));
            DMZAgentClient cx = client(t);
            new Step().send(cx);
            new Step().with(s -> { s.phase = "result"; s.status = "ok"; s.args = null; }).send(cx);
            new Step().with(s -> {
                s.phase = "result"; s.status = "refused"; s.refusedBy = "host"; s.args = null;
            }).send(cx);
            new Step().with(s -> {
                s.phase = "intent"; s.callId = null; s.tool = null; s.args = null;
                s.intent = Map.of("text", "tidy the build");
            }).send(cx);
            assertEquals(4, t.calls.get());
        }
    }

    @Nested
    @DisplayName("reading the answer: runs is true exactly for proceed and warn (§7.16)")
    class Directive {

        @Test
        void eachKnownDirective() {
            Map<String, Boolean> want = Map.of(
                "proceed", true, "warn", true,
                "hold", false, "block", false, "shutdown", false);
            assertEquals(Directives.ALL.size(), want.size());
            for (String d : Directives.ALL) {
                Transport t = Transport.serving(answer(d));
                StepResult r = new Step().send(client(t));
                assertEquals(d, r.directive());
                assertEquals(want.get(d), r.runs(), d);
            }
        }

        @Test
        @DisplayName("an unknown directive is read as block, and kept raw")
        void anUnknownDirectiveDoesNotRun() {
            for (String d : new String[] { "quarantine", "PROCEED", "proceed ", "allow" }) {
                Transport t = Transport.serving(answer(d));
                StepResult r = new Step().send(client(t));
                assertEquals(d, r.directive(), "the raw string is exposed");
                assertFalse(r.runs(), "an unknown word from the governor is not a yes: " + d);
            }
        }

        @Test
        @DisplayName("a 2xx with no directive raises ServerError carrying its status (§1.9)")
        void anAnswerWithoutADirectiveIsNotAnAnswer() {
            Map<String, Object> missing = answer("proceed");
            missing.remove("directive");
            Map<String, Object> notAString = answer("proceed");
            notAString.put("directive", 1);
            for (int code : new int[] { 200, 201 }) {
                for (Object body : new Object[] {
                        missing, notAString, answer(""), "not json", Map.of() }) {
                    Transport t = new Transport(code, body);
                    DMZAgentServerException e = assertThrows(DMZAgentServerException.class,
                        () -> new Step().send(client(t)));
                    assertTrue(e.getMessage().contains("directive"), e.getMessage());
                    assertEquals(code, e.statusCode(), "the response's own status");
                }
            }
        }

        @Test
        void anUnansweredStepRaises() {
            Transport t = new Transport(503, Map.of("detail", "unavailable"));
            DMZAgentServerException e = assertThrows(DMZAgentServerException.class,
                () -> new Step().send(client(t)));
            assertEquals(503, e.statusCode());
        }

        @Test
        void holdNamesItsApproval() {
            StepResult r = new Step().send(client(Transport.serving(answer("hold"))));
            assertFalse(r.runs());
            assertEquals("apr_9", r.approvalId());
        }

        @Test
        void behaviorsArriveVerbatim() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("tag", "kept-its-word");
            b.put("polarity", "neutral");
            b.put("strength", 1);
            b.put("source", "logic");
            b.put("evidence", List.of("fr_0"));
            Map<String, Object> over = new LinkedHashMap<>();
            over.put("behaviors", List.of(b));
            over.put("settled", false);
            over.put("livemode", true);
            over.put("anchor", Map.of("ledger_index", 40311, "hash", "c0d9"));
            StepResult r = new Step().send(client(Transport.serving(answer("proceed", over))));

            assertFalse(r.settled());
            assertEquals(Boolean.TRUE, r.livemode());
            assertEquals(Map.of("ledger_index", 40311, "hash", "c0d9"), r.anchor());
            Behavior got = r.behaviors().get(0);
            assertEquals("kept-its-word", got.tag(), "the canon's word, unmapped");
            assertEquals("neutral", got.polarity(), "an unknown polarity is kept raw");
            assertEquals(1.0, got.strength());
            assertEquals(List.of("fr_0"), got.evidence());
            assertEquals(List.of(), got.calls(), "calls MAY be absent; it reads as empty");
            assertNull(got.behaviorId(), "record-only fields are null on a step");
            assertThrows(UnsupportedOperationException.class,
                () -> r.behaviors().add(got), "the result is immutable");
        }

        @Test
        @DisplayName("an answer that leaves out settled or livemode does not invent them")
        void absentFieldsAreNotInvented() {
            Map<String, Object> sparse = new LinkedHashMap<>();
            sparse.put("frame_id", "fr_1");
            sparse.put("interaction_id", SESS);
            sparse.put("directive", "block");
            StepResult r = new Step().send(client(Transport.serving(sparse)));
            assertFalse(r.settled(), "absent settled reads as still running");
            assertNull(r.livemode());
            assertEquals(List.of(), r.behaviors());
            assertNull(r.scope());
        }
    }

    @Nested
    @DisplayName("what goes on the wire")
    class Wire {

        @Test
        void aCallStepCarriesOnlyWhatWasGiven() throws Exception {
            Transport t = Transport.serving(answer("proceed"));
            new Step().send(client(t));
            Seen s = t.seen.get(0);
            assertEquals("POST", s.method());
            assertEquals("/v1/agent-stream/step", s.path());
            assertEquals(Map.of(
                "agent_subject_id", AGENT, "interaction_id", SESS, "phase", "call",
                "call_id", "call_1", "tool", "Bash", "args", Map.of("command", "ls")),
                s.json(), "no interaction_kind, no nulls, nothing inferred");
        }

        @Test
        @DisplayName("Idempotency-Key travels only when the caller gave one")
        void idempotencyKeyOnlyWhenGiven() {
            Object[] seven = new Object[7];
            java.util.Arrays.fill(seven, answer("proceed"));
            Transport t = Transport.serving(seven);
            DMZAgentClient cx = client(t);
            new Step().send(cx);
            new Step().with(s -> s.key = "harness-retry-7").send(cx);
            AgentSession session = cx.agentSession(AGENT, SESS);
            session.call("call_2", "Bash", null, null);
            session.call("call_2", "Bash", null, null, "harness-retry-8");
            session.intent("x", null, null);
            session.result("call_2", "Bash", "ok", null, null, "k-res");
            session.refused("call_3", "Bash", "host", null, null);

            List<String> keys = t.seen.stream().map(Seen::idempotencyKey)
                .collect(Collectors.toList());
            assertEquals(java.util.Arrays.asList(
                null, "harness-retry-7", null, "harness-retry-8", null, "k-res", null),
                keys, "the SDK never generates a key");
        }

        @Test
        void theSessionSendsEachPhase() throws Exception {
            Transport t = Transport.serving(answer("proceed"));
            AgentSession s = client(t).agentSession(AGENT, SESS);
            s.intent("Add a trace id.", List.of("src/obs/"), List.of("Edit"));
            s.call("call_7", "Bash", Map.of("command", "git push"), null);
            s.refused("call_9", "Bash", "harness", "remote writes are the runner's", "call_7");
            s.result("call_8", "Read", "ok", "def main(): ...", null);

            assertEquals(Map.of("agent_subject_id", AGENT, "interaction_id", SESS,
                "phase", "intent", "intent", Map.of(
                    "text", "Add a trace id.", "paths", List.of("src/obs/"),
                    "tools", List.of("Edit"))),
                t.seen.get(0).json());
            assertEquals(Map.of("agent_subject_id", AGENT, "interaction_id", SESS,
                "phase", "call", "call_id", "call_7", "tool", "Bash",
                "args", Map.of("command", "git push")),
                t.seen.get(1).json());
            assertEquals(Map.of("agent_subject_id", AGENT, "interaction_id", SESS,
                "phase", "result", "call_id", "call_9", "tool", "Bash",
                "status", "refused", "refused_by", "harness",
                "reason", "remote writes are the runner's", "attempt_of", "call_7"),
                t.seen.get(2).json());
            assertEquals(Map.of("agent_subject_id", AGENT, "interaction_id", SESS,
                "phase", "result", "call_id", "call_8", "tool", "Read",
                "status", "ok", "result", "def main(): ..."),
                t.seen.get(3).json());
        }

        @Test
        @DisplayName("the handle holds its two ids and nothing else (§5.23)")
        void theHandleHoldsOnlyItsIds() throws Exception {
            List<String> state = new ArrayList<>();
            for (Field f : AgentSession.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                state.add(f.getName());
                assertTrue(Modifier.isFinal(f.getModifiers()), f.getName() + " must be final");
            }
            state.sort(null);
            assertEquals(List.of("agentSubjectId", "client", "interactionId"), state);

            // And behaviourally: a refusal is not remembered into the next call.
            Transport t = Transport.serving(answer("block"));
            AgentSession s = client(t).agentSession(AGENT, SESS);
            s.refused("call_1", "Bash", "governor", null, null);
            s.call("call_2", "Bash", Map.of("command", "ls"), null);
            assertFalse(t.seen.get(1).json().containsKey("attempt_of"),
                "attemptOf is the caller's to say, never inferred");
            assertEquals(AGENT, s.agentSubjectId());
            assertEquals(SESS, s.interactionId());
        }
    }

    @Nested
    @DisplayName("the conduct record (§2.12)")
    class ConductRecord {

        @Test
        @DisplayName("defaults send no filters, and the subject id keeps its colons")
        void defaults() {
            Transport t = Transport.serving(page(List.of(behaviorBody("bhv_1")), "c2"));
            BehaviorPage p = client(t).listBehaviors(AGENT);
            Seen s = t.seen.get(0);
            assertEquals("GET", s.method());
            assertEquals("/v1/subjects/subject:dv_test:agent-a/behaviors", s.path());
            assertEquals(Map.of(), queryOf(s), "polarity defaults server-side, not here");
            assertEquals(1, t.calls.get(), "one page means one request");
            assertEquals("c2", p.nextCursor());

            Behavior b = p.behaviors().get(0);
            assertEquals("bhv_1", b.behaviorId());
            assertEquals(AGENT, b.subjectId());
            assertEquals(SESS, b.interactionId());
            assertEquals("2026-10-07T15:02:11Z", b.observedAt());
            assertEquals(0.82, b.strength());
            assertEquals(List.of("call_12", "call_14"), b.calls());
            assertEquals(Map.of("ledger_index", 40312, "hash", "77ab"), b.anchor());
        }

        @Test
        void everyFilterAndTheCursor() {
            Transport t = Transport.serving(page(List.of(), null));
            client(t).listBehaviors(AGENT, "negative", SESS,
                "2026-10-01T00:00:00Z", "2026-10-07T00:00:00Z", 10, "eyJpIjo0MH0");
            assertEquals(Map.of(
                "polarity", "negative", "interaction_id", SESS,
                "since", "2026-10-01T00:00:00Z", "until", "2026-10-07T00:00:00Z",
                "limit", "10", "cursor", "eyJpIjo0MH0"),
                queryOf(t.seen.get(0)));
        }

        @Test
        void aSubjectIdIsEncodedAsOnePathSegment() {
            Transport t = Transport.serving(page(List.of(), null));
            DMZAgentClient cx = client(t);
            cx.listBehaviors("subject:dv:a/b c");
            assertEquals("/v1/subjects/subject:dv:a%2Fb%20c/behaviors", t.seen.get(0).path());
            // A dot-segment would be resolved away by the URL parser — even
            // percent-encoded — and address another resource. Refused.
            for (String dots : new String[] { ".", ".." }) {
                assertThrows(IllegalArgumentException.class, () -> cx.listBehaviors(dots));
                assertThrows(IllegalArgumentException.class, () -> cx.getApproval(dots));
            }
            assertEquals(1, t.calls.get());
        }

        @Test
        void refusedBeforeTheRoundTrip() {
            for (int bad : new int[] { 0, -1, 101, 500 }) {
                Transport t = Transport.serving(page(List.of(), null));
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> client(t).listBehaviors(AGENT, null, null, null, null, bad, null));
                assertTrue(e.getMessage().contains("limit"));
                assertEquals(0, t.calls.get());
            }
            Transport t = Transport.serving(page(List.of(), null));
            assertThrows(IllegalArgumentException.class, () -> client(t).listBehaviors(" "));
            assertEquals(0, t.calls.get());
        }

        @Test
        @DisplayName("iterBehaviors walks every page, fetching each only when asked")
        void iterWalksPagesLazily() {
            Transport t = Transport.serving(
                page(List.of(behaviorBody("bhv_1"), behaviorBody("bhv_2")), "c2"),
                page(List.of(), "c3"),
                page(List.of(behaviorBody("bhv_3")), null));
            var it = client(t).iterBehaviors(AGENT, "negative", null, null, null, 2).iterator();
            assertEquals("bhv_1", it.next().behaviorId());
            assertEquals(1, t.calls.get(), "the first item must not fetch page two");
            assertEquals("bhv_2", it.next().behaviorId());
            assertEquals(1, t.calls.get());
            assertEquals("bhv_3", it.next().behaviorId(), "an empty page with a cursor is skipped");
            assertEquals(3, t.calls.get());
            assertFalse(it.hasNext());
            assertEquals(3, t.calls.get(), "a null cursor ends the walk");

            assertEquals(null, queryOf(t.seen.get(0)).get("cursor"));
            assertEquals("c2", queryOf(t.seen.get(1)).get("cursor"));
            assertEquals("c3", queryOf(t.seen.get(2)).get("cursor"));
            for (Seen s : t.seen) {
                assertEquals("negative", queryOf(s).get("polarity"));
                assertEquals("2", queryOf(s).get("limit"));
            }
        }

        @Test
        void aShortCircuitNeverRequestsTheNextPage() {
            Transport t = Transport.serving(page(List.of(behaviorBody("bhv_1")), "c2"));
            client(t).iterBehaviors(AGENT).findFirst();
            assertEquals(1, t.calls.get());
        }

        @Test
        @DisplayName("there is no method that removes or amends a behavior")
        void noBehaviorMutators() {
            List<String> found = new ArrayList<>();
            for (var m : DMZAgentClient.class.getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("behavior") && !n.startsWith("list") && !n.startsWith("iter")) {
                    found.add(m.getName());
                }
            }
            assertTrue(found.isEmpty(), "unexpected conduct-record mutators: " + found);
        }
    }

    @Nested
    @DisplayName("one approval, by id (§2.13)")
    class GetApproval {

        @Test
        void readsOneApproval() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("approval_id", "apr_9");
            a.put("status", "approved");
            a.put("subject_id", AGENT);
            a.put("decision", Map.of("decision", "approve", "actor_id", "acct_1",
                                     "decided_at", "2026-10-07T15:05:00Z"));
            Transport t = Transport.serving(a);
            Approval got = client(t).getApproval("apr_9");
            assertEquals("GET", t.seen.get(0).method());
            assertEquals("/v1/approvals/apr_9", t.seen.get(0).path());
            assertEquals("approved", got.status());
            assertEquals("acct_1", got.decision().actorId());
            assertEquals("decline", got.onExpiry());
        }

        @Test
        @DisplayName("an unknown id is 404 → the base DMZAgentException")
        void notFoundIsTheBaseType() {
            Transport t = new Transport(404, Map.of("detail", "no such approval"));
            DMZAgentException e = assertThrows(DMZAgentException.class,
                () -> client(t).getApproval("apr_missing"));
            assertEquals(DMZAgentException.class, e.getClass(),
                "no dedicated not-found type: §2.13 defers one");
            assertEquals(404, e.statusCode());
        }

        @Test
        void aBlankIdIsRefusedBeforeItAddressesTheList() {
            for (String bad : new String[] { null, "", "  " }) {
                Transport t = Transport.serving(Map.of());
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> client(t).getApproval(bad));
                assertTrue(e.getMessage().contains("approvalId"));
                assertEquals(0, t.calls.get());
            }
        }
    }

    @Test
    @DisplayName("STEP_PHASES and DIRECTIVES are exposed as EventKinds is (§8.6)")
    void constants() {
        assertEquals(List.of("intent", "call", "result"), StepPhases.ALL);
        assertEquals(List.of("proceed", "warn", "hold", "block", "shutdown"), Directives.ALL);
        assertThrows(UnsupportedOperationException.class, () -> Directives.ALL.add("x"));
        assertEquals(4, EventKinds.ALL.size(), "a step is not an event kind");
    }
}
