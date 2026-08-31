package com.dmzagent.sdk;

import com.dmzagent.sdk.exceptions.DMZAgentRateLimitException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The circuit-breaker state cache (sdk-spec.md §4.4).
 *
 * <p>{@code check()} is a network round trip in front of a sensitive
 * action. The cache removes it for repeated checks on the same subject,
 * and the whole reason to be careful is that a cached {@code closed} is
 * an allow the server might no longer give.
 *
 * <p>The rules these tests hold:
 *
 * <ul>
 *   <li>off unless the caller sets a TTL — a caching safety check nobody
 *       asked for is worse than a slow one;
 *   <li>a served entry always says it was served, and how old it was, so
 *       a caller recording a denial can tell it read stale state;
 *   <li>one TTL for every state: holding a deny longer than an allow is a
 *       safety policy that belongs to whoever set the TTL;
 *   <li>{@code subject} and {@code interaction} with the same id are
 *       different keys;
 *   <li>the map is bounded, because the key is a subject id;
 *   <li>errors are never cached, and {@code LAST_KNOWN} is opt-in,
 *       marked, and unreachable without a TTL to fall back on.
 * </ul>
 */
final class CbStateCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "ck_test_cache";

    private static Map<String, Object> closedBody() {
        return Map.of(
            "state", "closed", "allow", true, "warning", false,
            "reason", "no policies fired", "fired_policies", List.of(),
            "checked_at", "2026-08-31T00:00:00Z",
            "latency_ms", 12.3, "route_latency_ms", 18.7);
    }

    private static Map<String, Object> openBody() {
        return Map.of(
            "state", "open", "allow", false, "warning", false,
            "reason", "policy fired",
            "fired_policies",
            List.of(Map.of("cb_policy_id", "p1", "name", "n", "action", "block")),
            "checked_at", "2026-08-31T00:00:00Z",
            "latency_ms", 9.1, "route_latency_ms", 11.0);
    }

    /** One scripted outcome per call: a body, an IOException, or a status. */
    private record Step(Map<String, Object> body, IOException failure, Integer status,
                        Map<String, String> headers) {
        static Step ok(Map<String, Object> b) { return new Step(b, null, null, Map.of()); }
        static Step boom() { return new Step(null, new IOException("down"), null, Map.of()); }
        static Step status(int s, Map<String, String> h) { return new Step(null, null, s, h); }
    }

    /** Counts calls and serves the scripted queue, never touching a socket. */
    private static final class Transport implements Interceptor {
        final AtomicInteger calls = new AtomicInteger();
        private final List<Step> steps;

        Transport(Step... s) {
            this.steps = new ArrayList<>(List.of(s));
            if (this.steps.isEmpty()) this.steps.add(Step.ok(closedBody()));
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            int n = calls.incrementAndGet();
            Step step = steps.get(Math.min(n - 1, steps.size() - 1));
            if (step.failure() != null) throw step.failure();
            Request req = chain.request();
            int code = step.status() != null ? step.status() : 200;
            Object payload = step.body() != null ? step.body() : Map.of("detail", "no");
            Response.Builder rb = new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 400 ? "Error" : "OK")
                .body(ResponseBody.create(
                    MAPPER.writeValueAsString(payload),
                    MediaType.get("application/json")));
            step.headers().forEach(rb::header);
            return rb.build();
        }
    }

    private static DMZAgentClient client(Transport t) {
        return new DMZAgentClient(KEY, null, null, null, t);
    }

    private static DMZAgentClient client(
            Transport t, Duration ttl, int max, CbCacheOnError onError) {
        return new DMZAgentClient(KEY, null, null, null, t, ttl, max, onError);
    }

    private static DMZAgentClient cached(Transport t, Duration ttl) {
        return client(t, ttl, CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.RAISE);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ===================================================================== //

    @Nested
    @DisplayName("the cache is off unless asked for")
    class OffByDefault {

        @Test
        void everyCheckIsARoundTripByDefault() {
            Transport t = new Transport();
            try (DMZAgentClient cx = client(t)) {
                for (int i = 0; i < 3; i++) cx.check("user:ws:a");
            }
            assertEquals(3, t.calls.get());
        }

        @Test
        void aDefaultClientReportsNoCachingOnItsResults() {
            try (DMZAgentClient cx = client(new Transport())) {
                CheckResult r = cx.check("user:ws:a");
                assertFalse(r.cached());
                assertEquals(Duration.ZERO, r.cacheAge());
                assertFalse(r.stale());
            }
        }

        @Test
        void freshIsHarmlessWithTheCacheOff() {
            Transport t = new Transport();
            try (DMZAgentClient cx = client(t)) {
                cx.check("user:ws:a", null, true);
            }
            assertEquals(1, t.calls.get());
        }
    }

    @Nested
    @DisplayName("serving from the cache")
    class Serving {

        @Test
        void aSecondCheckInsideTheTtlMakesNoRequest() {
            Transport t = new Transport();
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                cx.check("user:ws:a");
                cx.check("user:ws:a");
            }
            assertEquals(1, t.calls.get());
        }

        @Test
        void aServedEntrySaysSoAndCarriesItsAge() {
            try (DMZAgentClient cx = cached(new Transport(), Duration.ofSeconds(60))) {
                cx.check("user:ws:a");
                sleep(15);
                CheckResult second = cx.check("user:ws:a");
                assertTrue(second.cached());
                assertFalse(second.stale());
                assertTrue(second.cacheAge().toMillis() >= 5,
                    "the age has to be real, not a placeholder");
            }
        }

        @Test
        void theServersOwnNumbersAreNotRewritten() {
            try (DMZAgentClient cx = cached(new Transport(), Duration.ofSeconds(60))) {
                CheckResult fresh = cx.check("user:ws:a");
                CheckResult hit = cx.check("user:ws:a");
                assertEquals(fresh.latencyMs(), hit.latencyMs());
                assertEquals(fresh.routeLatencyMs(), hit.routeLatencyMs());
                assertEquals(fresh.checkedAt(), hit.checkedAt());
                assertEquals(fresh.raw(), hit.raw());
            }
        }

        @Test
        void theDecisionItselfSurvivesTheRoundTrip() {
            try (DMZAgentClient cx =
                     cached(new Transport(Step.ok(openBody())), Duration.ofSeconds(60))) {
                CheckResult first = cx.check("user:ws:a");
                CheckResult second = cx.check("user:ws:a");
                assertEquals(first.state(), second.state());
                assertFalse(second.allow());
                assertEquals(first.firedPolicies(), second.firedPolicies());
            }
        }

        @Test
        void anExpiredEntryIsNotServed() {
            Transport t = new Transport();
            try (DMZAgentClient cx = cached(t, Duration.ofMillis(30))) {
                cx.check("user:ws:a");
                sleep(80);
                assertFalse(cx.check("user:ws:a").cached());
            }
            assertEquals(2, t.calls.get());
        }

        @Test
        void freshBypassesTheCacheAndReplacesIt() {
            Transport t = new Transport(Step.ok(closedBody()), Step.ok(openBody()));
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                assertTrue(cx.check("user:ws:a").allow());
                CheckResult forced = cx.check("user:ws:a", null, true);
                assertFalse(forced.allow());
                assertFalse(forced.cached());
                assertFalse(cx.check("user:ws:a").allow(), "the refresh was stored");
            }
            assertEquals(2, t.calls.get());
        }

        @Test
        void guardPassesFreshThrough() {
            Transport t = new Transport();
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                cx.guard("user:ws:a").close();
                cx.guard("user:ws:a", null, false, true).close();
            }
            assertEquals(2, t.calls.get());
        }

        @Test
        void guardRaisesOnACachedOpenTheSameAsAFreshOne() {
            try (DMZAgentClient cx =
                     cached(new Transport(Step.ok(openBody())), Duration.ofSeconds(60))) {
                cx.check("user:ws:a");
                assertThrows(
                    com.dmzagent.sdk.exceptions.CircuitBreakerOpenException.class,
                    () -> cx.guard("user:ws:a", null, true));
            }
        }
    }

    @Nested
    @DisplayName("one ttl for every state")
    class OneTtl {

        @Test
        void anAllowExpiresOnTheSameSchedule() {
            assertExpiresTogether(closedBody());
        }

        @Test
        void aDenyExpiresOnTheSameSchedule() {
            // Holding a deny longer than an allow is a safety policy, and it
            // belongs to whoever set the TTL.
            assertExpiresTogether(openBody());
        }

        private void assertExpiresTogether(Map<String, Object> body) {
            Transport t = new Transport(Step.ok(body));
            try (DMZAgentClient cx = cached(t, Duration.ofMillis(30))) {
                cx.check("user:ws:a");
                assertTrue(cx.check("user:ws:a").cached());
                sleep(80);
                assertFalse(cx.check("user:ws:a").cached());
            }
            assertEquals(2, t.calls.get());
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        void subjectAndInteractionWithTheSameIdDoNotCollide() {
            Transport t = new Transport();
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                cx.check("x", null);
                cx.check(null, "x");
            }
            assertEquals(2, t.calls.get(),
                "a subject scope and an interaction scope are different questions");
        }

        @Test
        void aScopePrefixCannotBeConfusedWithASubjectId() {
            assertFalse(CBStateCache.key("subject", "a")
                .equals(CBStateCache.key("subjecta", "")));
        }

        @Test
        void differentSubjectsAreCachedSeparately() {
            Transport t = new Transport(Step.ok(closedBody()), Step.ok(openBody()));
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                assertTrue(cx.check("a").allow());
                assertFalse(cx.check("b").allow());
                assertTrue(cx.check("a").allow(), "b's deny did not overwrite a");
            }
        }
    }

    @Nested
    @DisplayName("the cache is bounded")
    class Bounded {

        @Test
        void leastRecentlyUsedIsEvicted() {
            CBStateCache cache = new CBStateCache(Duration.ofSeconds(60), 2);
            CheckResult r = CheckResult.fromResponse(closedBody());
            cache.put("a", r);
            cache.put("b", r);
            cache.get("a");                       // touch a, so b is now oldest
            cache.put("c", r);
            assertEquals(2, cache.size());
            assertNull(cache.get("b"));
            assertNotNull(cache.get("a"));
            assertNotNull(cache.get("c"));
        }

        @Test
        void aClientSeeingManySubjectsDoesNotGrowWithoutLimit() {
            // The client's cache is private, so the bound is asserted by its
            // only visible consequence: an evicted subject costs another
            // round trip.
            Transport t = new Transport();
            try (DMZAgentClient cx =
                     client(t, Duration.ofSeconds(60), 2, CbCacheOnError.RAISE)) {
                cx.check("a");
                cx.check("b");
                cx.check("c");
                assertEquals(3, t.calls.get());

                assertFalse(cx.check("a").cached(), "a was evicted when c arrived");
                assertEquals(4, t.calls.get());
                assertTrue(cx.check("c").cached());
                assertEquals(4, t.calls.get());
            }
        }

        @Test
        void aMaxBelowOneIsRefused() {
            assertThrows(IllegalArgumentException.class,
                () -> new CBStateCache(Duration.ofSeconds(60), 0));
        }
    }

    @Nested
    @DisplayName("when the check fails")
    class Failure {

        @Test
        void raiseIsTheDefaultAndMatchesAClientWithNoCache() {
            Transport t = new Transport(Step.ok(closedBody()), Step.boom());
            try (DMZAgentClient cx = cached(t, Duration.ofSeconds(60))) {
                cx.check("user:ws:a");
                assertThrows(DMZAgentServerException.class,
                    () -> cx.check("user:ws:a", null, true));
            }
        }

        @Test
        void lastKnownServesThePreviousStateMarkedStale() {
            Transport t = new Transport(Step.ok(openBody()), Step.boom());
            try (DMZAgentClient cx = client(t, Duration.ofSeconds(60),
                    CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN)) {
                cx.check("user:ws:a");
                CheckResult served = cx.check("user:ws:a", null, true);
                assertTrue(served.cached());
                assertTrue(served.stale());
                assertFalse(served.allow(),
                    "the last known state, not an optimistic default");
            }
        }

        @Test
        void lastKnownServesAnExpiredEntryToo() {
            Transport t = new Transport(Step.ok(openBody()), Step.boom());
            try (DMZAgentClient cx = client(t, Duration.ofMillis(30),
                    CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN)) {
                cx.check("user:ws:a");
                sleep(80);
                CheckResult served = cx.check("user:ws:a");
                assertTrue(served.stale());
                assertFalse(served.allow());
            }
        }

        @Test
        void lastKnownWithNothingKnownRaises() {
            // Never an invented state for a subject this client has never
            // successfully checked.
            Transport t = new Transport(Step.boom());
            try (DMZAgentClient cx = client(t, Duration.ofSeconds(60),
                    CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN)) {
                assertThrows(DMZAgentServerException.class, () -> cx.check("never-seen"));
            }
        }

        @Test
        void aFailureIsNeverItselfCached() {
            Transport t = new Transport(
                Step.ok(closedBody()), Step.boom(), Step.ok(openBody()));
            try (DMZAgentClient cx = client(t, Duration.ofSeconds(60),
                    CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN)) {
                cx.check("user:ws:a");
                cx.check("user:ws:a", null, true);            // fails, serves stale
                CheckResult third = cx.check("user:ws:a", null, true);  // recovers
                assertFalse(third.cached());
                assertFalse(third.allow());
                assertFalse(cx.check("user:ws:a").allow());
            }
        }

        @Test
        void aRateLimitIsAnAnswerAndIsNotMasked() {
            // 429 carries a retryAfter the caller can act on. Serving a
            // cached state instead would drop that signal.
            Transport t = new Transport(
                Step.ok(closedBody()),
                Step.status(429, Map.of("Retry-After", "30")));
            try (DMZAgentClient cx = client(t, Duration.ofSeconds(60),
                    CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN)) {
                cx.check("user:ws:a");
                assertThrows(DMZAgentRateLimitException.class,
                    () -> cx.check("user:ws:a", null, true));
            }
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void lastKnownWithoutATtlIsRefused() {
            // There is nothing to fall back TO. Accepting the pair would
            // leave someone believing they had an outage story that can
            // never fire.
            assertThrows(IllegalArgumentException.class,
                () -> new DMZAgentClient(KEY, null, null, null, new Transport(),
                    null, CBStateCache.DEFAULT_MAX_ENTRIES, CbCacheOnError.LAST_KNOWN));
        }

        @Test
        void aZeroTtlDisablesTheCacheRatherThanCachingForever() {
            Transport t = new Transport();
            try (DMZAgentClient cx = cached(t, Duration.ZERO)) {
                cx.check("user:ws:a");
                cx.check("user:ws:a");
            }
            assertEquals(2, t.calls.get());
        }
    }

    @Nested
    @DisplayName("thread safety (§4.2 — the client is shareable, so the cache must be)")
    class ThreadSafety {

        @Test
        void concurrentChecksOnOneClientDoNotCorruptTheCache() throws Exception {
            Transport t = new Transport();
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            try (DMZAgentClient cx =
                     client(t, Duration.ofSeconds(60), 16, CbCacheOnError.RAISE)) {
                List<Thread> threads = new ArrayList<>();
                for (int n = 0; n < 8; n++) {
                    final int base = n;
                    Thread th = new Thread(() -> {
                        try {
                            for (int i = 0; i < 40; i++) {
                                cx.check("user:ws:" + ((base + i) % 32));
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    });
                    threads.add(th);
                    th.start();
                }
                for (Thread th : threads) th.join();
            }
            assertTrue(errors.isEmpty(), () -> "threads failed: " + errors);
        }
    }
}
