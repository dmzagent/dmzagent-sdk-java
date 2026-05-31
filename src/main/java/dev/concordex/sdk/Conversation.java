package dev.concordex.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateful handle for a single interaction. Per spec §6.1,
 * Conversation is constructed via {@code client.conversation(...)};
 * direct construction is NOT part of the public API.
 *
 * <p>Usage:
 *
 * <pre>{@code
 *   var participants = List.of(
 *       Map.of("subject_id", "user:ws:bot",  "role", "agent",    "kind", "agent"),
 *       Map.of("subject_id", "user:ws:cust", "role", "customer", "kind", "human"));
 *
 *   try (var conv = cx.conversation(participants)) {
 *       conv.says("user:ws:cust", "I want a refund.");
 *       conv.says("user:ws:bot",  "I can help.");
 *       try (var g = conv.guard("user:ws:bot")) {
 *           if (!g.getResult().allow()) throw new IllegalStateException(g.getResult().reason());
 *       }
 *       conv.toolCall("user:ws:bot", "refund.issue", Map.of("amount", 99));
 *   }
 * }</pre>
 *
 * <p>The Conversation tracks {@code interactionId} across calls.
 * The first event leaves it {@code null} on the wire; the server
 * mints one and returns it on the response, and every subsequent
 * call re-sends that id so the server stitches events into the
 * same interaction row.
 *
 * <p>Per spec §6.3 the current close path is a no-op; v1.0 will
 * emit an {@code end_interaction} event. The {@link AutoCloseable}
 * contract is reserved.
 */
public final class Conversation implements AutoCloseable {

    /** Roles the SDK treats as "this participant counts as the
     * agent for the wire protocol's agent_subject_id requirement." */
    private static final Set<String> AGENT_ROLES = Set.of("agent", "service", "system");

    private final ConcordexClient client;
    private final String          agentSubjectId;
    private final String          interactionKind;
    private final Map<String, Object> metadata;
    private final List<Map<String, Object>> subjects;
    private String interactionId;
    private boolean closed = false;

    Conversation(
        ConcordexClient           client,
        List<Map<String, Object>> participants,
        String                    agentSubjectId,
        String                    interactionKind,
        Map<String, Object>       metadata
    ) {
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException(
                "participants must be a non-empty list");
        }

        // Normalize and validate participants. Each entry needs at
        // least subject_id; role/kind default to "other".
        List<Map<String, Object>> roster = new ArrayList<>(participants.size());
        for (int i = 0; i < participants.size(); i++) {
            Map<String, Object> p = participants.get(i);
            Object sidRaw = p.get("subject_id");
            String sid = (sidRaw instanceof String s) ? s.trim() : "";
            if (sid.isEmpty()) {
                throw new IllegalArgumentException(
                    "participants[" + i + "] missing subject_id");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("subject_id", sid);
            entry.put("role", p.getOrDefault("role", "other"));
            entry.put("kind", p.getOrDefault("kind", "other"));
            if (p.containsKey("metadata")) entry.put("metadata", p.get("metadata"));
            roster.add(entry);
        }

        // Derive the wire-level agent_subject_id when the caller didn't
        // set it explicitly. First participant whose role looks
        // agent-ish wins; otherwise the first participant.
        String derived = agentSubjectId;
        if (derived == null || derived.isEmpty()) {
            derived = null;
            for (Map<String, Object> p : roster) {
                Object role = p.get("role");
                if (role instanceof String s && AGENT_ROLES.contains(s.toLowerCase())) {
                    derived = (String) p.get("subject_id");
                    break;
                }
            }
            if (derived == null) derived = (String) roster.get(0).get("subject_id");
        }

        this.client          = client;
        this.agentSubjectId  = derived;
        this.interactionKind = (interactionKind != null && !interactionKind.isEmpty())
            ? interactionKind : "chat_session";
        this.metadata        = (metadata != null) ? metadata : Map.of();
        this.subjects        = roster;
        this.interactionId   = null;
    }

    // ===================================================================== //
    // Roster
    // ===================================================================== //

    /** Server-assigned id, available after the first event. */
    public String interactionId() {
        return interactionId;
    }

    /** Snapshot of the current roster — defensive copy. Mutate via
     * {@link #addSubject}. */
    public List<Map<String, Object>> subjects() {
        return List.copyOf(subjects);
    }

    /**
     * Add another participant. Idempotent on {@code subjectId}: if
     * the id is already in the roster, this refreshes its role,
     * kind, and metadata.
     */
    public void addSubject(String subjectId, String role, String kind,
                           Map<String, Object> metadata) {
        if (subjectId == null || subjectId.isEmpty()) {
            throw new IllegalArgumentException("subjectId is required");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("subject_id", subjectId);
        entry.put("role", role != null ? role : "other");
        entry.put("kind", kind != null ? kind : "other");
        entry.put("metadata", metadata != null ? metadata : Map.of());

        for (int i = 0; i < subjects.size(); i++) {
            if (subjectId.equals(subjects.get(i).get("subject_id"))) {
                subjects.set(i, entry);
                return;
            }
        }
        subjects.add(entry);
    }

    /** Convenience: {@code addSubject(id, role, kind, null)}. */
    public void addSubject(String subjectId, String role, String kind) {
        addSubject(subjectId, role, kind, null);
    }

    /** Convenience: defaults role and kind to {@code "other"}. */
    public void addSubject(String subjectId) {
        addSubject(subjectId, "other", "other", null);
    }

    // ===================================================================== //
    // Event emission
    // ===================================================================== //

    /** A subject in the conversation spoke. */
    public EmitResult says(String subjectId, String text) {
        return says(subjectId, text, null);
    }

    /** {@code says} with an extra payload map merged alongside
     * {@code {text}}. */
    public EmitResult says(String subjectId, String text,
                           Map<String, Object> payloadExtra) {
        EmitResult r = client.subjectSays(
            subjectId, text, agentSubjectId,
            interactionId, subjects, payloadExtra);
        captureInteractionId(r);
        return r;
    }

    /** A subject (typically the agent) invoked a tool. */
    public EmitResult toolCall(String subjectId, String tool,
                               Map<String, Object> args) {
        EmitResult r = client.toolCall(subjectId, tool, args,
            interactionId, subjects);
        captureInteractionId(r);
        return r;
    }

    /** A tool returned a result. */
    public EmitResult toolResult(String subjectId, String tool, Object result) {
        EmitResult r = client.toolResult(subjectId, tool, result,
            interactionId, subjects);
        captureInteractionId(r);
        return r;
    }

    /** Structured observation — video keyframe, IoT event, anything
     * not utterance-shaped. */
    public EmitResult observation(Map<String, Object> payload) {
        EmitResult r = client.observation(agentSubjectId, subjects,
            payload, interactionId);
        captureInteractionId(r);
        return r;
    }

    private void captureInteractionId(EmitResult r) {
        if (interactionId == null && r.interactionId() != null
                                  && !r.interactionId().isEmpty()) {
            interactionId = r.interactionId();
        }
    }

    // ===================================================================== //
    // Circuit breaker
    // ===================================================================== //

    /** CB check scoped to a specific subject in the conversation. */
    public CheckResult check(String subjectId) {
        return client.check(subjectId, null);
    }

    /** Try-with-resources guard. */
    public Guard guard(String subjectId) {
        return client.guard(subjectId, null, false);
    }

    /** Try-with-resources guard, with optional raise-on-open. */
    public Guard guard(String subjectId, boolean raiseOnOpen) {
        return client.guard(subjectId, null, raiseOnOpen);
    }

    // ===================================================================== //
    // Lifecycle
    // ===================================================================== //

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // v0.5 no-op — v1.0 will emit an end_interaction event so
        // the server can close the row and stop accepting events
        // for this id.
    }
}
