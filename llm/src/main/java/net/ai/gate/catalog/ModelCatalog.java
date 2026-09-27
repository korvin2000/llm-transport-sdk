package net.ai.gate.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import org.jetbrains.annotations.ApiStatus;

/// The runtime's model catalog: the freshest data wins across bundled data, catalog artifacts, metadata feeds and
/// live listings; host-configured models always win. Reads never perform I/O; a background refresh keeps the
/// snapshot current. Thread-safe.
@ApiStatus.NonExtendable
public interface ModelCatalog {
    /// Every known model of the configured providers: the merged snapshot.
    List<Model> all();

    List<Model> all(String providerId);

    Optional<Model> find(ModelRef ref);

    /// A catalogued model, or an `UNLISTED` one for a known provider (reported as `unlisted_model` on use).
    /// @throws IllegalArgumentException for an unknown provider, naming the known ones
    Model require(String providerId, String modelId);

    default Model require(ModelRef ref) { return require(ref.providerId(), ref.modelId()); }

    /// Models of providers whose auth is configured (a local check, no network); after a successful live listing,
    /// only the models it listed plus host-configured ones.
    List<Model> available();

    /// Fetches feeds and live listings now, for the given providers or all; failures are reported per source and
    /// keep the previous data.
    RefreshReport refresh(String... providerIds);

    /// The last successful refresh of any source.
    Optional<Instant> refreshedAt();
}
