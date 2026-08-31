package com.dmzagent.sdk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pinned spec version reaches the wire.
 *
 * <p>{@link SpecVersion} already removes the drift at the source: the
 * value is filtered from {@code <dmzagent.spec.version>} at build time,
 * so there is one copy rather than a constant free to wander from the
 * pom. What nothing asserted is the last hop — that the User-Agent a
 * server actually receives carries that value. The three releases of
 * {@code dmzagent-java/0.6.0} sent against a 0.8.x spec were visible
 * only there.
 */
final class VersionMarkerTest {

    private static final Pattern PINNED =
        Pattern.compile("<dmzagent\\.spec\\.version>([^<]+)</dmzagent\\.spec\\.version>");

    private static String pinnedSpecVersion() throws IOException {
        // Surefire runs with the project root as the working directory.
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher m = PINNED.matcher(pom);
        assertTrue(m.find(), "pom.xml has no <dmzagent.spec.version>");
        return m.group(1).trim();
    }

    @Test
    void theFilteredResourceMatchesTheManifestPin() throws IOException {
        // Guards the filtering itself: a build that stopped substituting the
        // property would leave SpecVersion holding a literal placeholder.
        assertEquals(pinnedSpecVersion(), SpecVersion.VALUE);
    }

    @Test
    void theDefaultUserAgentCarriesThePinnedVersion() throws IOException {
        StringBuilder seen = new StringBuilder();
        try (DMZAgentClient cx = new DMZAgentClient(
                "ck_test_x", null, null, null,
                chain -> {
                    seen.append(chain.request().header("User-Agent"));
                    return new okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(okhttp3.ResponseBody.create(
                            "{\"state\":\"closed\",\"allow\":true}",
                            okhttp3.MediaType.get("application/json")))
                        .build();
                })) {
            cx.check("user:ws:a");
        }
        assertEquals("dmzagent-java/" + pinnedSpecVersion(), seen.toString());
    }
}
