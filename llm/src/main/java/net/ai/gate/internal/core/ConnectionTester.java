package net.ai.gate.internal.core;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import net.ai.gate.Llm;
import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.cache.CacheMode;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.diagnostics.ConnectionReport;
import net.ai.gate.diagnostics.ConnectionReport.Kind;
import net.ai.gate.diagnostics.ConnectionReport.Status;
import net.ai.gate.diagnostics.ConnectionReport.Step;
import net.ai.gate.diagnostics.ConnectionTest;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.LlmException;
import net.ai.gate.model.Model;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.TransportOptions;
import org.jspecify.annotations.Nullable;

/// `llm.test(model)`: configuration → network → authentication → model access → optional inference, stopping at
/// the first failure, all within one shared time budget. Credentials count as *verified* only when the provider
/// offers a non-billable authenticated probe (its model listing); otherwise the authentication stage reports
/// `NOT_SUPPORTED` with the local source of the credentials. Non-billable unless an inference probe is requested;
/// the probe bypasses the response cache.
final class ConnectionTester {
    private final Core core;
    private final Llm llm;
    private final CredentialStore store;
    private final List<Step> steps = new ArrayList<>();
    private long deadline;

    ConnectionTester(Core core, Llm llm, CredentialStore store) { this.core = core; this.llm = llm; this.store = store; }

    ConnectionReport test(Model model, ConnectionTest settings) {
        deadline = System.nanoTime() + settings.timeout().toNanos();
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
        if (settings.inferenceProbe().isEmpty()) {
            add(Kind.INFERENCE, Status.SKIPPED, null, "not requested: an inference probe is billable", null);
        } else {
            try {
                llm.complete(model, Conversation.of("Reply with OK."), ChatOptions.builder().maxTokens(settings.inferenceProbe().getAsInt())
                        .timeouts(p -> p.total(remaining())).responseCache(CacheMode.BYPASS).build());
                add(Kind.INFERENCE, Status.PASSED, t, "the model answered", null);
            } catch (RuntimeException e) {
                return fail(Kind.INFERENCE, t, e);
            }
        }
        return ConnectionReport.of(steps);
    }

    /// What is left of the shared budget; never zero, so a late stage still fails with a deadline rather than an argument error.
    private Duration remaining() { return Duration.ofNanos(Math.max(1_000_000, deadline - System.nanoTime())); }

    private void add(Kind kind, Status status, @Nullable Long start, String message, @Nullable LlmException error) {
        steps.add(Step.of(kind, status, start == null ? null : Duration.ofNanos(System.nanoTime() - start), message, error));
    }

    /// Records the failed stage and marks the later ones `SKIPPED`.
    private ConnectionReport fail(Kind kind, long start, Exception e) {
        add(kind, Status.FAILED, start, String.valueOf(e.getMessage()), e instanceof LlmException l ? l : null);
        var remaining = EnumSet.range(kind, Kind.INFERENCE);
        remaining.remove(kind);
        remaining.forEach(k -> add(k, Status.SKIPPED, null, "an earlier stage failed", null));
        return ConnectionReport.of(steps);
    }
}
