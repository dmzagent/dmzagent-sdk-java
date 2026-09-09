package com.dmzagent.sdk;

import com.dmzagent.sdk.exceptions.DMZAgentConflictException;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The white-label approval control and the readable ledger (spec §2.8–§2.10).
 *
 * <p>What these hold, and why each is here rather than an assertion that
 * merely passes:
 *
 * <ul>
 *   <li>{@code actorId} is refused locally and before any round trip. The
 *       point of a human-in-the-loop control is that a person is on the other
 *       end, and an approval whose actor is the integration that requested it
 *       records nobody. The test asserts <em>no request was made</em>, because
 *       a server-side rejection would also throw and would tell us nothing
 *       about where the check lives.
 *   <li>A settled or expired approval is a conflict, not a retry. The call did
 *       not fail, it lost.
 *   <li>Expiry declines, and {@code onExpiry} cannot be talked into anything
 *       else by a server that sends something else.
 *   <li>Neither list method follows a cursor on its own; the streams do, and
 *       only when the consumer asks for the next item.
 *   <li>There is no method that closes an incident, because the ledger has no
 *       endpoint for one.
 * </ul>
 */
@DisplayName("approvals and the incident ledger (spec §2.8–§2.10)")
class ApprovalsAndLedgerTest {

    private static final String KEY = "ck_test_approvals";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //: How many requests past the last scripted body the stub tolerates
    //: before it calls the walk unbounded. Only a client that keeps
    //: following a cursor nobody advanced ever reaches it.
    private static final int RUNAWAY_AFTER = 5;

    private static Map<String, Object> approvalBody(String status) {
        return approvalBody(status, Map.of());
    }

    private static Map<String, Object> approvalBody(
            String status, Map<String, Object> over) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approval_id", "apr_7f3c9a1b");
        d.put("status", status);
        d.put("subject_id", "subject:dv:checkout-bot");
        d.put("interaction_id", "ix_2b8e");
        d.put("frame_id", "fr_91ac");
        d.put("action", Map.of("tool", "refund.issue",
                               "args", Map.of("amount_cents", 9900)));
        d.put("reason", "refund above the reviewed ceiling");
        d.put("fired_policies", List.of(Map.of(
            "cb_policy_id", "cbp_11", "name", "refund ceiling",
            "action", "require_approval")));
        d.put("requested_at", "2026-09-09T12:00:00Z");
        d.put("expires_at", "2026-09-09T12:15:00Z");
        d.put("on_expiry", "decline");
        d.put("anchor", Map.of("ledger_index", 40197, "hash", "b1c4"));
        d.put("decision", null);
        d.putAll(over);
        return d;
    }

    private static Map<String, Object> incidentBody(Map<String, Object> over) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("incident_id", "inc_5d2a70");
        d.put("status", "remediated");
        d.put("kind", "cb_open");
        d.put("subject_id", "subject:dv:checkout-bot");
        d.put("frame_id", "fr_91ac");
        d.put("opened_at", "2026-09-09T11:58:02Z");
        d.put("closed_at", "2026-09-09T12:04:31Z");
        d.put("reason", "refund above the reviewed ceiling");
        d.put("fired_policies", List.of());
        Map<String, Object> rem = new LinkedHashMap<>();
        rem.put("remediation_id", "rem_88fe");
        rem.put("kind", "approval");
        rem.put("approval_id", "apr_7f3c9a1b");
        rem.put("outcome", "approved");
        rem.put("actor_id", "acct_4471");
        rem.put("reason", "verified by phone");
        rem.put("occurred_at", "2026-09-09T12:04:31Z");
        rem.put("anchor", Map.of("ledger_index", 40202, "hash", "9ee0"));
        d.put("remediations", List.of(rem));
        d.put("anchor", Map.of("ledger_index", 40197, "hash", "b1c4"));
        d.putAll(over);
        return d;
    }

    /** One captured request: what the SDK actually put on the wire. */
    private record Seen(String method, String path, String query, String body) { }

    /**
     * Serves each scripted body in turn, repeating the last — then refuses.
     *
     * <p>The repeat matters: a page carrying {@code next_cursor} is served
     * again and again, which is exactly what a client that auto-paginates
     * keeps asking for. Left unbounded, the stub would let that client
     * <em>hang</em>, and a hang is not a failing assertion — it is a build
     * timeout with no test named. So the stub stops, and the unbounded walk
     * becomes a named failure in the test that provoked it.
     */
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
                    + " scripted page(s) — the caller asked for one page and the "
                    + "SDK kept following the cursor");
            }
            Request req = chain.request();
            String sent = "";
            if (req.body() != null) {
                okio.Buffer buf = new okio.Buffer();
                req.body().writeTo(buf);
                sent = buf.readUtf8();
            }
            seen.add(new Seen(req.method(), req.url().encodedPath(),
                              req.url().query(), sent));
            Object payload = bodies.get(Math.min(n - 1, bodies.size() - 1));
            return new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 400 ? "Error" : "OK")
                .body(ResponseBody.create(
                    MAPPER.writeValueAsString(payload),
                    MediaType.get("application/json")))
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

    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("a denial that names an approval is an ask, not a refusal")
    class TheDistinction {

        @Test
        void awaitingApprovalWhenTheServerNamesOne() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("state", "open");
            d.put("allow", false);
            d.put("warning", false);
            d.put("reason", "refund above the reviewed ceiling");
            d.put("pending_approval_id", "apr_7f3c9a1b");
            CheckResult r = CheckResult.fromResponse(d);
            assertFalse(r.allow());
            assertTrue(r.awaitingApproval());
            assertEquals("apr_7f3c9a1b", r.pendingApprovalId());
        }

        @Test
        void aPlainDenialIsNotAwaitingAnything() {
            CheckResult r = CheckResult.fromResponse(
                Map.of("state", "open", "allow", false, "warning", false));
            assertFalse(r.allow());
            assertFalse(r.awaitingApproval());
            assertNull(r.pendingApprovalId());
        }

        @Test
        @DisplayName("an older response without the field still refuses")
        void additiveOnPurpose() {
            // A client reading `allow` alone must not start allowing what it
            // used to deny.
            CheckResult r = CheckResult.fromResponse(Map.of(
                "state", "open", "allow", false, "warning", false,
                "reason", "policy fired"));
            assertFalse(r.allow());
            assertNull(r.pendingApprovalId());
        }
    }

    @Nested
    @DisplayName("a decision records a human, or it does not happen")
    class TheDecision {

        @Test
        void refusesADecisionWithNoHumanBeforeAnyRequest() {
            for (String actor : new String[] { null, "", "   " }) {
                Transport t = Transport.serving(approvalBody("approved"));
                DMZAgentClient cx = client(t);
                IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> cx.decideApproval("apr_7f3c9a1b", "approve", actor, null, null));
                assertTrue(e.getMessage().contains("actor"), e.getMessage());
                // The assertion that matters. A server-side rejection would
                // throw too, and would not tell us the check is where the
                // mistake is.
                assertEquals(0, t.calls.get(),
                    "a decision with no human must not reach the wire");
            }
        }

        @Test
        void refusesAnUnknownDecisionBeforeAnyRequest() {
            Transport t = Transport.serving(approvalBody("approved"));
            DMZAgentClient cx = client(t);
            IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> cx.decideApproval("apr_7f3c9a1b", "maybe", "acct_1", null, null));
            assertTrue(e.getMessage().contains("decision"));
            assertEquals(0, t.calls.get());
        }

        @Test
        void sendsTheActorAndReturnsTheDecision() throws Exception {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("decision", "approve");
            decision.put("actor_id", "acct_4471");
            decision.put("actor_label", "Dana R.");
            decision.put("reason", "verified the order by phone");
            decision.put("decided_at", "2026-09-09T12:04:31Z");
            Transport t = Transport.serving(
                approvalBody("approved", Map.of("decision", decision)));

            Approval a = client(t).decideApproval(
                "apr_7f3c9a1b", "approve", "acct_4471", "Dana R.",
                "verified the order by phone");

            assertEquals(1, t.seen.size());
            Seen s = t.seen.get(0);
            assertEquals("POST", s.method());
            assertEquals("/v1/approvals/apr_7f3c9a1b/decision", s.path());
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = MAPPER.readValue(s.body(), Map.class);
            assertEquals(Map.of(
                "decision", "approve", "actor_id", "acct_4471",
                "actor_label", "Dana R.", "reason", "verified the order by phone"),
                sent);
            assertEquals("approved", a.status());
            assertNotNull(a.decision());
            assertEquals("acct_4471", a.decision().actorId());
            assertFalse(a.isPending());
        }

        @Test
        void approveAndDeclineSendTheirOwnVerb() throws Exception {
            Transport t = Transport.serving(
                approvalBody("approved"), approvalBody("declined"));
            DMZAgentClient cx = client(t);
            cx.approveApproval("apr_1", "acct_1");
            cx.declineApproval("apr_2", "acct_2");
            List<String> verbs = new ArrayList<>();
            for (Seen s : t.seen) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = MAPPER.readValue(s.body(), Map.class);
                verbs.add((String) m.get("decision"));
            }
            assertEquals(List.of("approve", "decline"), verbs);
        }

        @Test
        void theWrappersStillRequireAHuman() {
            Transport t = Transport.serving(approvalBody("approved"));
            DMZAgentClient cx = client(t);
            assertThrows(IllegalArgumentException.class,
                () -> cx.approveApproval("apr_1", ""));
            assertThrows(IllegalArgumentException.class,
                () -> cx.declineApproval("apr_1", ""));
            assertEquals(0, t.calls.get());
        }
    }

    @Nested
    @DisplayName("a settled approval is lost, not failed")
    class Settled {

        @Test
        void aSecondDecisionIsAConflictNamingItsState() {
            for (String settled : new String[] { "approved", "declined", "expired" }) {
                Transport t = new Transport(409,
                    Map.of("detail", "already settled", "status", settled));
                DMZAgentConflictException e = assertThrows(
                    DMZAgentConflictException.class,
                    () -> client(t).decideApproval(
                        "apr_7f3c9a1b", "approve", "acct_9002", null, null));
                assertTrue(e.getMessage().contains(settled), e.getMessage());
                assertEquals(409, e.statusCode());
            }
        }

        @Test
        @DisplayName("a 409 without an approval status still reads as the idempotency conflict")
        void theOther409() {
            // Same type, and the message must not assert the wrong cause.
            Transport t = new Transport(409, Map.of("detail", "in flight"));
            DMZAgentConflictException e = assertThrows(
                DMZAgentConflictException.class,
                () -> client(t).decideApproval(
                    "apr_7f3c9a1b", "approve", "acct_4471", null, null));
            assertTrue(e.getMessage().contains("Idempotency-Key"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("expiry fails closed")
    class Expiry {

        @Test
        void onExpiryIsDeclineEvenIfTheServerSaysOtherwise() {
            // An approval that becomes an allow because nobody looked at it
            // is not a human-in-the-loop control. There is no path — server
            // -sent or otherwise — by which this SDK reports one.
            Approval a = Approval.fromResponse(
                approvalBody("pending", Map.of("on_expiry", "approve")));
            assertEquals("decline", a.onExpiry());
        }

        @Test
        void anExpiredApprovalCarriesNoDecision() {
            Approval a = Approval.fromResponse(approvalBody("expired"));
            assertNull(a.decision());
            assertFalse(a.isPending());
        }
    }

    @Nested
    @DisplayName("paging: bounded by default, lazy on request")
    class Paging {

        @Test
        void listApprovalsDefaultsToPendingAndDoesNotFollowTheCursor() {
            Transport t = Transport.serving(Map.of(
                "approvals", List.of(approvalBody("pending")), "next_cursor", "c2"));
            ApprovalPage page = client(t).listApprovals();
            assertEquals(1, t.calls.get(), "one page means one request");
            assertEquals("pending", queryOf(t.seen.get(0)).get("status"));
            assertEquals("c2", page.nextCursor());
            assertEquals(1, page.size());
        }

        @Test
        void listApprovalsPassesEveryFilter() {
            Transport t = Transport.serving(Map.of("approvals", List.of()));
            client(t).listApprovals("approved", "subject:dv:bot", 50, "eyJpIjo0MH0");
            assertEquals(Map.of(
                "status", "approved", "subject_id", "subject:dv:bot",
                "limit", "50", "cursor", "eyJpIjo0MH0"),
                queryOf(t.seen.get(0)));
        }

        @Test
        void aPageLimitTheServerWouldRejectIsRefusedBeforeTheRoundTrip() {
            for (int bad : new int[] { 0, -1, 101, 1000 }) {
                Transport t = Transport.serving(Map.of("approvals", List.of()));
                IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> client(t).listApprovals(null, null, bad, null));
                assertTrue(e.getMessage().contains("limit"));
                assertEquals(0, t.calls.get());
            }
        }

        @Test
        @DisplayName("iterApprovals fetches a page only when asked past the one it holds")
        void iterIsLazy() {
            Transport t = Transport.serving(
                Map.of("approvals",
                       List.of(approvalBody("pending"), approvalBody("pending")),
                       "next_cursor", "c2"),
                Map.of("approvals", List.of(approvalBody("pending"))));
            var it = client(t).iterApprovals().iterator();

            it.next();
            assertEquals(1, t.calls.get(),
                "the first item must not have fetched page two");
            it.next();
            assertEquals(1, t.calls.get());
            it.next();                     // exhausts page one, fetches page two
            assertEquals(2, t.calls.get());
            assertFalse(it.hasNext());
            assertEquals(2, t.calls.get(), "a null cursor must end the walk");
        }

        @Test
        void aShortCircuitNeverRequestsTheNextPage() {
            Transport t = Transport.serving(Map.of(
                "approvals", List.of(approvalBody("pending")), "next_cursor", "c2"));
            client(t).iterApprovals().findFirst();
            assertEquals(1, t.calls.get());
        }
    }

    @Nested
    @DisplayName("the ledger")
    class Ledger {

        @Test
        void getIncidentsDefaultsToAllAndParsesRemediations() {
            // Map.of rejects a null value, and a null next_cursor is the
            // shape the last page actually has.
            Map<String, Object> lastPage = new LinkedHashMap<>();
            lastPage.put("incidents", List.of(incidentBody(Map.of())));
            lastPage.put("next_cursor", null);
            Transport t = Transport.serving(lastPage);
            IncidentPage page = client(t).getIncidents();
            assertEquals("all", queryOf(t.seen.get(0)).get("status"));
            Incident inc = page.incidents().get(0);
            assertEquals("remediated", inc.status());
            assertFalse(inc.isOpen());
            assertEquals(1, inc.remediations().size());
            Remediation r = inc.remediations().get(0);
            assertEquals("approval", r.kind());
            assertEquals("apr_7f3c9a1b", r.approvalId());
            assertEquals(Map.of("ledger_index", 40202, "hash", "9ee0"), r.anchor());
        }

        @Test
        void getIncidentsPassesTheWholeWindow() {
            Transport t = Transport.serving(Map.of("incidents", List.of()));
            client(t).getIncidents("open", "subject:dv:bot",
                "2026-09-01T00:00:00Z", "2026-09-09T00:00:00Z", 100, null);
            assertEquals(Map.of(
                "status", "open", "subject_id", "subject:dv:bot",
                "since", "2026-09-01T00:00:00Z", "until", "2026-09-09T00:00:00Z",
                "limit", "100"),
                queryOf(t.seen.get(0)));
        }

        @Test
        @DisplayName("an unanswered incident is an incident with no remediations")
        void unanswered() {
            // Not an error, not an empty result, and not collapsed to null.
            Map<String, Object> over = new LinkedHashMap<>();
            over.put("status", "open");
            over.put("closed_at", null);
            over.put("remediations", List.of());
            Transport t = Transport.serving(
                Map.of("incidents", List.of(incidentBody(over))));
            Incident inc = client(t).getIncidents(
                "open", null, null, null, null, null).incidents().get(0);
            assertTrue(inc.isOpen());
            assertTrue(inc.remediations().isEmpty());
            assertNull(inc.closedAt());
        }

        @Test
        @DisplayName("the incident anchor is the one the check handed back")
        void anchorsCompare() {
            // The whole point of making the ledger readable.
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("state", "open");
            checked.put("allow", false);
            checked.put("warning", false);
            checked.put("reason", "ceiling");
            checked.put("anchor", Map.of("ledger_index", 40197, "hash", "b1c4"));
            CheckResult r = CheckResult.fromResponse(checked);

            Transport t = Transport.serving(
                Map.of("incidents", List.of(incidentBody(Map.of()))));
            Incident inc = client(t).getIncidents().incidents().get(0);
            assertEquals(r.anchor(), inc.anchor());
        }

        @Test
        void iterIncidentsWalksPagesLazily() {
            Transport t = Transport.serving(
                Map.of("incidents", List.of(incidentBody(Map.of())),
                       "next_cursor", "c2"),
                Map.of("incidents", List.of(incidentBody(Map.of()))));
            List<Incident> got = client(t).iterIncidents().collect(Collectors.toList());
            assertEquals(2, got.size());
            assertEquals(2, t.calls.get());
        }

        @Test
        @DisplayName("the ledger is append-only in the surface too")
        void noCloseIncident() {
            // A convenience that reads as closing an incident would describe
            // a ledger this is not — there is no endpoint behind one (§5.21).
            List<String> found = new ArrayList<>();
            for (var m : DMZAgentClient.class.getMethods()) {
                String n = m.getName();
                if (n.equals("closeIncident") || n.equals("resolveIncident")
                    || n.equals("deleteIncident") || n.equals("updateIncident")) {
                    found.add(n);
                }
            }
            assertTrue(found.isEmpty(), "unexpected ledger mutators: " + found);
        }
    }
}
