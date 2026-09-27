package net.ai.gate.json;

import java.util.Arrays;
import java.util.LinkedHashMap;

import net.ai.gate.internal.json.JsonReader;
import net.ai.gate.internal.json.RecordBinder;
import org.jspecify.annotations.Nullable;

/// Static entry point: parsing, building, and record binding with the default mapper.
///
/// Binding uses canonical constructors and accessors only — no field access, no deep reflection. Supported:
/// records, enums, strings, numbers, booleans, lists, sets, maps with string keys, `Optional`, arrays,
/// `java.time` values, `URI`, `UUID` and [JsonValue] itself. SDK value types expose their own canonical forms
/// (`Model.toJson()`, `Conversation.toJson()`).
public final class Json {
    private Json() { }

    /// Parses JSON text; depth and size are bounded.
    /// @throws IllegalArgumentException with the offset of the first syntax error
    public static JsonValue parse(String json) { return JsonReader.parse(json); }

    /// An object from alternating names and values: `Json.object("path", "a.txt", "limit", 10)`.
    public static JsonObject object(@Nullable Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) throw new IllegalArgumentException("Expected name/value pairs");
        var members = new LinkedHashMap<String, JsonValue>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (!(namesAndValues[i] instanceof String name)) throw new IllegalArgumentException("Name at " + i + " is not a string");
            members.put(name, valueOf(namesAndValues[i + 1]));
        }
        return JsonObject.of(members);
    }

    public static JsonArray array(@Nullable Object... values) {
        return JsonArray.of(Arrays.stream(values).map(Json::valueOf).toList());
    }

    /// The JSON form of a value; `null` becomes [JsonNull].
    /// @throws IllegalArgumentException for types without a JSON form
    public static JsonValue valueOf(@Nullable Object value) { return RecordBinder.toJson(value); }

    /// Binds JSON to `type` through canonical constructors.
    /// @throws IllegalArgumentException naming the JSON path that does not fit
    public static <T> T convert(JsonValue json, Class<T> type) { return RecordBinder.fromJson(json, type); }

    /// A strict-compatible schema for a record: every component required, `Optional<T>` nullable, enums as `enum`,
    /// [Description] texts as `description`.
    public static JsonSchema schemaOf(Class<?> recordType) { return JsonSchema.of(RecordBinder.schema(recordType)); }
}
