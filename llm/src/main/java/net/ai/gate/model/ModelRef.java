package net.ai.gate.model;

import net.ai.gate.internal.validation.Checks;

/// Immutable reference to a model of a provider — what conversations, replies and configurations persist.
/// The model id is opaque: never split on `:` or `/`, never normalized.
public record ModelRef(String providerId, String modelId) {
    public ModelRef {
        Checks.notBlank(providerId, "Provider id");
        Checks.notBlank(modelId, "Model id");
    }

    @Override public String toString() { return providerId + "/" + modelId; }
}
