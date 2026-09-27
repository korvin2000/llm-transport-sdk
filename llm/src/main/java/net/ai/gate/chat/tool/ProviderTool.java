package net.ai.gate.chat.tool;

import java.util.Objects;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.JsonObject;

/// Immutable provider-hosted tool (web search, code execution…), created by provider packages. Sent to another
/// API it is a soft adaptation: dropped with a warning, or a failure under `strict()`.
public final class ProviderTool implements Tool {
    private final String api, name;
    private final JsonObject config;

    private ProviderTool(String api, String name, JsonObject config) { this.api = api; this.name = name; this.config = config; }

    /// For provider packages: `api` is the `WireApi` id that understands `config`.
    public static ProviderTool of(String api, String name, JsonObject config) {
        return new ProviderTool(Checks.notBlank(api, "API id"), Checks.notBlank(name, "Tool name"), config);
    }

    public String api() { return api; }
    @Override public String name() { return name; }
    /// The provider's tool definition, sent as is.
    public JsonObject config() { return config; }

    @Override public boolean equals(Object o) {
        return o instanceof ProviderTool t && api.equals(t.api) && name.equals(t.name) && config.equals(t.config);
    }

    @Override public int hashCode() { return Objects.hash(api, name, config); }
    @Override public String toString() { return "ProviderTool[" + api + ":" + name + "]"; }
}
