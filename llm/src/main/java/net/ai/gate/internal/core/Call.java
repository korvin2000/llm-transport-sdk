package net.ai.gate.internal.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.config.RetryPolicy;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.config.WireLog;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.error.TransportException;
import net.ai.gate.event.LlmListener;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.event.RequestEvent.Finished.Outcome;
import net.ai.gate.internal.http.HttpErrors;
import net.ai.gate.internal.http.Redaction;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.lifecycle.Registration;
import net.ai.gate.metadata.Attempt;
import net.ai.gate.metadata.ResponseInfo;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.TransportOptions;
import net.ai.gate.spi.http.WireInterceptor;
import org.jspecify.annotations.Nullable;

/// One logical call: its identity, deadline and cancel token, the attempt loop — credentials ▷ interceptors ▷
/// transport, retrying only documented not-processed failures — and its events, delivered in order. The call owns
/// its token for its whole lifetime: the link to the caller's token is released on every terminal path, and every
/// blocking phase (credentials, send, body, backoff) observes cancellation and the deadline. Each attempt is recorded
/// in a ledger: when it started, how it ended, whether the request left the client. Confined to the thread running
/// it — handed over once by `Llm.start` — except cancellation.
final class Call {
    private static final System.Logger WIRE = System.getLogger("net.ai.gate.wire");

    private final Core core;
    final Provider provider;
    final ModelRef model;
    final String api;
    private final CredentialStore store;
    final boolean streaming;
    final String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final long start = System.nanoTime();
    private final @Nullable Long deadline;
    final CancelToken token = CancelToken.create();
    private final Registration parentLink;
    final TimeoutPolicy timeouts;
    private final RetryPolicy retry;
    private final Map<String, String> tags, headers;
    private final List<LlmListener> listeners;
    private final Function<HttpReply, LlmException.Details> errors;
    private volatile @Nullable ErrorCode watchdogFired;
    private volatile boolean sent;
    private @Nullable Duration firstOutput;
    private @Nullable String providerRequestId;
    private int attempts;
    private boolean finished;
    private final List<Attempt> ledger = new ArrayList<>();
    private @Nullable Instant attemptAt;     // the open attempt, if any
    private long attemptStart;
    private boolean attemptSent;
    private @Nullable Integer attemptStatus;

    Call(Core core, Provider provider, ModelRef model, String api, ChatOptions options, CredentialStore store, boolean streaming,
         Function<HttpReply, LlmException.Details> errors) {
        this.core = core; this.provider = provider; this.model = model; this.api = api; this.store = store;
        this.streaming = streaming; this.errors = errors;
        timeouts = options.timeouts().orElse(TimeoutPolicy.defaults());
        retry = options.retry().orElse(RetryPolicy.defaults());
        deadline = timeouts.total().map(t -> start + t.toNanos()).orElse(null);
        tags = options.tags();
        headers = options.headers();
        listeners = options.listeners();
        parentLink = options.cancel().map(parent -> parent.onCancel(token::cancel)).orElse(() -> { });
        core.track(token);
    }

    // ---- lifecycle events

    void started() { core.hub().emit(RequestEvent.Started.of(requestId, model, tags, core.clock().instant(), api, streaming), listeners); }

    void firstOutput() {
        if (firstOutput != null) return;
        firstOutput = elapsed();
        core.hub().emit(RequestEvent.FirstOutput.of(requestId, model, tags, core.clock().instant(), firstOutput), listeners);
    }

    /// Content-free progress of a stream, for listeners of hosts that own the stream.
    void progress(@Nullable Long outputTokens, long outputChars) {
        core.hub().emit(RequestEvent.Progress.of(requestId, model, tags, core.clock().instant(), outputTokens, outputChars), listeners);
    }

    void completed(@Nullable AssistantMessage reply, boolean fromCache) { finished(Outcome.COMPLETED, reply, null, fromCache); }

    /// Adds call facts to a failure, marks the partial reply, emits `Finished` and returns what the caller throws.
    RuntimeException fail(RuntimeException error, @Nullable AssistantMessage partial) {
        var failure = error instanceof UncheckedIOException io ? classify(io.getCause()) : error;
        if (failure instanceof LlmException e) {
            var available = partial != null ? partial : e.partial().orElse(null);
            endAttempt(e.code(), e.outcomeUnknown());
            // the call facts of a partial reply: its timings survive the failure
            var info = available == null || !available.info().requestId().isEmpty() ? null
                    : ResponseInfo.builder(requestId, provider.id()).providerRequestId(providerRequestId).route(available.info().route().orElse(null))
                            .attempts(attempts).attemptsDetail(List.copyOf(ledger)).latency(elapsed()).timeToFirstOutput(firstOutput).build();
            var marked = available == null ? null : available.toBuilder()
                    .stopReason(e instanceof RequestCancelledException ? StopReason.ABORTED : StopReason.ERROR).errorMessage(e.getMessage())
                    .info(info != null ? info : available.info()).build();
            failure = HttpErrors.withFacts(e, requestId, provider.id(), attempts, marked);
            finished(failure instanceof RequestCancelledException ? Outcome.CANCELLED : Outcome.FAILED, marked, (LlmException) failure, false);
        } else {
            finished(Outcome.FAILED, partial, null, false);
        }
        return failure;
    }

    /// Exactly once: the terminal event, the JFR record, and the release of the token links.
    private void finished(Outcome outcome, @Nullable AssistantMessage reply, @Nullable LlmException error, boolean fromCache) {
        if (finished) return;
        finished = true;
        core.untrack(token);
        parentLink.close();
        endAttempt(error == null ? null : error.code(), error != null && error.outcomeUnknown());
        var usage = reply != null ? reply.usage() : Usage.empty();
        core.hub().emit(RequestEvent.Finished.builder(requestId, model, tags, core.clock().instant(), outcome)
                .usage(usage).latency(elapsed()).timeToFirstOutput(firstOutput).attempts(fromCache ? 0 : attempts).attemptsDetail(List.copyOf(ledger))
                .warnings(reply != null ? reply.warnings() : List.of()).fromCache(fromCache)
                .errorCode(error == null ? null : error.code()).outcomeUnknown(error != null && error.outcomeUnknown())
                .providerRequestId(providerRequestId).build(), listeners);
        Jfr.record(this, outcome, usage, fromCache, error == null ? null : error.code());
    }

    Duration elapsed() { return Duration.ofNanos(System.nanoTime() - start); }
    @Nullable Duration timeToFirstOutput() { return firstOutput; }
    @Nullable String providerRequestId() { return providerRequestId; }
    int attempts() { return attempts; }
    CredentialStore store() { return store; }

    /// Every attempt so far; an open one — the attempt that produced the reply — is closed as successful.
    List<Attempt> ledger() {
        endAttempt(null, false);
        return List.copyOf(ledger);
    }
    @Nullable Long deadline() { return deadline; }
    Map<String, String> tags() { return tags; }
    void watchdogFired(@Nullable ErrorCode code) { if (code != null) watchdogFired = code; }

    // ---- the attempt loop

    /// Sends until a 2xx reply, retrying pre-send failures and not-processed statuses within the deadline. The
    /// returned reply's body is unread; consume it with [#read].
    /// @throws LlmException the mapped failure, without call facts (added by [#fail])
    HttpReply send(HttpCall relative, boolean replayable) {
        ResolvedAuth rejected = null;
        boolean refreshed = false;
        for (int attempt = 1; ; attempt++) {
            attempts = attempt;
            beginAttempt();
            checkActive();
            var auth = resolve(rejected);
            rejected = null;
            checkActive();
            var call = assemble(provider, relative, headers, auth);
            HttpReply reply;
            try {
                reply = blocking(() -> chain(call, 0));
            } catch (IOException e) {
                var failure = classify(e);
                var delay = failure.retryable() && replayable ? backoff(attempt + 1, null) : null;
                if (delay == null) throw failure;
                endAttempt(failure.code(), failure.outcomeUnknown());
                pause(failure.code(), attempt + 1, delay);
                continue;
            }
            providerRequestId = reply.header("x-request-id").or(() -> reply.header("request-id")).orElse(providerRequestId);
            attemptStatus = reply.status();
            if (reply.successful()) return reply;
            LlmException.Details details;
            try (reply) { details = read(reply, () -> errors.apply(reply)); }
            if (reply.status() == 401 && !refreshed && core.resolver().usesOAuth(provider, store)) {
                refreshed = true;
                rejected = auth;   // one forced refresh of exactly this token; later attempts resolve normally
                endAttempt(details.code(), false);
                continue;
            }
            boolean retryable = retry.retryOnStatus().contains(reply.status()) && !details.outcomeUnknown()
                    && !details.code().equals(ErrorCode.QUOTA_EXHAUSTED);   // waiting does not refill a quota
            var delay = retryable && replayable ? backoff(attempt + 1, details.retryAfter().orElse(null)) : null;
            if (delay == null) throw HttpErrors.exception(details.toBuilder().retryable(retryable).build(), null);
            endAttempt(details.code(), false);
            pause(details.code(), attempt + 1, delay);
        }
    }

    private void beginAttempt() {
        attemptAt = core.clock().instant();
        attemptStart = System.nanoTime();
        attemptSent = false;
        attemptStatus = null;
    }

    private void endAttempt(@Nullable ErrorCode error, boolean outcomeUnknown) {
        var at = attemptAt;
        if (at == null) return;
        ledger.add(new Attempt(ledger.size() + 1, at, Duration.ofNanos(System.nanoTime() - attemptStart), attemptStatus, error, attemptSent, outcomeUnknown));
        attemptAt = null;
    }

    /// Credentials, inside the cancellation and deadline guards: a refresh may block on the store and the network.
    private ResolvedAuth resolve(@Nullable ResolvedAuth rejected) {
        try {
            return blocking(() -> rejected == null ? core.resolver().resolve(provider, store)
                                                  : core.resolver().refreshAfterRejection(provider, store, rejected));
        } catch (IOException e) {
            throw classify(e);
        }
    }

    /// Consumes a reply inside this call's lifetime: cancellation and the total deadline close the body, so the
    /// reader fails promptly and the failure is classified by [#classify].
    <T> T read(HttpReply reply, Supplier<T> reader) {
        T result;
        try (var _ = token.onCancel(reply::close);
             var watchdog = new Watchdog(reply::close, deadline, null, System::nanoTime)) {
            try {
                result = reader.get();
            } finally {
                watchdogFired(watchdog.fired());
            }
        }
        if (watchdogFired != null) throw classify(null);   // the body was aborted: whatever was read is not the reply
        checkActive();
        return result;
    }

    private HttpReply chain(HttpCall call, int index) throws IOException {
        var interceptors = core.interceptors();
        if (index < interceptors.size()) return interceptors.get(index).intercept(new Link(call, index));
        checkOrigin(call);
        var remaining = deadline == null ? null : Duration.ofNanos(Math.max(1_000_000, deadline - System.nanoTime()));
        if (core.http().wireLog() != WireLog.OFF) WIRE.log(System.Logger.Level.INFO, () -> "→ " + describe(call));
        sent = true;
        attemptSent = true;
        var reply = provider.transport().orElse(core.transport()).send(call, TransportOptions.of(timeouts.connect(), remaining, streaming));
        if (core.http().wireLog() != WireLog.OFF) WIRE.log(System.Logger.Level.INFO, () -> "← " + reply.status() + " " + requestId);
        return reply;
    }

    /// One interceptor's view of the rest of the chain.
    private final class Link implements WireInterceptor.Chain {
        private final HttpCall call;
        private final int index;

        Link(HttpCall call, int index) { this.call = call; this.index = index; }

        @Override public HttpCall call() { return call; }
        @Override public String providerId() { return provider.id(); }
        @Override public String requestId() { return requestId; }
        @Override public HttpReply proceed(HttpCall next) throws IOException { return chain(next, index + 1); }
    }

    private interface IoAction<T> { T run() throws IOException; }

    /// Cancellation interrupts the blocked thread; only an interrupt caused by the token is cleared afterwards, an
    /// interrupt from elsewhere stays pending for the caller.
    private <T> T blocking(IoAction<T> action) throws IOException {
        var interrupt = new BlockingInterrupt();
        try (var _ = token.onCancel(() -> interrupt.abort(null));
             var _ = new Watchdog(() -> interrupt.abort(ErrorCode.DEADLINE_EXCEEDED), deadline, null, System::nanoTime);
             interrupt) {
            try {
                checkActive();
                return action.run();
            } catch (IOException | RuntimeException e) {
                if (watchdogFired != null || token.isCancelled()) throw classify(e);
                throw e;
            }
        }
    }

    /// Serializes interruption with leaving a blocking phase, so a late callback cannot interrupt the caller's
    /// next operation. An interrupt already pending before ours belongs to the caller and is preserved.
    private final class BlockingInterrupt implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private boolean active = true, interrupted;

        synchronized void abort(@Nullable ErrorCode reason) {
            if (!active) return;
            watchdogFired(reason);
            if (!thread.isInterrupted()) {
                interrupted = true;
                thread.interrupt();
            }
        }

        @Override public synchronized void close() {
            active = false;
            if (interrupted) Thread.interrupted();
        }
    }

    private @Nullable Duration backoff(int nextAttempt, @Nullable Duration retryAfter) {
        if (nextAttempt > retry.maxAttempts()) return null;
        Duration delay;
        if (retryAfter != null) {
            if (retryAfter.compareTo(retry.maxRetryAfter()) > 0) return null;
            delay = retryAfter;
        } else {
            delay = Duration.ofMillis(ThreadLocalRandom.current().nextLong(retry.backoffCeiling(nextAttempt).toMillis() + 1));
        }
        return deadline != null && System.nanoTime() + delay.toNanos() - deadline >= 0 ? null : delay;
    }

    private void pause(ErrorCode reason, int nextAttempt, Duration delay) {
        core.hub().emit(RequestEvent.Retrying.of(requestId, model, tags, core.clock().instant(), nextAttempt, delay, reason), listeners);
        try {
            blocking(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted during backoff");
                }
                return null;
            });
        } catch (IOException e) {
            throw classify(e);
        }
    }

    void checkActive() {
        if (watchdogFired != null) throw classify(null);
        if (token.isCancelled()) throw cancelled(null);
        if (deadline != null && System.nanoTime() - deadline >= 0)
            throw new RequestTimeoutException(details(ErrorCode.DEADLINE_EXCEEDED, "The total deadline of " + timeouts.total().orElseThrow()
                    + " passed").outcomeUnknown(sent).build());
    }

    /// Before send: retryable; after send: the provider may have executed the request.
    LlmException classify(@Nullable Throwable e) {
        var fired = watchdogFired;
        if (fired != null) return new RequestTimeoutException(details(fired, fired == ErrorCode.STREAM_IDLE_TIMEOUT
                ? "No bytes for " + timeouts.streamIdle() : "The total deadline passed").outcomeUnknown(sent).build(), e);
        if (token.isCancelled()) return cancelled(e);
        if (e instanceof InterruptedIOException && !(e instanceof HttpTimeoutException)) {
            Thread.currentThread().interrupt();
            return cancelled(e);
        }
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException || e instanceof UnknownHostException)
            return new TransportException(details(ErrorCode.CONNECT_FAILED, "Cannot connect to " + provider.baseUrl().getHost() + ": " + e)
                    .retryable(true).build(), e);
        if (e instanceof HttpTimeoutException)
            return new RequestTimeoutException(details(ErrorCode.DEADLINE_EXCEEDED, "No response before the deadline").outcomeUnknown(true).build(), e);
        return new TransportException(details(ErrorCode.OUTCOME_UNKNOWN, "The connection failed after sending: " + e).outcomeUnknown(sent).build(), e);
    }

    /// Cancelled after the request left: the provider may still have executed it.
    private RequestCancelledException cancelled(@Nullable Throwable cause) {
        return new RequestCancelledException(details(ErrorCode.CANCELLED, "The call was cancelled").outcomeUnknown(sent).build(), cause);
    }

    private static LlmException.Details.Builder details(ErrorCode code, String message) { return LlmException.Details.builder(code, message); }

    // ---- request assembly, shared with preview() and the response-cache key

    /// Provider headers ▷ codec headers ▷ call headers ▷ credentials, names compared case-insensitively; the URI
    /// resolved against the base URL (or the credential's).
    static HttpCall assemble(Provider provider, HttpCall relative, Map<String, String> callHeaders, ResolvedAuth auth) {
        var uri = relative.uri();
        if (uri.isAbsolute() || uri.getRawPath().startsWith("/"))
            throw new IllegalArgumentException("Requests use URIs relative to the provider's base URL: " + uri);
        var base = auth.baseUrl().orElse(provider.baseUrl()).toString();
        var resolved = URI.create(base.endsWith("/") ? base : base + "/").resolve(uri);
        if (!auth.query().isEmpty()) resolved = URI.create(resolved + (resolved.getRawQuery() == null ? "?" : "&") + auth.query().entrySet()
                .stream().map(q -> encode(q.getKey()) + "=" + encode(q.getValue())).collect(Collectors.joining("&")));
        var all = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        all.putAll(provider.headers());
        all.putAll(relative.headers());
        all.putAll(callHeaders);
        all.putAll(auth.headers());
        boolean credentials = !auth.headers().isEmpty() || !auth.query().isEmpty();
        if (credentials && "http".equals(resolved.getScheme()) && !isLoopback(resolved) && !provider.allowsInsecureCredentials())
            throw new InvalidRequestException(details(ErrorCode.INVALID_REQUEST, "Refusing to send credentials of '" + provider.id()
                    + "' over cleartext HTTP to " + resolved.getHost() + "; use HTTPS or Provider.Builder.allowInsecureCredentials()").build());
        return HttpCall.of(relative.method(), resolved, all, relative.body().orElse(null));
    }

    /// Interceptors cannot redirect credentials: the destination must stay the provider's origin.
    private void checkOrigin(HttpCall call) {
        var origin = provider.baseUrl();
        if (!Objects.equals(call.uri().getScheme(), origin.getScheme()) || !Objects.equals(call.uri().getHost(), origin.getHost())
                || call.uri().getPort() != origin.getPort())
            throw new IllegalStateException("A wire interceptor changed the destination of '" + provider.id() + "' to " + call.uri().getHost());
    }

    static boolean isLoopback(URI uri) {
        var host = String.valueOf(uri.getHost());
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String describe(HttpCall call) {
        var redacted = Redaction.headers(call.headers()).entrySet().stream().map(h -> h.getKey() + ": " + h.getValue()).collect(Collectors.joining(", "));
        var body = core.http().wireLog() == WireLog.BODIES ? " " + call.body().map(Object::toString).orElse("") : "";
        return call.method() + " " + Redaction.uri(call.uri()) + " [" + redacted + "] " + requestId + body;
    }
}
