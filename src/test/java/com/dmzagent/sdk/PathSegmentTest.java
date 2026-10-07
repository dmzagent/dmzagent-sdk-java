package com.dmzagent.sdk;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every id this SDK puts in a URL path goes in as one RFC 3986 segment
 * (spec §2.12): a space is {@code %20}, not the form encoder's {@code +},
 * which in a path is a literal plus and names a different resource. A
 * {@code .} or {@code ..} id is refused, because the URL parser resolves
 * dot-segments away — even percent-encoded.
 *
 * <p>The agent-mode paths and {@code decideApproval} are held in
 * {@link AgentModeTest} and {@link ApprovalsAndLedgerTest}; these are the
 * older call sites that form-encoded until 0.11.0.
 */
@DisplayName("ids in paths are RFC 3986 segments")
class PathSegmentTest {

    private static final String KEY = "ck_test_paths";
    private static final String RESERVED_ID = "a b+c/d:e";
    private static final String RESERVED_SEG = "a%20b%2Bc%2Fd:e";

    /** Records each request's encoded path; answers with {@code body}. */
    private static final class Paths implements Interceptor {
        final List<String> seen = new ArrayList<>();
        private final String body;

        Paths(String body) { this.body = body; }

        @Override
        public Response intercept(Chain chain) {
            Request req = chain.request();
            seen.add(req.method() + " " + req.url().encodedPath());
            return new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
        }
    }

    private static DMZAgentClient client(Paths p) {
        return new DMZAgentClient(KEY, null, null, null, p);
    }

    @Test
    void awaitOutcomeFrameId() {
        Paths p = new Paths("{\"frame_id\":\"x\",\"outcome\":\"applied\","
            + "\"reasoning\":[],\"summary\":{\"complete\":true}}");
        DMZAgentClient cx = client(p);
        cx.awaitOutcome(RESERVED_ID, 5.0);
        assertEquals(List.of("GET /v1/frames/" + RESERVED_SEG + "/story"), p.seen);
        for (String dots : new String[] { ".", ".." }) {
            assertThrows(IllegalArgumentException.class, () -> cx.awaitOutcome(dots, 5.0));
        }
        assertEquals(1, p.seen.size());
    }

    @Test
    void getDivisionConfigId() {
        Paths p = new Paths("{\"division_id\":\"x\",\"config\":{}}");
        DMZAgentClient cx = client(p);
        cx.getDivisionConfig(RESERVED_ID);
        assertEquals(List.of("GET /v1/divisions/" + RESERVED_SEG + "/config"), p.seen);
        for (String dots : new String[] { ".", ".." }) {
            assertThrows(IllegalArgumentException.class, () -> cx.getDivisionConfig(dots));
        }
        assertEquals(1, p.seen.size());
    }

    @Test
    void updateDivisionConfigId() {
        Paths p = new Paths("{\"division_id\":\"x\",\"config\":{}}");
        DMZAgentClient cx = client(p);
        cx.updateDivisionConfig(RESERVED_ID, Map.of("reasoning_mode", "trace"));
        assertEquals(List.of("PUT /v1/divisions/" + RESERVED_SEG + "/config"), p.seen);
        for (String dots : new String[] { ".", ".." }) {
            assertThrows(IllegalArgumentException.class,
                () -> cx.updateDivisionConfig(dots, Map.of()));
        }
        assertEquals(1, p.seen.size());
    }
}
