package com.dmzagent.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dmzagent.sdk.exceptions.DMZAgentServerException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 0.8.1: {@code awaitOutcome()} against the division-scoped story
 * endpoint.
 *
 * <p>Mirrors {@code contract-tests/outcome-vectors.json}. Kept here as well
 * because the request-shape assertion — that no {@code workspace_id} is
 * sent, on every request and not merely the first — is the whole point of
 * the fix.
 *
 * <p>What was wrong at 0.8.0, specifically in this SDK: the loop returned
 * the first response that parsed. The story endpoint answers 200 throughout
 * the fan-out, handing back traces as each workspace finishes, so that is a
 * half-finished story. (It also sent no {@code workspace_id}, which the
 * endpoint then required, so in practice the first request 422'd.)
 */
class AwaitOutcomeTest {

    private static final String API_KEY = "ck_test_xxxxxxxxxxxxxxxxxxxxx";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> trace(String id, String ws, String outcome) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("trace_id", id);
        t.put("workspace_id", ws);
        t.put("outcome", outcome);
        return t;
    }

    private static final List<Map<String, Object>> BOTH_TRACES = List.of(
        trace("trace_1", "ws_1", "applied"),
        trace("trace_2", "ws_2", "no_change"));

    /** A story page. {@code outcome == null} omits the key entirely. */
    private static Map<String, Object> story(
            boolean complete, String outcome, List<Map<String, Object>> traces) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("trace_count", traces.size());
        summary.put("workspace_count", 2);
        summary.put("complete", complete);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("frame_id", "frame_abc");
        body.put("subject_id", "subject:dv_test:acme-bot");
        body.put("division_id", "dv_test");
        body.put("workspace_id", null);
        body.put("workspace_ids", List.of("ws_1", "ws_2"));
        if (outcome != null) body.put("outcome", outcome);
        body.put("reasoning", traces);
        body.put("summary", summary);
        return body;
    }

    /** Serves {@code pages} in order — the last repeats — recording each URL. */
    private static final class Serve {
        final List<String> urls = new ArrayList<>();
        private final List<Map<String, Object>> pages;
        private final AtomicInteger i = new AtomicInteger();

        Serve(List<Map<String, Object>> pages) { this.pages = pages; }

        Interceptor interceptor() {
            return chain -> {
                Request req = chain.request();
                urls.add(req.url().toString());
                int idx = Math.min(i.getAndIncrement(), pages.size() - 1);
                return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(
                        MAPPER.writeValueAsString(pages.get(idx)),
                        MediaType.get("application/json")))
                    .build();
            };
        }
    }

    private static DMZAgentClient client(Serve serve) {
        return new DMZAgentClient(API_KEY, "https://api.test", Duration.ofSeconds(5),
                                  null, serve.interceptor());
    }

    // ------------------------------------------------------------------ //
    // request shape
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("sends no workspace_id — the endpoint is division-scoped")
    void sendsNoWorkspaceId() {
        Serve serve = new Serve(List.of(story(true, "applied", BOTH_TRACES)));
        try (DMZAgentClient cx = client(serve)) {
            cx.awaitOutcome("frame_abc", 5.0);
        }
        assertFalse(serve.urls.isEmpty(), "no request was made");
        for (String u : serve.urls) {
            assertFalse(u.contains("workspace_id"), "sent workspace_id: " + u);
        }
        assertTrue(serve.urls.get(0).contains("/v1/frames/frame_abc/story"));
    }

    // ------------------------------------------------------------------ //
    // termination
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("returns as soon as the story is complete")
    void returnsOnceComplete() {
        Serve serve = new Serve(List.of(story(true, "applied", BOTH_TRACES)));
        try (DMZAgentClient cx = client(serve)) {
            OutcomeResult r = cx.awaitOutcome("frame_abc", 5.0);
            assertTrue(r.complete());
        }
        assertEquals(1, serve.urls.size());
    }

    @Test
    @DisplayName("keeps polling while the fan-out is outstanding")
    void keepsPollingWhileIncomplete() {
        // The regression: this used to return the first page.
        Serve serve = new Serve(List.of(
            story(false, "applied", List.of(trace("trace_1", "ws_1", "applied"))),
            story(true, "applied", BOTH_TRACES)));
        try (DMZAgentClient cx = client(serve)) {
            OutcomeResult r = cx.awaitOutcome("frame_abc", 5.0);
            assertAll(
                () -> assertEquals(2, serve.urls.size()),
                () -> assertTrue(r.complete()),
                () -> assertEquals(2, r.reasoning().size()));
        }
    }

    @Test
    @DisplayName("times out rather than returning a partial story")
    void timesOutRatherThanReturningPartial() {
        Serve serve = new Serve(List.of(
            story(false, "applied", List.of(trace("trace_1", "ws_1", "applied")))));
        try (DMZAgentClient cx = client(serve)) {
            DMZAgentServerException e = assertThrows(
                DMZAgentServerException.class, () -> cx.awaitOutcome("frame_abc", 0.5));
            assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    // result shape
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("reports the server's fold verbatim, not a recomputed one")
    void reportsServerFoldVerbatim() {
        // An SDK recomputing `outcome` from reasoning[] — say by taking the
        // first trace — would report "applied" here instead of "failed".
        Serve serve = new Serve(List.of(story(true, "failed", List.of(
            trace("trace_1", "ws_1", "applied"),
            trace("trace_2", "ws_2", "failed")))));
        try (DMZAgentClient cx = client(serve)) {
            assertEquals("failed", cx.awaitOutcome("frame_abc", 5.0).outcome());
        }
    }

    @Test
    @DisplayName("carries per-trace workspace ids and the scope fields")
    void carriesWorkspaceAttribution() {
        Serve serve = new Serve(List.of(story(true, "applied", BOTH_TRACES)));
        try (DMZAgentClient cx = client(serve)) {
            OutcomeResult r = cx.awaitOutcome("frame_abc", 5.0);
            assertAll(
                () -> assertEquals("dv_test", r.divisionId()),
                () -> assertEquals(List.of("ws_1", "ws_2"), r.workspaceIds()),
                () -> assertEquals("ws_1", r.reasoning().get(0).get("workspace_id")),
                () -> assertEquals("ws_2", r.reasoning().get(1).get("workspace_id")));
        }
    }

    @Test
    @DisplayName("held is carried through — it joined the enum in 0.8.1")
    void heldIsCarriedThrough() {
        Serve serve = new Serve(List.of(story(true, "held",
            List.of(trace("trace_1", "ws_1", "held")))));
        try (DMZAgentClient cx = client(serve)) {
            assertEquals("held", cx.awaitOutcome("frame_abc", 5.0).outcome());
        }
    }

    @Test
    @DisplayName("a missing outcome is null, not no_change")
    void missingOutcomeIsNull() {
        // It defaulted to "no_change", reporting a clean result for a frame
        // nothing had reasoned over. A missing value is not a benign one.
        Serve serve = new Serve(List.of(story(true, null, BOTH_TRACES)));
        try (DMZAgentClient cx = client(serve)) {
            assertNull(cx.awaitOutcome("frame_abc", 5.0).outcome());
        }
    }

    // ------------------------------------------------------------------ //
    // §1.4 User-Agent
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("User-Agent carries the pinned spec version")
    void userAgentCarriesSpecVersion() {
        // This drifted silently for three releases: DEFAULT_UA read
        // dmzagent-java/0.6.0 while the pom pinned 0.8.0, and nothing
        // compared them. Now derived from the pom property, and asserted.
        final List<String> uas = new ArrayList<>();
        Interceptor capture = chain -> {
            Request req = chain.request();
            uas.add(req.header("User-Agent"));
            return new Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(
                    MAPPER.writeValueAsString(story(true, "applied", BOTH_TRACES)),
                    MediaType.get("application/json")))
                .build();
        };
        try (DMZAgentClient cx = new DMZAgentClient(
                API_KEY, "https://api.test", Duration.ofSeconds(5), null, capture)) {
            cx.awaitOutcome("frame_abc", 5.0);
        }
        assertEquals("dmzagent-java/" + SpecVersion.VALUE, uas.get(0));
        assertFalse(SpecVersion.VALUE.startsWith("${"),
                    "resource filtering did not substitute the version");
    }
}
