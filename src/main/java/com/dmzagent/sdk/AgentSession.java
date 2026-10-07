package com.dmzagent.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A handle bound to one agent session (spec §5.23). Constructed via
 * {@link DMZAgentClient#agentSession}; direct construction is not part of
 * the public API.
 *
 * <p>Each method sends one step through {@link DMZAgentClient#agentStep}
 * and returns its {@link StepResult}:
 *
 * <pre>{@code
 * AgentSession s = cx.agentSession("subject:dv:agent-a", "sess_4b1e");
 * s.intent("Add a trace id to every request.", List.of("src/obs/"), List.of("Edit", "Bash"));
 *
 * StepResult r = s.call("call_7", "Bash", Map.of("command", "git push origin HEAD"), null);
 * if (r.runs()) {
 *     Object out = runTool();
 *     s.result("call_7", "Bash", "ok", out, null);
 * } else {
 *     // A call that did not run is reported, whoever refused it.
 *     s.refused("call_7", "Bash", "governor", r.reason(), null);
 * }
 * }</pre>
 *
 * <p>The handle holds nothing beyond its two ids. It does not remember
 * refusals, does not infer {@code attemptOf}, and does not track the last
 * directive: a session that was answered {@code shutdown} is told so by
 * the server on every later step, not by this object. Pass
 * {@code attemptOf} yourself when you know a call retries an earlier one.
 */
public final class AgentSession {

    private final DMZAgentClient client;
    private final String         agentSubjectId;
    private final String         interactionId;

    AgentSession(DMZAgentClient client, String agentSubjectId, String interactionId) {
        DMZAgentClient.requireStepIds(agentSubjectId, interactionId);
        this.client         = client;
        this.agentSubjectId = agentSubjectId;
        this.interactionId  = interactionId;
    }

    /** The agent acting in this session. */
    public String agentSubjectId() { return agentSubjectId; }

    /** The session's caller-assigned id. */
    public String interactionId() { return interactionId; }

    // ----------------------------------------------------------------- //
    // intent
    // ----------------------------------------------------------------- //

    /** {@code intent(text, paths, tools, null)}. */
    public StepResult intent(String text, List<String> paths, List<String> tools) {
        return intent(text, paths, tools, null);
    }

    /**
     * Send {@code phase: intent} — what the agent says it will do and touch.
     *
     * @param paths          paths it says it will touch, or {@code null}
     * @param tools          tools it says it will use, or {@code null}
     * @param idempotencyKey caller-generated, or {@code null}; never generated here
     */
    public StepResult intent(
        String text, List<String> paths, List<String> tools, String idempotencyKey
    ) {
        Map<String, Object> intent = new LinkedHashMap<>();
        if (text  != null) intent.put("text",  text);
        if (paths != null) intent.put("paths", paths);
        if (tools != null) intent.put("tools", tools);
        return client.agentStep(agentSubjectId, interactionId, StepPhases.INTENT,
            null, null, null, null, null, null, null, null, intent, null, null,
            idempotencyKey);
    }

    // ----------------------------------------------------------------- //
    // call
    // ----------------------------------------------------------------- //

    /** {@code call(callId, tool, args, attemptOf, null)}. */
    public StepResult call(
        String callId, String tool, Map<String, Object> args, String attemptOf
    ) {
        return call(callId, tool, args, attemptOf, null);
    }

    /**
     * Send {@code phase: call}, <em>before</em> the tool runs. Run it only
     * when the answer's {@link StepResult#runs()} is {@code true}.
     *
     * @param args           the arguments, verbatim, or {@code null}
     * @param attemptOf      the {@code callId} this call retries, when you know; or {@code null}
     * @param idempotencyKey caller-generated, or {@code null}; never generated here
     */
    public StepResult call(
        String callId, String tool, Map<String, Object> args, String attemptOf,
        String idempotencyKey
    ) {
        return client.agentStep(agentSubjectId, interactionId, StepPhases.CALL,
            callId, tool, args, null, null, null, null, attemptOf, null, null, null,
            idempotencyKey);
    }

    // ----------------------------------------------------------------- //
    // result
    // ----------------------------------------------------------------- //

    /** {@code result(callId, tool, status, result, reason, null)}. */
    public StepResult result(
        String callId, String tool, String status, Object result, String reason
    ) {
        return result(callId, tool, status, result, reason, null);
    }

    /**
     * Send {@code phase: result} for a call that ran.
     *
     * @param status {@code ok} or {@code error}. A call that did not run is
     *               reported with {@link #refused}, which says who refused it.
     * @param result what the tool returned, or {@code null}
     * @param reason why it failed, in the tool's words, or {@code null}
     * @throws IllegalArgumentException locally, with no round trip, for any
     *         other {@code status}
     */
    public StepResult result(
        String callId, String tool, String status, Object result, String reason,
        String idempotencyKey
    ) {
        if (!"ok".equals(status) && !"error".equals(status)) {
            throw new IllegalArgumentException(
                "status must be ok or error on result(), got '" + status
                + "' — report a call that did not run with refused(), naming who refused it");
        }
        return client.agentStep(agentSubjectId, interactionId, StepPhases.RESULT,
            callId, tool, null, status, result, null, reason, null, null, null, null,
            idempotencyKey);
    }

    // ----------------------------------------------------------------- //
    // refused
    // ----------------------------------------------------------------- //

    /** {@code refused(callId, tool, refusedBy, reason, attemptOf, null)}. */
    public StepResult refused(
        String callId, String tool, String refusedBy, String reason, String attemptOf
    ) {
        return refused(callId, tool, refusedBy, reason, attemptOf, null);
    }

    /**
     * Send {@code phase: result, status: refused} for a call that did not run.
     *
     * <p>Spec §1.9 requires this for every call that did not run, whoever
     * refused it: a refusal is the evidence that makes a later step a
     * second attempt.
     *
     * @param refusedBy {@code governor} | {@code harness} | {@code host}
     * @param reason    why, in the refuser's words, or {@code null}
     * @param attemptOf the {@code callId} this call retried, when you know; or {@code null}
     */
    public StepResult refused(
        String callId, String tool, String refusedBy, String reason, String attemptOf,
        String idempotencyKey
    ) {
        return client.agentStep(agentSubjectId, interactionId, StepPhases.RESULT,
            callId, tool, null, "refused", null, refusedBy, reason, attemptOf, null,
            null, null, idempotencyKey);
    }
}
