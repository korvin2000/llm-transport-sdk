package net.ai.gate.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.internal.json.JsonReader;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// Aggregates stream events into the reply `complete()` would return: parts by index (they may interleave), deltas
/// until the authoritative `PartEnd`, partial tool arguments parsed from the fragments so far. Every retained form
/// — deltas, authoritative parts, native and unknown payloads — is charged against [#LIMIT_CHARS] (characters). A
/// snapshot carries the last usage update and marks tool calls and reasoning that never got their `PartEnd` as
/// incomplete. Confined to the consuming thread; [#snapshot()] may be read from any thread.
final class Accumulator {
    static final long LIMIT_CHARS = 32L << 20;

    private enum Kind { TEXT, REASONING, TOOL }

    private static final class Part {
        final Kind kind;
        final StringBuilder text = new StringBuilder();
        @Nullable String callId, name;
        @Nullable Content done;

        Part(Kind kind) { this.kind = kind; }

        Content content() {
            if (done != null) return done;
            return switch (kind) {
                case TEXT -> Content.text(text.toString());
                case REASONING -> Content.reasoning(text.toString());
                case TOOL -> ToolCall.of(callId == null ? "call_unknown" : callId, name == null ? "unknown" : name, text.toString());
            };
        }
    }

    private final ModelRef model;
    private final String api;
    private final TreeMap<Integer, Part> parts = new TreeMap<>();
    private @Nullable String responseId, responseModel;
    private @Nullable Usage observed;
    private long size, outputChars;

    Accumulator(ModelRef model, String api) { this.model = model; this.api = api; }

    /// The event to deliver: tool-call deltas carry the parsed partial arguments, and `Done` carries the aggregate.
    synchronized ChatEvent accept(ChatEvent event) {
        return switch (event) {
            case ChatEvent.TextDelta d -> { append(d.index(), Kind.TEXT, d.text()); yield d; }
            case ChatEvent.ReasoningDelta d -> { append(d.index(), Kind.REASONING, d.text()); yield d; }
            case ChatEvent.ToolCallStart s -> {
                var part = part(s.index(), Kind.TOOL);
                part.callId = s.callId();
                part.name = s.name();
                yield s;
            }
            case ChatEvent.ToolCallDelta d -> {
                var part = append(d.index(), Kind.TOOL, d.fragment());
                yield new ChatEvent.ToolCallDelta(d.index(), d.fragment(), JsonReader.parsePartialObject(part.text.toString()));
            }
            case ChatEvent.PartEnd p -> {
                charge(sizeOf(p.content()));
                var part = part(p.index(), kindOf(p.content()));
                part.done = p.content();
                part.text.setLength(0);   // the authoritative part replaces the deltas
                yield p;
            }
            case ChatEvent.Started s -> {
                responseId = s.responseId().orElse(null);
                responseModel = s.responseModel().orElse(null);
                yield s;
            }
            case ChatEvent.Done d -> ChatEvent.Done.of(aggregate(d.message()));
            case ChatEvent.Unknown u -> { charge(u.raw().toJson().length()); yield u; }
            case ChatEvent.UsageUpdate u -> {
                var update = u.observed().toBuilder().finalForCall(false).build();
                observed = update;
                yield new ChatEvent.UsageUpdate(update);
            }
        };
    }

    /// What arrived so far, as an appendable reply: parts cut off are marked, and never sent back to a model.
    synchronized AssistantMessage snapshot() {
        var b = AssistantMessage.builder(model, api).content(contents()).stopReason(StopReason.ABORTED)
                .responseId(responseId).responseModel(responseModel);
        if (observed != null) b.usage(observed);
        int index = 0;
        for (var part : parts.values()) {
            if (part.done == null && part.kind != Kind.TEXT) b.incompletePart(index);
            index++;
        }
        return b.build();
    }

    /// Output tokens of the last usage update, if it reported them.
    synchronized @Nullable Long outputTokens() {
        var usage = observed;
        return usage == null || usage.output().isEmpty() ? null : usage.output().getAsLong();
    }

    /// Characters of text, reasoning and tool-argument deltas so far.
    synchronized long outputChars() { return outputChars; }

    private AssistantMessage aggregate(AssistantMessage fromDecoder) {
        var b = fromDecoder.toBuilder();
        if (!parts.isEmpty()) b.content(contents());
        if (fromDecoder.responseId().isEmpty()) b.responseId(responseId);
        if (fromDecoder.responseModel().isEmpty()) b.responseModel(responseModel);
        return b.build();
    }

    private List<Content> contents() {
        var contents = new ArrayList<Content>();
        parts.values().forEach(p -> contents.add(p.content()));
        return contents;
    }

    private Part append(int index, Kind kind, String delta) {
        charge(delta.length());
        outputChars += delta.length();
        var part = part(index, kind);
        part.text.append(delta);
        return part;
    }

    private void charge(long chars) {
        size += chars;
        if (size > LIMIT_CHARS) throw new IllegalStateException("The reply exceeds the accumulation limit of " + LIMIT_CHARS + " characters");
    }

    private Part part(int index, Kind kind) { return parts.computeIfAbsent(index, _ -> new Part(kind)); }

    private static Kind kindOf(Content content) {
        return content instanceof ToolCall ? Kind.TOOL : content instanceof Content.Reasoning ? Kind.REASONING : Kind.TEXT;
    }

    /// The characters a part retains, whatever its form.
    static long sizeOf(Content content) {
        return switch (content) {
            case Content.Text t -> t.text().length();
            case Content.Reasoning r -> r.text().map(String::length).orElse(0) + r.signature().map(String::length).orElse(0)
                    + r.providerData().toJson().length();
            case ToolCall c -> c.name().length() + c.argumentsJson().length();
            case ToolResult r -> r.content().stream().mapToLong(Accumulator::sizeOf).sum();
            case Content.Refusal r -> r.text().length();
            case Content.Unknown u -> u.raw().toJson().length();
            case Content.Image i -> (i.source() instanceof Content.Source.Inline inline ? inline.data().length : 64) + i.providerData().toJson().length();
            case Content.Document d -> d.source() instanceof Content.Source.Inline inline ? inline.data().length : 64;
            case Content.Audio a -> a.data().length;
        };
    }
}
