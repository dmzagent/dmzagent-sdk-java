package com.dmzagent.sdk;

import java.util.List;

/**
 * The four event kinds accepted by {@code /v1/agent-stream/event}.
 *
 * <p>Per spec §8.6, the constant array
 * {@code ["subject_says", "tool_call", "tool_result", "observation"]}
 * is exposed under Java naming as:
 *
 * <ul>
 *   <li>{@link #ALL} — immutable {@code List<String>} of all kinds.
 *   <li>{@link #SUBJECT_SAYS}, {@link #TOOL_CALL}, {@link #TOOL_RESULT},
 *       {@link #OBSERVATION} — individual constants.
 * </ul>
 *
 * <p>The on-wire {@code kind} value is ALWAYS the snake_case form
 * regardless of how the SDK exposes the enum — these constants ARE
 * the wire values.
 */
public final class EventKinds {

    public static final String SUBJECT_SAYS = "subject_says";
    public static final String TOOL_CALL    = "tool_call";
    public static final String TOOL_RESULT  = "tool_result";
    public static final String OBSERVATION  = "observation";

    /** All four kinds, in spec order. */
    public static final List<String> ALL = List.of(
        SUBJECT_SAYS,
        TOOL_CALL,
        TOOL_RESULT,
        OBSERVATION
    );

    private EventKinds() {}
}
