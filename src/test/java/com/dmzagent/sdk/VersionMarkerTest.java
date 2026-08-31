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
 * The spec version is written down in two places; they have to agree.
 *
 * <p>Found while adding the state cache: the default User-Agent read
 * {@code dmzagent-java/0.6.0} while {@code pom.xml}'s
 * {@code <dmzagent.spec.version>} read {@code 0.8.0}. The User-Agent is
 * the one a server actually sees, so the version reported on every
 * request and the version declared to the spec repo had drifted apart
 * with nothing to notice.
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
    void theSpecVersionConstantMatchesTheManifestPin() throws IOException {
        assertEquals(pinnedSpecVersion(), DMZAgentClient.SPEC_VERSION);
    }

    @Test
    void theDefaultUserAgentCarriesThePinnedVersion() throws IOException {
        // The marker a server actually sees.
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
