package net.ai.gate.internal.core;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import net.ai.gate.LlmCall;
import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.cache.CacheMode;
import net.ai.gate.cache.ResponseCache;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.HistoryIssue;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.options.HistoryPolicy;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.chat.stream.ChatStream;
import net.ai.gate.diagnostics.PreparedCall;
import net.ai.gate.diagnostics.PreparedRequest;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.internal.auth.KeyAuth;
import net.ai.gate.internal.cache.Exchange;
import net.ai.gate.internal.http.FrameReader;
import net.ai.gate.internal.http.HttpErrors;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.ResponseInfo;
import net.ai.gate.metadata.TokenCount;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiFeatures;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.WireApi;
import org.jspecify.annotations.Nullable;

/// Orders the pipeline stages; every API goes through the same ones. See the package documentation.
final class Engine {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");
    /// Statuses of a counting request that mean "no such endpoint here" (gateways): counting falls back.
    private static final Set<Integer> NO_ENDPOINT = Set.of(404, 405, 501);

    private final Core core;

    Engine(Core core) { this.core = core; }

    /// The pure part of a call: everything up to the encoded request. No I/O, no credentials. `options` carry the
    /// output limit the codec reported; `notes` and `context` belong to one execution (see [#execution()]).
    record Prepared(Provider provider, WireApi api, Model model, ChatOptions options, Conversation conversation, boolean streaming,
                    HttpCall call, Notes notes, CodecContext context) implements PreparedCall {
        @Override public PreparedRequest request() {
            try {
                var placeholders = provider.apiKeyAuth().orElse(null) instanceof KeyAuth k ? k.placeholders() : Map.<String, String>of();
                var assembled = Call.assemble(provider, call, options.headers(), ResolvedAuth.headers(placeholders, "preview"));
                return PreparedRequest.of(model.ref(), api.id(), assembled, notes.warnings(), notes.notes(), placeholders.keySet());
            } catch (LlmException | IllegalArgumentException e) {
                return PreparedRequest.rejected(model.ref(), api.id(), List.of(String.valueOf(e.getMessage())), notes.warnings());
            }
        }

        @Override public ChatOptions effectiveOptions() { return options; }
        @Override public Conversation effectiveConversation() { return conversation; }
        @Override public String digest() { return Exchange.key(provider.id(), api.id(), api.revision(), "", call); }

        /// A copy for one execution, with its own notes: decoding warns into them, and executions may run concurrently.
        Prepared execution() {
            var copy = notes.copy();
            return new Prepared(provider, api, model, options, conversation, streaming, call, copy, context.with(copy));
        }

        @Override public String toString() { return "PreparedCall[" + model.ref() + ", " + api.id() + (streaming ? ", streaming" : "") + "]"; }
    }

    Prepared prepare(Model model, Conversation conversation, ChatOptions callOptions, boolean streaming) {
        var provider = core.provider(model.providerId());
        var api = api(provider, model);
        var merged = merged(provider, callOptions);
        var notes = new Notes(merged.strict(), merged.strictCodes());
        var options = Resolver.resolve(model, api, conversation, merged, notes);
        var adapted = Handoff.adapt(conversation, api, model, options.reasoningHandoff().orElseThrow(),
                options.historyPolicy().orElse(HistoryPolicy.ALLOW_ADAPTATION), notes);
        var context = new CodecContext(provider, model, notes, core.mapper(), Resolver.estimateTokens(adapted));
        if (adapted.cacheBreakpointsWithRetention().stream().anyMatch(b -> b.retention() != null)
                && api.features(context).promptCache() != ApiFeatures.PromptCache.EXPLICIT_MARKERS)
            notes.adapt("cache_hint_ignored", api.id() + " has no per-marker cache retention; the call's cacheRetention applies");
        HttpCall call;
        try {
            call = api.encode(new ApiRequest(model, adapted, options, streaming), context);
        } catch (LlmException | UnsupportedOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidRequestException(LlmException.Details.builder(ErrorCode.INVALID_REQUEST,
                    api.id() + " cannot encode the request: " + e.getMessage()).providerId(provider.id()).build(), e);
        }
        if (options.payload().isPresent()) call = call.withBody(edited(call, options.payload().get()));
        var limit = context.outputLimit();
        if (limit.isPresent() && !limit.equals(options.maxTokens())) options = options.toBuilder().maxTokens(limit.getAsInt()).build();
        notes.prepared();
        return new Prepared(provider, api, model, options, adapted, streaming, call, notes, context);
    }

    private WireApi api(Provider provider, Model model) {
        return model.api().map(id -> provider.api(id).orElseThrow(() -> new IllegalArgumentException("Provider '" + provider.id()
                + "' does not speak " + id + "; it speaks " + provider.apis().stream().map(WireApi::id).toList()))).orElse(provider.defaultApi());
    }

    private ChatOptions merged(Provider provider, ChatOptions callOptions) {
        return core.defaults().overriddenBy(provider.defaults()).overriddenBy(callOptions);
    }

    PreparedRequest preview(Model model, Conversation conversation, ChatOptions options) {
        try {
            return prepare(model, conversation, options, false).request();
        } catch (LlmException | IllegalArgumentException e) {
            return PreparedRequest.rejected(model.ref(), model.api().orElse("default"), List.of(String.valueOf(e.getMessage())), List.of());
        }
    }

    ApiFeatures features(Model model) {
        var provider = core.provider(model.providerId());
        return api(provider, model).features(new CodecContext(provider, model, new Notes(false, Set.of()), core.mapper(), 0));
    }

    /// Every conversion the hand-off makes, none refused.
    List<HistoryIssue> check(Model model, Conversation conversation, ChatOptions callOptions) {
        var provider = core.provider(model.providerId());
        var notes = new Notes(false, Set.of());
        Handoff.adapt(conversation, api(provider, model), model, merged(provider, callOptions).reasoningHandoff().orElse(ReasoningHandoff.KEEP),
                HistoryPolicy.ALLOW_ADAPTATION, notes);
        return notes.issues();
    }

    /// The API's counting endpoint, else the first registered tokenizer for the model, else the estimate.
    TokenCount countTokens(Prepared p, CredentialStore store) {
        var request = p.api().countRequest(p.call());
        if (request.isPresent()) {
            try {
                var counted = exchange(p.provider(), request.get(), true, store, "count_tokens", reply -> p.api().countReply(reply));
                if (counted.isPresent()) return new TokenCount(counted.getAsLong(), true, TokenCount.ENDPOINT, 0);
            } catch (LlmException e) {
                if (!NO_ENDPOINT.contains(e.httpStatus().orElse(0))) throw e;
                LOG.log(System.Logger.Level.DEBUG, () -> p.provider().id() + " has no token-counting endpoint (" + e.getMessage() + "); counting locally");
            }
        }
        var conversation = p.conversation();
        var tokenizer = core.tokenizers().stream().filter(t -> t.supports(p.model())).findFirst();
        if (tokenizer.isPresent())   // message and tool framing is not text: a margin of a few tokens each
            return new TokenCount(Resolver.countTokens(conversation, tokenizer.get()::count), false, TokenCount.TOKENIZER,
                    8L * (conversation.messages().size() + conversation.tools().size() + 1));
        long estimate = Resolver.estimateTokens(conversation);
        return new TokenCount(estimate, false, TokenCount.ESTIMATE, estimate / 4);
    }

    AssistantMessage complete(Prepared p, CredentialStore store) {
        var call = newCall(p, store, false);
        call.started();
        return complete(p, call);
    }

    /// The call is started; its failures are reported through it.
    AssistantMessage complete(Prepared p, Call call) {
        try {
            var cache = cachePlan(p, call.store());
            call.checkActive();
            var cached = lookup(cache);
            if (cached != null) {
                call.firstOutput();
                return cached.body() == null ? new DefaultChatStream(this, call, p, null, cached.frames().iterator(), null, true, null).result()
                        : finish(call, p, decode(p, cached.reply()), true);
            }
            var sent = call.send(p.call(), true);
            // endpoints that only stream (the ChatGPT Codex backend) answer with events: read them as the stream would
            if (sent.header("content-type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                return live(p, call, sent, cache).result();
            try (var reply = sent) {
                var message = call.read(reply, () -> decode(p, reply));
                call.firstOutput();
                var result = finish(call, p, message, false);
                store(cache, () -> new Exchange(reply.status(), reply.json(), List.of(), p.call().body().orElse(JsonNull.INSTANCE)));
                return result;
            }
        } catch (RuntimeException e) {
            throw call.fail(e, null);
        }
    }

    ChatStream stream(Prepared p, CredentialStore store) {
        var call = newCall(p, store, true);
        call.started();
        return stream(p, call);
    }

    /// The call is started; failures before the response headers are reported through it and thrown here.
    ChatStream stream(Prepared p, Call call) {
        try {
            var cache = cachePlan(p, call.store());
            call.checkActive();
            var cached = lookup(cache);
            if (cached != null) return new DefaultChatStream(this, call, p, null, cached.frames().iterator(), null, true, null);
            return live(p, call, call.send(p.call(), true), cache);
        } catch (RuntimeException e) {
            throw call.fail(e, null);
        }
    }

    /// Starts the call on the caller's thread — its id and `Started` exist at once — and runs it on the executor.
    LlmCall start(Prepared p, CredentialStore store) {
        var call = newCall(p, store, p.streaming());
        call.started();
        return DefaultLlmCall.start(call, core.executor(), () -> {
            if (!p.streaming()) return complete(p, call);
            try (var stream = stream(p, call)) {
                return stream.result();
            }
        });
    }

    private DefaultChatStream live(Prepared p, Call call, HttpReply reply, @Nullable CachePlan cache) {
        try {
            var reader = new FrameReader(reply.body(), p.api().streamFormat());
            return new DefaultChatStream(this, call, p, reply, reader, reader, false, cache);
        } catch (RuntimeException e) {
            reply.close();
            throw e;
        }
    }

    /// Provider APIs and live listings: the same credentials, retries, deadline, cancellation, events and error
    /// mapping; the reply is consumed inside the call's lifetime and closed afterwards.
    <T> T exchange(Provider provider, HttpCall relative, boolean replayable, CredentialStore store, String operation,
                   Function<HttpReply, T> reader) {
        return exchange(provider, relative, replayable, store, operation, core.defaults(), reader);
    }

    /// As above, with explicit runtime-scope options (connection tests bound the whole probe to one budget).
    <T> T exchange(Provider provider, HttpCall relative, boolean replayable, CredentialStore store, String operation,
                   ChatOptions runtimeOptions, Function<HttpReply, T> reader) {
        var options = runtimeOptions.overriddenBy(provider.defaults());
        var call = new Call(core, provider, new ModelRef(provider.id(), operation), "http", options, store, false, HttpErrors::details);
        call.started();
        try (var reply = call.send(relative, replayable)) {
            var result = call.read(reply, () -> reader.apply(reply));
            call.completed(null, false);
            return result;
        } catch (RuntimeException e) {
            throw call.fail(e, null);
        }
    }

    /// Cost, operational facts, warnings and silent context-overflow detection; emits `Finished`.
    AssistantMessage finish(Call call, Prepared p, AssistantMessage message, boolean fromCache) {
        var usage = priced(p, message.usage(), !fromCache);
        var warnings = new LinkedHashSet<Warning>(p.notes().warnings());
        warnings.addAll(message.warnings());
        if (!warnings.isEmpty()) LOG.log(System.Logger.Level.INFO, () -> call.requestId + " " + p.model().ref() + ": "
                + warnings.stream().map(w -> w.code() + " — " + w.message()).collect(Collectors.joining("; ")));
        var reply = message.toBuilder().usage(usage).warnings(new ArrayList<>(warnings))
                .info(ResponseInfo.builder(call.requestId, p.provider().id()).providerRequestId(call.providerRequestId())
                        .attempts(fromCache ? 0 : call.attempts()).attemptsDetail(call.ledger()).latency(call.elapsed()).timeToFirstOutput(call.timeToFirstOutput())
                        .fromCache(fromCache).build())
                .build();
        var window = p.model().contextWindow();
        var input = usage.totalInput().isPresent() ? usage.totalInput() : usage.input();
        if (window.isPresent() && input.isPresent() && input.getAsLong() > window.getAsLong())
            throw new InvalidRequestException(LlmException.Details.builder(ErrorCode.CONTEXT_OVERFLOW, "The provider reported "
                    + input.getAsLong() + " input tokens, above the context window of " + window.getAsLong() + "; the input was truncated")
                    .partial(reply).build());
        call.completed(reply, fromCache);
        return reply;
    }

    /// `usage` with its cost from the model's prices, if they cover it; `spent` is false for cache replays.
    static Usage priced(Prepared p, Usage usage, boolean spent) {
        return usage.toBuilder().cost(p.model().prices().flatMap(prices -> prices.cost(usage)).orElse(null)).spent(spent).build();
    }

    AssistantMessage decode(Prepared p, HttpReply reply) {
        try {
            return p.api().decode(reply, p.context());
        } catch (LlmException | UncheckedIOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidResponseException(LlmException.Details.builder(ErrorCode.MALFORMED_RESPONSE,
                    p.api().id() + " cannot decode the reply: " + e.getMessage()).build(), e);
        }
    }

    private Call newCall(Prepared p, CredentialStore store, boolean streaming) {
        return new Call(core, p.provider(), p.model().ref(), p.api().id(), p.options(), store, streaming,
                reply -> p.api().decodeError(reply, p.context()));
    }

    // ---- response cache stage: opt-in, keyed by the credential namespace and the effective request, replayed
    //      through the same codec

    record CachePlan(ResponseCache cache, String key, CacheMode mode) { }

    private @Nullable CachePlan cachePlan(Prepared p, CredentialStore store) {
        var mode = p.options().responseCache().orElse(CacheMode.READ_WRITE);
        var cache = core.responseCache();
        if (cache == null) {
            if (mode == CacheMode.OFFLINE) throw new InvalidRequestException(LlmException.Details.builder(ErrorCode.INVALID_REQUEST,
                    "CacheMode.OFFLINE needs a response cache: Llm.builder().responseCache(…)").build());
            return null;
        }
        if (mode == CacheMode.BYPASS) return null;
        if (!core.interceptors().isEmpty() && mode != CacheMode.OFFLINE) {
            LOG.log(System.Logger.Level.DEBUG, "Wire interceptors may change requests after the cache key is taken; caching is skipped");
            return null;
        }
        var effective = Call.assemble(p.provider(), p.call(), p.options().headers(), ResolvedAuth.none("cache-key"));
        var key = Exchange.key(p.provider().id(), p.api().id(), p.api().revision(), core.credentialNamespace(store, p.provider()), effective);
        return new CachePlan(cache, key, mode);
    }

    private static @Nullable Exchange lookup(@Nullable CachePlan plan) {
        if (plan == null || plan.mode() == CacheMode.REFRESH) return null;
        try {
            var entry = plan.cache().get(plan.key());
            if (entry.isPresent()) return Exchange.decode(entry.get());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Response cache read failed; continuing uncached", e);
        }
        if (plan.mode() == CacheMode.OFFLINE) throw new InvalidRequestException(LlmException.Details.builder(ErrorCode.CACHE_MISS,
                "No cached reply for request " + plan.key() + " (CacheMode.OFFLINE); record it once with CacheMode.REFRESH").build());
        return null;
    }

    interface ExchangeSource { Exchange get(); }

    static void store(@Nullable CachePlan plan, ExchangeSource exchange) {
        if (plan == null || plan.mode() == CacheMode.OFFLINE) return;
        try {
            plan.cache().put(plan.key(), exchange.get().encode());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Response cache write failed", e);
        }
    }

    /// The payload hook edits the body last; it may not change the model or the stream flag.
    private static JsonObject edited(HttpCall call, UnaryOperator<JsonObject> hook) {
        if (!(call.body().orElse(null) instanceof JsonObject body)) throw new IllegalArgumentException("The payload hook needs a JSON object body");
        var result = hook.apply(body);
        for (var member : Set.of("model", "stream"))
            if (!Objects.equals(body.get(member), result.get(member)))
                throw new IllegalArgumentException("The payload hook may not change '" + member + "'");
        return result;
    }
}
