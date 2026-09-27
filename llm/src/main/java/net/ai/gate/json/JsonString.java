package net.ai.gate.json;

import java.util.Objects;

/// Immutable JSON string.
public final class JsonString implements JsonValue {
    private final String value;

    private JsonString(String value) { this.value = value; }

    public static JsonString of(String value) { return new JsonString(Objects.requireNonNull(value, "value")); }

    public String value() { return value; }

    @Override public boolean equals(Object o) { return o instanceof JsonString s && value.equals(s.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return toJson(); }
}
