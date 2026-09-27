package net.ai.gate.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.internal.serialization.ConversationJson;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// Immutable, thread-safe source of truth of a chat: system prompt, tools and messages, portable across models.
/// Appending returns a new conversation; the SDK adapts a copy to each target model and never edits this one.
public final class Conversation {
    private final @Nullable String system;
    private final List<Tool> tools;
    private final List<Message> messages;
    private final List<Integer> cacheBreakpoints;

    private Conversation(@Nullable String system, List<Tool> tools, List<Message> messages, List<Integer> cacheBreakpoints) {
        this.system = system;
        this.tools = List.copyOf(tools);
        this.messages = List.copyOf(messages);
        this.cacheBreakpoints = List.copyOf(cacheBreakpoints);
        var names = new HashSet<String>();
        for (var tool : this.tools) if (!names.add(tool.name())) throw new IllegalArgumentException("Duplicate tool '" + tool.name() + "'");
        int previous = -1;
        for (var breakpoint : this.cacheBreakpoints) {
            if (breakpoint <= previous || breakpoint > this.messages.size())
                throw new IllegalArgumentException("Cache breakpoints must be ascending and within the messages: " + this.cacheBreakpoints + " for " + this.messages.size() + " messages");
            previous = breakpoint;
        }
    }

    public static Conversation of(String userText) { return builder().user(userText).build(); }
    public static Builder builder() { return new Builder(); }

    /// Top-level instructions.
    public Optional<String> system() { return Optional.ofNullable(system); }
    public List<Tool> tools() { return tools; }
    public List<Message> messages() { return messages; }

    /// Explicit prompt-cache prefix ends, as the number of messages before each marker (`0` = after system and
    /// tools); empty means automatic placement.
    public List<Integer> cacheBreakpoints() { return cacheBreakpoints; }

    public Conversation append(Message... added) {
        var copy = new ArrayList<>(messages);
        copy.addAll(List.of(added));
        return new Conversation(system, tools, copy, cacheBreakpoints);
    }

    /// The reply — tool calls and signatures included — followed by the host's results in one step.
    public Conversation append(AssistantMessage reply, List<ToolResult> results) {
        return results.isEmpty() ? append(reply) : append(reply, ToolResultMessage.of(results));
    }

    public Conversation appendUser(String text) { return append(UserMessage.of(text)); }
    public Conversation withSystem(@Nullable String text) { return new Conversation(text, tools, messages, cacheBreakpoints); }
    public Conversation withTools(List<Tool> replaced) { return new Conversation(system, replaced, messages, cacheBreakpoints); }
    /// A copy with other messages and no explicit breakpoints.
    public Conversation withMessages(List<Message> replaced) { return new Conversation(system, tools, replaced, List.of()); }

    /// A copy with other messages and explicit breakpoints (the SDK's per-call adaptation remaps them).
    /// @throws IllegalArgumentException when the breakpoints are not ascending within the messages
    public Conversation withMessages(List<Message> replaced, List<Integer> breakpoints) { return new Conversation(system, tools, replaced, breakpoints); }

    public Builder toBuilder() {
        var b = new Builder();
        b.system = system;
        b.tools.addAll(tools);
        b.messages.addAll(messages);
        b.cacheBreakpoints.addAll(cacheBreakpoints);
        return b;
    }

    /// The canonical JSON form (`ai-gate.conversation/1`): deterministic member order, absent fields omitted.
    public JsonObject toJson() { return ConversationJson.write(this); }

    /// Reads the canonical form; performs no file or network I/O.
    /// @throws IllegalArgumentException naming the JSON path that does not fit
    public static Conversation fromJson(JsonObject json) { return ConversationJson.read(json); }

    @Override public boolean equals(Object o) {
        return o instanceof Conversation c && Objects.equals(system, c.system) && tools.equals(c.tools)
                && messages.equals(c.messages) && cacheBreakpoints.equals(c.cacheBreakpoints);
    }

    @Override public int hashCode() { return Objects.hash(system, tools, messages, cacheBreakpoints); }

    @Override public String toString() {
        return "Conversation[system=" + (system == null ? "none" : system.length() + " chars") + ", tools="
                + tools.stream().map(Tool::name).toList() + ", messages=" + messages.size() + "]";
    }

    /// Not thread-safe. Singular methods add, plural ones replace.
    public static final class Builder {
        private @Nullable String system;
        private final List<Tool> tools = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();
        private final List<Integer> cacheBreakpoints = new ArrayList<>();

        private Builder() { }

        public Builder system(String text) { system = text; return this; }
        public Builder tool(Tool tool) { tools.add(tool); return this; }
        public Builder tools(List<? extends Tool> replaced) { tools.clear(); tools.addAll(replaced); return this; }
        public Builder user(String text) { return message(UserMessage.of(text)); }
        public Builder user(Content... parts) { return message(UserMessage.of(parts)); }
        /// A prior assistant turn given as plain text (few-shot examples, imported histories).
        public Builder assistant(String text) {
            return message(AssistantMessage.builder(new ModelRef("unknown", "unknown"), "unknown").text(text).build());
        }
        public Builder message(Message message) { messages.add(message); return this; }
        /// Replaces the messages; breakpoints beyond the new size are dropped.
        public Builder messages(List<? extends Message> replaced) {
            messages.clear();
            messages.addAll(replaced);
            cacheBreakpoints.removeIf(b -> b > messages.size());
            return this;
        }

        /// Marks the end of everything added so far as a prompt-cache prefix; overrides automatic placement.
        public Builder cacheBreakpoint() {
            if (cacheBreakpoints.isEmpty() || cacheBreakpoints.getLast() != messages.size()) cacheBreakpoints.add(messages.size());
            return this;
        }

        public Conversation build() { return new Conversation(system, tools, messages, cacheBreakpoints); }
    }
}
