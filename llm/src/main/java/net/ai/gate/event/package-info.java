/// Observation: sealed, immutable, content-free and secret-free events for progress UIs, metrics and audit.
///
/// Signatures in this package mention only root and `json` types. Login progress is not an event: it reaches only
/// the `AuthInteraction` of the `login()` caller.
@NullMarked
package net.ai.gate.event;

import org.jspecify.annotations.NullMarked;
