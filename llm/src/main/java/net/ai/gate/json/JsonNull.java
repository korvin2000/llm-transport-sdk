package net.ai.gate.json;

/// The JSON `null` literal.
public enum JsonNull implements JsonValue {
    INSTANCE;

    @Override public String toString() { return "null"; }
}
