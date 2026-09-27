package net.ai.gate.internal.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.ai.gate.event.LlmEvent;
import net.ai.gate.event.LlmListener;
import net.ai.gate.lifecycle.Registration;

/// Ordered, synchronous, isolated delivery: runtime listeners first, then the call's own. A listener's exception is
/// logged and never reaches the call.
final class EventHub {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");

    private final CopyOnWriteArrayList<LlmListener> listeners;

    EventHub(List<LlmListener> initial) { listeners = new CopyOnWriteArrayList<>(initial); }

    Registration add(LlmListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void emit(LlmEvent event) { emit(event, List.of()); }

    void emit(LlmEvent event, List<LlmListener> callListeners) {
        for (var listener : listeners) deliver(listener, event);
        for (var listener : callListeners) deliver(listener, event);
    }

    int size() { return listeners.size(); }

    void clear() { listeners.clear(); }

    private static void deliver(LlmListener listener, LlmEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Listener " + listener + " failed on " + event, e);
        }
    }
}
