package net.ai.gate.catalog;

import java.net.URI;
import java.util.List;

import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.catalog.FeedHttp;

/// The public model-metadata database the bundled catalog is generated from; refreshed at runtime (on by default,
/// off with `CatalogOptions.noFeeds()` or `offline()`). Requests carry no credentials, prompts or usage.
///
/// **Stub (roadmap slice 3):** map the feed's provider and model ids explicitly to preset ids, limits, modalities,
/// reasoning levels and prices, with each entry's update time; pin the format with a feed contract test.
public final class ModelsDevFeed implements CatalogFeed {
    public static final URI URL = URI.create("https://models.dev/api.json");

    public ModelsDevFeed() { }

    @Override public String id() { return "models.dev"; }

    @Override public List<Model> fetch(FeedHttp http) {
        throw new UnsupportedOperationException("The models.dev feed adapter is not implemented yet (roadmap slice 3)");
    }
}
