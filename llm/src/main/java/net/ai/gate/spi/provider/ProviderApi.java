package net.ai.gate.spi.provider;

import java.util.function.Function;

/// A typed provider-only API (files, batches, cached contents, balances), declared as a constant by a provider
/// package and obtained with `llm.providerApi(providerId, constant)`. Immutable.
public final class ProviderApi<T> {
    /// Valid for every provider, whatever its wire APIs.
    public static final String ANY_API = "*";

    private final String api, name;
    private final Class<T> type;
    private final Function<ProviderApiContext, T> factory;

    private ProviderApi(String api, String name, Class<T> type, Function<ProviderApiContext, T> factory) {
        this.api = api; this.name = name; this.type = type; this.factory = factory;
    }

    /// `api`: the `WireApi` id family the provider must speak, or [#ANY_API].
    public static <T> ProviderApi<T> of(String api, String name, Class<T> type, Function<ProviderApiContext, T> factory) {
        return new ProviderApi<>(api, name, type, factory);
    }

    public String api() { return api; }
    public String name() { return name; }
    public Class<T> type() { return type; }

    /// For the core: called once per runtime and provider.
    public T create(ProviderApiContext context) { return type.cast(factory.apply(context)); }

    @Override public String toString() { return "ProviderApi[" + api + ":" + name + "]"; }
}
