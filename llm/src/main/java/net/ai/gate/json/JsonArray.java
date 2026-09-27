package net.ai.gate.json;

import java.util.List;

/// Immutable JSON array.
public final class JsonArray implements JsonValue {
    private static final JsonArray EMPTY = new JsonArray(List.of());

    private final List<JsonValue> values;

    private JsonArray(List<JsonValue> values) { this.values = values; }

    public static JsonArray of(List<? extends JsonValue> values) {
        return values.isEmpty() ? EMPTY : new JsonArray(List.copyOf(values));
    }

    public List<JsonValue> values() { return values; }

    @Override public boolean equals(Object o) { return o instanceof JsonArray a && values.equals(a.values); }
    @Override public int hashCode() { return values.hashCode(); }
    @Override public String toString() { return toJson(); }
}
