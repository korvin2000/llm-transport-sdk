package net.ai.gate.internal.core;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import net.ai.gate.Llm;
import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.cache.CacheMode;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.diagnostics.ConnectionReport;
import net.ai.gate.diagnostics.ConnectionReport.Kind;
import net.ai.gate.diagnostics.ConnectionReport.Status;
import net.ai.gate.diagnostics.ConnectionReport.Step;
import net.ai.gate.diagnostics.ConnectionTest;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.LlmException;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.Model;
import net.ai.gate.spi.protocol.ApiFeatures;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.TransportOptions;
import org.jspecify.annotations.Nullable;

/// `llm.test(model)`: configuration → network → authentication → model access → optional inference, stopping at
/// the first failure, all within one shared time budget. Credentials count as *verified* only when the provider
/// offers a non-billable authenticated probe (its model listing); otherwise the authentication stage reports
/// `NOT_SUPPORTED` with the local source of the credentials. Non-billable unless an inference probe or one of the
/// probes after it (usage fields, tool round trip, cache round trip) is requested; probes bypass the response cache.
final class ConnectionTester {
    private static final int PROBE_TOKENS = 256;
    /// Long enough to reach every provider's minimum cacheable prefix (up to 4096 tokens).
    private static final String CACHE_PREFIX = "This is the stable prefix of an AI Gate prompt-cache probe. ".repeat(400);

    record Echo(String text) { }

    private final Core core;
    private final Llm llm;
    private final CredentialStore store;
    private final List<Step> steps = new ArrayList<>();
    private final EnumSet<Kind> planned = EnumSet.range(Kind.CONFIGURATION, Kind.INFERENCE);
    private long deadline;
    private int probeTokens = PROBE_TOKENS;

    ConnectionTester(Core core, Llm llm, CredentialStore store) { this.core = core; this.llm = llm; this.store = store; }

    ConnectionReport test(Model model, ConnectionTest settings) {
        deadline = System.nanoTime() + settings.timeout().toNanos();
        if (settings.usageFields()) planned.add(Kind.USAGE);
        if (settings.toolRoundTrip()) planned.add(Kind.TOOLS);
        if (settings.cacheRoundTrip()) planned.add(Kind.CACHE);
        probeTokens = settings.inferenceProbe().orElse(PROBE_TOKENS);
        Provider provider;
        long t = System.nanoTime();
        try {
            provider = core.provider(model.providerId());
            var prepared = core.engine().prepare(model, Conversation.of("ping"), ChatOptions.none(), false);
            add(Kind.CONFIGURATION, Status.PASSED, t, provider.id() + " via " + prepared.api().id(), null);
        } catch (RuntimeException e) {
            return fail(Kind.CONFIGURATION, t, e);
        }

        t = System.nanoTime();
        var transport = provider.transport().orElse(core.transport());
        var connect = core.defaults().timeouts().orElse(TimeoutPolicy.defaults()).connect();
        try (var reply = transport.send(HttpCall.of("GET", provider.baseUrl(), Map.of(), null), TransportOptions.of(connect, remaining(), false))) {
            add(Kind.NETWORK, Status.PASSED, t, provider.baseUrl().getHost() + " answered HTTP " + reply.status(), null);
        } catch (IOException | RuntimeException e) {
            return fail(Kind.NETWORK, t, e);
        }

        t = System.nanoTime();
        String source;
        try {
            source = core.resolver().resolve(provider, store).source();
        } catch (RuntimeException e) {
            return fail(Kind.AUTHENTICATION, t, e);
        }
        if (provider.modelSource().isEmpty()) {
            add(Kind.AUTHENTICATION, Status.NOT_SUPPORTED, t, "credentials from " + source + " are configured but not verified: the provider has no non-billable probe", null);
            add(Kind.MODEL_ACCESS, Status.NOT_SUPPORTED, null, "the provider has no model listing", null);
        } else {
            List<Model> listed;
            try {
                var options = ChatOptions.builder().timeouts(p -> p.total(remaining())).responseCache(CacheMode.BYPASS).build();
                listed = provider.modelSource().get().fetch(core.providerHttp(provider, store, options));
                add(Kind.AUTHENTICATION, Status.PASSED, t, "credentials from " + source + " were accepted", null);
            } catch (IOException | RuntimeException e) {
                return fail(e instanceof AuthenticationException ? Kind.AUTHENTICATION : Kind.MODEL_ACCESS, t, e);
            }
            t = System.nanoTime();
            if (listed.stream().noneMatch(m -> m.id().equals(model.id())))
                return fail(Kind.MODEL_ACCESS, t, new IllegalStateException(model.id() + " is not listed for these credentials"));
            add(Kind.MODEL_ACCESS, Status.PASSED, t, model.id() + " is listed", null);
        }

        t = System.nanoTime();
        AssistantMessage answer = null;
        if (settings.inferenceProbe().isEmpty()) {
            add(Kind.INFERENCE, Status.SKIPPED, null, "not requested: an inference probe is billable", null);
        } else {
            try {
                answer = llm.complete(model, Conversation.of("Reply with OK."), probe().build());
                add(Kind.INFERENCE, Status.PASSED, t, "the model answered", null);
            } catch (RuntimeException e) {
                return fail(Kind.INFERENCE, t, e);
            }
        }
        for (var kind : planned) {
            if (kind.compareTo(Kind.INFERENCE) <= 0) continue;
            t = System.nanoTime();
            try {
                switch (kind) {
                    case USAGE -> usage(answer != null ? answer : llm.complete(model, Conversation.of("Reply with OK."), probe().build()), t);
                    case TOOLS -> tools(model, t);
                    case CACHE -> cache(model, t);
                    default -> throw new IllegalStateException(kind.toString());
                }
            } catch (RuntimeException e) {
                return fail(kind, t, e);
            }
        }
        return ConnectionReport.of(steps);
    }

    /// Passes when input and output are reported: without them no call can be priced.
    private void usage(AssistantMessage reply, long start) {
        var u = reply.usage();
        if (u.input().isEmpty() || u.output().isEmpty())
            throw new IllegalStateException("the reply reports " + reported(u) + "; input and output are needed to price a call");
        add(Kind.USAGE, Status.PASSED, start, "reported: " + reported(u), null);
    }

    private static String reported(Usage u) {
        var fields = new StringJoiner(", ", "[", "]");
        if (u.input().isPresent()) fields.add("input");
        if (u.output().isPresent()) fields.add("output");
        if (u.reasoning().isPresent()) fields.add("reasoning");
        if (u.cacheRead().isPresent()) fields.add("cache_read");
        if (u.cacheWrite().isPresent()) fields.add("cache_write");
        u.cacheWrites().keySet().forEach(r -> fields.add("cache_write_" + r.name().toLowerCase(Locale.ROOT)));
        return fields.toString();
    }

    /// A forced call of a one-argument tool, then its result: two billable calls.
    private void tools(Model model, long start) {
        var ask = Conversation.builder().tool(Tool.of("echo", "Returns the text it is given", Echo.class))
                .user("Call the echo tool with the text 'ok'.").build();
        var first = llm.complete(model, ask, probe().toolChoice(ToolChoice.required()).build());
        if (!first.hasToolCalls()) throw new IllegalStateException("the model answered without calling the tool");
        var call = first.toolCalls().getFirst();
        llm.complete(model, ask.append(first, List.of(ToolResult.of(call, "ok"))), probe().build());
        add(Kind.TOOLS, Status.PASSED, start, "called " + call.name() + " with " + call.argumentsJson() + " and answered its result", null);
    }

    /// The same cacheable prefix twice: passes when the second call reports reading from the cache.
    private void cache(Model model, long start) {
        if (core.engine().features(model).promptCache() == ApiFeatures.PromptCache.NONE) {
            add(Kind.CACHE, Status.NOT_SUPPORTED, start, "the API has no prompt caching", null);
            return;
        }
        var ask = Conversation.builder().system(CACHE_PREFIX).cacheBreakpoint().user("Reply with OK.").build();
        var first = llm.complete(model, ask, probe().build()).usage();
        var second = llm.complete(model, ask, probe().build()).usage();
        if (second.cacheRead().orElse(0) == 0) throw new IllegalStateException("the second call reported no cache read: " + second);
        add(Kind.CACHE, Status.PASSED, start, "the second call read " + second.cacheRead().getAsLong() + " cached tokens"
                + (first.cacheWrite().isPresent() ? "; the first wrote " + first.cacheWrite().getAsLong() : ""), null);
    }

    /// Probe options: bounded output, the shared time budget, never the response cache.
    private ChatOptions.Builder probe() {
        return ChatOptions.builder().maxTokens(probeTokens).timeouts(p -> p.total(remaining())).responseCache(CacheMode.BYPASS);
    }

    /// What is left of the shared budget; never zero, so a late stage still fails with a deadline rather than an argument error.
    private Duration remaining() { return Duration.ofNanos(Math.max(1_000_000, deadline - System.nanoTime())); }

    private void add(Kind kind, Status status, @Nullable Long start, String message, @Nullable LlmException error) {
        steps.add(Step.of(kind, status, start == null ? null : Duration.ofNanos(System.nanoTime() - start), message, error));
    }

    /// Records the failed stage and marks the later planned ones `SKIPPED`.
    private ConnectionReport fail(Kind kind, long start, Exception e) {
        add(kind, Status.FAILED, start, String.valueOf(e.getMessage()), e instanceof LlmException l ? l : null);
        planned.stream().filter(k -> k.compareTo(kind) > 0).forEach(k -> add(k, Status.SKIPPED, null, "an earlier stage failed", null));
        return ConnectionReport.of(steps);
    }
}
