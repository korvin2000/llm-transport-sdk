package net.ai.gate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import net.ai.gate.auth.Auth;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.cache.ResponseCache;
import net.ai.gate.catalog.CatalogOptions;
import net.ai.gate.catalog.ModelCatalog;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.stream.ChatStream;
import net.ai.gate.config.HttpOptions;
import net.ai.gate.diagnostics.ConnectionReport;
import net.ai.gate.diagnostics.ConnectionTest;
import net.ai.gate.diagnostics.PreparedRequest;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.event.LlmListener;
import net.ai.gate.internal.core.DefaultLlm;
import net.ai.gate.internal.core.LlmConfig;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonObject;
import net.ai.gate.lifecycle.Registration;
import net.ai.gate.model.Model;
import net.ai.gate.spi.http.WireInterceptor;
import net.ai.gate.spi.provider.ProviderApi;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/// Thread-safe, closeable runtime over a fixed set of providers — the entry point of the SDK. Each call goes to the
/// provider its model names: never to another provider, model or credential. The runtime owns what it creates
/// (the JDK transport, background refresh, its executor) and borrows what it is given. `close()` is idempotent,
/// cancels in-flight calls and stops background work; later calls throw `IllegalStateException`.
///
/// ```java
/// try (Llm llm = Llm.create()) {
///     Model model = llm.model("anthropic", "claude-sonnet-5");
///     System.out.println(llm.complete(model, "Explain Java records in two sentences.").text());
/// }
/// ```
///
/// Not for implementation by consumers: methods may be added in minor releases.
@ApiStatus.NonExtendable
public interface Llm extends AutoCloseable {

    /// Providers discovered on the class or module path, keys from the environment, in-memory credentials,
    /// default catalog refresh.
    static Llm create() { return builder().discoverProviders().build(); }

    /// Exactly these providers; otherwise as [#create()].
    static Llm of(Provider... providers) {
        var builder = builder();
        for (var p : providers) builder.provider(p);
        return builder.build();
    }

    static Builder builder() { return new Builder(); }

    ModelCatalog models();

    Auth auth();

    /// A catalogued model, or an `UNLISTED` one for a known provider.
    /// @throws IllegalArgumentException for an unknown provider, naming the known ids
    default Model model(String providerId, String modelId) { return models().require(providerId, modelId); }

    /// Blocks until the reply, the total deadline or cancellation; retries happen inside.
    AssistantMessage complete(Model model, Conversation conversation, ChatOptions options);

    default AssistantMessage complete(Model model, Conversation conversation) { return complete(model, conversation, ChatOptions.none()); }

    default AssistantMessage complete(Model model, String userText) { return complete(model, Conversation.of(userText)); }

    /// Sets `OutputFormat.of(outputType)`, completes and binds with the runtime's `JsonMapper`.
    /// @throws InvalidResponseException `output_truncated`, `output_refused` or `output_invalid` (`partial()` keeps the reply)
    <T> T complete(Model model, Conversation conversation, Class<T> outputType);

    /// Runs on the runtime executor (a virtual thread per call by default); cancelling the future cancels the call.
    CompletableFuture<AssistantMessage> completeAsync(Model model, Conversation conversation, ChatOptions options);

    default CompletableFuture<AssistantMessage> completeAsync(Model model, Conversation conversation) {
        return completeAsync(model, conversation, ChatOptions.none());
    }

    /// Returns once response headers arrive; failures before that are thrown here, later ones by the iterator.
    ChatStream stream(Model model, Conversation conversation, ChatOptions options);

    default ChatStream stream(Model model, Conversation conversation) { return stream(model, conversation, ChatOptions.none()); }

    /// Exactly what `complete()` would send, or why it would not. No network, no credentials.
    PreparedRequest preview(Model model, Conversation conversation, ChatOptions options);

    /// Staged, non-billable check: configuration → network → authentication → model access.
    default ConnectionReport test(Model model) { return test(model, _ -> { }); }

    ConnectionReport test(Model model, Consumer<ConnectionTest.Builder> options);

    /// The effective configuration, redacted: providers, APIs, auth sources, catalog sources, policies. No I/O.
    JsonObject describe();

    /// A view sharing providers, transport, catalog, caches and listeners with another credential store (users,
    /// tenants). Its `auth()` and `models().available()` reflect that store; `close()` on a view is a no-op.
    Llm withCredentials(CredentialStore store);

    /// Released by `Registration.close()` or when the runtime closes.
    Registration addListener(LlmListener listener);

    /// Layer 3: a typed provider-only API (files, batches, cached contents). No I/O to obtain.
    /// @throws IllegalStateException when the provider does not speak the API's family
    @ApiStatus.Experimental
    <T> T providerApi(String providerId, ProviderApi<T> api);

    @Override void close();

    /// Not thread-safe. `build()` validates everything, reports all violations at once and performs no I/O.
    final class Builder {
        private final Map<String, Provider> providers = new LinkedHashMap<>();
        private boolean discover;
        private CredentialStore credentials = CredentialStore.inMemory();
        private Environment environment = Environment.system();
        private ChatOptions defaults = ChatOptions.none();
        private CatalogOptions catalog = CatalogOptions.defaults();
        private HttpOptions http = HttpOptions.defaults();
        private @Nullable ResponseCache responseCache;
        private final List<WireInterceptor> interceptors = new ArrayList<>();
        private final List<LlmListener> listeners = new ArrayList<>();
        private @Nullable Executor executor;
        private @Nullable JsonMapper jsonMapper;
        private Clock clock = Clock.systemUTC();

        private Builder() { }

        /// Adds; an equal id replaces. Explicit providers win over discovered ones.
        public Builder provider(Provider provider) { providers.put(provider.id(), provider); return this; }
        /// Adds the presets of every `ProviderBundle` on the class or module path.
        public Builder discoverProviders() { discover = true; return this; }
        /// Default in memory; borrowed.
        public Builder credentials(CredentialStore store) { credentials = store; return this; }
        /// Default `Environment.system()`; multi-tenant servers use `Environment.none()`.
        public Builder environment(Environment value) { environment = value; return this; }
        /// Runtime-wide call defaults.
        public Builder defaults(ChatOptions options) { defaults = options; return this; }
        public Builder defaults(Consumer<ChatOptions.Builder> edit) {
            var b = defaults.toBuilder();
            edit.accept(b);
            defaults = b.build();
            return this;
        }
        public Builder catalog(Consumer<CatalogOptions.Builder> edit) {
            var b = catalog.toBuilder();
            edit.accept(b);
            catalog = b.build();
            return this;
        }
        public Builder http(HttpOptions options) { http = options; return this; }
        public Builder http(Consumer<HttpOptions.Builder> edit) {
            var b = http.toBuilder();
            edit.accept(b);
            http = b.build();
            return this;
        }
        /// Opt-in; borrowed.
        public Builder responseCache(ResponseCache cache) { responseCache = cache; return this; }
        /// Adds; runs in registration order.
        public Builder interceptor(WireInterceptor interceptor) { interceptors.add(interceptor); return this; }
        public Builder listener(LlmListener listener) { listeners.add(listener); return this; }
        /// For `completeAsync()`; borrowed. Default: a virtual thread per call, owned by the runtime.
        public Builder executor(Executor value) { executor = value; return this; }
        /// Default: record binding.
        public Builder jsonMapper(JsonMapper mapper) { jsonMapper = mapper; return this; }
        /// For tests: credential expiry, backoff and catalog age.
        public Builder clock(Clock value) { clock = value; return this; }

        public Llm build() {
            if (http.transport().isPresent() && http.customizesJdkTransport())
                throw new IllegalArgumentException("HttpOptions for the JDK transport cannot be combined with an injected transport");
            return DefaultLlm.create(new LlmConfig(List.copyOf(providers.values()), discover, credentials, environment,
                    defaults, catalog, http, responseCache, List.copyOf(interceptors), List.copyOf(listeners), executor,
                    jsonMapper, clock));
        }
    }
}
