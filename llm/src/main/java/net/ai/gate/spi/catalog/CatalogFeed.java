package net.ai.gate.spi.catalog;

import java.io.IOException;
import java.util.List;

import net.ai.gate.model.Model;

/// **SPI**. A source of model metadata across providers — limits, prices, reasoning levels. Entries carry their own
/// `updatedAt` for the freshness merge and map ids to provider ids explicitly. Requests carry no credentials,
/// prompts, usage or host identifiers.
public interface CatalogFeed {
    String id();

    List<Model> fetch(FeedHttp http) throws IOException;
}
