package net.ai.gate.internal.json;

import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;

/// Writes JSON in member insertion order: identical trees produce identical bytes.
public final class JsonWriter {
    private JsonWriter() { }

    public static String write(JsonValue value, boolean pretty) {
        var sb = new StringBuilder();
        write(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonValue value, boolean pretty, int depth) {
        switch (value) {
            case JsonObject o -> {
                sb.append('{');
                int n = 0;
                for (var e : o.members().entrySet()) {
                    if (n++ > 0) sb.append(',');
                    indent(sb, pretty, depth + 1);
                    quote(sb, e.getKey());
                    sb.append(pretty ? ": " : ":");
                    write(sb, e.getValue(), pretty, depth + 1);
                }
                if (n > 0) indent(sb, pretty, depth);
                sb.append('}');
            }
            case JsonArray a -> {
                sb.append('[');
                int n = 0;
                for (var v : a.values()) {
                    if (n++ > 0) sb.append(',');
                    indent(sb, pretty, depth + 1);
                    write(sb, v, pretty, depth + 1);
                }
                if (n > 0) indent(sb, pretty, depth);
                sb.append(']');
            }
            case JsonString s -> quote(sb, s.value());
            case JsonNumber n -> sb.append(n);
            case JsonBoolean b -> sb.append(b.value());
            case JsonNull _ -> sb.append("null");
        }
    }

    private static void indent(StringBuilder sb, boolean pretty, int depth) {
        if (pretty) sb.append('\n').repeat("  ", depth);
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append("\\u%04x".formatted((int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
