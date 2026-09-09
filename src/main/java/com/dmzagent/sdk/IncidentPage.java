package com.dmzagent.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One page of {@link DMZAgentClient#getIncidents} (spec §7.13).
 *
 * <p>Newest {@code ledger_index} first, as the server ordered it. The SDK
 * does not re-sort: ordering by a timestamp cannot separate two entries
 * written in the same second, and the ledger's own order is the one that
 * means something.
 */
public record IncidentPage(
    @JsonProperty("incidents")   List<Incident> incidents,
    @JsonProperty("next_cursor") String nextCursor,
    Map<String, Object>          raw
) implements Iterable<Incident> {

    @Override
    public Iterator<Incident> iterator() {
        return incidents.iterator();
    }

    public int size() {
        return incidents.size();
    }

    @SuppressWarnings("unchecked")
    public static IncidentPage fromResponse(Map<String, Object> data) {
        if (data == null) data = Map.of();
        List<Incident> items = new ArrayList<>();
        Object raw = data.get("incidents");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    items.add(Incident.fromResponse((Map<String, Object>) o));
                }
            }
        }
        return new IncidentPage(
            List.copyOf(items), Approval.nullableStr(data, "next_cursor"), data);
    }
}
