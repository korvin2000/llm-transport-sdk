package net.ai.gate.internal.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Recursive-descent JSON parser with bounded depth and size. In partial mode it parses a prefix of a document —
/// streamed tool arguments — closing open structures and dropping the incomplete trailing token.
public final class JsonReader {
    static final int MAX_DEPTH = 256, MAX_CHARS = 64 << 20;

    private final String s;
    private final boolean partial;
    private int i;

    private JsonReader(String s, boolean partial) {
        if (s.length() > MAX_CHARS) throw new IllegalArgumentException("JSON text exceeds " + MAX_CHARS + " characters");
        this.s = s;
        this.partial = partial;
    }

    public static JsonValue parse(String text) {
        var r = new JsonReader(text, false);
        var value = r.value(0);
        r.ws();
        if (r.i < r.s.length()) throw r.error("trailing characters");
        return value;
    }

    /// Best effort, never throws: the members a complete object starting with `text` would at least contain.
    public static JsonObject parsePartialObject(String text) {
        try {
            return new JsonReader(text, true).next(0) instanceof JsonObject o ? o : JsonObject.of(Map.of());
        } catch (IllegalArgumentException e) {
            return JsonObject.of(Map.of());
        }
    }

    private JsonValue value(int depth) {
        var v = next(depth);
        if (v == null) throw error("unexpected end of input");
        return v;
    }

    /// `null` only in partial mode, for an incomplete trailing token.
    private @Nullable JsonValue next(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        ws();
        if (i >= s.length()) return end();
        return switch (s.charAt(i)) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", JsonBoolean.TRUE);
            case 'f' -> literal("false", JsonBoolean.FALSE);
            case 'n' -> literal("null", JsonNull.INSTANCE);
            default -> number();
        };
    }

    private @Nullable JsonValue object(int depth) {
        i++;
        var members = new LinkedHashMap<String, JsonValue>();
        ws();
        if (at('}')) return JsonObject.of(members);
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') return closeOr(JsonObject.of(members), "expected a member name");
            var name = string();
            ws();
            if (name == null || !at(':')) return closeOr(JsonObject.of(members), "expected ':'");
            var value = next(depth + 1);
            if (value == null) return JsonObject.of(members);
            members.put(((JsonString) name).value(), value);
            ws();
            if (at('}')) return JsonObject.of(members);
            if (!at(',')) return closeOr(JsonObject.of(members), "expected ',' or '}'");
        }
    }

    private @Nullable JsonValue array(int depth) {
        i++;
        var values = new ArrayList<JsonValue>();
        ws();
        if (at(']')) return JsonArray.of(values);
        while (true) {
            var value = next(depth + 1);
            if (value == null) return JsonArray.of(values);
            values.add(value);
            ws();
            if (at(']')) return JsonArray.of(values);
            if (!at(',')) return closeOr(JsonArray.of(values), "expected ',' or ']'");
        }
    }

    /// A backslash-u escape is taken as one UTF-16 code unit; an unpaired surrogate (a lone high or low surrogate)
    /// is accepted as-is and not rejected, since JSON text does not require well-formed UTF-16.
    private @Nullable JsonValue string() {
        i++;
        var sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return JsonString.of(sb.toString());
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) { i = s.length(); end(); return JsonString.of(sb.toString()); }
                    try { sb.append((char) Integer.parseInt(s, i, i + 4, 16)); } catch (NumberFormatException x) { throw error("bad \\u escape"); }
                    i += 4;
                }
                default -> throw error("bad escape '\\" + e + "'");
            }
        }
        return partial ? JsonString.of(sb.toString()) : end();
    }

    private @Nullable JsonValue literal(String word, JsonValue value) {
        if (s.startsWith(word, i)) { i += word.length(); return value; }
        if (partial && word.startsWith(s.substring(i))) { i = s.length(); return null; }
        throw error("unexpected character");
    }

    private @Nullable JsonValue number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character '" + s.charAt(i) + "'");
        try {
            return JsonNumber.of(s.substring(start, i));
        } catch (NumberFormatException e) {
            if (partial && i == s.length()) return null;
            throw error("malformed number");
        }
    }

    private @Nullable JsonValue end() {
        if (partial) return null;
        throw error("unexpected end of input");
    }

    /// In partial mode a truncated structure is closed; otherwise the syntax error stands.
    private JsonValue closeOr(JsonValue closed, String problem) {
        if (partial && i >= s.length()) return closed;
        throw error(problem);
    }

    private boolean at(char c) {
        if (i < s.length() && s.charAt(i) == c) { i++; return true; }
        return false;
    }

    private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private IllegalArgumentException error(String problem) {
        return new IllegalArgumentException("Invalid JSON at offset " + i + ": " + problem);
    }
}
