package net.ai.gate.json;

import net.ai.gate.internal.json.JsonWriter;

/// Immutable JSON value. Numbers keep their lexical form and objects keep insertion order, so written JSON is
/// byte-stable (prompt-cache prefixes, cache keys, fixtures). `toString()` returns compact JSON.
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    /// Compact JSON text.
    default String toJson() { return JsonWriter.write(this, false); }

    /// Indented JSON text for people.
    default String toPrettyJson() { return JsonWriter.write(this, true); }
}
