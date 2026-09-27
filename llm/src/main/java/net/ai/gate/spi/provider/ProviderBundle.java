package net.ai.gate.spi.provider;

import java.util.List;

import net.ai.gate.Provider;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.CatalogFeed;

/// **SPI**, loaded with `ServiceLoader`: contributes presets, model data and feeds to `Llm.create()` and
/// `ProvidersConfig`. Hosts register bundles for private gateways the same way.
public interface ProviderBundle {
    /// Presets; empty for data-only bundles.
    List<Provider> providers();

    /// Shipped model data, with its source and timestamp.
    default List<Model> catalogModels() { return List.of(); }

    /// Runtime metadata feeds.
    default List<CatalogFeed> catalogFeeds() { return List.of(); }
}
