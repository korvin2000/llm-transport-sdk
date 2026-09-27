package net.ai.gate.chat;

import java.time.Instant;

/// One turn of a [Conversation]. Closed set: user input, a model reply, or the host's tool results.
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    /// Set by the SDK when not given; never sent to providers.
    Instant timestamp();
}
