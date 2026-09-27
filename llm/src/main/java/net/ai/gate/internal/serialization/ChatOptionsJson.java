package net.ai.gate.internal.serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.ai.gate.cache.CacheMode;
import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.config.RetryPolicy;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.ReasoningLevel;

/// The canonical JSON form of the portable members of [ChatOptions] (`ai-gate.options/1`); absent fields omitted.
/// `cancel`, `listeners` and `payload` are operational and never appear in this form. Provider options and a
/// [OutputFormat.Typed] output are configuration that cannot be represented and fail [#write(ChatOptions)].
public final class ChatOptionsJson {
    public static final String SCHEMA = "ai-gate.options/1";

    private static final Set<String> FIELDS = Set.of("schema", "temperature", "topP", "topK", "maxTokens", "stop", "seed",
            "reasoning", "reasoningHandoff", "toolChoice", "parallelToolCalls", "strict", "output", "cacheRetention",
            "sessionId", "responseCache", "timeouts", "retry", "headers", "tags");

    private ChatOptionsJson() { }

    // ---- write

    /// @throws IllegalArgumentException naming `providerOptions` or `output` when either is not representable
    public static JsonObject write(ChatOptions o) {
        if (!o.providerOptions().isEmpty()) throw new IllegalArgumentException("providerOptions: cannot be represented in JSON");
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("schema", Json.valueOf(SCHEMA));
        o.temperature().ifPresent(v -> json.put("temperature", JsonNumber.of(v)));
        o.topP().ifPresent(v -> json.put("topP", JsonNumber.of(v)));
        o.topK().ifPresent(v -> json.put("topK", JsonNumber.of((long) v)));
        o.maxTokens().ifPresent(v -> json.put("maxTokens", JsonNumber.of((long) v)));
        o.stops().ifPresent(stop -> json.put("stop", JsonArray.of(stop.stream().map(s -> (JsonValue) JsonString.of(s)).toList())));
        o.seed().ifPresent(v -> json.put("seed", JsonNumber.of(v)));
        o.reasoning().ifPresent(r -> json.put("reasoning", Json.valueOf(lower(r))));
        o.reasoningHandoff().ifPresent(h -> json.put("reasoningHandoff", Json.valueOf(lower(h))));
        o.toolChoice().ifPresent(tc -> json.put("toolChoice", writeToolChoice(tc)));
        o.parallelToolCalls().ifPresent(v -> json.put("parallelToolCalls", Json.valueOf(v)));
        o.strictSetting().ifPresent(v -> json.put("strict", Json.valueOf(v)));
        o.output().ifPresent(f -> json.put("output", writeOutput(f)));
        o.cacheRetention().ifPresent(v -> json.put("cacheRetention", Json.valueOf(lower(v))));
        o.sessionId().ifPresent(v -> json.put("sessionId", Json.valueOf(v)));
        o.responseCache().ifPresent(v -> json.put("responseCache", Json.valueOf(lower(v))));
        o.timeouts().ifPresent(t -> json.put("timeouts", t.toJson()));
        o.retry().ifPresent(r -> json.put("retry", r.toJson()));
        if (!o.headers().isEmpty()) json.put("headers", writeStringMap(o.headers()));
        if (!o.tags().isEmpty()) json.put("tags", writeStringMap(o.tags()));
        return JsonObject.of(json);
    }

    private static JsonValue writeToolChoice(ToolChoice tc) {
        if (tc instanceof ToolChoice.Auto) return Json.object("mode", "auto");
        if (tc instanceof ToolChoice.None) return Json.object("mode", "none");
        if (tc instanceof ToolChoice.Required) return Json.object("mode", "required");
        if (tc instanceof ToolChoice.Only only) return Json.object("mode", "only", "tool", only.toolName());
        throw new IllegalArgumentException("Unsupported tool choice: " + tc.getClass());
    }

    /// @throws IllegalArgumentException naming `output` for a [OutputFormat.Typed] format
    private static JsonValue writeOutput(OutputFormat f) {
        if (f instanceof OutputFormat.PlainText) return Json.object("type", "text");
        if (f instanceof OutputFormat.AnyJson) return Json.object("type", "json");
        if (f instanceof OutputFormat.Schema s) {
            var json = new LinkedHashMap<String, JsonValue>();
            json.put("type", Json.valueOf("schema"));
            json.put("name", Json.valueOf(s.name()));
            json.put("schema", s.schema().asJson());
            json.put("strict", Json.valueOf(s.strict()));
            return JsonObject.of(json);
        }
        throw new IllegalArgumentException("output: a typed record output format has no portable JSON form; use OutputFormat.jsonSchema(…) instead");
    }

    private static JsonValue writeStringMap(Map<String, String> map) {
        var json = new LinkedHashMap<String, JsonValue>();
        map.forEach((k, v) -> json.put(k, Json.valueOf(v)));
        return JsonObject.of(json);
    }

    // ---- read

    /// @throws IllegalArgumentException naming the member that does not fit, or an unknown member (not prefixed `x-`)
    public static ChatOptions read(JsonObject json) {
        if (!(json.get("schema").orElse(null) instanceof JsonString s) || !SCHEMA.equals(s.value()))
            throw fail("schema", "must be '" + SCHEMA + "'");
        for (var name : json.members().keySet())
            if (!FIELDS.contains(name) && !name.startsWith("x-")) throw fail(name, "unknown member");

        var b = ChatOptions.builder();
        optDouble(json, "temperature").ifPresent(b::temperature);
        optDouble(json, "topP").ifPresent(b::topP);
        optInt(json, "topK").ifPresent(b::topK);
        optInt(json, "maxTokens").ifPresent(b::maxTokens);
        var stop = json.get("stop").orElse(null);
        if (stop instanceof JsonArray a) b.stops(strings(a, "stop"));
        else if (stop != null && !(stop instanceof JsonNull)) throw fail("stop", "expected an array");
        optLong(json, "seed").ifPresent(b::seed);
        optString(json, "reasoning").ifPresent(v -> b.reasoning(ReasoningLevel.valueOf(upper(v))));
        optString(json, "reasoningHandoff").ifPresent(v -> b.reasoningHandoff(ReasoningHandoff.valueOf(upper(v))));
        var toolChoice = json.get("toolChoice").orElse(null);
        if (toolChoice instanceof JsonObject tco) b.toolChoice(readToolChoice(tco));
        else if (toolChoice != null && !(toolChoice instanceof JsonNull)) throw fail("toolChoice", "expected an object");
        optBoolean(json, "parallelToolCalls").ifPresent(b::parallelToolCalls);
        optBoolean(json, "strict").ifPresent(b::strict);
        var output = json.get("output").orElse(null);
        if (output instanceof JsonObject oo) b.output(readOutput(oo));
        else if (output != null && !(output instanceof JsonNull)) throw fail("output", "expected an object");
        optString(json, "cacheRetention").ifPresent(v -> b.cacheRetention(CacheRetention.valueOf(upper(v))));
        optString(json, "sessionId").ifPresent(b::sessionId);
        optString(json, "responseCache").ifPresent(v -> b.responseCache(CacheMode.valueOf(upper(v))));
        var timeouts = json.get("timeouts").orElse(null);
        if (timeouts instanceof JsonObject to) b.timeouts(readTimeouts(to));
        else if (timeouts != null && !(timeouts instanceof JsonNull)) throw fail("timeouts", "expected an object");
        var retry = json.get("retry").orElse(null);
        if (retry instanceof JsonObject ro) b.retry(readRetry(ro));
        else if (retry != null && !(retry instanceof JsonNull)) throw fail("retry", "expected an object");
        var headers = json.get("headers").orElse(null);
        if (headers instanceof JsonObject ho) ho.members().forEach((k, v) -> b.header(k, str(v, "headers." + k)));
        else if (headers != null && !(headers instanceof JsonNull)) throw fail("headers", "expected an object");
        var tags = json.get("tags").orElse(null);
        if (tags instanceof JsonObject to) to.members().forEach((k, v) -> b.tag(k, str(v, "tags." + k)));
        else if (tags != null && !(tags instanceof JsonNull)) throw fail("tags", "expected an object");
        return b.build();
    }

    private static ToolChoice readToolChoice(JsonObject json) {
        var mode = str(member(json, "mode", "toolChoice.mode"), "toolChoice.mode");
        return switch (mode) {
            case "auto" -> ToolChoice.auto();
            case "none" -> ToolChoice.none();
            case "required" -> ToolChoice.required();
            case "only" -> ToolChoice.only(str(member(json, "tool", "toolChoice.tool"), "toolChoice.tool"));
            default -> throw fail("toolChoice.mode", "unknown mode '" + mode + "'");
        };
    }

    private static OutputFormat readOutput(JsonObject json) {
        var type = str(member(json, "type", "output.type"), "output.type");
        return switch (type) {
            case "text" -> OutputFormat.text();
            case "json" -> OutputFormat.json();
            case "schema" -> {
                var name = str(member(json, "name", "output.name"), "output.name");
                if (!(member(json, "schema", "output.schema") instanceof JsonObject schema)) throw fail("output.schema", "expected an object");
                var strict = bool(member(json, "strict", "output.strict"), "output.strict");
                yield new OutputFormat.Schema(name, JsonSchema.of(schema), strict);
            }
            default -> throw fail("output.type", "unknown type '" + type + "'");
        };
    }

    private static TimeoutPolicy readTimeouts(JsonObject json) {
        try {
            return TimeoutPolicy.fromJson(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("timeouts." + e.getMessage());
        }
    }

    private static RetryPolicy readRetry(JsonObject json) {
        try {
            return RetryPolicy.fromJson(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("retry." + e.getMessage());
        }
    }

    private static List<String> strings(JsonArray array, String path) {
        var list = new ArrayList<String>();
        for (int i = 0; i < array.values().size(); i++) list.add(str(array.values().get(i), path + "[" + i + "]"));
        return list;
    }

    // ---- JSON helpers

    private static JsonValue member(JsonObject json, String name, String path) {
        return json.get(name).orElseThrow(() -> fail(path, "required"));
    }

    private static String str(JsonValue v, String path) {
        if (v instanceof JsonString s) return s.value();
        throw fail(path, "expected a string");
    }

    private static boolean bool(JsonValue v, String path) {
        if (v instanceof JsonBoolean b) return b.value();
        throw fail(path, "expected a boolean");
    }

    private static Optional<Double> optDouble(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonNumber n) return Optional.of(n.doubleValue());
        throw fail(name, "expected a number");
    }

    private static Optional<Integer> optInt(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonNumber n) return Optional.of((int) n.longValue());
        throw fail(name, "expected a number");
    }

    private static Optional<Long> optLong(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonNumber n) return Optional.of(n.longValue());
        throw fail(name, "expected a number");
    }

    private static Optional<String> optString(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonString s) return Optional.of(s.value());
        throw fail(name, "expected a string");
    }

    private static Optional<Boolean> optBoolean(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonBoolean b) return Optional.of(b.value());
        throw fail(name, "expected a boolean");
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value.toUpperCase(Locale.ROOT); }

    private static IllegalArgumentException fail(String path, String message) { return new IllegalArgumentException(path + ": " + message); }
}
