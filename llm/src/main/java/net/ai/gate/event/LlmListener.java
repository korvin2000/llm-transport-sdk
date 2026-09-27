package net.ai.gate.event;

/// **SPI** (host). Observation only: called synchronously on the thread that produced the event, outside SDK locks.
/// Must be brief; a thrown exception is logged and never affects the call. Events of one call arrive in order.
@FunctionalInterface
public interface LlmListener {
    void onEvent(LlmEvent event);
}
