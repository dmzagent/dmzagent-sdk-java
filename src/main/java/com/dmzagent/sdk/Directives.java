package com.dmzagent.sdk;

import java.util.List;

/**
 * The five directives a step can be answered with (spec §1.9).
 *
 * <p>Per spec §8.6, {@code DIRECTIVES} is exposed the same way as
 * {@link EventKinds}: {@link #ALL} as an immutable list, plus one constant
 * per value. The constants ARE the wire values.
 *
 * <p>This list is what the SDK <em>knows</em>, not what the server may
 * send. Appendix B lets the server add a directive; {@link StepResult}
 * keeps such a value as its raw string and reports
 * {@link StepResult#runs()} as {@code false} for it, because an unknown
 * word from the governor is read as {@link #BLOCK}.
 */
public final class Directives {

    /** Run the call. */
    public static final String PROCEED  = "proceed";
    /** Run it; DMZAgent is watching, and the caller MAY tell the agent so. */
    public static final String WARN     = "warn";
    /** Wait for the approval named in {@code approvalId}; approved runs, anything else is {@link #BLOCK}. */
    public static final String HOLD     = "hold";
    /** Do not run the call; the session carries on. */
    public static final String BLOCK    = "block";
    /** Do not run the call, and end the session. */
    public static final String SHUTDOWN = "shutdown";

    /** All five directives, in spec order. */
    public static final List<String> ALL = List.of(PROCEED, WARN, HOLD, BLOCK, SHUTDOWN);

    private Directives() {}
}
