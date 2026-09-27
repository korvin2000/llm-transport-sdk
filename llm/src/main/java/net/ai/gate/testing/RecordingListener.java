package net.ai.gate.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.ai.gate.event.LlmEvent;
import net.ai.gate.event.LlmListener;

/// Records events for assertions on progress, retries, warnings and cost; the reference listener. Thread-safe.
public final class RecordingListener implements LlmListener {
    private final CopyOnWriteArrayList<LlmEvent> events = new CopyOnWriteArrayList<>();

    public RecordingListener() { }

    @Override public void onEvent(LlmEvent event) { events.add(event); }

    public List<LlmEvent> events() { return List.copyOf(events); }

    /// Events of one type, in arrival order: `listener.events(RequestEvent.Finished.class)`.
    public <T extends LlmEvent> List<T> events(Class<T> type) { return events.stream().filter(type::isInstance).map(type::cast).toList(); }

    public void clear() { events.clear(); }

    @Override public String toString() { return "RecordingListener" + events; }
}
