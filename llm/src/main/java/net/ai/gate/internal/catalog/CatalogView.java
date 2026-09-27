package net.ai.gate.internal.catalog;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.catalog.ModelCatalog;
import net.ai.gate.catalog.RefreshReport;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;

/// `llm.models()` for one credential store: shared metadata, store-specific availability and listings.
public final class CatalogView implements ModelCatalog {
    private final CatalogService service;
    private final Map<String, Provider> providers;
    private final Function<String, Provider> lookup;
    private final CredentialStore store;
    private final Predicate<Provider> configured;

    /// `lookup` resolves a provider id or throws the runtime's "unknown provider" error.
    public CatalogView(CatalogService service, Map<String, Provider> providers, Function<String, Provider> lookup,
                       CredentialStore store, Predicate<Provider> configured) {
        this.service = service; this.providers = providers; this.lookup = lookup; this.store = store; this.configured = configured;
    }

    @Override public List<Model> all() { return List.copyOf(service.snapshot().values()); }

    @Override public List<Model> all(String providerId) {
        provider(providerId);
        return service.snapshot().values().stream().filter(m -> m.providerId().equals(providerId)).toList();
    }

    @Override public Optional<Model> find(ModelRef ref) { return Optional.ofNullable(service.snapshot().get(ref)); }

    @Override public Model require(String providerId, String modelId) {
        var provider = provider(providerId);
        var model = service.snapshot().get(new ModelRef(providerId, modelId));
        return model != null ? model
                : Model.builder(providerId, modelId).api(provider.defaultApi().id()).source(Model.Source.UNLISTED).build();
    }

    @Override public List<Model> available() {
        return providers.values().stream().filter(configured).flatMap(p -> {
            var listed = service.listed(p, store);
            return all(p.id()).stream().filter(m -> listed.isEmpty() || listed.get().contains(m.id()) || m.source() == Model.Source.CUSTOM);
        }).toList();
    }

    @Override public RefreshReport refresh(String... providerIds) {
        var targets = providerIds.length == 0 ? List.copyOf(providers.values()) : Arrays.stream(providerIds).map(this::provider).toList();
        return service.refresh(targets, store);
    }

    @Override public Optional<Instant> refreshedAt() { return service.refreshedAt(); }

    private Provider provider(String id) { return lookup.apply(id); }
}
