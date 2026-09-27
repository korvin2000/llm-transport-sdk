package net.ai.gate.spi.protocol;

/// **SPI**. Typed compat flags of one API family (`OpenAiCompletionsCompat`, `AnthropicCompat`…): the quirks of
/// compatible providers as data, never as base-URL sniffing. Immutable; merged field by field: preset ▷ provider
/// configuration ▷ model. Unset fields take the type's documented defaults.
public interface ApiCompat {
    /// The `WireApi` id these flags configure.
    String api();

    /// A copy in which every field set in `higher` wins; `higher` has the same class.
    ApiCompat overriddenBy(ApiCompat higher);
}
