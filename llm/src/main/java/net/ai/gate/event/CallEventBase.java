package net.ai.gate.event;

import java.time.Instant;
import java.util.Map;

import net.ai.gate.model.ModelRef;

/// Shared state of the request events: the identity of the call.
abstract class CallEventBase extends EventBase {
    private final String requestId, providerId;
    private final ModelRef model;

    CallEventBase(String requestId, ModelRef model, Map<String, String> tags, Instant at) {
        super(at, tags);
        this.requestId = requestId; this.providerId = model.providerId(); this.model = model;
    }

    public String requestId() { return requestId; }
    public String providerId() { return providerId; }
    public ModelRef model() { return model; }

    @Override public String toString() { return getClass().getSimpleName() + "[" + requestId + ", " + model + "]"; }
}
