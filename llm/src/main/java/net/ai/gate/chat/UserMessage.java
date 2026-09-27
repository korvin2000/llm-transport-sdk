package net.ai.gate.chat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import net.ai.gate.chat.content.Content;

/// Immutable user turn: text and media parts.
public final class UserMessage implements Message {
    private final List<Content> content;
    private final Instant timestamp;

    private UserMessage(List<Content> content, Instant timestamp) {
        if (content.isEmpty()) throw new IllegalArgumentException("A user message needs at least one part");
        this.content = List.copyOf(content);
        this.timestamp = timestamp;
    }

    public static UserMessage of(String text) { return new UserMessage(List.of(Content.text(text)), Instant.now()); }
    public static UserMessage of(Content... parts) { return new UserMessage(List.of(parts), Instant.now()); }
    public static UserMessage of(List<Content> parts, Instant timestamp) { return new UserMessage(parts, timestamp); }

    public List<Content> content() { return content; }
    @Override public Instant timestamp() { return timestamp; }

    /// The text parts, concatenated.
    public String text() {
        return content.stream().filter(Content.Text.class::isInstance).map(c -> ((Content.Text) c).text()).collect(Collectors.joining());
    }

    /// A copy with other parts (image downgrades during hand-off).
    public UserMessage withContent(List<Content> parts) { return new UserMessage(parts, timestamp); }

    @Override public boolean equals(Object o) { return o instanceof UserMessage m && content.equals(m.content); }
    @Override public int hashCode() { return content.hashCode(); }
    @Override public String toString() { return "UserMessage" + content; }
}
