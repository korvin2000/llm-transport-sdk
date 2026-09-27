package net.ai.gate.internal.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.catalog.CatalogOptions;
import net.ai.gate.catalog.RefreshReport;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.event.CatalogEvent;
import net.ai.gate.event.LlmEvent;
import net.ai.gate.internal.serialization.ModelJson;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.catalog.FeedHttp;
import net.ai.gate.spi.catalog.ProviderHttp;
import org.jspecify.annotations.Nullable;

/// The runtime's catalog: merges every source per `(provider, model)` and field — host-configured (`CUSTOM`) values
/// always win, otherwise the newest non-absent value wins and absent never overwrites present; structured fields
/// (prices, capabilities, compat) merge component by component. Public metadata is shared by every credential view;
/// live listings run with the view's own credentials and their availability is kept per credential namespace.
/// Reads return the published snapshot and never block on the network; refreshes publish a new snapshot atomically
/// and one at a time. Thread-safe.
public final class CatalogService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("net.ai.gate.catalog");
    private static final String SNAPSHOT_SCHEMA = "ai-gate.catalog-snapshot/1";

    /// What the catalog needs from the runtime for one provider and credential store.
    public interface Access {
        /// Authenticated, deadline-bound GETs under the provider's base URL, with the store's credentials.
        ProviderHttp http(Provider provider, CredentialStore store);
        /// Local check: the store or the environment holds credentials for the provider.
        boolean configured(Provider provider, CredentialStore store);
        /// The non-secret identity of those credentials; listings are kept per namespace.
        String namespace(Provider provider, CredentialStore store);
    }

    private final Map<String, Provider> providers;
    private final CatalogOptions options;
    private final Supplier<List<Model>> shipped;
    private final List<CatalogFeed> feeds;
    private final Access access;
    private final FeedHttp feedHttp;
    private final CredentialStore rootStore;
    private final Clock clock;
    private final Consumer<LlmEvent> events;
    private final Map<String, List<Model>> feedData = new ConcurrentHashMap<>(), liveData = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Object publish = new Object();
    private volatile @Nullable Map<ModelRef, Model> snapshot;
    private volatile List<Model> shippedModels = List.of(), persisted = List.of();
    private volatile @Nullable Instant refreshedAt, lastAttempt;
    private volatile @Nullable Thread background;
    private volatile boolean closed;

    public CatalogService(Map<String, Provider> providers, CatalogOptions options, Supplier<List<Model>> shipped, List<CatalogFeed> feeds,
                          Access access, FeedHttp feedHttp, CredentialStore rootStore, Clock clock, Consumer<LlmEvent> events) {
        this.providers = providers; this.options = options; this.shipped = shipped; this.feeds = List.copyOf(feeds);
        this.access = access; this.feedHttp = feedHttp; this.rootStore = rootStore; this.clock = clock; this.events = events;
    }

    public CatalogOptions options() { return options; }
    public List<CatalogFeed> feeds() { return feeds; }

    /// The merged snapshot; the first read loads shipped data and the snapshot file, later reads may schedule a
    /// background refresh.
    public Map<ModelRef, Model> snapshot() {
        var current = snapshot;
        if (current == null) {
            synchronized (publish) {
                if (snapshot == null) {
                    shippedModels = List.copyOf(shipped.get());
                    loadSnapshotFile();
                    snapshot = merge();
                }
                current = snapshot;
            }
        }
        scheduleBackgroundRefresh();
        return Objects.requireNonNull(current);
    }

    /// Model ids of the last successful live listing made with `store`'s credentials; empty when none succeeded.
    public Optional<Set<String>> listed(Provider provider, CredentialStore store) {
        return Optional.ofNullable(liveData.get(listingKey(provider, store)))
                .map(l -> l.stream().map(Model::id).collect(Collectors.toUnmodifiableSet()));
    }

    public Optional<Instant> refreshedAt() { return Optional.ofNullable(refreshedAt); }

    /// Fetches feeds and, with `store`'s credentials, the live listings of `targets`; each failed source keeps its
    /// previous data.
    public RefreshReport refresh(Collection<Provider> targets, CredentialStore store) {
        var byProvider = new LinkedHashMap<String, Optional<LlmException>>();
        var feedErrors = new ArrayList<LlmException>();
        var failures = new ArrayList<String>();
        boolean succeeded = false;
        lastAttempt = clock.instant();
        if (options.feedsEnabled()) {
            for (var feed : feeds) {
                try {
                    feedData.put(feed.id(), stamp(feed.fetch(feedHttp), Model.Source.FEED));
                    succeeded = true;
                } catch (Exception e) {
                    feedErrors.add(failure("Feed " + feed.id(), e));
                    failures.add(feed.id());
                }
            }
        }
        if (options.liveListingsEnabled()) {
            for (var provider : targets) {
                if (provider.modelSource().isEmpty() || !access.configured(provider, store)) continue;
                try {
                    var models = provider.modelSource().get().fetch(access.http(provider, store)).stream()
                            .filter(m -> m.providerId().equals(provider.id())).toList();
                    liveData.put(listingKey(provider, store), stamp(models, Model.Source.LIVE));
                    byProvider.put(provider.id(), Optional.empty());
                    succeeded = true;
                } catch (Exception e) {
                    byProvider.put(provider.id(), Optional.of(failure("Live listing of " + provider.id(), e)));
                    failures.add(provider.id());
                }
            }
        }
        long changed;
        Map<ModelRef, Model> after;
        synchronized (publish) {
            if (succeeded) refreshedAt = clock.instant();
            var before = snapshot();
            after = merge();
            snapshot = after;
            changed = after.entrySet().stream().filter(e -> !e.getValue().equals(before.get(e.getKey()))).count();
            saveSnapshotFile(after.values());
        }
        events.accept(CatalogEvent.Refreshed.of(List.copyOf(byProvider.keySet()), (int) changed, failures, clock.instant()));
        return RefreshReport.of(byProvider, feedErrors);
    }

    private String listingKey(Provider provider, CredentialStore store) { return access.namespace(provider, store) + '\u0000' + provider.id(); }

    private void scheduleBackgroundRefresh() {
        if (!options.backgroundRefresh() || closed) return;
        var now = clock.instant();
        var last = lastAttempt;
        if (last != null && Duration.between(last, now).compareTo(options.refreshInterval()) < 0) return;
        if (!refreshing.compareAndSet(false, true)) return;
        lastAttempt = now;
        background = Thread.ofVirtual().name("ai-gate-catalog-refresh").start(() -> {
            try {
                refresh(providers.values(), rootStore);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Background catalog refresh failed", e);
            } finally {
                refreshing.set(false);
            }
        });
    }

    private Map<ModelRef, Model> merge() {
        var byRef = new LinkedHashMap<ModelRef, List<Model>>();
        Consumer<Model> add = m -> { if (providers.containsKey(m.providerId())) byRef.computeIfAbsent(m.ref(), _ -> new ArrayList<>()).add(m); };
        shippedModels.forEach(add);
        persisted.forEach(add);
        feedData.values().forEach(l -> l.forEach(add));
        liveData.values().forEach(l -> l.forEach(add));
        providers.values().forEach(p -> p.models().forEach(add));
        var merged = new LinkedHashMap<ModelRef, Model>();
        byRef.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ModelRef::providerId).thenComparing(ModelRef::modelId)))
                .forEach(e -> {
                    var model = e.getValue().stream().sorted(FRESHNESS).reduce(CatalogService::overlay).orElseThrow();
                    var provider = providers.get(model.providerId());
                    merged.put(e.getKey(), model.api().isPresent() || provider == null ? model : model.toBuilder().api(provider.defaultApi().id()).build());
                });
        return Collections.unmodifiableMap(merged);
    }

    /// Oldest first, host-configured last: folding with [#overlay] lets the freshest data win. Equal timestamps keep
    /// their source order (shipped, persisted, feeds, live, host), so later sources win ties.
    private static final Comparator<Model> FRESHNESS = Comparator.<Model, Boolean>comparing(m -> m.source() == Model.Source.CUSTOM)
            .thenComparing(m -> m.updatedAt().orElse(Instant.MIN));

    /// `top`'s present fields win; its absent fields keep `base`'s; prices, capabilities and compat merge component
    /// by component; provenance is `top`'s.
    static Model overlay(Model base, Model top) {
        var b = top.toBuilder();
        if (top.name().equals(top.id())) b.name(base.name().equals(base.id()) ? null : base.name());
        if (top.api().isEmpty()) b.api(base.api().orElse(null));
        if (top.input().isEmpty()) b.input(base.input().toArray(Modality[]::new));
        if (top.output().isEmpty()) b.output(base.output().toArray(Modality[]::new));
        if (top.contextWindow().isEmpty()) base.contextWindow().ifPresent(b::contextWindow);
        if (top.maxOutputTokens().isEmpty()) base.maxOutputTokens().ifPresent(b::maxOutputTokens);
        if (top.reasoningLevels().isEmpty()) b.reasoningLevels(base.reasoningLevels());
        b.capabilities(base.capabilities().overriddenBy(top.capabilities()));
        b.prices(base.prices().map(bp -> top.prices().map(bp::overriddenBy).orElse(bp)).or(top::prices).orElse(null));
        b.compat(base.compat().map(bc -> top.compat().map(tc -> tc.getClass() == bc.getClass() ? bc.overriddenBy(tc) : tc).orElse(bc))
                .or(top::compat).orElse(null));
        if (top.deprecatedAt().isEmpty()) b.deprecatedAt(base.deprecatedAt().orElse(null));
        if (top.updatedAt().isEmpty()) b.updatedAt(base.updatedAt().orElse(null));
        return b.build();
    }

    private List<Model> stamp(List<Model> models, Model.Source source) {
        var now = clock.instant();
        return models.stream().map(m -> m.toBuilder().source(source).updatedAt(m.updatedAt().orElse(now)).build()).toList();
    }

    private void loadSnapshotFile() {
        var file = options.snapshotFile().orElse(null);
        if (file == null || !Files.exists(file)) return;
        try {
            var json = (JsonObject) Json.parse(Files.readString(file));
            if (!SNAPSHOT_SCHEMA.equals(json.string("schema"))) return;
            persisted = ModelJson.readAll(json.get("models").orElseThrow(), null, Model.Source.CATALOG, null).stream()
                    .filter(m -> m.source() != Model.Source.CUSTOM && m.source() != Model.Source.UNLISTED).toList();
            json.get("refreshedAt").ifPresent(v -> refreshedAt = Json.convert(v, Instant.class));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable catalog snapshot " + file, e);
        }
    }

    private void saveSnapshotFile(Collection<Model> models) {
        var file = options.snapshotFile().orElse(null);
        if (file == null) return;
        Path temp = null;
        try {
            var parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "models", ".tmp");
            var json = Json.object("schema", SNAPSHOT_SCHEMA, "refreshedAt", refreshedAt,
                    "models", ModelJson.writeAll(models.stream().filter(m -> m.source() != Model.Source.CUSTOM).toList()));
            Files.writeString(temp, json.toPrettyJson(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best effort */ }
            LOG.log(System.Logger.Level.WARNING, "Cannot write catalog snapshot " + file, e);
        }
    }

    private static LlmException failure(String source, Exception e) {
        if (e instanceof LlmException l) return l;
        return new LlmException(LlmException.Details.builder(ErrorCode.of("catalog_refresh_failed"), source + " failed: " + e).build(), e);
    }

    @Override public void close() {
        closed = true;
        var thread = background;
        if (thread != null) thread.interrupt();
    }
}
