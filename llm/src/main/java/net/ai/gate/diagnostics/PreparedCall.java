package net.ai.gate.diagnostics;

import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import org.jetbrains.annotations.ApiStatus;

/// A call prepared once — options resolved, history adapted, request encoded — to be inspected, counted and admitted,
/// then executed exactly as prepared with `Llm.start(prepared)` or `Llm.complete(prepared)`; only credentials are
/// resolved at dispatch. Immutable and reusable: every execution is a new call. Obtained from `Llm.prepare(…)` and
/// executable only by the runtime that prepared it. Not for implementation by consumers.
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface PreparedCall {
    /// What will be sent: body, URI, headers with credential placeholders, warnings and notes.
    PreparedRequest request();

    /// The options after resolution: reasoning mapped, limits clamped, and `maxTokens` as the request carries it —
    /// raised above a thinking budget or derived from the catalog when the API needs one.
    ChatOptions effectiveOptions();

    /// The history after hand-off to the target model.
    Conversation effectiveConversation();

    boolean streaming();

    /// SHA-256 (hex) of the API revision and the encoded request: equal for equal inputs, free of credentials.
    String digest();
}
