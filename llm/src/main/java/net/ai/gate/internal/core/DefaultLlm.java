package net.ai.gate.internal.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import net.ai.gate.Llm;
import net.ai.gate.LlmCall;
import net.ai.gate.Provider;
import net.ai.gate.auth.Auth;
import net.ai.gate.auth.AuthStatus;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.catalog.ModelCatalog;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.HistoryIssue;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.stream.ChatStream;
import net.ai.gate.config.RetryPolicy;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.diagnostics.ConnectionReport;
import net.ai.gate.diagnostics.ConnectionTest;
import net.ai.gate.diagnostics.PreparedCall;
import net.ai.gate.diagnostics.PreparedRequest;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.event.LlmListener;
import net.ai.gate.internal.auth.DefaultAuth;
import net.ai.gate.internal.catalog.CatalogView;
import net.ai.gate.internal.http.Redaction;
import net.ai.gate.internal.json.RecordMapper;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonValue;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.lifecycle.Registration;
import net.ai.gate.metadata.TokenCount;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiFeatures;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.spi.provider.ProviderApi;
import net.ai.gate.spi.provider.ProviderApiContext;

/// The facade: a thin view over the shared [Core] with one credential store. The runtime owns the core;
/// `withCredentials` views share it and close nothing.
public final class DefaultLlm implements Llm {
    private final Core core;
    private final CredentialStore store;
    private final boolean view;
    private final CatalogView catalog;
    private final DefaultAuth auth;
    private final ConcurrentHashMap<String, Object> providerApis = new ConcurrentHashMap<>();

    private DefaultLlm(Core core, CredentialStore store, boolean view) {
        this.core = core;
        this.store = store;
        this.view = view;
        catalog = new CatalogView(core.catalog(), core.providers(), core::provider, store, p -> core.resolver().configured(p, store));
        auth = new DefaultAuth(core::provider, core.resolver(), store);
    }

    public static Llm create(LlmConfig config) { return new DefaultLlm(new Core(config), config.credentials(), false); }

    @Override public ModelCatalog models() { core.checkOpen(); return catalog; }

    @Override public Auth auth() { core.checkOpen(); return auth; }

    @Override public AssistantMessage complete(Model model, Conversation conversation, ChatOptions options) {
        var engine = core.engine();
        return engine.complete(engine.prepare(model, conversation, options, false), store);
    }

    @Override public <T> T complete(Model model, Conversation conversation, Class<T> outputType) {
        var reply = complete(model, conversation, ChatOptions.builder().output(outputType).build());
        return bind(reply, outputType, core.mapper());
    }

    /// The future's own token is linked to the caller's token for the call's lifetime only; cancelling the future
    /// cancels the call, and completing it releases the link.
    @Override public CompletableFuture<AssistantMessage> completeAsync(Model model, Conversation conversation, ChatOptions options) {
        core.checkOpen();
        var token = CancelToken.create();
        var link = options.cancel().map(parent -> parent.onCancel(token::cancel)).orElse(() -> { });
        var withToken = options.toBuilder().cancel(token).build();
        var future = new CompletableFuture<AssistantMessage>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                token.cancel();
                return super.cancel(mayInterruptIfRunning);
            }
        };
        future.whenComplete((_, _) -> link.close());
        try {
            core.executor().execute(() -> {
                try {
                    future.complete(complete(model, conversation, withToken));
                } catch (RuntimeException | Error e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("The runtime's executor rejected the call", e));
        }
        return future;
    }

    @Override public ChatStream stream(Model model, Conversation conversation, ChatOptions options) {
        var engine = core.engine();
        return engine.stream(engine.prepare(model, conversation, options, true), store);
    }

    @Override public LlmCall start(Model model, Conversation conversation, ChatOptions options) {
        var engine = core.engine();
        return engine.start(engine.prepare(model, conversation, options, true), store);
    }

    @Override public LlmCall start(PreparedCall prepared) { return core.engine().start(own(prepared).execution(), store); }

    @Override public AssistantMessage complete(PreparedCall prepared) { return core.engine().complete(own(prepared).execution(), store); }

    @Override public PreparedCall prepare(Model model, Conversation conversation, ChatOptions options, boolean streaming) {
        return core.engine().prepare(model, conversation, options, streaming);
    }

    @Override public AssistantMessage compact(Model model, Conversation conversation, ChatOptions options) {
        var engine = core.engine();
        return engine.complete(engine.prepareCompaction(model, conversation, options), store);
    }

    @Override public PreparedRequest preview(Model model, Conversation conversation, ChatOptions options) {
        core.checkOpen();
        return core.engine().preview(model, conversation, options);
    }

    @Override public ApiFeatures features(Model model) { return core.engine().features(model); }

    @Override public TokenCount countTokens(PreparedCall prepared) { return core.engine().countTokens(own(prepared), store); }

    @Override public List<HistoryIssue> check(Model model, Conversation conversation, ChatOptions options) {
        return core.engine().check(model, conversation, options);
    }

    /// A call this runtime prepared: its provider is one of the runtime's own.
    private Engine.Prepared own(PreparedCall prepared) {
        core.checkOpen();
        if (prepared instanceof Engine.Prepared p && core.providers().get(p.provider().id()) == p.provider()) return p;
        throw new IllegalArgumentException(prepared + " was not prepared by this runtime");
    }

    @Override public ConnectionReport test(Model model, Consumer<ConnectionTest.Builder> options) {
        var settings = ConnectionTest.builder();
        options.accept(settings);
        return new ConnectionTester(core, this, store).test(model, settings.build());
    }

    @Override public JsonObject describe() {
        core.checkOpen();
        var http = core.http();
        var catalogOptions = core.catalog().options();
        return Json.object(
                "providers", core.providers().values().stream().map(p -> describe(p, auth.status(p.id()))).toList(),
                "catalog", Json.object("policy", catalogOptions.toString(), "refreshedAt", core.catalog().refreshedAt().orElse(null),
                        "feeds", core.catalog().feeds().stream().map(CatalogFeed::id).toList(),
                        "snapshotFile", catalogOptions.snapshotFile().map(Object::toString).orElse(null)),
                "http", Json.object("transport", http.transport().isPresent() ? "injected" : "jdk", "httpVersion", http.httpVersion(),
                        "insecureTls", http.insecureSkipTlsVerification(), "wireLog", http.wireLog()),
                "defaults", describe(core.defaults()),
                "responseCache", core.responseCache() != null,
                "interceptors", core.interceptors().size(),
                "listeners", core.hub().size(),
                "credentials", view ? "view" : "runtime");
    }

    private static JsonObject describe(Provider p, AuthStatus status) {
        return Json.object("id", p.id(), "name", p.name(), "preset", p.preset().orElse(null), "baseUrl", Redaction.uri(p.baseUrl()),
                "apis", p.apis().stream().map(a -> a.id() + "@" + a.revision()).toList(),
                "auth", Json.object("state", status.state(), "source", status.source().orElse(null)),
                "oauth", p.oauthAuth().isPresent(), "models", p.models().size(), "headers", Redaction.headers(p.headers()),
                "defaults", describe(p.defaults()));
    }

    /// The effective values of an option scope, redacted; never throws.
    static JsonObject describe(ChatOptions o) {
        var json = new LinkedHashMap<String, JsonValue>();
        o.temperature().ifPresent(v -> json.put("temperature", Json.valueOf(v)));
        o.topP().ifPresent(v -> json.put("topP", Json.valueOf(v)));
        o.topK().ifPresent(v -> json.put("topK", Json.valueOf(v)));
        o.maxTokens().ifPresent(v -> json.put("maxTokens", Json.valueOf(v)));
        if (!o.stop().isEmpty()) json.put("stop", Json.valueOf(o.stop()));
        o.seed().ifPresent(v -> json.put("seed", Json.valueOf(v)));
        o.reasoning().ifPresent(v -> json.put("reasoning", Json.valueOf(v)));
        o.reasoningHandoff().ifPresent(v -> json.put("reasoningHandoff", Json.valueOf(v)));
        o.toolChoice().ifPresent(v -> json.put("toolChoice", Json.valueOf(v.toString())));
        o.parallelToolCalls().ifPresent(v -> json.put("parallelToolCalls", Json.valueOf(v)));
        if (o.strict()) json.put("strict", Json.valueOf(true));
        o.output().ifPresent(v -> json.put("output", Json.valueOf(v.getClass().getSimpleName())));
        o.cacheRetention().ifPresent(v -> json.put("cacheRetention", Json.valueOf(v)));
        o.sessionId().ifPresent(v -> json.put("sessionId", Json.valueOf(v)));
        o.responseCache().ifPresent(v -> json.put("responseCache", Json.valueOf(v)));
        if (!o.strictCodes().isEmpty()) json.put("strictCodes", Json.valueOf(o.strictCodes().stream().sorted().toList()));
        o.historyPolicy().ifPresent(v -> json.put("historyPolicy", Json.valueOf(v)));
        o.continuation().ifPresent(c -> json.put("continueFrom", Json.valueOf(c.api() + ":" + c.opaqueId())));
        var timeouts = o.timeouts().orElse(TimeoutPolicy.defaults());
        json.put("timeouts", Json.object("connect", timeouts.connect(), "streamIdle", timeouts.streamIdle(), "total", timeouts.total().orElse(null)));
        var retry = o.retry().orElse(RetryPolicy.defaults());
        json.put("retry", Json.object("maxAttempts", retry.maxAttempts(), "retryOnStatus", retry.retryOnStatus().stream().sorted().toList(),
                "initialBackoff", retry.initialBackoff(), "backoffMultiplier", retry.backoffMultiplier(), "maxBackoff", retry.maxBackoff(),
                "maxRetryAfter", retry.maxRetryAfter()));
        if (!o.headers().isEmpty()) json.put("headers", Json.valueOf(Redaction.headers(o.headers())));
        if (!o.tags().isEmpty()) json.put("tags", Json.valueOf(o.tags()));
        if (!o.providerOptions().isEmpty()) json.put("providerOptions", Json.valueOf(o.providerOptions().stream().map(p -> p.getClass().getSimpleName()).toList()));
        if (!o.listeners().isEmpty()) json.put("listeners", Json.valueOf(o.listeners().size()));
        if (o.payload().isPresent()) json.put("payloadHook", Json.valueOf(true));
        return JsonObject.of(json);
    }

    @Override public Llm withCredentials(CredentialStore credentials) {
        core.checkOpen();
        return new DefaultLlm(core, credentials, true);
    }

    @Override public Registration addListener(LlmListener listener) {
        core.checkOpen();
        return core.hub().add(listener);
    }

    @Override public <T> T providerApi(String providerId, ProviderApi<T> api) {
        var provider = core.provider(providerId);
        if (!api.api().equals(ProviderApi.ANY_API) && provider.api(api.api()).isEmpty())
            throw new IllegalStateException("Provider '" + providerId + "' does not speak " + api.api() + ", which " + api + " needs; it speaks "
                    + provider.apis().stream().map(WireApi::id).toList());
        return api.type().cast(providerApis.computeIfAbsent(providerId + '\u0000' + api.api() + '\u0000' + api.name(),
                _ -> api.create(new ProviderApiContext() {
                    @Override public Provider provider() { return provider; }
                    @Override public JsonMapper jsonMapper() { return core.mapper(); }
                    @Override public <R> R exchange(HttpCall call, Replay replay, Function<HttpReply, R> reader) {
                        return core.engine().exchange(provider, call, replay == Replay.SAFE, store, api.name(), reader);
                    }
                })));
    }

    @Override public void close() { if (!view) core.close(); }

    @Override public String toString() { return "Llm[providers=" + core.providers().keySet() + (view ? ", view" : "") + "]"; }

    /// Structured output through the runtime's mapper, with the same truncation and refusal rules as `as(type)`.
    static <T> T bind(AssistantMessage reply, Class<T> type, JsonMapper mapper) {
        if (mapper == RecordMapper.INSTANCE || reply.stopReason().equals(StopReason.LENGTH) || reply.refusal().isPresent()) return reply.as(type);
        try {
            return mapper.fromJson(reply.json(), type);
        } catch (IllegalArgumentException e) {
            throw new InvalidResponseException(LlmException.Details.builder(ErrorCode.OUTPUT_INVALID,
                    "The reply does not fit " + type.getSimpleName() + ": " + e.getMessage()).partial(reply).build(), e);
        }
    }
}
