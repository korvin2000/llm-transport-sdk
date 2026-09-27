package net.ai.gate.spi.protocol;

import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.model.Model;

/// What a codec receives: the target model, the history already adapted to it, and the effective options — scopes
/// merged, reasoning mapped, foreign provider options removed.
public record ApiRequest(Model model, Conversation conversation, ChatOptions options, boolean streaming) { }
