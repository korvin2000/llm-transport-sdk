package net.ai.gate.spi.protocol;

/// **SPI**. Typed provider-specific request options (`AnthropicOptions`, `OpenAiResponsesOptions`…), carried by
/// `ChatOptions` and read only by codecs of [#api()]. Elsewhere they are inert and reported as
/// `option_not_applicable`, so one call can carry tuning for several providers. Immutable.
public interface ProviderOptions {
    String api();
}
