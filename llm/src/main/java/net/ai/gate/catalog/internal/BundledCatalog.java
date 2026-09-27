package net.ai.gate.catalog.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import net.ai.gate.Provider;
import net.ai.gate.catalog.ModelsDevFeed;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.provider.ProviderBundle;

/// Contributes the shipped model data and the metadata feed to every runtime. Data only: no presets.
public final class BundledCatalog implements ProviderBundle {
    /// Parsed once per class loader, on first use.
    private static final class Data {
        static final List<Model> MODELS = load();
    }

    /// For `ServiceLoader`.
    public BundledCatalog() { }

    @Override public List<Provider> providers() { return List.of(); }

    @Override public List<Model> catalogModels() { return Data.MODELS; }

    @Override public List<CatalogFeed> catalogFeeds() { return List.of(new ModelsDevFeed()); }

    private static List<Model> load() {
        try (var in = Objects.requireNonNull(BundledCatalog.class.getResourceAsStream("/net/ai/gate/catalog/models.json"), "models.json is missing")) {
            var document = (JsonObject) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!"ai-gate.catalog/1".equals(document.string("schema"))) throw new IllegalStateException("Unsupported catalog schema");
            return ((JsonArray) document.get("models").orElseThrow()).values().stream().map(m -> Model.fromJson((JsonObject) m)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
