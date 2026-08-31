package com.dmzagent.sdk;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process circuit-breaker state cache (sdk-spec.md §4.4).
 *
 * <p>{@code check()} is a network round trip on a path callers put in
 * front of a sensitive action, and it is often the only synchronous
 * DMZAgent call in a request. This removes that round trip for repeated
 * checks on the same subject.
 *
 * <p><b>What the caller is choosing.</b> A cached {@code closed} is an
 * allow the server might no longer give. <b>The TTL is the maximum time a
 * newly-opened breaker can go unobserved by this client.</b> Nothing here
 * softens that, and every served entry carries its age so the caller can
 * see it.
 *
 * <p>One TTL applies to every state. Holding a deny longer than an allow
 * is a safety policy and it belongs to whoever set the TTL, not to this
 * class.
 *
 * <p>The clock is {@link System#nanoTime()}: a TTL measured against the
 * wall clock would expire early or late whenever the host's time is
 * adjusted, and the adjustment is invisible to the caller.
 *
 * <p>Thread-safe by a single monitor, because the client it belongs to is
 * documented shareable across threads (spec §4.2).
 */
final class CBStateCache {

    /** Off at zero. Callers treat non-positive as "the caller never asked
     *  for a cache" and must not substitute a default. */
    static final Duration DISABLED_TTL = Duration.ZERO;

    static final int DEFAULT_MAX_ENTRIES = 1024;

    /** A cached result and the age at which it was read. */
    record Hit(CheckResult result, Duration age) {}

    private record Stored(CheckResult result, long storedAtNanos) {}

    private final long ttlNanos;
    private final int  maxEntries;

    /**
     * {@code accessOrder = true} makes this a true LRU: a {@code get}
     * moves the key to the end, and {@code removeEldestEntry} drops the
     * front. Bounded because the key is a subject id — an agent that sees
     * a hundred thousand subjects would otherwise hold a hundred thousand
     * entries for the life of the process.
     */
    private final LinkedHashMap<String, Stored> entries;

    CBStateCache(Duration ttl, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("cbCacheMaxEntries must be at least 1");
        }
        this.ttlNanos = (ttl == null || ttl.isNegative() || ttl.isZero())
            ? 0L
            : ttl.toNanos();
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
                return size() > CBStateCache.this.maxEntries;
            }
        };
    }

    static String key(String scope, String scopeRef) {
        // Separated by NUL, which cannot occur inside a scope name or a
        // subject id, so `subject` + "a" can never collide with
        // `subjecta` + "".
        return scope + '\0' + scopeRef;
    }

    boolean enabled() {
        return ttlNanos > 0L;
    }

    /**
     * A live entry, or {@code null}.
     *
     * <p>An expired entry is left in place rather than dropped — the
     * {@link CbCacheOnError#LAST_KNOWN} policy is the reason it is still
     * worth something after the TTL. Eviction is by size, never by age.
     */
    Hit get(String cacheKey) {
        Hit hit = lookup(cacheKey);
        if (hit == null) return null;
        return hit.age().toNanos() <= ttlNanos ? hit : null;
    }

    /** A live OR expired entry. Only {@link CbCacheOnError#LAST_KNOWN}
     *  may use this, and only after a failed check. */
    Hit getAny(String cacheKey) {
        return lookup(cacheKey);
    }

    /**
     * Store a SUCCESSFUL check. Errors are never cached: a failure is not
     * a state, and serving one back would turn one bad round trip into a
     * TTL's worth of them.
     */
    void put(String cacheKey, CheckResult result) {
        if (!enabled()) return;
        synchronized (entries) {
            entries.put(cacheKey, new Stored(result, System.nanoTime()));
        }
    }

    void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private Hit lookup(String cacheKey) {
        if (!enabled()) return null;
        Stored entry;
        long now;
        synchronized (entries) {
            entry = entries.get(cacheKey);   // accessOrder: this is the LRU touch
            now = System.nanoTime();
        }
        if (entry == null) return null;
        long ageNanos = Math.max(0L, now - entry.storedAtNanos());
        return new Hit(entry.result(), Duration.ofNanos(ageNanos));
    }
}
