package net.ai.gate.vendors.google.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.ai.gate.chat.Message;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.provider.ProviderApiContext;
import net.ai.gate.spi.provider.ProviderApiContext.Replay;
import net.ai.gate.vendors.google.CachedContent;
import net.ai.gate.vendors.google.GeminiCaches;

/// `Gemini.CACHES` over the `cachedContents` endpoints, executed by the core through [ProviderApiContext]: its
/// credentials, deadline, retries and error mapping. Contents are encoded exactly as `generateContent` encodes them.
public final class CachesClient implements GeminiCaches {
    private final ProviderApiContext context;

    public CachesClient(ProviderApiContext context) { this.context = context; }

    @Override public CachedContent create(Model model, List<Message> contents, Duration ttl) {
        var body = Json.object("model", "models/" + model.id(), "contents", GenerateContentCodec.contents(contents), "ttl", ttl(ttl));
        return cached(context.exchange(HttpCall.post("cachedContents", body), Replay.UNSAFE));
    }

    @Override public CachedContent get(String name) { return cached(context.exchange(HttpCall.get(name(name)), Replay.SAFE)); }

    @Override public List<CachedContent> list() {
        var all = new ArrayList<CachedContent>();
        String page = null;
        do {
            var path = "cachedContents" + (page == null ? "" : "?pageToken=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
            var reply = (JsonObject) context.exchange(HttpCall.get(path), Replay.SAFE);
            reply.objects("cachedContents").forEach(c -> all.add(cached(c)));
            page = reply.optString("nextPageToken").filter(t -> !t.isEmpty()).orElse(null);
        } while (page != null);
        return List.copyOf(all);
    }

    @Override public CachedContent extend(String name, Duration ttl) {
        var call = HttpCall.of("PATCH", URI.create(name(name) + "?updateMask=ttl"), Map.of(), Json.object("ttl", ttl(ttl)));
        return cached(context.exchange(call, Replay.SAFE));
    }

    @Override public void delete(String name) {
        context.exchange(HttpCall.of("DELETE", URI.create(name(name)), Map.of(), null), Replay.SAFE, _ -> null);
    }

    /// Only `cachedContents/…` names: a caller-supplied path must never reach another endpoint.
    private static String name(String name) {
        if (!name.matches("cachedContents/[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Not a cached-content name: " + name);
        return name;
    }

    private static String ttl(Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("A cache TTL must be positive: " + ttl);
        return ttl.toSeconds() + "s";
    }

    private CachedContent cached(Object json) {
        var c = (JsonObject) json;
        var model = c.optString("model").orElse("").replaceFirst("^models/", "");
        var usage = c.object("usageMetadata");
        var tokens = usage.optLong("totalTokenCount");
        return CachedContent.of(c.string("name"), new ModelRef(context.provider().id(), model),
                c.optString("expireTime").map(Instant::parse).orElse(null),
                tokens.isPresent() ? Usage.builder().cacheWrite(tokens.getAsLong()).raw(usage).build() : Usage.empty());
    }
}
