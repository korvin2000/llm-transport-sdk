package net.ai.gate.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/// Immutable JSON object; members keep insertion order.
public final class JsonObject implements JsonValue {
    private static final JsonObject EMPTY = new JsonObject(new LinkedHashMap<>());

    private final Map<String, JsonValue> members;

    private JsonObject(LinkedHashMap<String, JsonValue> members) { this.members = Collections.unmodifiableMap(members); }

    /// A copy of `members`, in their iteration order.
    public static JsonObject of(Map<String, ? extends JsonValue> members) {
        return members.isEmpty() ? EMPTY : new JsonObject(new LinkedHashMap<>(members));
    }

    public Optional<JsonValue> get(String name) { return Optional.ofNullable(members.get(name)); }

    /// The string member `name`.
    /// @throws IllegalArgumentException when the member is absent or not a string
    public String string(String name) {
        if (members.get(name) instanceof JsonString s) return s.value();
        throw new IllegalArgumentException("JSON member '" + name + "' is absent or not a string");
    }

    /// The object member `name`, or an empty object when absent or of another type.
    public JsonObject object(String name) { return members.get(name) instanceof JsonObject o ? o : EMPTY; }

    public Map<String, JsonValue> members() { return members; }

    public boolean isEmpty() { return members.isEmpty(); }

    /// A copy with one member added or replaced; `value` is converted with [Json#valueOf].
    public JsonObject with(String name, @Nullable Object value) {
        var copy = new LinkedHashMap<>(members);
        copy.put(name, Json.valueOf(value));
        return new JsonObject(copy);
    }

    /// A copy without the member `name`.
    public JsonObject without(String name) {
        if (!members.containsKey(name)) return this;
        var copy = new LinkedHashMap<>(members);
        copy.remove(name);
        return new JsonObject(copy);
    }

    @Override public boolean equals(Object o) { return o instanceof JsonObject j && members.equals(j.members); }
    @Override public int hashCode() { return members.hashCode(); }
    @Override public String toString() { return toJson(); }
}
