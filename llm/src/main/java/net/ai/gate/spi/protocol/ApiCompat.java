package net.ai.gate.spi.protocol;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// **SPI**. Typed compat flags of one API family (`OpenAiCompletionsCompat`, `AnthropicCompat`…): the quirks of
/// compatible providers as data, never as base-URL sniffing. Immutable; merged field by field: preset ▷ provider
/// configuration ▷ model. Unset fields take the type's documented defaults. Each type also reads its canonical JSON
/// form with a static `fromJson(JsonObject)`, which fails naming an unknown member (members prefixed `x-` are ignored).
public interface ApiCompat {
    /// The `WireApi` id these flags configure.
    String api();

    /// A copy in which every field set in `higher` wins; `higher` has the same class.
    ApiCompat overriddenBy(ApiCompat higher);

    /// The canonical JSON form (`ProvidersConfig`): the fields that are set, enum values in lower case.
    JsonObject toJson();

    /// For [#toJson()]: alternating names and values; `null` values are left out, enums written in lower case.
    static JsonObject json(@Nullable Object... namesAndValues) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (int i = 0; i < namesAndValues.length; i += 2)
            if (namesAndValues[i + 1] instanceof Object value)
                members.put((String) namesAndValues[i], Json.valueOf(value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : value));
        return JsonObject.of(members);
    }

    /// For `fromJson`: the boolean member `name`.
    static boolean flag(JsonValue value, String name) {
        if (value instanceof JsonBoolean b) return b.value();
        throw new IllegalArgumentException("compat '" + name + "' must be a boolean");
    }

    /// For `fromJson`: the string member `name`.
    static String text(JsonValue value, String name) {
        if (value instanceof JsonString s) return s.value();
        throw new IllegalArgumentException("compat '" + name + "' must be a string");
    }

    /// For `fromJson`: the enum member `name`, in any case.
    static <E extends Enum<E>> E choice(JsonValue value, String name, Class<E> type) {
        if (value instanceof JsonString s)
            for (var constant : type.getEnumConstants()) if (constant.name().equalsIgnoreCase(s.value())) return constant;
        throw new IllegalArgumentException("compat '" + name + "' must be one of "
                + Arrays.stream(type.getEnumConstants()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList());
    }

    /// For `fromJson`: fails on a member no field reads, unless it is prefixed `x-`.
    static void unknown(String name) {
        if (!name.startsWith("x-")) throw new IllegalArgumentException("unknown compat field '" + name + "'");
    }
}
