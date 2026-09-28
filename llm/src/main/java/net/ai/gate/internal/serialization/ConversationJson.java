package net.ai.gate.internal.serialization;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.CacheBreakpoint;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.Message;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.tool.FunctionTool;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.metadata.Attempt;
import net.ai.gate.metadata.Cost;
import net.ai.gate.metadata.ResponseInfo;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// The canonical JSON form of [Conversation]: deterministic member order, absent fields omitted, no file or network
/// I/O on read. `Message.timestamp()` round-trips through the ISO-8601 `at` member; [ResponseInfo] and `Usage.raw()`
/// are transient and never appear in this form. A form is written as `ai-gate.conversation/1` unless it uses a
/// member of version 2 — per-breakpoint retention, incomplete parts, cache-write classes, non-final usage — so older
/// readers keep reading what they can represent; both versions are read.
///
/// The archive form of one reply (`ai-gate.reply/1`, [AssistantMessage#toJson()]) is the message's member set plus
/// `usage.raw` and `info` (without rate limits and raw body).
public final class ConversationJson {
    public static final String SCHEMA = "ai-gate.conversation/2", SCHEMA_V1 = "ai-gate.conversation/1", REPLY = "ai-gate.reply/1";

    private ConversationJson() { }

    // ---- write

    public static JsonObject write(Conversation c) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("schema", Json.valueOf(needsV2(c) ? SCHEMA : SCHEMA_V1));
        c.system().ifPresent(s -> json.put("system", Json.valueOf(s)));
        json.put("tools", JsonArray.of(c.tools().stream().map(ConversationJson::writeTool).toList()));
        json.put("messages", JsonArray.of(c.messages().stream().map(ConversationJson::writeMessage).toList()));
        if (!c.cacheBreakpoints().isEmpty()) json.put("cacheBreakpoints", JsonArray.of(c.cacheBreakpointsWithRetention().stream()
                .map(b -> b.retention() == null ? (JsonValue) JsonNumber.of(b.index()) : Json.object("index", b.index(), "retention", lower(b.retention())))
                .toList()));
        return JsonObject.of(json);
    }

    private static boolean needsV2(Conversation c) {
        return c.cacheBreakpointsWithRetention().stream().anyMatch(b -> b.retention() != null) || c.messages().stream().anyMatch(m ->
                m instanceof AssistantMessage a && (!a.complete() || !a.usage().cacheWrites().isEmpty() || !a.usage().finalForCall()));
    }

    /// The archive form of one reply.
    public static JsonObject writeReply(AssistantMessage a) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("schema", Json.valueOf(REPLY));
        json.putAll(((JsonObject) writeMessage(a)).members());
        var usage = ((JsonObject) json.get("usage"));
        if (!(a.usage().raw() instanceof JsonNull)) json.put("usage", usage.with("raw", a.usage().raw()));
        var info = a.info();
        if (!info.requestId().isEmpty()) json.put("info", writeInfo(info));
        return JsonObject.of(json);
    }

    private static JsonValue writeInfo(ResponseInfo i) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("requestId", Json.valueOf(i.requestId()));
        json.put("providerId", Json.valueOf(i.providerId()));
        i.providerRequestId().ifPresent(v -> json.put("providerRequestId", Json.valueOf(v)));
        i.route().ifPresent(v -> json.put("route", Json.valueOf(v)));
        json.put("attempts", JsonNumber.of(i.attempts()));
        json.put("attemptsDetail", JsonArray.of(i.attemptsDetail().stream().map(ConversationJson::writeAttempt).toList()));
        json.put("latency", Json.valueOf(i.latency().toString()));
        i.timeToFirstOutput().ifPresent(v -> json.put("timeToFirstOutput", Json.valueOf(v.toString())));
        json.put("fromCache", Json.valueOf(i.fromCache()));
        return JsonObject.of(json);
    }

    private static JsonValue writeAttempt(Attempt a) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("index", JsonNumber.of(a.index()));
        json.put("startedAt", Json.valueOf(a.startedAt()));
        json.put("duration", Json.valueOf(a.duration().toString()));
        if (a.httpStatus() != null) json.put("httpStatus", JsonNumber.of(a.httpStatus()));
        if (a.error() != null) json.put("error", Json.valueOf(a.error().value()));
        json.put("sent", Json.valueOf(a.sent()));
        json.put("outcomeUnknown", Json.valueOf(a.outcomeUnknown()));
        return JsonObject.of(json);
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    private static JsonValue writeTool(Tool tool) {
        var json = new LinkedHashMap<String, JsonValue>();
        if (tool instanceof FunctionTool f) {
            json.put("type", Json.valueOf("function"));
            json.put("name", Json.valueOf(f.name()));
            f.description().ifPresent(d -> json.put("description", Json.valueOf(d)));
            json.put("parameters", f.parameters().asJson());
            json.put("strict", Json.valueOf(f.strict()));
        } else if (tool instanceof ProviderTool p) {
            json.put("type", Json.valueOf("provider"));
            json.put("api", Json.valueOf(p.api()));
            json.put("name", Json.valueOf(p.name()));
            json.put("config", p.config());
        } else {
            throw new IllegalArgumentException("Unsupported tool type: " + tool.getClass());
        }
        return JsonObject.of(json);
    }

    private static JsonValue writeMessage(Message m) {
        var json = new LinkedHashMap<String, JsonValue>();
        if (m instanceof UserMessage u) {
            json.put("role", Json.valueOf("user"));
            json.put("at", Json.valueOf(u.timestamp()));
            json.put("content", writeParts(u.content()));
        } else if (m instanceof AssistantMessage a) {
            json.put("role", Json.valueOf("assistant"));
            json.put("at", Json.valueOf(a.timestamp()));
            json.put("model", Json.object("provider", a.model().providerId(), "id", a.model().modelId()));
            json.put("api", Json.valueOf(a.api()));
            json.put("content", writeParts(a.content()));
            if (!a.complete()) json.put("incompleteParts", Json.valueOf(a.incompleteParts()));
            json.put("stopReason", Json.valueOf(a.stopReason().raw()));
            a.errorMessage().ifPresent(e -> json.put("errorMessage", Json.valueOf(e)));
            a.responseModel().ifPresent(v -> json.put("responseModel", Json.valueOf(v)));
            a.responseId().ifPresent(v -> json.put("responseId", Json.valueOf(v)));
            json.put("usage", writeUsage(a.usage()));
            json.put("warnings", JsonArray.of(a.warnings().stream()
                    .map(w -> (JsonValue) Json.object("code", w.code(), "message", w.message())).toList()));
        } else if (m instanceof ToolResultMessage t) {
            json.put("role", Json.valueOf("tool"));
            json.put("at", Json.valueOf(t.timestamp()));
            json.put("results", JsonArray.of(t.results().stream().map(ConversationJson::writeResult).toList()));
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + m.getClass());
        }
        return JsonObject.of(json);
    }

    private static JsonValue writeUsage(Usage u) {
        var json = new LinkedHashMap<String, JsonValue>();
        u.input().ifPresent(v -> json.put("input", JsonNumber.of(v)));
        u.cacheRead().ifPresent(v -> json.put("cacheRead", JsonNumber.of(v)));
        u.cacheWrite().ifPresent(v -> json.put("cacheWrite", JsonNumber.of(v)));
        if (!u.cacheWrites().isEmpty()) {
            var classes = new LinkedHashMap<String, JsonValue>();
            u.cacheWrites().forEach((retention, tokens) -> classes.put(lower(retention), JsonNumber.of(tokens)));
            json.put("cacheWrites", JsonObject.of(classes));
        }
        u.output().ifPresent(v -> json.put("output", JsonNumber.of(v)));
        u.reasoning().ifPresent(v -> json.put("reasoning", JsonNumber.of(v)));
        u.reportedTotal().ifPresent(v -> json.put("total", JsonNumber.of(v)));
        json.put("spent", Json.valueOf(u.spent()));
        if (!u.finalForCall()) json.put("final", Json.valueOf(false));
        u.cost().ifPresent(c -> json.put("cost", writeCost(c)));
        return JsonObject.of(json);
    }

    private static JsonValue writeCost(Cost c) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("currency", Json.valueOf(c.currency().getCurrencyCode()));
        json.put("input", JsonNumber.of(c.input()));
        json.put("cacheRead", JsonNumber.of(c.cacheRead()));
        json.put("cacheWrite", JsonNumber.of(c.cacheWrite()));
        json.put("output", JsonNumber.of(c.output()));
        json.put("total", JsonNumber.of(c.total()));
        return JsonObject.of(json);
    }

    private static JsonValue writeResult(ToolResult r) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("callId", Json.valueOf(r.callId()));
        json.put("toolName", Json.valueOf(r.toolName()));
        json.put("error", Json.valueOf(r.isError()));
        json.put("content", writeParts(r.content()));
        return JsonObject.of(json);
    }

    private static JsonArray writeParts(List<Content> parts) { return JsonArray.of(parts.stream().map(ConversationJson::writePart).toList()); }

    private static JsonValue writePart(Content c) {
        var json = new LinkedHashMap<String, JsonValue>();
        if (c instanceof Content.Text t) {
            json.put("type", Json.valueOf("text"));
            json.put("text", Json.valueOf(t.text()));
            if (!t.citations().isEmpty()) json.put("citations", JsonArray.of(t.citations().stream().map(ConversationJson::writeCitation).toList()));
        } else if (c instanceof Content.Image img) {
            json.put("type", Json.valueOf("image"));
            json.put("mediaType", Json.valueOf(img.mediaType()));
            img.detail().ifPresent(d -> json.put("detail", Json.valueOf(d)));
            json.put("source", writeSource(img.source()));
            if (!(img.providerData() instanceof JsonNull)) json.put("providerData", img.providerData());
        } else if (c instanceof Content.Document d) {
            json.put("type", Json.valueOf("document"));
            json.put("mediaType", Json.valueOf(d.mediaType()));
            d.title().ifPresent(t -> json.put("title", Json.valueOf(t)));
            json.put("source", writeSource(d.source()));
        } else if (c instanceof Content.Audio a) {
            json.put("type", Json.valueOf("audio"));
            json.put("format", Json.valueOf(a.format()));
            json.put("data", Json.valueOf(Base64.getEncoder().encodeToString(a.data())));
            a.transcript().ifPresent(t -> json.put("transcript", Json.valueOf(t)));
        } else if (c instanceof Content.Reasoning r) {
            json.put("type", Json.valueOf("reasoning"));
            r.text().ifPresent(t -> json.put("text", Json.valueOf(t)));
            r.signature().ifPresent(s -> json.put("signature", Json.valueOf(s)));
            json.put("redacted", Json.valueOf(r.redacted()));
            json.put("providerData", r.providerData());
        } else if (c instanceof Content.Refusal r) {
            json.put("type", Json.valueOf("refusal"));
            json.put("text", Json.valueOf(r.text()));
        } else if (c instanceof Content.Unknown u) {
            json.put("type", Json.valueOf("unknown"));
            json.put("name", Json.valueOf(u.type()));
            json.put("raw", u.raw());
        } else if (c instanceof ToolCall call) {
            json.put("type", Json.valueOf("tool_call"));
            json.put("id", Json.valueOf(call.id()));
            json.put("name", Json.valueOf(call.name()));
            json.put("arguments", Json.valueOf(call.argumentsJson()));
        } else if (c instanceof ToolResult r) {
            json.put("type", Json.valueOf("tool_result"));
            json.put("callId", Json.valueOf(r.callId()));
            json.put("toolName", Json.valueOf(r.toolName()));
            json.put("error", Json.valueOf(r.isError()));
            json.put("content", writeParts(r.content()));
        } else {
            throw new IllegalArgumentException("Unsupported content part: " + c.getClass());
        }
        return JsonObject.of(json);
    }

    private static JsonValue writeCitation(Content.Citation c) {
        return Json.object("title", c.title(), "source", c.source(), "startIndex", c.startIndex(), "endIndex", c.endIndex());
    }

    private static JsonValue writeSource(Content.Source source) {
        if (source instanceof Content.Source.Local l) return Json.object("kind", "local", "path", l.file().toString());
        if (source instanceof Content.Source.Remote r) return Json.object("kind", "remote", "url", r.url().toString());
        if (source instanceof Content.Source.Inline i) return Json.object("kind", "inline", "data", Base64.getEncoder().encodeToString(i.data()));
        if (source instanceof Content.Source.Ref f) return Json.object("kind", "ref", "fileId", f.fileId());
        throw new IllegalArgumentException("Unsupported source: " + source.getClass());
    }

    // ---- read

    /// @throws IllegalArgumentException naming the JSON path that does not fit
    public static Conversation read(JsonObject json) {
        if (!(json.get("schema").orElse(null) instanceof JsonString s) || !Set.of(SCHEMA, SCHEMA_V1).contains(s.value()))
            throw fail("schema", "must be '" + SCHEMA + "' or '" + SCHEMA_V1 + "'");
        var builder = Conversation.builder();
        var system = optString(json, "system", "system");
        if (system != null) builder.system(system);
        readTools(json).forEach(builder::tool);
        var messages = readMessages(json);
        builder.messages(messages);
        var breakpoints = readBreakpoints(json);
        var conversation = builder.build();
        return breakpoints.isEmpty() ? conversation : conversation.withMessages(messages, List.of()).withCacheBreakpoints(breakpoints);
    }

    /// Reads [#writeReply]'s form.
    public static AssistantMessage readReply(JsonObject json) {
        if (!(json.get("schema").orElse(null) instanceof JsonString s) || !REPLY.equals(s.value())) throw fail("schema", "must be '" + REPLY + "'");
        var at = parseInstant(str(member(json, "at", "at"), "at"), "at");
        var reply = readAssistant(json, "reply", at);
        var b = reply.toBuilder();
        if (member(json, "usage", "usage") instanceof JsonObject usage && usage.get("raw").orElse(null) instanceof JsonValue raw)
            b.usage(reply.usage().toBuilder().raw(raw).build());
        if (json.get("info").orElse(null) instanceof JsonObject info) b.info(readInfo(info, "info"));
        return b.build();
    }

    private static ResponseInfo readInfo(JsonObject json, String path) {
        var b = ResponseInfo.builder(str(member(json, "requestId", path + ".requestId"), path + ".requestId"),
                str(member(json, "providerId", path + ".providerId"), path + ".providerId"));
        b.providerRequestId(optString(json, "providerRequestId", path)).route(optString(json, "route", path));
        optLong(json, "attempts", path).ifPresent(n -> b.attempts(n.intValue()));
        if (json.get("attemptsDetail").orElse(null) instanceof JsonArray attempts) {
            var list = new ArrayList<Attempt>();
            for (int i = 0; i < attempts.values().size(); i++) list.add(readAttempt(obj(attempts.values().get(i), path + ".attemptsDetail[" + i + "]"), path + ".attemptsDetail[" + i + "]"));
            b.attemptsDetail(list);
        }
        var latency = optString(json, "latency", path);
        if (latency != null) b.latency(parseDuration(latency, path + ".latency"));
        var first = optString(json, "timeToFirstOutput", path);
        if (first != null) b.timeToFirstOutput(parseDuration(first, path + ".timeToFirstOutput"));
        if (json.get("fromCache").orElse(null) instanceof JsonValue fromCache) b.fromCache(bool(fromCache, path + ".fromCache"));
        return b.build();
    }

    private static Attempt readAttempt(JsonObject json, String path) {
        var error = optString(json, "error", path);
        return new Attempt(optLong(json, "index", path).orElseThrow(() -> fail(path + ".index", "required")).intValue(),
                parseInstant(str(member(json, "startedAt", path + ".startedAt"), path + ".startedAt"), path + ".startedAt"),
                parseDuration(str(member(json, "duration", path + ".duration"), path + ".duration"), path + ".duration"),
                optLong(json, "httpStatus", path).map(Long::intValue).orElse(null), error == null ? null : ErrorCode.of(error),
                bool(member(json, "sent", path + ".sent"), path + ".sent"), bool(member(json, "outcomeUnknown", path + ".outcomeUnknown"), path + ".outcomeUnknown"));
    }

    private static List<Tool> readTools(JsonObject json) {
        var value = json.get("tools").orElse(null);
        if (value == null) return List.of();
        if (!(value instanceof JsonArray array)) throw fail("tools", "expected an array");
        var tools = new ArrayList<Tool>();
        for (int i = 0; i < array.values().size(); i++) {
            var path = "tools[" + i + "]";
            tools.add(readTool(obj(array.values().get(i), path), path));
        }
        return tools;
    }

    private static Tool readTool(JsonObject t, String path) {
        var type = str(member(t, "type", path + ".type"), path + ".type");
        return switch (type) {
            case "function" -> {
                var name = str(member(t, "name", path + ".name"), path + ".name");
                var builder = Tool.function(name);
                var description = optString(t, "description", path);
                if (description != null) builder.description(description);
                if (!(member(t, "parameters", path + ".parameters") instanceof JsonObject schema)) throw fail(path + ".parameters", "expected an object");
                builder.parameters(JsonSchema.of(schema));
                if (bool(member(t, "strict", path + ".strict"), path + ".strict")) builder.strict();
                yield builder.build();
            }
            case "provider" -> {
                var api = str(member(t, "api", path + ".api"), path + ".api");
                var name = str(member(t, "name", path + ".name"), path + ".name");
                if (!(member(t, "config", path + ".config") instanceof JsonObject config)) throw fail(path + ".config", "expected an object");
                yield ProviderTool.of(api, name, config);
            }
            default -> throw fail(path + ".type", "unknown tool type '" + type + "'");
        };
    }

    private static List<Message> readMessages(JsonObject json) {
        var value = json.get("messages").orElse(null);
        if (value == null) return List.of();
        if (!(value instanceof JsonArray array)) throw fail("messages", "expected an array");
        var messages = new ArrayList<Message>();
        for (int i = 0; i < array.values().size(); i++) {
            var path = "messages[" + i + "]";
            messages.add(readMessage(obj(array.values().get(i), path), path));
        }
        return messages;
    }

    private static Message readMessage(JsonObject m, String path) {
        var role = str(member(m, "role", path + ".role"), path + ".role");
        var at = parseInstant(str(member(m, "at", path + ".at"), path + ".at"), path + ".at");
        return switch (role) {
            case "user" -> UserMessage.of(readParts(m, path), at);
            case "assistant" -> readAssistant(m, path, at);
            case "tool" -> ToolResultMessage.of(readResults(m, path), at);
            default -> throw fail(path + ".role", "unknown role '" + role + "'");
        };
    }

    private static AssistantMessage readAssistant(JsonObject m, String path, Instant at) {
        if (!(member(m, "model", path + ".model") instanceof JsonObject mo)) throw fail(path + ".model", "expected an object");
        var model = new ModelRef(str(member(mo, "provider", path + ".model.provider"), path + ".model.provider"),
                str(member(mo, "id", path + ".model.id"), path + ".model.id"));
        var api = str(member(m, "api", path + ".api"), path + ".api");
        var builder = AssistantMessage.builder(model, api).content(readParts(m, path))
                .stopReason(StopReason.of(str(member(m, "stopReason", path + ".stopReason"), path + ".stopReason")))
                .timestamp(at);
        if (m.get("incompleteParts").orElse(null) instanceof JsonArray incomplete)
            for (int i = 0; i < incomplete.values().size(); i++)
                builder.incompletePart((int) num(incomplete.values().get(i), path + ".incompleteParts[" + i + "]").longValue());
        var errorMessage = optString(m, "errorMessage", path);
        if (errorMessage != null) builder.errorMessage(errorMessage);
        var responseModel = optString(m, "responseModel", path);
        if (responseModel != null) builder.responseModel(responseModel);
        var responseId = optString(m, "responseId", path);
        if (responseId != null) builder.responseId(responseId);
        if (!(member(m, "usage", path + ".usage") instanceof JsonObject usage)) throw fail(path + ".usage", "expected an object");
        builder.usage(readUsage(usage, path + ".usage"));
        if (!(member(m, "warnings", path + ".warnings") instanceof JsonArray warnings)) throw fail(path + ".warnings", "expected an array");
        var list = new ArrayList<Warning>();
        for (int i = 0; i < warnings.values().size(); i++) {
            var wp = path + ".warnings[" + i + "]";
            var wo = obj(warnings.values().get(i), wp);
            list.add(new Warning(str(member(wo, "code", wp + ".code"), wp + ".code"), str(member(wo, "message", wp + ".message"), wp + ".message")));
        }
        builder.warnings(list);
        return builder.build();
    }

    private static Usage readUsage(JsonObject json, String path) {
        var b = Usage.builder();
        optLong(json, "input", path).ifPresent(b::input);
        optLong(json, "cacheRead", path).ifPresent(b::cacheRead);
        optLong(json, "cacheWrite", path).ifPresent(b::cacheWrite);
        if (json.get("cacheWrites").orElse(null) instanceof JsonObject classes)
            classes.members().forEach((name, tokens) -> b.cacheWrite(retention(name, path + ".cacheWrites." + name), num(tokens, path + ".cacheWrites." + name).longValue()));
        if (json.get("final").orElse(null) instanceof JsonValue value) b.finalForCall(bool(value, path + ".final"));
        optLong(json, "output", path).ifPresent(b::output);
        optLong(json, "reasoning", path).ifPresent(b::reasoning);
        optLong(json, "total", path).ifPresent(b::total);
        b.spent(bool(member(json, "spent", path + ".spent"), path + ".spent"));
        var cost = json.get("cost").orElse(null);
        if (cost instanceof JsonObject co) b.cost(readCost(co, path + ".cost"));
        else if (cost != null && !(cost instanceof JsonNull)) throw fail(path + ".cost", "expected an object");
        return b.build();
    }

    private static Cost readCost(JsonObject json, String path) {
        return new Cost(parseCurrency(str(member(json, "currency", path + ".currency"), path + ".currency"), path + ".currency"),
                num(member(json, "input", path + ".input"), path + ".input").value(),
                num(member(json, "cacheRead", path + ".cacheRead"), path + ".cacheRead").value(),
                num(member(json, "cacheWrite", path + ".cacheWrite"), path + ".cacheWrite").value(),
                num(member(json, "output", path + ".output"), path + ".output").value(),
                num(member(json, "total", path + ".total"), path + ".total").value());
    }

    private static List<ToolResult> readResults(JsonObject json, String path) {
        if (!(member(json, "results", path + ".results") instanceof JsonArray array)) throw fail(path + ".results", "expected an array");
        var results = new ArrayList<ToolResult>();
        for (int i = 0; i < array.values().size(); i++) {
            var rp = path + ".results[" + i + "]";
            results.add(readResult(obj(array.values().get(i), rp), rp));
        }
        return results;
    }

    /// `callId`/`toolName`/`error`/`content` — shared by standalone results and `tool_result` parts.
    private static ToolResult readResult(JsonObject json, String path) {
        var callId = str(member(json, "callId", path + ".callId"), path + ".callId");
        var toolName = str(member(json, "toolName", path + ".toolName"), path + ".toolName");
        var error = bool(member(json, "error", path + ".error"), path + ".error");
        var content = readParts(json, path);
        var call = ToolCall.of(callId, toolName, "{}");
        return error ? ToolResult.error(call, "").withContent(content) : ToolResult.of(call, content);
    }

    private static List<Content> readParts(JsonObject json, String path) {
        if (!(member(json, "content", path + ".content") instanceof JsonArray array)) throw fail(path + ".content", "expected an array");
        var parts = new ArrayList<Content>();
        for (int i = 0; i < array.values().size(); i++) {
            var pp = path + ".content[" + i + "]";
            parts.add(readPart(obj(array.values().get(i), pp), pp));
        }
        return parts;
    }

    private static Content readPart(JsonObject json, String path) {
        var type = str(member(json, "type", path + ".type"), path + ".type");
        return switch (type) {
            case "text" -> {
                var text = str(member(json, "text", path + ".text"), path + ".text");
                var citations = new ArrayList<Content.Citation>();
                var citationsVal = json.get("citations").orElse(null);
                if (citationsVal instanceof JsonArray array) {
                    for (int i = 0; i < array.values().size(); i++) {
                        var cp = path + ".citations[" + i + "]";
                        var co = obj(array.values().get(i), cp);
                        citations.add(new Content.Citation(str(member(co, "title", cp + ".title"), cp + ".title"),
                                parseUri(str(member(co, "source", cp + ".source"), cp + ".source"), cp + ".source"),
                                (int) num(member(co, "startIndex", cp + ".startIndex"), cp + ".startIndex").longValue(),
                                (int) num(member(co, "endIndex", cp + ".endIndex"), cp + ".endIndex").longValue()));
                    }
                } else if (citationsVal != null && !(citationsVal instanceof JsonNull)) throw fail(path + ".citations", "expected an array");
                yield Content.Text.of(text, citations);
            }
            case "image" -> readImage(json, path);
            case "document" -> readDocument(json, path);
            case "audio" -> {
                var data = parseBase64(str(member(json, "data", path + ".data"), path + ".data"), path + ".data");
                var format = str(member(json, "format", path + ".format"), path + ".format");
                yield Content.Audio.of(data, format, optString(json, "transcript", path));
            }
            case "reasoning" -> Content.Reasoning.of(optString(json, "text", path), optString(json, "signature", path),
                    bool(member(json, "redacted", path + ".redacted"), path + ".redacted"), json.get("providerData").orElse(JsonNull.INSTANCE));
            case "refusal" -> Content.Refusal.of(str(member(json, "text", path + ".text"), path + ".text"));
            case "unknown" -> Content.Unknown.of(str(member(json, "name", path + ".name"), path + ".name"), member(json, "raw", path + ".raw"));
            case "tool_call" -> ToolCall.of(str(member(json, "id", path + ".id"), path + ".id"), str(member(json, "name", path + ".name"), path + ".name"),
                    str(member(json, "arguments", path + ".arguments"), path + ".arguments"));
            case "tool_result" -> readResult(json, path);
            default -> throw fail(path + ".type", "unknown content type '" + type + "'");
        };
    }

    private static Content.Image readImage(JsonObject json, String path) {
        var mediaType = str(member(json, "mediaType", path + ".mediaType"), path + ".mediaType");
        if (!(member(json, "source", path + ".source") instanceof JsonObject so)) throw fail(path + ".source", "expected an object");
        var kind = str(member(so, "kind", path + ".source.kind"), path + ".source.kind");
        return Content.Image.of(readSource(so, kind, path), mediaType, optString(json, "detail", path),
                json.get("providerData").orElse(JsonNull.INSTANCE));
    }

    /// A source of any kind; the media type travels beside it, so nothing is re-derived from a file name.
    private static Content.Source readSource(JsonObject so, String kind, String path) {
        return switch (kind) {
            case "local" -> new Content.Source.Local(parsePath(str(member(so, "path", path + ".source.path"), path + ".source.path"), path + ".source.path"));
            case "remote" -> new Content.Source.Remote(parseUri(str(member(so, "url", path + ".source.url"), path + ".source.url"), path + ".source.url"));
            case "inline" -> new Content.Source.Inline(parseBase64(str(member(so, "data", path + ".source.data"), path + ".source.data"), path + ".source.data"));
            case "ref" -> new Content.Source.Ref(str(member(so, "fileId", path + ".source.fileId"), path + ".source.fileId"));
            default -> throw fail(path + ".source.kind", "unknown source kind '" + kind + "'");
        };
    }

    private static Content.Document readDocument(JsonObject json, String path) {
        var mediaType = str(member(json, "mediaType", path + ".mediaType"), path + ".mediaType");
        if (!(member(json, "source", path + ".source") instanceof JsonObject so)) throw fail(path + ".source", "expected an object");
        var kind = str(member(so, "kind", path + ".source.kind"), path + ".source.kind");
        return Content.Document.of(readSource(so, kind, path), mediaType, optString(json, "title", path));
    }

    /// Plain indices, or `{index, retention}` objects for breakpoints with their own retention.
    private static List<CacheBreakpoint> readBreakpoints(JsonObject json) {
        var value = json.get("cacheBreakpoints").orElse(null);
        if (value == null) return List.of();
        if (!(value instanceof JsonArray array)) throw fail("cacheBreakpoints", "expected an array");
        var list = new ArrayList<CacheBreakpoint>();
        for (int i = 0; i < array.values().size(); i++) {
            var path = "cacheBreakpoints[" + i + "]";
            list.add(array.values().get(i) instanceof JsonObject b
                    ? new CacheBreakpoint((int) num(member(b, "index", path + ".index"), path + ".index").longValue(),
                            retention(str(member(b, "retention", path + ".retention"), path + ".retention"), path + ".retention"))
                    : new CacheBreakpoint((int) num(array.values().get(i), path).longValue(), null));
        }
        return list;
    }

    private static CacheRetention retention(String name, String path) {
        try { return CacheRetention.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw fail(path, "unknown retention '" + name + "'"); }
    }

    private static Duration parseDuration(String text, String path) {
        try { return Duration.parse(text); } catch (RuntimeException e) { throw fail(path, "not an ISO-8601 duration: " + text); }
    }

    // ---- JSON helpers

    private static JsonObject obj(JsonValue v, String path) {
        if (v instanceof JsonObject o) return o;
        throw fail(path, "expected an object");
    }

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

    private static JsonNumber num(JsonValue v, String path) {
        if (v instanceof JsonNumber n) return n;
        throw fail(path, "expected a number");
    }

    private static @Nullable String optString(JsonObject json, String name, String path) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return null;
        if (v instanceof JsonString s) return s.value();
        throw fail(path + "." + name, "expected a string");
    }

    private static Optional<Long> optLong(JsonObject json, String name, String path) {
        var v = json.get(name).orElse(null);
        if (v == null || v instanceof JsonNull) return Optional.empty();
        if (v instanceof JsonNumber n) return Optional.of(n.longValue());
        throw fail(path + "." + name, "expected a number");
    }

    private static Instant parseInstant(String text, String path) {
        try { return Instant.parse(text); } catch (RuntimeException e) { throw fail(path, "not an ISO-8601 instant: " + text); }
    }

    private static URI parseUri(String text, String path) {
        try { return URI.create(text); } catch (IllegalArgumentException e) { throw fail(path, "not a valid URI: " + text); }
    }

    private static Path parsePath(String text, String path) {
        try { return Path.of(text); } catch (RuntimeException e) { throw fail(path, "not a valid path: " + text); }
    }

    private static Currency parseCurrency(String code, String path) {
        try { return Currency.getInstance(code); } catch (RuntimeException e) { throw fail(path, "not a valid currency: " + code); }
    }

    private static byte[] parseBase64(String text, String path) {
        try { return Base64.getDecoder().decode(text); } catch (IllegalArgumentException e) { throw fail(path, "not valid base64"); }
    }

    private static IllegalArgumentException fail(String path, String message) { return new IllegalArgumentException(path + ": " + message); }
}
