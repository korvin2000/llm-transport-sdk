package net.ai.gate.json;

/// Immutable JSON Schema document, sent to models for tool parameters and structured output.
public final class JsonSchema {
    private final JsonObject json;

    private JsonSchema(JsonObject json) { this.json = json; }

    public static JsonSchema of(JsonObject json) { return new JsonSchema(json); }

    /// @throws IllegalArgumentException when `json` is not a JSON object
    public static JsonSchema parse(String json) {
        if (Json.parse(json) instanceof JsonObject o) return new JsonSchema(o);
        throw new IllegalArgumentException("A JSON Schema must be a JSON object");
    }

    public JsonObject asJson() { return json; }

    @Override public boolean equals(Object o) { return o instanceof JsonSchema s && json.equals(s.json); }
    @Override public int hashCode() { return json.hashCode(); }
    @Override public String toString() { return json.toJson(); }
}
