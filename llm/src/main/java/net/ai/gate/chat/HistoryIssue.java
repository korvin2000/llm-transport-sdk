package net.ai.gate.chat;

import net.ai.gate.metadata.Warning;
import org.jspecify.annotations.Nullable;

/// One conversion the hand-off to a target model makes, located in the conversation: what `Llm.check(…)` reports and
/// what `HistoryPolicy.REJECT_LOSSY` refuses.
/// @param messageIndex into `Conversation.messages()`
/// @param partIndex into that message's parts; `null` when the whole message is affected
public record HistoryIssue(int messageIndex, @Nullable Integer partIndex, Warning warning) {
    @Override public String toString() { return "messages[" + messageIndex + "]" + (partIndex == null ? "" : ".content[" + partIndex + "]") + ": " + warning; }
}
