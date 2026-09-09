package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One page of {@link DMZAgentClient#listApprovals} (spec §7.11).
 *
 * <p>{@code nextCursor} is {@code null} on the last page. Nothing here
 * follows it for you — see {@link DMZAgentClient#iterApprovals}.
 */
public record ApprovalPage(
    @JsonProperty("approvals")   List<Approval> approvals,
    @JsonProperty("next_cursor") String nextCursor,
    Map<String, Object>          raw
) implements Iterable<Approval> {

    @Override
    public Iterator<Approval> iterator() {
        return approvals.iterator();
    }

    public int size() {
        return approvals.size();
    }

    @SuppressWarnings("unchecked")
    public static ApprovalPage fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        List<Approval> items = new ArrayList<>();
        Object raw = data.get("approvals");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    items.add(Approval.fromResponse((Map<String, Object>) o));
                }
            }
        }
        return new ApprovalPage(
            List.copyOf(items), Approval.nullableStr(data, "next_cursor"), data);
    }
}
