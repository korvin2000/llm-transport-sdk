package net.ai.gate.spi.protocol;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/// Immutable unit of a stream: one SSE event (multi-line data joined with `\n`) or one NDJSON line.
public final class Frame {
    private final @Nullable String event, id;
    private final String data;

    private Frame(@Nullable String event, String data, @Nullable String id) { this.event = event; this.data = data; this.id = id; }

    public static Frame of(String data) { return new Frame(null, data, null); }
    public static Frame of(@Nullable String event, String data) { return new Frame(event, data, null); }
    public static Frame of(@Nullable String event, String data, @Nullable String id) { return new Frame(event, data, id); }

    /// The SSE `event:` field.
    public Optional<String> event() { return Optional.ofNullable(event); }
    public String data() { return data; }
    public Optional<String> id() { return Optional.ofNullable(id); }

    @Override public boolean equals(Object o) {
        return o instanceof Frame f && Objects.equals(event, f.event) && data.equals(f.data) && Objects.equals(id, f.id);
    }

    @Override public int hashCode() { return Objects.hash(event, data, id); }
    @Override public String toString() { return "Frame[" + (event == null ? "" : event + ": ") + data.length() + " chars]"; }
}
