package net.ai.gate.internal.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.Frame;
import org.jspecify.annotations.Nullable;

/// One recorded, successful exchange in the versioned `ai-gate.exchange/1` format: the non-streamed body or the
/// stream frames, plus the request body for diagnosing misses. Also the cassette format.
public record Exchange(int status, @Nullable JsonValue body, List<Frame> frames, JsonValue request) {
    private static final String SCHEMA = "ai-gate.exchange/1";

    public Exchange { frames = List.copyOf(frames); }

    /// SHA-256 over everything that selects a reply: provider, API and revision, the credential namespace (store
    /// identity, scope and account — never a secret), and the request as it would leave the runtime minus its
    /// credentials: method, resolved URI, every provider, codec and call header, and the canonical body. A codec
    /// change that alters the wire bytes therefore misses naturally, and two tenants never share an entry.
    public static String key(String providerId, String api, String revision, String namespace, HttpCall effective) {
        var parts = new ArrayList<>(List.of(providerId, api, revision, namespace, effective.method(), effective.uri().toString()));
        effective.headers().entrySet().stream().map(h -> h.getKey().toLowerCase(Locale.ROOT) + ":" + h.getValue()).sorted().forEach(parts::add);
        parts.add(effective.body().map(JsonValue::toJson).orElse(""));
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) digest.update((part + '\u0000').getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public byte[] encode() {
        var json = Json.object("schema", SCHEMA, "status", status);
        json = body != null ? json.with("body", body)
                : json.with("frames", frames.stream().map(f -> Json.object("event", f.event().orElse(null), "data", f.data())).toList());
        return json.with("request", request).toPrettyJson().getBytes(StandardCharsets.UTF_8);
    }

    /// @throws IllegalArgumentException for entries of another schema or shape: they are treated as misses
    public static Exchange decode(byte[] bytes) {
        if (!(Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof JsonObject json)
                || !(json.get("schema").orElse(null) instanceof JsonString schema && schema.value().equals(SCHEMA)))
            throw new IllegalArgumentException("Not an " + SCHEMA + " entry");
        var frames = new ArrayList<Frame>();
        if (json.get("frames").orElse(null) instanceof JsonArray array)
            for (var value : array.values()) {
                if (!(value instanceof JsonObject frame)) throw new IllegalArgumentException("Malformed frame in " + SCHEMA + " entry");
                frames.add(Frame.of(frame.get("event").orElse(null) instanceof JsonString e ? e.value() : null, frame.string("data")));
            }
        return new Exchange((int) Json.convert(json.get("status").orElseThrow(), Integer.class), json.get("body").orElse(null),
                frames, json.get("request").orElse(JsonNull.INSTANCE));
    }

    /// The recorded non-streamed reply, as a transport would return it.
    public HttpReply reply() {
        return HttpReply.of(status, Map.of("content-type", List.of("application/json")),
                (body == null ? "null" : body.toJson()).getBytes(StandardCharsets.UTF_8));
    }
}
