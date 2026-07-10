package com.dmzagent.sdk.concordia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaAuthException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaCanonNotInstalledException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaCircuitOpenException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaPermissionDeniedException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaPolicyEngineUnavailableException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaProtocolException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaQuotaExceededException;
import com.dmzagent.sdk.concordia.ConcordiaExceptions.ConcordiaSubjectNotFoundException;

import com.dmzagent.sdk.concordia.ConcordiaTypes.EnforceCovenantResult;
import com.dmzagent.sdk.concordia.ConcordiaTypes.InstalledCanon;
import com.dmzagent.sdk.concordia.ConcordiaTypes.LedgerEntry;
import com.dmzagent.sdk.concordia.ConcordiaTypes.LedgerPage;
import com.dmzagent.sdk.concordia.ConcordiaTypes.PolicySummary;
import com.dmzagent.sdk.concordia.ConcordiaTypes.QueryCorpusResult;
import com.dmzagent.sdk.concordia.ConcordiaTypes.RecordDecisionResult;
import com.dmzagent.sdk.concordia.ConcordiaTypes.SubjectSoul;

/**
 * Concordia MCP 1.0 client — the governance side of DMZAgent.
 *
 * <p>Companion to {@code com.dmzagent.sdk.DMZAgentClient} for the
 * agent-stream surface. Customer agents speak MCP 1.0 over JSON-RPC
 * against {@code /mcp/v1} to enforce covenants, record audit
 * decisions, query installed Canons, and read soul snapshots.
 *
 * <pre>{@code
 * try (var client = new ConcordiaClient.Builder()
 *         .apiKey(System.getenv("DMZAGENT_API_KEY"))
 *         .build()) {
 *
 *     EnforceCovenantResult v = client.enforceCovenant(
 *         "user:alice",
 *         "payment.issue",
 *         Map.of("amount", 9900, "currency", "usd"),
 *         Map.of("session_id", "s_42"));
 *
 *     if (v.blocked()) {
 *         return safeFallback(v.rationale());
 *     }
 *
 *     client.recordDecision(
 *         "user:alice", "payment_issued",
 *         Map.of("refund_id", "re_123"),
 *         "agent", "completed");
 * }
 * }</pre>
 *
 * <p>Naming follows spec §8 Java conventions (camelCase methods,
 * {@code …Exception} suffixes, {@code ConcordiaClient} for the
 * client class). The wire field names stay snake_case via Jackson's
 * {@code @JsonProperty} on the result records.
 *
 * <p>Implements {@link AutoCloseable}; use try-with-resources to
 * release the OkHttp connection pool.
 */
public final class ConcordiaClient implements AutoCloseable {

    /** Production base URL. */
    public static final String DEFAULT_BASE_URL  = "https://api.dmzagent.com";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final String DEFAULT_USER_AGENT = "dmzagent-concordia-java/0.7.0";

    private static final String MCP_PATH = "/mcp/v1";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient   http;
    private final ObjectMapper   mapper;
    private final String         apiKey;
    private final String         baseUrl;
    private final String         userAgent;
    private final AtomicInteger  idSeq = new AtomicInteger(0);

    private ConcordiaClient(Builder b) {
        if (b.apiKey == null || b.apiKey.isEmpty()) {
            throw new ConcordiaException(
                "apiKey required (pass apiKey or set DMZAGENT_API_KEY)");
        }
        if (!b.apiKey.startsWith("ck_")) {
            throw new ConcordiaException(
                "apiKey must start with 'ck_' — double-check you copied a DMZAgent key, not another service's token");
        }
        this.apiKey    = b.apiKey;
        this.baseUrl   = stripTrailingSlash(b.baseUrl == null ? DEFAULT_BASE_URL : b.baseUrl);
        this.userAgent = b.userAgent == null ? DEFAULT_USER_AGENT : b.userAgent;
        this.mapper    = b.mapper != null ? b.mapper : new ObjectMapper();
        Duration to = b.timeout == null ? DEFAULT_TIMEOUT : b.timeout;
        OkHttpClient.Builder hb = b.httpClientBuilder != null
            ? b.httpClientBuilder : new OkHttpClient.Builder();
        this.http = hb
            .callTimeout(to)
            .build();
    }

    /** Convenience constructor — equivalent to {@code new Builder().apiKey(k).build()}. */
    public ConcordiaClient(String apiKey) {
        this(new Builder().apiKey(apiKey));
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    /** Releases the underlying OkHttp dispatcher / pool. */
    @Override
    public void close() {
        try { http.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        try { http.connectionPool().evictAll(); } catch (Exception ignored) {}
    }

    // ===== JSON-RPC dispatch =================================================

    private JsonNode rpc(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id",      idSeq.incrementAndGet());
        body.put("method",  method);
        body.put("params",  params == null ? Collections.emptyMap() : params);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ConcordiaProtocolException("Failed to serialize request body", e);
        }

        Request req = new Request.Builder()
            .url(baseUrl + MCP_PATH)
            .post(RequestBody.create(json, JSON))
            .header("Authorization", "Bearer " + apiKey)
            .header("User-Agent",     userAgent)
            .header("Content-Type",   "application/json")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            int status = resp.code();
            ResponseBody rb = resp.body();
            String text = rb == null ? "" : rb.string();

            if (status != 200) {
                Map<String, Object> data = new HashMap<>();
                data.put("status", status);
                data.put("body",   text.length() > 1000 ? text.substring(0, 1000) : text);
                throw new ConcordiaProtocolException(
                    "Concordia returned HTTP " + status, status, data);
            }

            JsonNode envelope;
            try {
                envelope = mapper.readTree(text);
            } catch (JsonProcessingException e) {
                throw new ConcordiaProtocolException("Concordia returned non-JSON body", e);
            }
            if (envelope == null || !envelope.isObject()) {
                throw new ConcordiaProtocolException("Envelope is not a JSON object");
            }
            if (!envelope.hasNonNull("jsonrpc")
                || !"2.0".equals(envelope.get("jsonrpc").asText())) {
                throw new ConcordiaProtocolException("Envelope jsonrpc != \"2.0\"");
            }

            JsonNode error = envelope.get("error");
            if (error != null && error.isObject()) {
                int code = error.hasNonNull("code") ? error.get("code").asInt() : 0;
                String msg = error.hasNonNull("message") ? error.get("message").asText() : "";
                Map<String, Object> data = new HashMap<>();
                JsonNode d = error.get("data");
                if (d != null && d.isObject()) {
                    data = mapper.convertValue(d,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
                throw mapError(code, msg, data);
            }

            JsonNode result = envelope.get("result");
            if (result == null) {
                throw new ConcordiaProtocolException(
                    "Envelope missing both `result` and `error`");
            }
            return result;
        } catch (SocketTimeoutException ste) {
            throw new ConcordiaProtocolException(
                "Concordia request timed out", ste);
        } catch (IOException ioe) {
            throw new ConcordiaProtocolException(
                "Concordia transport error: " + ioe.getMessage(), ioe);
        }
    }

    private static ConcordiaException mapError(int code, String msg, Map<String, Object> data) {
        switch (code) {
            case -32001: return new ConcordiaAuthException(msg, data);
            case -32002: return new ConcordiaQuotaExceededException(msg, data);
            case -32003: return new ConcordiaPolicyEngineUnavailableException(msg, data);
            case -32004: return new ConcordiaCanonNotInstalledException(msg, data);
            case -32005: return new ConcordiaSubjectNotFoundException(msg, data);
            case -32006: return new ConcordiaCircuitOpenException(msg, data);
            case -32007: return new ConcordiaPermissionDeniedException(msg, data);
            default:
                String errId = data == null ? null : (String) data.get("error_id");
                return new ConcordiaException(msg, code, errId, data);
        }
    }

    private <T> T callTool(String name, Map<String, Object> arguments, Class<T> type) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name",      name);
        params.put("arguments", arguments);
        JsonNode result = rpc("tools/call", params);
        if (!result.isObject()) {
            throw new ConcordiaProtocolException("tools/call " + name + " returned non-object");
        }
        // Prefer the typed `_data` field; fall back to canonical content[].text JSON.
        JsonNode data = result.get("_data");
        if (data != null && data.isObject()) {
            try {
                return mapper.treeToValue(data, type);
            } catch (JsonProcessingException e) {
                throw new ConcordiaProtocolException(
                    "tools/call " + name + " _data did not deserialize", e);
            }
        }
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(asText(block, "type"))) {
                    String text = asText(block, "text");
                    if (text != null) {
                        try {
                            return mapper.readValue(text, type);
                        } catch (JsonProcessingException e) {
                            // Try next block
                        }
                    }
                }
            }
        }
        throw new ConcordiaProtocolException(
            "tools/call " + name + " response had no decodable content");
    }

    private static String asText(JsonNode n, String field) {
        JsonNode f = n.get(field);
        return f == null || f.isNull() ? null : f.asText();
    }

    // ===== Discovery =========================================================

    /** MCP initialize handshake. Returns the raw JSON-RPC `result`. */
    public JsonNode initialize() {
        return rpc("initialize", null);
    }

    /** Liveness check. */
    public boolean ping() {
        JsonNode r = rpc("ping", null);
        return r.hasNonNull("ok") && r.get("ok").asBoolean(false);
    }

    // ===== The four tools ====================================================

    public EnforceCovenantResult enforceCovenant(
            String subjectId,
            String actionKind,
            Map<String, Object> actionPayload,
            Map<String, Object> context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("subject_id",     subjectId);
        args.put("action_kind",    actionKind);
        args.put("action_payload", actionPayload == null ? Collections.emptyMap() : actionPayload);
        args.put("context",        context       == null ? Collections.emptyMap() : context);
        return callTool("enforce_covenant", args, EnforceCovenantResult.class);
    }

    public RecordDecisionResult recordDecision(
            String subjectId,
            String decisionKind,
            Map<String, Object> payload,
            String actor,
            String outcome) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("subject_id",    subjectId);
        args.put("decision_kind", decisionKind);
        args.put("payload",       payload == null ? Collections.emptyMap() : payload);
        args.put("actor",         actor   == null ? "agent"     : actor);
        args.put("outcome",       outcome == null ? "completed" : outcome);
        return callTool("record_decision", args, RecordDecisionResult.class);
    }

    public QueryCorpusResult queryCorpus(
            String query,
            String scope,
            List<String> canonFilter,
            int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("scope", scope == null ? "installed" : scope);
        args.put("limit", limit);
        if (canonFilter != null && !canonFilter.isEmpty()) {
            args.put("canon_filter", canonFilter);
        }
        return callTool("query_corpus", args, QueryCorpusResult.class);
    }

    /** Convenience: {@code queryCorpus(query, "installed", null, 20)}. */
    public QueryCorpusResult queryCorpus(String query) {
        return queryCorpus(query, "installed", null, 20);
    }

    public SubjectSoul getSubjectSoul(
            String subjectId,
            String depth,
            List<String> tagFilter) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("subject_id", subjectId);
        args.put("depth",      depth == null ? "latest" : depth);
        if (tagFilter != null && !tagFilter.isEmpty()) {
            args.put("tag_filter", tagFilter);
        }
        return callTool("get_subject_soul", args, SubjectSoul.class);
    }

    /** Convenience: {@code getSubjectSoul(subjectId, "latest", null)}. */
    public SubjectSoul getSubjectSoul(String subjectId) {
        return getSubjectSoul(subjectId, "latest", null);
    }

    // ===== Resources =========================================================

    private <T> T readResource(String uri, Class<T> type) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        JsonNode result = rpc("resources/read", params);
        if (!result.isObject() || !result.has("contents")) {
            throw new ConcordiaProtocolException(
                "resources/read " + uri + " returned no contents array");
        }
        for (JsonNode block : result.get("contents")) {
            if ("application/json".equals(asText(block, "mimeType"))) {
                String text = asText(block, "text");
                if (text != null) {
                    try {
                        return mapper.readValue(text, type);
                    } catch (JsonProcessingException e) {
                        throw new ConcordiaProtocolException(
                            "resources/read " + uri + " returned non-JSON content", e);
                    }
                }
            }
        }
        throw new ConcordiaProtocolException(
            "resources/read " + uri + " returned no JSON content block");
    }

    /** {@code concordia:/workspace/policies}. */
    public List<PolicySummary> workspacePolicies() {
        PoliciesPage page = readResource("concordia:/workspace/policies", PoliciesPage.class);
        return page.policies == null ? Collections.emptyList() : page.policies;
    }

    /** {@code concordia:/workspace/canons}. */
    public List<InstalledCanon> workspaceCanons() {
        CanonsPage page = readResource("concordia:/workspace/canons", CanonsPage.class);
        return page.canons == null ? Collections.emptyList() : page.canons;
    }

    /** Paginated ledger read. */
    public LedgerPage recentLedger(int since, int limit) {
        String uri = "concordia:/workspace/recent-ledger?since=" + since + "&limit=" + limit;
        return readResource(uri, LedgerPage.class);
    }

    /** Iterator: walks every ledger entry from {@code since} onwards. */
    public Iterable<LedgerEntry> iterLedger(int since, int pageSize) {
        return () -> new Iterator<>() {
            Integer cursor = since;
            Iterator<LedgerEntry> page = Collections.emptyIterator();
            @Override public boolean hasNext() {
                while (!page.hasNext() && cursor != null) {
                    LedgerPage p = recentLedger(cursor, pageSize);
                    page   = p.entries() == null
                        ? Collections.emptyIterator() : p.entries().iterator();
                    cursor = p.nextSince();
                }
                return page.hasNext();
            }
            @Override public LedgerEntry next() {
                if (!hasNext()) throw new NoSuchElementException();
                return page.next();
            }
        };
    }

    // Internal resource page wrappers — Jackson deserializes from the
    // server's {workspace_id, count, policies/canons} envelope, then
    // we expose only the inner list to callers.
    private static final class PoliciesPage {
        public List<PolicySummary> policies;
    }
    private static final class CanonsPage {
        public List<InstalledCanon> canons;
    }

    // ===== Builder ===========================================================

    public static final class Builder {
        private String  apiKey;
        private String  baseUrl;
        private Duration timeout;
        private String  userAgent;
        private ObjectMapper mapper;
        private OkHttpClient.Builder httpClientBuilder;

        public Builder apiKey(String v)   { this.apiKey   = v; return this; }
        public Builder baseUrl(String v)  { this.baseUrl  = v; return this; }
        public Builder timeout(Duration v){ this.timeout  = v; return this; }
        public Builder userAgent(String v){ this.userAgent = v; return this; }
        public Builder objectMapper(ObjectMapper v) { this.mapper = v; return this; }
        public Builder httpClient(OkHttpClient.Builder v) { this.httpClientBuilder = v; return this; }

        public ConcordiaClient build() {
            // Resolve apiKey from env if not set explicitly.
            if (this.apiKey == null) {
                this.apiKey = System.getenv("DMZAGENT_API_KEY");
            }
            return new ConcordiaClient(this);
        }
    }
}
