package net.ai.gate.json;

/// Immutable JSON boolean: exactly two instances.
public final class JsonBoolean implements JsonValue {
    public static final JsonBoolean TRUE = new JsonBoolean(true), FALSE = new JsonBoolean(false);

    private final boolean value;

    private JsonBoolean(boolean value) { this.value = value; }

    public static JsonBoolean of(boolean value) { return value ? TRUE : FALSE; }

    public boolean value() { return value; }

    @Override public String toString() { return Boolean.toString(value); }
}
