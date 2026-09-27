package net.ai.gate.event;

import java.time.Instant;
import java.util.Map;

/// Shared state of every event class.
abstract class EventBase {
    private final Instant at;
    private final Map<String, String> tags;

    EventBase(Instant at, Map<String, String> tags) { this.at = at; this.tags = Map.copyOf(tags); }

    public Instant at() { return at; }
    public Map<String, String> tags() { return tags; }
}
