package net.ai.gate.event;

import java.time.Instant;
import java.util.Map;

/// Sealed, immutable, content-free and secret-free. Keep a `default` branch: variants may be added.
public sealed interface LlmEvent permits RequestEvent, CredentialEvent, CatalogEvent {
    Instant at();

    /// The call's `ChatOptions.tags()`; empty for events not tied to a call.
    Map<String, String> tags();
}
