package net.ai.gate.vendors.anthropic.internal;

import java.util.List;

import net.ai.gate.Provider;
import net.ai.gate.spi.provider.ProviderBundle;
import net.ai.gate.vendors.anthropic.Anthropic;

/// Contributes the `anthropic` preset to `Llm.create()`.
public final class AnthropicBundle implements ProviderBundle {
    @Override public List<Provider> providers() { return List.of(Anthropic.provider()); }
}
