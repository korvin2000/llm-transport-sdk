package net.ai.gate.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.CacheBreakpoint;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.HistoryIssue;
import net.ai.gate.chat.Message;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.HistoryPolicy;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.spi.protocol.WireApi;
import org.jspecify.annotations.Nullable;

/// Adapts a conversation to a target (API, model) for one call; the stored conversation keeps every part as
/// produced, so switching back replays a model's own reasoning natively. Every conversion is a located
/// [HistoryIssue]; the lossy ones are what `HistoryPolicy.REJECT_LOSSY` refuses.
///
/// | History part | Same origin | Other origin | Lossy |
/// |---|---|---|---|
/// | reasoning text | replayed natively | `<thinking>` text under `KEEP` (`reasoning_converted`); omitted under `DROP` | yes |
/// | signatures, redacted reasoning | replayed natively | omitted (`reasoning_dropped`) | yes |
/// | tool call | as is | id normalized by the target API, collisions disambiguated; results remapped | no |
/// | refusal | as is | assistant text (`history_adapted`) | yes |
/// | unknown part | replayed | omitted (`history_adapted`) | yes |
/// | generated image or audio | replayed | image omitted, audio as its transcript (`history_adapted`) | yes |
/// | part cut off in a partial reply | omitted (`incomplete_part_omitted`) | same | no |
/// | images, model without image input | placeholder text (`image_omitted`) | same | yes |
/// | empty assistant turn (aborted) | dropped | dropped | no |
/// | explicit cache breakpoints | kept | remapped past dropped turns (`cache_breakpoints_remapped`) | no |
final class Handoff {
    static final String IMAGE_PLACEHOLDER = "[image omitted: the model does not accept images]";

    private final WireApi api;
    private final Model model;
    private final ReasoningHandoff mode;
    private final HistoryPolicy policy;
    private final Notes notes;
    private final boolean images;
    private final Map<String, String> ids = new HashMap<>();
    private final Set<String> taken = new HashSet<>();   // ids that stay as they are, so normalized foreign ids never collide with them

    private Handoff(WireApi api, Model model, ReasoningHandoff mode, HistoryPolicy policy, Notes notes) {
        this.api = api; this.model = model; this.mode = mode; this.policy = policy; this.notes = notes;
        images = model.input().isEmpty() || model.input().contains(Modality.IMAGE);
    }

    static Conversation adapt(Conversation conversation, WireApi api, Model model, ReasoningHandoff mode, HistoryPolicy policy, Notes notes) {
        return new Handoff(api, model, mode, policy, notes).adapt(conversation);
    }

    private Conversation adapt(Conversation conversation) {
        var tools = new ArrayList<Tool>();
        for (var tool : conversation.tools()) {
            if (tool instanceof ProviderTool p && !p.api().equals(api.id()))
                notes.adapt("option_dropped", "hosted tool " + p.name() + " belongs to " + p.api() + ", not " + api.id());
            else tools.add(tool);
        }
        for (var message : conversation.messages())
            if (message instanceof AssistantMessage a)
                a.toolCalls().stream().map(ToolCall::id).filter(id -> api.normalizeToolCallId(id).equals(id)).forEach(taken::add);
        var messages = new ArrayList<Message>();
        var kept = new boolean[conversation.messages().size()];
        for (int i = 0; i < kept.length; i++) {
            int at = i;
            var adapted = switch (conversation.messages().get(i)) {
                case UserMessage u -> images ? u : u.withContent(withoutImages(u.content(), at));
                case AssistantMessage a -> assistant(a, at);
                case ToolResultMessage r -> r.withResults(results(r.results(), at));
            };
            if (adapted != null) {
                messages.add(adapted);
                kept[i] = true;
            }
        }
        var adapted = conversation.withTools(tools);
        if (messages.equals(conversation.messages())) return adapted;
        return adapted.withMessages(messages, List.of()).withCacheBreakpoints(remap(conversation.cacheBreakpointsWithRetention(), kept));
    }

    /// Breakpoints count kept messages only; two that collapse onto one boundary become one, the later's retention winning.
    private List<CacheBreakpoint> remap(List<CacheBreakpoint> breakpoints, boolean[] kept) {
        var remapped = new ArrayList<CacheBreakpoint>();
        for (var breakpoint : breakpoints) {
            int mapped = 0;
            for (int i = 0; i < breakpoint.index(); i++) if (kept[i]) mapped++;
            if (!remapped.isEmpty() && remapped.getLast().index() == mapped) remapped.removeLast();
            remapped.add(new CacheBreakpoint(mapped, breakpoint.retention()));
        }
        if (!remapped.equals(breakpoints)) notes.note("cache_breakpoints_remapped", "cache breakpoints "
                + breakpoints.stream().map(CacheBreakpoint::index).toList() + " → " + remapped.stream().map(CacheBreakpoint::index).toList() + " after dropped turns");
        return remapped;
    }

    private @Nullable AssistantMessage assistant(AssistantMessage a, int at) {
        boolean sameOrigin = a.model().equals(model.ref()) && a.api().equals(api.id());
        if (!sameOrigin && policy == HistoryPolicy.SAME_ORIGIN_REQUIRED && !a.content().isEmpty())
            throw rejected(new HistoryIssue(at, null, new Warning("history_adapted", "a turn of " + a.model() + " via " + a.api()
                    + " cannot be passed to " + model.ref() + " via " + api.id())), "SAME_ORIGIN_REQUIRED");
        if (sameOrigin && a.complete()) return a.content().isEmpty() ? null : a;
        var incomplete = new HashSet<>(a.incompleteParts());
        var parts = new ArrayList<Content>();
        var thinking = new StringBuilder();
        for (int j = 0; j < a.content().size(); j++) {
            var part = a.content().get(j);
            if (incomplete.contains(j)) {
                issue(at, j, false, "incomplete_part_omitted", "a part cut off in a partial reply of " + a.model() + " was omitted");
                continue;
            }
            if (sameOrigin) {
                parts.add(part);
                continue;
            }
            switch (part) {
                case Content.Reasoning r when mode == ReasoningHandoff.KEEP && r.text().isPresent() && !r.redacted() -> {
                    thinking.append(r.text().get());
                    issue(at, j, false, "reasoning_converted", "reasoning of " + a.model() + " passed on as <thinking> text");
                }
                case Content.Reasoning _ -> issue(at, j, false, "reasoning_dropped", "reasoning of " + a.model() + " cannot be passed to " + model.ref());
                case ToolCall call -> {
                    var id = ids.containsKey(call.id()) ? ids.get(call.id()) : unique(api.normalizeToolCallId(call.id()), call.id());
                    if (!id.equals(call.id())) {
                        ids.put(call.id(), id);
                        lossless(at, j, "tool_call_id_normalized", "tool call ids of " + a.api() + " rewritten for " + api.id());
                    }
                    taken.add(id);
                    parts.add(call.withId(id));
                }
                case Content.Refusal r -> {
                    parts.add(Content.text(r.text()));
                    issue(at, j, false, "history_adapted", "a refusal of " + a.model() + " was passed on as text");
                }
                case Content.Unknown u -> issue(at, j, false, "history_adapted", "a '" + u.type() + "' part of " + a.api() + " was omitted");
                case Content.Image _ -> issue(at, j, false, "history_adapted", "an image generated by " + a.model() + " was omitted");
                case Content.Audio audio -> {
                    audio.transcript().ifPresent(t -> parts.add(Content.text(t)));
                    issue(at, j, false, "history_adapted", "audio generated by " + a.model() + " was passed on as its transcript, if any");
                }
                default -> parts.add(part);
            }
        }
        if (!thinking.isEmpty()) parts.addFirst(Content.text("<thinking>" + thinking + "</thinking>"));
        return parts.isEmpty() ? null : a.toBuilder().content(parts).build();
    }

    private List<ToolResult> results(List<ToolResult> results, int at) {
        var adapted = new ArrayList<ToolResult>();
        for (int j = 0; j < results.size(); j++) {
            var result = results.get(j);
            var remapped = ids.containsKey(result.callId()) ? result.withCallId(ids.get(result.callId())) : result;
            adapted.add(images ? remapped : remapped.withContent(withoutImages(remapped.content(), at, j)));
        }
        return adapted;
    }

    private List<Content> withoutImages(List<Content> parts, int at) {
        var adapted = new ArrayList<Content>();
        for (int j = 0; j < parts.size(); j++) adapted.add(withoutImage(parts.get(j), at, j));
        return adapted;
    }

    /// Images inside tool result `result` are located at that result.
    private List<Content> withoutImages(List<Content> parts, int at, int result) {
        return parts.stream().map(p -> withoutImage(p, at, result)).toList();
    }

    private Content withoutImage(Content part, int at, int j) {
        if (!(part instanceof Content.Image)) return part;
        issue(at, j, true, "image_omitted", "an image was replaced by a placeholder: the model does not accept images");
        return Content.text(IMAGE_PLACEHOLDER);
    }

    /// A lossy conversion: refused under `REJECT_LOSSY`, else recorded — as a soft adaptation when `soft`.
    private void issue(int message, int part, boolean soft, String code, String text) {
        var issue = new HistoryIssue(message, part, new Warning(code, text));
        if (policy == HistoryPolicy.REJECT_LOSSY && !code.equals("incomplete_part_omitted")) throw rejected(issue, "REJECT_LOSSY");
        notes.issue(issue, soft);
    }

    private void lossless(int message, int part, String code, String text) { notes.issue(new HistoryIssue(message, part, new Warning(code, text)), false); }

    private static InvalidRequestException rejected(HistoryIssue issue, String policy) {
        return new InvalidRequestException(LlmException.Details.builder(ErrorCode.INVALID_REQUEST, issue + " (rejected by historyPolicy " + policy + ")").build());
    }

    /// A normalized id another call already uses gets a deterministic suffix, so two calls never share an id.
    private String unique(String normalized, String original) {
        if (normalized.equals(original) || !taken.contains(normalized)) return normalized;
        var suffix = "_" + Integer.toHexString(original.hashCode());
        var base = normalized.length() + suffix.length() > 64 ? normalized.substring(0, 64 - suffix.length()) : normalized;
        var candidate = base + suffix;
        for (int n = 2; taken.contains(candidate); n++) candidate = base + suffix + n;
        return candidate;
    }
}
