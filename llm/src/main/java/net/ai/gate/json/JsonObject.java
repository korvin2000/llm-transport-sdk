package net.ai.gate.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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

    /// The array member `name` as a list, or an empty list when absent or of another type.
    public List<JsonValue> array(String name) { return members.get(name) instanceof JsonArray a ? a.values() : List.of(); }

    /// The objects of the array member `name`; other elements are skipped.
    public List<JsonObject> objects(String name) {
        return array(name).stream().filter(JsonObject.class::isInstance).map(JsonObject.class::cast).toList();
    }

    /// The string member `name`, or empty when absent or of another type.
    public Optional<String> optString(String name) {
        return members.get(name) instanceof JsonString s ? Optional.of(s.value()) : Optional.empty();
    }

    /// The integral number member `name`, or empty when absent, of another type or not a `long`.
    public OptionalLong optLong(String name) {
        if (!(members.get(name) instanceof JsonNumber n)) return OptionalLong.empty();
        try { return OptionalLong.of(n.longValue()); } catch (ArithmeticException e) { return OptionalLong.empty(); }
    }

    /// The boolean member `name`; `false` when absent or of another type.
    public boolean bool(String name) { return members.get(name) instanceof JsonBoolean b && b.value(); }

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
