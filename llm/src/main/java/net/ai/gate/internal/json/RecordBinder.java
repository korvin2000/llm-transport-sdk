package net.ai.gate.internal.json;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.ai.gate.json.Description;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Record ↔ JSON binding and schema derivation through canonical constructors and accessors only.
public final class RecordBinder {
    /// Types written as their `toString()` and read with their `parse`/`create` factory.
    private static final Map<Class<?>, Function<String, Object>> TEXT_TYPES = Map.of(
            Instant.class, Instant::parse, LocalDate.class, LocalDate::parse, LocalDateTime.class, LocalDateTime::parse,
            LocalTime.class, LocalTime::parse, OffsetDateTime.class, OffsetDateTime::parse,
            ZonedDateTime.class, ZonedDateTime::parse, Duration.class, Duration::parse, URI.class, URI::create,
            UUID.class, UUID::fromString);
    private static final Map<Class<?>, String> FORMATS = Map.of(Instant.class, "date-time",
            OffsetDateTime.class, "date-time", ZonedDateTime.class, "date-time", LocalDate.class, "date",
            LocalTime.class, "time", Duration.class, "duration", URI.class, "uri", UUID.class, "uuid");

    private RecordBinder() { }

    // ---- Java → JSON

    public static JsonValue toJson(@Nullable Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case JsonValue j -> j;
            case String s -> JsonString.of(s);
            case Character c -> JsonString.of(c.toString());
            case Boolean b -> JsonBoolean.of(b);
            case Integer n -> JsonNumber.of(n.longValue());
            case Long n -> JsonNumber.of(n.longValue());
            case Short n -> JsonNumber.of(n.longValue());
            case Byte n -> JsonNumber.of(n.longValue());
            case BigDecimal n -> JsonNumber.of(n);
            case BigInteger n -> JsonNumber.of(new BigDecimal(n));
            case Double n -> JsonNumber.of(n.doubleValue());
            case Float n -> JsonNumber.of(n.doubleValue());
            case Enum<?> e -> JsonString.of(e.name());
            case Optional<?> o -> toJson(o.orElse(null));
            case OptionalInt o -> o.isPresent() ? JsonNumber.of(o.getAsInt()) : JsonNull.INSTANCE;
            case OptionalLong o -> o.isPresent() ? JsonNumber.of(o.getAsLong()) : JsonNull.INSTANCE;
            case OptionalDouble o -> o.isPresent() ? JsonNumber.of(o.getAsDouble()) : JsonNull.INSTANCE;
            case Collection<?> c -> JsonArray.of(c.stream().map(RecordBinder::toJson).toList());
            case Map<?, ?> m -> {
                var members = new LinkedHashMap<String, JsonValue>();
                m.forEach((k, v) -> members.put(String.valueOf(k), toJson(v)));
                yield JsonObject.of(members);
            }
            case Record r -> record(r);
            case TemporalAccessor t -> JsonString.of(t.toString());
            case TemporalAmount t -> JsonString.of(t.toString());
            case URI u -> JsonString.of(u.toString());
            case UUID u -> JsonString.of(u.toString());
            case Path p -> JsonString.of(p.toString().replace('\\', '/'));
            default -> {
                if (!value.getClass().isArray()) throw new IllegalArgumentException(
                        "No JSON form for " + value.getClass().getName() + "; use a record or configure a JsonMapper");
                var values = new ArrayList<JsonValue>();
                for (int i = 0; i < Array.getLength(value); i++) values.add(toJson(Array.get(value, i)));
                yield JsonArray.of(values);
            }
        };
    }

    private static JsonObject record(Record r) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (var c : r.getClass().getRecordComponents()) {
            try {
                var accessor = c.getAccessor();
                accessor.trySetAccessible();
                members.put(c.getName(), toJson(accessor.invoke(r)));
            } catch (ReflectiveOperationException e) {
                throw inaccessible(r.getClass(), e);
            }
        }
        return JsonObject.of(members);
    }

    // ---- JSON → Java

    @SuppressWarnings("unchecked") // bind() produces an instance of `type`, or its wrapper for primitives
    public static <T> T fromJson(JsonValue json, Class<T> type) {
        var value = bind(json, type, "$");
        if (value == null) throw new IllegalArgumentException("$: JSON null cannot bind to " + type.getName());
        return (T) value;
    }

    private static @Nullable Object bind(JsonValue json, Type type, String path) {
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            var args = p.getActualTypeArguments();
            if (raw == Optional.class) return json instanceof JsonNull ? Optional.empty() : Optional.ofNullable(bind(json, args[0], path));
            if (raw == List.class || raw == Collection.class || raw == Iterable.class) return Collections.unmodifiableList(list(json, args[0], path));
            if (raw == Set.class) return Collections.unmodifiableSet(new LinkedHashSet<>(list(json, args[0], path)));
            if (raw == Map.class) {
                var map = new LinkedHashMap<String, @Nullable Object>();
                object(json, path).members().forEach((k, v) -> map.put(k, bind(v, args[1], path + "." + k)));
                return Collections.unmodifiableMap(map);
            }
            return bind(json, raw, path);
        }
        if (!(type instanceof Class<?> c)) throw error(path, "unsupported type " + type.getTypeName());
        if (json instanceof JsonNull) {
            if (c.isPrimitive()) throw error(path, "null for " + c.getName());
            return c == Optional.class ? Optional.empty() : null;
        }
        if (JsonValue.class.isAssignableFrom(c)) {
            if (c.isInstance(json)) return json;
            throw error(path, "expected " + c.getSimpleName());
        }
        if (c == String.class) return text(json, path);
        if (c == boolean.class || c == Boolean.class) {
            if (json instanceof JsonBoolean b) return b.value();
            throw error(path, "expected a boolean");
        }
        if (c == char.class || c == Character.class) return character(json, path);
        if (c.isPrimitive() || Number.class.isAssignableFrom(c)) return number(json, c, path);
        if (c.isEnum()) {
            var name = text(json, path);
            for (var k : c.getEnumConstants()) if (((Enum<?>) k).name().equalsIgnoreCase(name)) return k;
            throw error(path, "'" + name + "' is not a constant of " + c.getSimpleName());
        }
        if (c.isRecord()) return record(json, c, path);
        if (c.isArray()) {
            var values = list(json, c.getComponentType(), path);
            var array = Array.newInstance(c.getComponentType(), values.size());
            for (int i = 0; i < values.size(); i++) Array.set(array, i, values.get(i));
            return array;
        }
        var parse = TEXT_TYPES.get(c);
        if (parse != null) {
            try { return parse.apply(text(json, path)); } catch (RuntimeException e) { throw error(path, e.getMessage()); }
        }
        throw error(path, "no JSON binding for " + c.getName());
    }

    private static Object record(JsonValue json, Class<?> type, String path) {
        var members = object(json, path);
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var args = new @Nullable Object[components.length];
        for (int i = 0; i < components.length; i++) {
            var c = components[i];
            types[i] = c.getType();
            var member = members.get(c.getName());
            if (member.isPresent()) args[i] = bind(member.get(), c.getGenericType(), path + "." + c.getName());
            else if (c.getType() == Optional.class) args[i] = Optional.empty();
            else throw error(path, "missing member '" + c.getName() + "'");
        }
        try {
            var constructor = type.getDeclaredConstructor(types);
            constructor.trySetAccessible();
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw error(path, "rejected by " + type.getSimpleName() + ": " + e.getCause().getMessage());
        } catch (ReflectiveOperationException e) {
            throw inaccessible(type, e);
        }
    }

    private static List<@Nullable Object> list(JsonValue json, Type element, String path) {
        if (!(json instanceof JsonArray a)) throw error(path, "expected an array");
        var values = new ArrayList<@Nullable Object>();
        for (int i = 0; i < a.values().size(); i++) values.add(bind(a.values().get(i), element, path + "[" + i + "]"));
        return values;
    }

    private static Object number(JsonValue json, Class<?> type, String path) {
        if (!(json instanceof JsonNumber n)) throw error(path, "expected a number");
        var v = n.value();
        try {
            if (type == int.class || type == Integer.class) return v.intValueExact();
            if (type == long.class || type == Long.class) return v.longValueExact();
            if (type == short.class || type == Short.class) return v.shortValueExact();
            if (type == byte.class || type == Byte.class) return v.byteValueExact();
            if (type == double.class || type == Double.class) return v.doubleValue();
            if (type == float.class || type == Float.class) return v.floatValue();
            if (type == BigInteger.class) return v.toBigIntegerExact();
            if (type == BigDecimal.class || type == Number.class) return v;
        } catch (ArithmeticException e) {
            throw error(path, n + " does not fit " + type.getSimpleName());
        }
        throw error(path, "no JSON binding for " + type.getName());
    }

    private static String text(JsonValue json, String path) {
        if (json instanceof JsonString s) return s.value();
        throw error(path, "expected a string");
    }

    /// A one-character JSON string binds to `char`/`Character`; any other length is a binding error.
    private static char character(JsonValue json, String path) {
        var s = text(json, path);
        if (s.length() != 1) throw error(path, "expected a one-character string, got '" + s + "'");
        return s.charAt(0);
    }

    private static JsonObject object(JsonValue json, String path) {
        if (json instanceof JsonObject o) return o;
        throw error(path, "expected an object");
    }

    // ---- schema

    public static JsonObject schema(Class<?> recordType) {
        if (!recordType.isRecord()) throw new IllegalArgumentException(recordType.getName() + " is not a record");
        return schema(recordType, new HashSet<>());
    }

    private static JsonObject schema(Type type, Set<Class<?>> visiting) {
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            var args = p.getActualTypeArguments();
            if (raw == Optional.class) return nullable(schema(args[0], visiting));
            if (Collection.class.isAssignableFrom(raw) || raw == Iterable.class)
                return Json.object("type", "array", "items", schema(args[0], visiting));
            if (raw == Map.class) return Json.object("type", "object", "additionalProperties", schema(args[1], visiting));
            return schema(raw, visiting);
        }
        if (!(type instanceof Class<?> c)) throw new IllegalArgumentException("No schema for " + type.getTypeName());
        if (c == String.class || c == char.class || c == Character.class) return Json.object("type", "string");
        if (c == boolean.class || c == Boolean.class) return Json.object("type", "boolean");
        if (c == int.class || c == long.class || c == short.class || c == byte.class || c == Integer.class
                || c == Long.class || c == Short.class || c == Byte.class || c == BigInteger.class) return Json.object("type", "integer");
        if (c.isPrimitive() || Number.class.isAssignableFrom(c)) return Json.object("type", "number");
        if (c.isEnum()) return Json.object("type", "string",
                "enum", Arrays.stream(c.getEnumConstants()).map(k -> ((Enum<?>) k).name()).toList());
        if (c.isArray()) return Json.object("type", "array", "items", schema(c.getComponentType(), visiting));
        if (c.isRecord()) return recordSchema(c, visiting);
        if (TEXT_TYPES.containsKey(c)) {
            var format = FORMATS.get(c);
            return format == null ? Json.object("type", "string") : Json.object("type", "string", "format", format);
        }
        if (c == Object.class || JsonValue.class.isAssignableFrom(c)) return JsonObject.of(Map.of());
        throw new IllegalArgumentException("No schema for " + c.getName());
    }

    private static JsonObject recordSchema(Class<?> type, Set<Class<?>> visiting) {
        if (!visiting.add(type)) throw new IllegalArgumentException("Recursive record " + type.getName() + " has no finite schema");
        var properties = new LinkedHashMap<String, JsonValue>();
        var required = new ArrayList<String>();
        for (RecordComponent c : type.getRecordComponents()) {
            var property = schema(c.getGenericType(), visiting);
            var description = c.getAnnotation(Description.class);
            properties.put(c.getName(), description == null ? property : property.with("description", description.value()));
            required.add(c.getName());
        }
        visiting.remove(type);
        var schema = Json.object("type", "object");
        var description = type.getAnnotation(Description.class);
        if (description != null) schema = schema.with("description", description.value());
        return schema.with("properties", JsonObject.of(properties)).with("required", required).with("additionalProperties", false);
    }

    private static JsonObject nullable(JsonObject schema) {
        return schema.get("type").orElse(null) instanceof JsonString t && !schema.members().containsKey("enum")
                ? schema.with("type", List.of(t.value(), "null"))
                : Json.object("anyOf", List.of(schema, Json.object("type", "null")));
    }

    private static IllegalArgumentException error(String path, @Nullable String problem) {
        return new IllegalArgumentException(path + ": " + problem);
    }

    private static IllegalArgumentException inaccessible(Class<?> type, ReflectiveOperationException e) {
        return new IllegalArgumentException("Cannot bind " + type.getName() + " (" + e + "); in a named module, export or "
                + "open its package to net.ai.gate", e);
    }
}
