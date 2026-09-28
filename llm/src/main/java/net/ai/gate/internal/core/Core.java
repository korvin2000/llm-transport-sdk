package net.ai.gate.internal.core;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.cache.ResponseCache;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.config.HttpOptions;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.internal.auth.AuthResolver;
import net.ai.gate.internal.auth.store.ScopedStore;
import net.ai.gate.internal.catalog.CatalogService;
import net.ai.gate.internal.http.JdkHttpTransport;
import net.ai.gate.internal.json.RecordMapper;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonValue;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.catalog.ProviderHttp;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.http.TransportOptions;
import net.ai.gate.spi.http.WireInterceptor;
import net.ai.gate.spi.protocol.Tokenizer;
import net.ai.gate.spi.provider.ProviderBundle;
import org.jspecify.annotations.Nullable;

/// What a runtime and all its credential views share: providers, transport, catalog, caches, listeners and the
/// engine. Owns what it created and releases it on close within a bounded budget; borrows everything injected.
/// Building performs no network I/O and resolves no credentials; bundled model data is read on the first catalog
/// access. Thread-safe.
final class Core implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");
    private static final Duration CLOSE_BUDGET = Duration.ofSeconds(5);
    private static final Duration FEED_CONNECT = Duration.ofSeconds(10), FEED_TIMEOUT = Duration.ofSeconds(60);

    private final Map<String, Provider> providers;
    private final ChatOptions defaults;
    private final HttpOptions http;
    private final HttpTransport transport;
    private final boolean ownsTransport;
    private final EventHub hub;
    private final AuthResolver resolver;
    private final CatalogService catalog;
    private final @Nullable ResponseCache responseCache;
    private final List<WireInterceptor> interceptors;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Executor executor;
    private final @Nullable ExecutorService ownedExecutor;
    private final CredentialStore credentials;
    private final Engine engine;
    private final List<Tokenizer> tokenizers;
    private final Set<CancelToken> active = ConcurrentHashMap.newKeySet();
    private final Map<CredentialStore, String> storeIdentities = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    Core(LlmConfig config) {
        var bundles = ServiceLoader.load(ProviderBundle.class).stream().map(ServiceLoader.Provider::get).toList();
        var configured = new LinkedHashMap<String, Provider>();
        if (config.discoverProviders()) bundles.forEach(b -> b.providers().forEach(p -> configured.putIfAbsent(p.id(), p)));
        config.providers().forEach(p -> configured.put(p.id(), p));
        providers = Collections.unmodifiableMap(configured);
        defaults = config.defaults();
        http = config.http();
        ownsTransport = http.transport().isEmpty();
        transport = http.transport().orElseGet(() -> new JdkHttpTransport(http, defaults.timeouts().orElse(TimeoutPolicy.defaults()).connect()));
        hub = new EventHub(config.listeners());
        clock = config.clock();
        resolver = new AuthResolver(config.environment(), clock, hub::emit);
        responseCache = config.responseCache();
        interceptors = config.interceptors();
        mapper = config.jsonMapper() != null ? config.jsonMapper() : RecordMapper.INSTANCE;
        credentials = config.credentials();
        ownedExecutor = config.executor() == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        executor = config.executor() != null ? config.executor() : ownedExecutor;
        tokenizers = config.tokenizers();
        engine = new Engine(this);
        var feeds = new ArrayList<CatalogFeed>(config.catalog().feeds());
        if (config.catalog().discoveredFeeds()) bundles.forEach(b -> feeds.addAll(b.catalogFeeds()));
        catalog = new CatalogService(providers, config.catalog(), () -> shipped(bundles), feeds, new CatalogService.Access() {
            @Override public ProviderHttp http(Provider provider, CredentialStore store) { return providerHttp(provider, store); }
            @Override public boolean configured(Provider provider, CredentialStore store) { return resolver.configured(provider, store); }
            @Override public String namespace(Provider provider, CredentialStore store) { return credentialNamespace(store, provider); }
        }, this::fetchFeed, credentials, clock, hub::emit);
        if (http.insecureSkipTlsVerification()) LOG.log(System.Logger.Level.WARNING, "TLS verification is disabled for this runtime");
        providers.values().stream().filter(p -> "http".equals(p.baseUrl().getScheme()) && !Call.isLoopback(p.baseUrl()))
                .forEach(p -> LOG.log(System.Logger.Level.WARNING, "Provider '" + p.id() + "' uses cleartext HTTP to " + p.baseUrl().getHost()));
    }

    private static List<Model> shipped(List<ProviderBundle> bundles) {
        var models = new ArrayList<Model>();
        bundles.forEach(b -> models.addAll(b.catalogModels()));
        return models;
    }

    /// @throws IllegalArgumentException naming the known providers
    Provider provider(String id) {
        checkOpen();
        var provider = providers.get(id);
        if (provider == null) throw new IllegalArgumentException("Unknown provider '" + id + "'. Known providers: " + providers.keySet()
                + ". Configure it with Llm.builder().provider(…) or put its bundle on the class path.");
        return provider;
    }

    Map<String, Provider> providers() { return providers; }
    ChatOptions defaults() { return defaults; }
    HttpOptions http() { return http; }
    HttpTransport transport() { return transport; }
    EventHub hub() { return hub; }
    AuthResolver resolver() { return resolver; }
    CatalogService catalog() { return catalog; }
    @Nullable ResponseCache responseCache() { return responseCache; }
    List<WireInterceptor> interceptors() { return interceptors; }
    JsonMapper mapper() { return mapper; }
    Clock clock() { return clock; }
    Executor executor() { return executor; }
    List<Tokenizer> tokenizers() { return tokenizers; }
    Engine engine() { return engine; }
    CredentialStore credentials() { return credentials; }

    void checkOpen() { if (closed.get()) throw new IllegalStateException("This Llm runtime is closed"); }

    void track(CancelToken token) {
        active.add(token);
        if (closed.get()) token.cancel();
    }

    void untrack(CancelToken token) { active.remove(token); }

    /// The non-secret identity of the credentials a call runs with — for response-cache keys and per-view catalog
    /// listings, so replies never cross stores, scopes or accounts. The runtime's own store is `runtime`, which keeps
    /// recorded cassettes stable across processes; every other root store gets an identity per instance; scoped
    /// views add their scope; a stored OAuth credential adds its issuer, client and account.
    String credentialNamespace(CredentialStore store, Provider provider) {
        var root = ScopedStore.rootOf(store);
        var identity = root == credentials ? "runtime" : storeIdentities.computeIfAbsent(root, _ -> "store-" + UUID.randomUUID());
        return identity + "/" + ScopedStore.scopeOf(store) + "/" + resolver.principal(provider, store);
    }

    /// Authenticated GETs under a provider's base URL, through the engine: live listings and connection tests.
    ProviderHttp providerHttp(Provider provider, CredentialStore store) { return providerHttp(provider, store, defaults); }

    ProviderHttp providerHttp(Provider provider, CredentialStore store, ChatOptions options) {
        return new ProviderHttp() {
            @Override public Provider provider() { return provider; }
            @Override public JsonValue get(String relativePath) {
                return engine.exchange(provider, HttpCall.get(relativePath), true, store, "list-models", options, HttpReply::json);
            }
        };
    }

    /// Feeds: no credentials, bounded time for headers and body alike.
    private JsonValue fetchFeed(URI url) throws IOException {
        var reply = transport.send(HttpCall.of("GET", url, Map.of("Accept", "application/json"), null),
                TransportOptions.of(FEED_CONNECT, FEED_TIMEOUT, false));
        try (reply; var _ = new Watchdog(reply::close, System.nanoTime() + FEED_TIMEOUT.toNanos(), null, System::nanoTime)) {
            if (!reply.successful()) throw new IOException("HTTP " + reply.status() + " from " + url.getHost());
            return reply.json();
        }
    }

    /// Cancels in-flight calls, stops background work and releases owned resources within [#CLOSE_BUDGET];
    /// unfinished cleanup is logged, never blocks the caller for longer.
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long end = System.nanoTime() + CLOSE_BUDGET.toNanos();
        active.forEach(CancelToken::cancel);
        catalog.close();
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
        if (ownsTransport) transport.close();
        if (ownedExecutor != null) {
            try {
                if (!ownedExecutor.awaitTermination(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS))
                    LOG.log(System.Logger.Level.WARNING, "Async calls did not finish within the close budget of " + CLOSE_BUDGET);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        hub.clear();
    }
}
