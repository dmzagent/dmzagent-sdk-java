package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One page of {@link DMZAgentClient#listBehaviors} (spec §7.18).
 *
 * <p>Newest {@code observedAt} first. {@code nextCursor} is {@code null}
 * on the last page. Nothing here follows it for you — see
 * {@link DMZAgentClient#iterBehaviors}.
 */
public record BehaviorPage(
    @JsonProperty("behaviors")   List<Behavior> behaviors,
    @JsonProperty("next_cursor") String nextCursor,
    Map<String, Object>          raw
) implements Iterable<Behavior> {

    @Override
    public Iterator<Behavior> iterator() {
        return behaviors.iterator();
    }

    public int size() {
        return behaviors.size();
    }

    public static BehaviorPage fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        return new BehaviorPage(
            StepResult.behaviors(data.get("behaviors")),
            Approval.nullableStr(data, "next_cursor"),
            data);
    }
}
