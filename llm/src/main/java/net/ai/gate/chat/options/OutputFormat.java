package net.ai.gate.chat.options;

import net.ai.gate.json.JsonSchema;

/// The shape of the model's answer: plain text (default), any JSON, a JSON Schema, or a Java record.
public sealed interface OutputFormat {
    static OutputFormat text() { return PlainText.INSTANCE; }
    static OutputFormat json() { return AnyJson.INSTANCE; }
    static OutputFormat jsonSchema(JsonSchema schema) { return new Schema("output", schema, true); }
    /// The schema is derived at encode time with the runtime's `JsonMapper`; read with `reply.as(type)`.
    static OutputFormat of(Class<?> recordType) { return new Typed(recordType); }

    enum PlainText implements OutputFormat { INSTANCE }
    enum AnyJson implements OutputFormat { INSTANCE }
    record Schema(String name, JsonSchema schema, boolean strict) implements OutputFormat { }
    record Typed(Class<?> type) implements OutputFormat { }
}
