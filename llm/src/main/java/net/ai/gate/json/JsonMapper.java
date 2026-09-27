package net.ai.gate.json;

/// **SPI** (host). Replaces record binding for structured output and tool arguments, for example with a
/// Jackson-backed mapper for POJOs. Thread-safe. The default binds records as [Json] does.
public interface JsonMapper {
    JsonValue toJson(Object value);

    <T> T fromJson(JsonValue json, Class<T> type);

    JsonSchema schemaFor(Class<?> type);
}
