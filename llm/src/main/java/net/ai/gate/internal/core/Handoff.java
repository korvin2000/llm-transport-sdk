package net.ai.gate.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.Message;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.spi.protocol.WireApi;
import org.jspecify.annotations.Nullable;

/// Adapts a conversation to a target (API, model) for one call; the stored conversation keeps every part as
/// produced, so switching back replays a model's own reasoning natively.
///
/// | History part | Same origin | Other origin |
/// |---|---|---|
/// | reasoning text | replayed natively | `<thinking>` text under `KEEP` (`reasoning_converted`); omitted under `DROP` |
/// | signatures, redacted reasoning | replayed natively | omitted (`reasoning_dropped`) |
/// | tool call | as is | id normalized by the target API, collisions disambiguated; results remapped |
/// | refusal | as is | assistant text (`history_adapted`) |
/// | unknown part | replayed | omitted (`history_adapted`) |
/// | generated image or audio | replayed | image omitted, audio as its transcript (`history_adapted`) |
/// | images, model without image input | placeholder text (`image_omitted`) | same |
/// | empty assistant turn (aborted) | dropped | dropped |
/// | explicit cache breakpoints | kept | remapped past dropped turns (`cache_breakpoints_remapped`) |
final class Handoff {
    static final String IMAGE_PLACEHOLDER = "[image omitted: the model does not accept images]";

    private Handoff() { }

    static Conversation adapt(Conversation conversation, WireApi api, Model model, ReasoningHandoff mode, Notes notes) {
        var tools = new ArrayList<Tool>();
        for (var tool : conversation.tools()) {
            if (tool instanceof ProviderTool p && !p.api().equals(api.id()))
                notes.adapt("option_dropped", "hosted tool " + p.name() + " belongs to " + p.api() + ", not " + api.id());
            else tools.add(tool);
        }
        boolean images = model.input().isEmpty() || model.input().contains(Modality.IMAGE);
        var ids = new HashMap<String, String>();
        var taken = new HashSet<String>();   // ids that stay as they are, so normalized foreign ids never collide with them
        for (var message : conversation.messages())
            if (message instanceof AssistantMessage a)
                a.toolCalls().stream().map(ToolCall::id).filter(id -> api.normalizeToolCallId(id).equals(id)).forEach(taken::add);
        var messages = new ArrayList<Message>();
        var kept = new boolean[conversation.messages().size()];
        for (int i = 0; i < kept.length; i++) {
            var message = conversation.messages().get(i);
            var adapted = switch (message) {
                case UserMessage u -> images ? u : u.withContent(withoutImages(u.content(), notes));
                case AssistantMessage a -> assistant(a, api, model, mode, ids, taken, notes);
                case ToolResultMessage r -> r.withResults(r.results().stream().map(result -> {
                    var remapped = ids.containsKey(result.callId()) ? result.withCallId(ids.get(result.callId())) : result;
                    return images ? remapped : remapped.withContent(withoutImages(remapped.content(), notes));
                }).toList());
            };
            if (adapted != null) {
                messages.add(adapted);
                kept[i] = true;
            }
        }
        var adapted = conversation.withTools(tools);
        if (messages.equals(conversation.messages())) return adapted;
        return adapted.withMessages(messages, remap(conversation.cacheBreakpoints(), kept, notes));
    }

    /// Breakpoints count kept messages only; two that collapse onto one boundary become one.
    private static List<Integer> remap(List<Integer> breakpoints, boolean[] kept, Notes notes) {
        var remapped = new ArrayList<Integer>();
        for (var breakpoint : breakpoints) {
            int mapped = 0;
            for (int i = 0; i < breakpoint; i++) if (kept[i]) mapped++;
            if (remapped.isEmpty() || remapped.getLast() != mapped) remapped.add(mapped);
        }
        if (!remapped.equals(breakpoints)) notes.note("cache_breakpoints_remapped", "cache breakpoints " + breakpoints + " → " + remapped + " after dropped turns");
        return remapped;
    }

    private static @Nullable AssistantMessage assistant(AssistantMessage a, WireApi api, Model model, ReasoningHandoff mode,
                                                        Map<String, String> ids, Set<String> taken, Notes notes) {
        if (a.content().isEmpty()) return null;
        if (a.model().equals(model.ref()) && a.api().equals(api.id())) return a;
        var parts = new ArrayList<Content>();
        var thinking = new StringBuilder();
        for (var part : a.content()) {
            switch (part) {
                case Content.Reasoning r when mode == ReasoningHandoff.KEEP && r.text().isPresent() && !r.redacted() -> {
                    thinking.append(r.text().get());
                    notes.warn("reasoning_converted", "reasoning of " + a.model() + " passed on as <thinking> text");
                }
                case Content.Reasoning _ -> notes.warn("reasoning_dropped", "reasoning of " + a.model() + " cannot be passed to " + model.ref());
                case ToolCall call -> {
                    var id = ids.containsKey(call.id()) ? ids.get(call.id()) : unique(api.normalizeToolCallId(call.id()), call.id(), taken);
                    if (!id.equals(call.id())) {
                        ids.put(call.id(), id);
                        notes.warn("tool_call_id_normalized", "tool call ids of " + a.api() + " rewritten for " + api.id());
                    }
                    taken.add(id);
                    parts.add(call.withId(id));
                }
                case Content.Refusal r -> {
                    parts.add(Content.text(r.text()));
                    notes.warn("history_adapted", "a refusal of " + a.model() + " was passed on as text");
                }
                case Content.Unknown u -> notes.warn("history_adapted", "a '" + u.type() + "' part of " + a.api() + " was omitted");
                case Content.Image _ -> notes.warn("history_adapted", "an image generated by " + a.model() + " was omitted");
                case Content.Audio audio -> {
                    audio.transcript().ifPresent(t -> parts.add(Content.text(t)));
                    notes.warn("history_adapted", "audio generated by " + a.model() + " was passed on as its transcript, if any");
                }
                default -> parts.add(part);
            }
        }
        if (!thinking.isEmpty()) parts.addFirst(Content.text("<thinking>" + thinking + "</thinking>"));
        return parts.isEmpty() ? null : a.toBuilder().content(parts).build();
    }

    /// A normalized id another call already uses gets a deterministic suffix, so two calls never share an id.
    private static String unique(String normalized, String original, Set<String> taken) {
        if (normalized.equals(original) || !taken.contains(normalized)) return normalized;
        var suffix = "_" + Integer.toHexString(original.hashCode());
        var base = normalized.length() + suffix.length() > 64 ? normalized.substring(0, 64 - suffix.length()) : normalized;
        var candidate = base + suffix;
        for (int n = 2; taken.contains(candidate); n++) candidate = base + suffix + n;
        return candidate;
    }

    private static List<Content> withoutImages(List<Content> parts, Notes notes) {
        return parts.stream().map(p -> {
            if (!(p instanceof Content.Image)) return p;
            notes.adapt("image_omitted", "an image was replaced by a placeholder: the model does not accept images");
            return (Content) Content.text(IMAGE_PLACEHOLDER);
        }).toList();
    }
}
