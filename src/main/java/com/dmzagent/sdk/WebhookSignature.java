package com.dmzagent.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Verifier for DMZAgent outbound webhook signatures.
 *
 * <p>DMZAgent signs outbound webhooks with HMAC-SHA256 using the
 * subscription's secret. The signature is carried in a header of
 * the form:
 *
 * <pre>
 *   t=&lt;unix_seconds&gt;,v1=&lt;hex_hmac_sha256(secret, t + "." + payload)&gt;
 * </pre>
 *
 * <p>Per spec §9 the verifier MUST:
 * <ol>
 *   <li>Parse {@code t=<unix>,v1=<hex>} from the header.</li>
 *   <li>Reject if {@code t} is missing, non-numeric, or older than
 *       {@code toleranceSeconds}.</li>
 *   <li>Compute {@code hmac_sha256(secret, t + "." + payload)} and
 *       constant-time compare against {@code v1}.</li>
 *   <li>Return {@code true} only on match.</li>
 * </ol>
 *
 * <p>This SDK returns {@code false} for ALL failure modes (missing
 * fields, malformed timestamp, wrong secret, tolerance breach,
 * digest mismatch). It does NOT throw on bad input — that mirrors
 * the Python and TypeScript reference implementations and lets
 * callers wire signature failures into normal control flow without
 * a try/catch around every verification.
 *
 * <p>Constant-time comparison uses
 * {@link MessageDigest#isEqual(byte[], byte[])} per JDK docs:
 * implemented to take the same amount of time regardless of where
 * the byte arrays first differ.
 */
public final class WebhookSignature {

    private static final int DEFAULT_TOLERANCE_SECONDS = 300;

    private WebhookSignature() {}

    /**
     * Verify a webhook signature with a 300-second tolerance using
     * the current system time. Convenience overload.
     */
    public static boolean verify(String payload, String header, String secret) {
        return verify(payload, header, secret, DEFAULT_TOLERANCE_SECONDS, null);
    }

    /**
     * Verify a webhook signature with explicit tolerance, using the
     * current system time.
     */
    public static boolean verify(String payload, String header, String secret,
                                 int toleranceSeconds) {
        return verify(payload, header, secret, toleranceSeconds, null);
    }

    /**
     * Verify a webhook signature.
     *
     * @param payload          raw request body as a UTF-8 string.
     * @param header           the {@code DMZAgent-Signature} header value.
     * @param secret           the subscription secret (start with {@code whsec_}).
     * @param toleranceSeconds reject when {@code |now - t| > toleranceSeconds}.
     * @param nowUnix          unix-seconds "now" override; {@code null}
     *                         to use the system clock. Tests use this.
     * @return {@code true} only on full match; {@code false} on any
     *         failure (missing fields, malformed timestamp, wrong
     *         secret, tolerance breach, digest mismatch).
     */
    public static boolean verify(
        String payload,
        String header,
        String secret,
        int toleranceSeconds,
        Long nowUnix
    ) {
        if (header == null || header.isEmpty()) return false;
        if (payload == null) payload = "";
        if (secret == null || secret.isEmpty()) return false;

        // Parse "t=<unix>,v1=<hex>" — accept whitespace and other
        // k=v pairs gracefully. Spec only mandates "t" and "v1".
        String tStr = null;
        String v1   = null;
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if      ("t".equals(k))  tStr = v;
            else if ("v1".equals(k)) v1   = v;
        }
        if (tStr == null || v1 == null) return false;

        long t;
        try {
            t = Long.parseLong(tStr);
        } catch (NumberFormatException e) {
            return false;
        }

        long now = (nowUnix != null) ? nowUnix : Instant.now().getEpochSecond();
        if (Math.abs(now - t) > toleranceSeconds) return false;

        // Compute hmac_sha256(secret, "{t}.{payload}")
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(tStr.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(payload.getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal();
        } catch (Exception e) {
            // HmacSHA256 is mandatory on every JRE — getting here
            // means the runtime is broken, not the input. Fail closed.
            return false;
        }

        byte[] provided = hexDecode(v1);
        if (provided == null) return false;

        // Constant-time compare — MessageDigest.isEqual is documented
        // as taking equal time regardless of where the inputs differ.
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * Decode a hex string to bytes; return {@code null} on malformed
     * input. Accepts upper- and lower-case digits, no separators.
     */
    private static byte[] hexDecode(String s) {
        int len = s.length();
        if ((len & 1) != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i),     16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
