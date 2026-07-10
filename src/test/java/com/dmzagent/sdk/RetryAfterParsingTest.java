package com.dmzagent.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DMZAgentClient#parseRetryAfter} — the
 * {@code Retry-After} header parser behind
 * {@code DMZAgentRateLimitException.getRetryAfter()} (spec §3).
 *
 * <p>Only the delta-seconds form is recognized; anything else —
 * absent header, HTTP-date form, garbage, negatives — parses to
 * {@code null}. The SDK never sleeps or retries on the value.
 */
class RetryAfterParsingTest {

    @Test
    void present_deltaSeconds_parses() {
        assertThat(DMZAgentClient.parseRetryAfter("30")).isEqualTo(30);
    }

    @Test
    void zero_isValid() {
        assertThat(DMZAgentClient.parseRetryAfter("0")).isEqualTo(0);
    }

    @Test
    void surroundingWhitespace_isTolerated() {
        assertThat(DMZAgentClient.parseRetryAfter(" 120 ")).isEqualTo(120);
    }

    @Test
    void absent_returnsNull() {
        assertThat(DMZAgentClient.parseRetryAfter(null)).isNull();
    }

    @Test
    void empty_returnsNull() {
        assertThat(DMZAgentClient.parseRetryAfter("")).isNull();
    }

    @Test
    void garbage_returnsNull() {
        assertThat(DMZAgentClient.parseRetryAfter("soon-ish")).isNull();
    }

    @Test
    void httpDateForm_returnsNull() {
        // RFC 9110 allows an HTTP-date; the DMZAgent API never emits
        // it, and the spec says unparseable → null.
        assertThat(DMZAgentClient.parseRetryAfter(
            "Fri, 10 Jul 2026 12:00:00 GMT")).isNull();
    }

    @Test
    void negative_returnsNull() {
        assertThat(DMZAgentClient.parseRetryAfter("-5")).isNull();
    }

    @Test
    void fractionalSeconds_returnsNull() {
        // delta-seconds is an integer; "1.5" is not parseable as one.
        assertThat(DMZAgentClient.parseRetryAfter("1.5")).isNull();
    }
}
