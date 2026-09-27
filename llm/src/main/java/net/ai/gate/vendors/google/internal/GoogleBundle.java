package net.ai.gate.vendors.google.internal;

import java.util.List;

import net.ai.gate.Provider;
import net.ai.gate.spi.provider.ProviderBundle;
import net.ai.gate.vendors.google.Gemini;

/// Contributes the `google` preset to `Llm.create()`.
public final class GoogleBundle implements ProviderBundle {
    @Override public List<Provider> providers() { return List.of(Gemini.provider()); }
}
