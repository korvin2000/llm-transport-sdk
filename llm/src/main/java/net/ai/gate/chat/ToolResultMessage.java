package net.ai.gate.chat;

import java.time.Instant;
import java.util.List;

import net.ai.gate.chat.content.ToolResult;

/// Immutable turn carrying the host's answers to the tool calls of the preceding reply.
public final class ToolResultMessage implements Message {
    private final List<ToolResult> results;
    private final Instant timestamp;

    private ToolResultMessage(List<ToolResult> results, Instant timestamp) {
        if (results.isEmpty()) throw new IllegalArgumentException("A tool result message needs at least one result");
        this.results = List.copyOf(results);
        this.timestamp = timestamp;
    }

    public static ToolResultMessage of(List<ToolResult> results) { return new ToolResultMessage(results, Instant.now()); }
    public static ToolResultMessage of(ToolResult... results) { return of(List.of(results)); }
    /// For deserialization: a turn with its original timestamp.
    public static ToolResultMessage of(List<ToolResult> results, Instant timestamp) { return new ToolResultMessage(results, timestamp); }

    public List<ToolResult> results() { return results; }
    @Override public Instant timestamp() { return timestamp; }

    /// A copy with other results (id remapping during hand-off).
    public ToolResultMessage withResults(List<ToolResult> replaced) { return new ToolResultMessage(replaced, timestamp); }

    @Override public boolean equals(Object o) { return o instanceof ToolResultMessage m && results.equals(m.results); }
    @Override public int hashCode() { return results.hashCode(); }
    @Override public String toString() { return "ToolResultMessage" + results; }
}
