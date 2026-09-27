package net.ai.gate.internal.json;

import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.json.JsonValue;

/// The default [JsonMapper]: record binding as in [net.ai.gate.json.Json].
public enum RecordMapper implements JsonMapper {
    INSTANCE;

    @Override public JsonValue toJson(Object value) { return RecordBinder.toJson(value); }
    @Override public <T> T fromJson(JsonValue json, Class<T> type) { return RecordBinder.fromJson(json, type); }
    @Override public JsonSchema schemaFor(Class<?> type) { return JsonSchema.of(RecordBinder.schema(type)); }
}
