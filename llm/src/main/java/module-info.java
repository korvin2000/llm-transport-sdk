/// AI Gate: one portable Java API over LLM providers and gateways — the facade and value types, the SPIs, the
/// JDK-based execution core, the vendor families, bundled model data and the testing kit.
module net.ai.gate {
    requires transitive java.net.http;                    // the API exposes HttpClient.Version (HttpOptions)
    requires static java.desktop;                         // AuthInteraction.console() opens a browser when available
    requires static jdk.jfr;                              // Flight Recorder events, when the module is present
    requires static transitive org.jspecify;              // nullness on the API, visible to consumers' compilers
    requires static transitive org.jetbrains.annotations; // @ApiStatus markers on the API

    // the API
    exports net.ai.gate;
    exports net.ai.gate.chat;
    exports net.ai.gate.chat.content;
    exports net.ai.gate.chat.tool;
    exports net.ai.gate.chat.stream;
    exports net.ai.gate.chat.options;
    exports net.ai.gate.model;
    exports net.ai.gate.metadata;
    exports net.ai.gate.catalog;
    exports net.ai.gate.providers;
    exports net.ai.gate.config;
    exports net.ai.gate.cache;
    exports net.ai.gate.diagnostics;
    exports net.ai.gate.lifecycle;
    exports net.ai.gate.error;
    exports net.ai.gate.auth;
    exports net.ai.gate.auth.oauth;
    exports net.ai.gate.auth.interaction;
    exports net.ai.gate.event;
    exports net.ai.gate.json;
    // the SPI
    exports net.ai.gate.spi.protocol;
    exports net.ai.gate.spi.http;
    exports net.ai.gate.spi.catalog;
    exports net.ai.gate.spi.provider;
    // vendor families and the testing kit
    exports net.ai.gate.vendors.openai;
    exports net.ai.gate.vendors.anthropic;
    exports net.ai.gate.vendors.google;
    exports net.ai.gate.testing;

    uses net.ai.gate.spi.provider.ProviderBundle;
    provides net.ai.gate.spi.provider.ProviderBundle with
            net.ai.gate.vendors.openai.internal.OpenAiBundle,
            net.ai.gate.vendors.anthropic.internal.AnthropicBundle,
            net.ai.gate.vendors.google.internal.GoogleBundle,
            net.ai.gate.catalog.internal.BundledCatalog;
}
