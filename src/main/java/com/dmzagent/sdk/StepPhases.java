package com.dmzagent.sdk;

import java.util.List;

/**
 * The three phases of an agent-mode step (spec §1.9, §2.11).
 *
 * <p>Per spec §8.6, {@code STEP_PHASES} is exposed the same way as
 * {@link EventKinds}: {@link #ALL} as an immutable list, plus one constant
 * per value. The constants ARE the wire values.
 */
public final class StepPhases {

    /** The agent states what it will do and touch. */
    public static final String INTENT = "intent";
    /** Sent <em>before</em> a tool runs; the answer governs whether it runs. */
    public static final String CALL   = "call";
    /** Sent after a call ran, failed, or was refused. */
    public static final String RESULT = "result";

    /** All three phases, in spec order. */
    public static final List<String> ALL = List.of(INTENT, CALL, RESULT);

    private StepPhases() {}
}
