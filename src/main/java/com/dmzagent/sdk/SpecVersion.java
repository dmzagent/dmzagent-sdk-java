package com.dmzagent.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The spec version this SDK implements, read from a build-filtered
 * resource rather than typed into a constant.
 *
 * <p>It was a literal, and it drifted: {@code DEFAULT_UA} in
 * {@link DMZAgentClient} read {@code dmzagent-java/0.6.0} while
 * {@code <dmzagent.spec.version>} in the pom had moved to 0.8.0, so the
 * User-Agent required by sdk-spec.md §1.4 misreported the version for
 * three releases. Nothing asserted the two agreed, so nothing caught it.
 * Deriving it from the pom property means there is one place to change.
 */
public final class SpecVersion {

    private static final String RESOURCE = "/dmzagent-sdk.properties";

    /** The pinned spec version, e.g. {@code "0.8.1"}. */
    public static final String VALUE = load();

    private SpecVersion() {}

    private static String load() {
        try (InputStream in = SpecVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    RESOURCE + " missing from the jar — resource filtering is not "
                    + "configured, so the spec version cannot be determined");
            }
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("spec.version");
            if (v == null || v.isBlank() || v.startsWith("${")) {
                // Unsubstituted placeholder means filtering silently did not
                // run. Failing loudly beats shipping "${dmzagent.spec.version}"
                // in a User-Agent header.
                throw new IllegalStateException(
                    "spec.version was not substituted at build time (got: " + v + ")");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
