package net.ai.gate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.internal.validation.Checks;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.ModelSource;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.protocol.ApiCompat;
import net.ai.gate.spi.protocol.WireApi;
import org.jspecify.annotations.Nullable;

/// Immutable, thread-safe configured backend: everything about it that is not a wire format. Presets
/// (`Providers.openai()`, `OpenAiCompatible.ollama()`…) are adjusted with [#toBuilder()], never mutated. The id is also
/// the credential-store key and the label of events and logs. Provider configuration has no credential fields.
public final class Provider {
    private final String id, name;
    private final URI baseUrl;
    private final Map<String, String> headers;
    private final List<WireApi> apis;
    private final @Nullable ApiKeyAuth apiKeyAuth;
    private final @Nullable OAuthAuth oauthAuth;
    private final List<Model> models;
    private final @Nullable ModelSource modelSource;
    private final @Nullable ApiCompat compat;
    private final ChatOptions defaults;
    private final @Nullable URI apiKeyUrl;
    private final boolean insecureCredentials;
    private final @Nullable String preset;
    private final @Nullable HttpTransport transport;

    private Provider(Builder b) {
        id = b.id; name = b.name == null ? b.id : b.name; baseUrl = b.baseUrl;
        var caseInsensitive = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(b.headers);
        headers = Collections.unmodifiableMap(caseInsensitive);
        apis = List.copyOf(b.apis); apiKeyAuth = b.apiKeyAuth; oauthAuth = b.oauthAuth; modelSource = b.modelSource;
        compat = b.compat; defaults = b.defaults; apiKeyUrl = b.apiKeyUrl; insecureCredentials = b.insecureCredentials;
        preset = b.preset; transport = b.transport;
        models = b.models.values().stream().map(m -> m.providerId().equals(id) ? m : m.withProviderId(id)).toList();
    }

    /// A provider speaking `defaultApi`; a base URL and an auth strategy are required before `build()`.
    public static Builder builder(String id, WireApi defaultApi) { return new Builder(id).defaultApi(defaultApi); }

    /// `anthropic`, `openai-work`, `corp-gw`: `[a-z0-9][a-z0-9._-]*`.
    public String id() { return id; }
    public String name() { return name; }
    /// Includes any deployment prefix and version path.
    public URI baseUrl() { return baseUrl; }
    /// Sent with every request; names compare case-insensitively.
    public Map<String, String> headers() { return headers; }
    /// Supported APIs; the first is the default. A model names the one it uses.
    public List<WireApi> apis() { return apis; }
    public WireApi defaultApi() { return apis.getFirst(); }
    /// The supported API with this id.
    public Optional<WireApi> api(String apiId) { return apis.stream().filter(a -> a.id().equals(apiId)).findFirst(); }
    public Optional<ApiKeyAuth> apiKeyAuth() { return Optional.ofNullable(apiKeyAuth); }
    public Optional<OAuthAuth> oauthAuth() { return Optional.ofNullable(oauthAuth); }
    /// Bundled with the preset or configured by the host.
    public List<Model> models() { return models; }
    /// Live listing: vendor `/models`, OpenRouter, Ollama, gateways.
    public Optional<ModelSource> modelSource() { return Optional.ofNullable(modelSource); }
    /// Default compat flags for its models.
    public Optional<ApiCompat> compat() { return Optional.ofNullable(compat); }
    /// Provider-scoped call defaults, e.g. long timeouts for local servers.
    public ChatOptions defaults() { return defaults; }
    /// "Get a key" link for UIs.
    public Optional<URI> apiKeyUrl() { return Optional.ofNullable(apiKeyUrl); }
    /// Credentials may be sent over cleartext `http://` to a non-loopback host.
    public boolean allowsInsecureCredentials() { return insecureCredentials; }
    /// The preset or template this provider derives from, as recorded by `ProvidersConfig`.
    public Optional<String> preset() { return Optional.ofNullable(preset); }
    /// A provider-bound transport (in-process servers, test fakes); the runtime's transport otherwise. Borrowed.
    public Optional<HttpTransport> transport() { return Optional.ofNullable(transport); }

    public Builder toBuilder() {
        var b = new Builder(id);
        b.name = name; b.baseUrl = baseUrl; b.headers.putAll(headers); b.apis.addAll(apis); b.apiKeyAuth = apiKeyAuth;
        b.oauthAuth = oauthAuth; models.forEach(m -> b.models.put(m.id(), m)); b.modelSource = modelSource; b.compat = compat;
        b.defaults = defaults; b.apiKeyUrl = apiKeyUrl; b.insecureCredentials = insecureCredentials; b.preset = preset;
        b.transport = transport;
        return b;
    }

    @Override public String toString() {
        return "Provider[" + id + ", " + baseUrl + ", apis=" + apis.stream().map(WireApi::id).toList() + ", models=" + models.size() + "]";
    }

    /// Not thread-safe. `build()` validates and lists every invalid field.
    public static final class Builder {
        private String id;
        private @Nullable String name;
        private @Nullable URI baseUrl;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final List<WireApi> apis = new ArrayList<>();
        private @Nullable ApiKeyAuth apiKeyAuth;
        private @Nullable OAuthAuth oauthAuth;
        private final Map<String, Model> models = new LinkedHashMap<>();
        private @Nullable ModelSource modelSource;
        private @Nullable ApiCompat compat;
        private ChatOptions defaults = ChatOptions.none();
        private @Nullable URI apiKeyUrl;
        private boolean insecureCredentials;
        private @Nullable String preset;
        private @Nullable HttpTransport transport;

        private Builder(String id) { this.id = id; }

        public Builder id(String value) { id = value; return this; }
        public Builder name(String value) { name = value; return this; }
        public Builder baseUrl(URI value) { baseUrl = value; return this; }
        public Builder baseUrl(String value) { return baseUrl(URI.create(value)); }
        /// Protected headers (credentials, content type, protocol versions) are rejected.
        public Builder header(String name, String value) { headers.put(Checks.header(name), Checks.headerValue(value, name)); return this; }
        /// Adds a supported API; the current default stays.
        public Builder api(WireApi api) {
            if (apis.stream().noneMatch(a -> a.id().equals(api.id()))) apis.add(api);
            return this;
        }
        /// Adds `api` if needed and makes it the default.
        public Builder defaultApi(WireApi api) {
            apis.removeIf(a -> a.id().equals(api.id()));
            apis.addFirst(api);
            return this;
        }
        public Builder auth(ApiKeyAuth auth) { apiKeyAuth = auth; return this; }
        public Builder auth(OAuthAuth auth) { oauthAuth = auth; return this; }
        /// Adds or replaces by model id.
        public Builder model(Model model) { models.put(model.id(), model); return this; }
        public Builder models(List<Model> replaced) { models.clear(); replaced.forEach(this::model); return this; }
        public Builder modelSource(ModelSource source) { modelSource = source; return this; }
        /// Merged field by field with the flags already set when both are of the same type.
        public Builder compat(ApiCompat flags) {
            compat = compat != null && compat.getClass() == flags.getClass() ? compat.overriddenBy(flags) : flags;
            return this;
        }
        public Builder defaults(ChatOptions options) { defaults = options; return this; }
        public Builder defaults(Consumer<ChatOptions.Builder> edit) {
            var b = defaults.toBuilder();
            edit.accept(b);
            defaults = b.build();
            return this;
        }
        public Builder apiKeyUrl(URI url) { apiKeyUrl = url; return this; }
        /// Credentials over `http://` to a non-loopback host; off by default.
        public Builder allowInsecureCredentials() { insecureCredentials = true; return this; }
        public Builder preset(String presetName) { preset = presetName; return this; }
        public Builder transport(HttpTransport value) { transport = value; return this; }

        public Provider build() {
            var problems = new ArrayList<String>();
            try { Checks.id(id, "Provider id"); } catch (IllegalArgumentException e) { problems.add(e.getMessage()); }
            if (baseUrl == null) problems.add("baseUrl is required");
            else try { Checks.baseUrl(baseUrl); } catch (IllegalArgumentException e) { problems.add(e.getMessage()); }
            if (apiKeyAuth == null && oauthAuth == null) problems.add("an auth strategy is required; keyless servers use ApiKeyAuth.none()");
            if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid provider '" + id + "': " + String.join("; ", problems));
            return new Provider(this);
        }
    }
}
