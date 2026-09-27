package net.ai.gate.vendors.google.internal;

import java.time.Duration;
import java.util.List;

import net.ai.gate.chat.Message;
import net.ai.gate.model.Model;
import net.ai.gate.spi.provider.ProviderApiContext;
import net.ai.gate.vendors.google.CachedContent;
import net.ai.gate.vendors.google.GeminiCaches;

/// `Gemini.CACHES` over `cachedContents` endpoints, executed by the core through [ProviderApiContext].
///
/// **Stub (roadmap slice 4):** create (`POST cachedContents`), get, list, extend (`PATCH` with `ttl`), delete.
public final class CachesClient implements GeminiCaches {
    private final ProviderApiContext context;

    public CachesClient(ProviderApiContext context) { this.context = context; }

    @Override public CachedContent create(Model model, List<Message> contents, Duration ttl) { throw pending(); }
    @Override public CachedContent get(String name) { throw pending(); }
    @Override public List<CachedContent> list() { throw pending(); }
    @Override public CachedContent extend(String name, Duration ttl) { throw pending(); }
    @Override public void delete(String name) { throw pending(); }

    private UnsupportedOperationException pending() {
        return new UnsupportedOperationException("Gemini cached contents for '" + context.provider().id()
                + "' are not implemented yet (roadmap slice 4)");
    }
}
