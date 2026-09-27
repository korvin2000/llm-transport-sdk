package net.ai.gate.internal.core;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Model;
import net.ai.gate.model.SupportLevel;
import net.ai.gate.spi.protocol.WireApi;

/// Effective options for one call — the scopes already merged by the caller — checked against the target model:
/// defaults made explicit, reasoning mapped to the nearest supported level, output limit clamped, provider options
/// of other API families made inert. Soft mismatches adapt (or fail under `strict()`); hard ones always fail.
final class Resolver {
    private Resolver() { }

    static ChatOptions resolve(Model model, WireApi api, Conversation conversation, ChatOptions options, Notes notes) {
        var b = options.toBuilder();
        if (model.source() == Model.Source.UNLISTED)
            notes.warn("unlisted_model", model.ref() + " is not in the catalog: no limits, prices or capability checks");
        if (options.cacheRetention().isEmpty()) {
            b.cacheRetention(CacheRetention.SHORT);
            notes.note("prompt_cache_default", "cacheRetention SHORT (the default)");
        }
        if (options.reasoningHandoff().isEmpty()) b.reasoningHandoff(ReasoningHandoff.KEEP);

        var applicable = options.providerOptions().stream().filter(o -> o.api().equals(api.id())).toList();
        options.providerOptions().stream().filter(o -> !o.api().equals(api.id())).forEach(o -> notes.warn("option_not_applicable",
                o.getClass().getSimpleName() + " applies to " + o.api() + ", not " + api.id() + "; it was not sent"));
        b.providers(applicable);

        options.reasoning().ifPresent(level -> {
            var support = model.capabilities().support(Capability.REASONING);
            boolean noControl = support == SupportLevel.UNSUPPORTED || model.reasoningLevels().isEmpty()
                    && support != SupportLevel.SUPPORTED && model.source() != Model.Source.UNLISTED;
            if (noControl) {
                notes.adapt("option_dropped", "reasoning " + level + " was not sent: " + model.ref() + " has no reasoning control");
                b.reasoning(null);
            } else {
                var mapped = level.nearest(model.reasoningLevels());
                if (mapped != level) {
                    notes.adapt("reasoning_clamped", "reasoning " + level + " → " + mapped + " (supported: " + model.reasoningLevels() + ")");
                    b.reasoning(mapped);
                }
            }
        });

        var limit = model.maxOutputTokens();
        if (options.maxTokens().isPresent() && limit.isPresent() && options.maxTokens().getAsInt() > limit.getAsLong()) {
            notes.adapt("max_tokens_clamped", "maxTokens " + options.maxTokens().getAsInt() + " → " + limit.getAsLong() + " (model maximum)");
            b.maxTokens((int) Math.min(Integer.MAX_VALUE, limit.getAsLong()));
        }

        if (!conversation.tools().isEmpty() && model.capabilities().support(Capability.TOOLS) == SupportLevel.UNSUPPORTED)
            throw unsupported(model + " does not support tools");
        if (options.output().orElse(null) instanceof OutputFormat.Schema || options.output().orElse(null) instanceof OutputFormat.Typed) {
            if (model.capabilities().support(Capability.STRUCTURED_OUTPUT) == SupportLevel.UNSUPPORTED
                    && model.capabilities().support(Capability.JSON_MODE) != SupportLevel.SUPPORTED)
                throw unsupported(model + " supports neither structured output nor JSON mode");
        }
        return b.build();
    }

    /// Input tokens estimated from characters (≈ 4 per token): enough to keep a default output limit inside the
    /// context window; never used for billing.
    static long estimateTokens(Conversation conversation) {
        long chars = conversation.system().map(String::length).orElse(0);
        for (var tool : conversation.tools()) chars += tool.name().length() + 200;
        for (var message : conversation.messages()) {
            chars += switch (message) {
                case UserMessage u -> u.text().length() + 1000L * (u.content().size() - 1);
                case AssistantMessage a -> a.text().length() + a.reasoningText().map(String::length).orElse(0)
                        + a.toolCalls().stream().mapToInt(c -> c.argumentsJson().length()).sum();
                case ToolResultMessage r -> r.results().stream().mapToInt(x -> x.text().length()).sum();
            };
        }
        return chars / 4 + 16;
    }

    private static InvalidRequestException unsupported(String message) {
        return new InvalidRequestException(LlmException.Details.builder(ErrorCode.UNSUPPORTED_FEATURE, message).build());
    }
}
