package net.ai.gate.catalog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.spi.catalog.CatalogFeed;
import org.jspecify.annotations.Nullable;

/// Immutable freshness policy of the model catalog. Default: background refresh every 24 h from discovered feeds
/// and live listings, snapshot in memory. Feed requests carry no credentials, prompts or usage.
public final class CatalogOptions {
    private static final CatalogOptions DEFAULTS = new Builder().build();

    private final Duration refreshInterval;
    private final boolean background, offline, feeds, liveListings;
    private final List<CatalogFeed> addedFeeds;
    private final @Nullable Path snapshotFile;

    private CatalogOptions(Builder b) {
        refreshInterval = b.refreshInterval; background = b.background; offline = b.offline; feeds = b.feeds;
        liveListings = b.liveListings; addedFeeds = List.copyOf(b.addedFeeds); snapshotFile = b.snapshotFile;
    }

    public static CatalogOptions defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    public Duration refreshInterval() { return refreshInterval; }
    /// Refresh on a runtime-owned virtual thread when the snapshot is older than the interval; never blocks reads.
    public boolean backgroundRefresh() { return background && !offline; }
    /// Bundled data and the snapshot only; never network.
    public boolean offline() { return offline; }
    public boolean feedsEnabled() { return feeds && !offline; }
    public boolean liveListingsEnabled() { return liveListings && !offline; }
    /// Feeds added by the host, in addition to discovered ones.
    public List<CatalogFeed> feeds() { return addedFeeds; }
    public Optional<Path> snapshotFile() { return Optional.ofNullable(snapshotFile); }

    public Builder toBuilder() {
        var b = new Builder();
        b.refreshInterval = refreshInterval; b.background = background; b.offline = offline; b.feeds = feeds;
        b.liveListings = liveListings; b.addedFeeds.addAll(addedFeeds); b.snapshotFile = snapshotFile;
        return b;
    }

    @Override public String toString() {
        return "CatalogOptions[" + (offline ? "offline" : background ? "refresh every " + refreshInterval : "manual refresh")
                + (feeds ? "" : ", no feeds") + (liveListings ? "" : ", no live listings") + "]";
    }

    /// Not thread-safe.
    public static final class Builder {
        private Duration refreshInterval = Duration.ofHours(24);
        private boolean background = true, offline, feeds = true, liveListings = true;
        private final List<CatalogFeed> addedFeeds = new ArrayList<>();
        private @Nullable Path snapshotFile;

        private Builder() { }

        public Builder refreshInterval(Duration interval) { refreshInterval = Checks.positive(interval, "Refresh interval"); return this; }
        /// No background refresh; `refresh()` only.
        public Builder manualRefresh() { background = false; return this; }
        public Builder offline() { offline = true; return this; }
        /// Adds a feed; discovered feeds are included by default.
        public Builder feed(CatalogFeed feed) { addedFeeds.add(feed); return this; }
        /// Live listings only.
        public Builder noFeeds() { feeds = false; return this; }
        /// Feeds only.
        public Builder noLiveListings() { liveListings = false; return this; }
        /// Persist the merged snapshot so a restart begins from the freshest data seen.
        public Builder snapshotFile(Path file) { snapshotFile = file; return this; }
        public CatalogOptions build() { return new CatalogOptions(this); }
    }
}
