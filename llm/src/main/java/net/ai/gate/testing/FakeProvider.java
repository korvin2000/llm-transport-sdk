package net.ai.gate.testing;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.LlmException;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.Prices;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.spi.protocol.ApiRequest;

/// A scripted provider behind a real `Llm`: resolution, hand-off, validation, retries, streaming aggregation,
/// response caching and events behave exactly as in production, because only the wire endpoint is simulated.
/// Several fakes can coexist in one runtime, so model-switching tests need no mocks. Thread-safe.
///
/// ```java
/// var fake = FakeProvider.create().reply(r -> r.toolCall("read_file", Json.object("path", "a.txt"))).reply("Done.");
/// try (var llm = Llm.of(fake.provider())) { … }
/// fake.assertAllRepliesConsumed();
/// ```
public final class FakeProvider {
    private final FakeWireApi api = new FakeWireApi();
    private final FakeServer server = new FakeServer(api);
    private final Provider provider;

    private FakeProvider(String providerId, List<Model> models) {
        var builder = Provider.builder(providerId, api).name("Fake (" + providerId + ")")
                .baseUrl("https://fake.invalid/" + providerId + "/v1").auth(ApiKeyAuth.none()).transport(server);
        models.forEach(builder::model);
        provider = builder.build();
    }

    /// Provider `fake` with models `fake` (text and images, tools, structured output) and `fake-thinker`
    /// (reasoning levels `LOW`, `MEDIUM`, `HIGH`).
    public static FakeProvider create() { return create("fake"); }

    /// A fake with the given models, or the two default models when none are given.
    public static FakeProvider create(String providerId, Model... models) {
        return new FakeProvider(providerId, models.length > 0 ? List.of(models) : List.of(
                Model.builder(providerId, "fake").name("Fake").input(Modality.TEXT, Modality.IMAGE).output(Modality.TEXT)
                        .contextWindow(128_000).maxOutputTokens(4_096)
                        .supports(Capability.STREAMING, Capability.TOOLS, Capability.PARALLEL_TOOLS, Capability.STRUCTURED_OUTPUT, Capability.VISION)
                        .prices(Prices.usd().input("1").output("2").build()).build(),
                Model.builder(providerId, "fake-thinker").name("Fake Thinker").input(Modality.TEXT).output(Modality.TEXT)
                        .contextWindow(200_000).maxOutputTokens(16_384).reasoningLevels(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
                        .supports(Capability.STREAMING, Capability.TOOLS, Capability.REASONING)
                        .prices(Prices.usd().input("3").output("15").build()).build()));
    }

    /// The next call answers with `text`.
    public FakeProvider reply(String text) { server.add(new FakeServer.Reply(ScriptedReply.text(text))); return this; }

    /// The next call answers with reasoning, tool calls, text, usage or a stop reason.
    public FakeProvider reply(Consumer<ScriptedReply.Builder> reply) {
        var builder = ScriptedReply.builder();
        reply.accept(builder);
        server.add(new FakeServer.Reply(builder.build()));
        return this;
    }

    /// The next call fails the way the provider would signal `error`: its HTTP status (so `Retry-After` and the
    /// retry policy apply), or a connection failure for `connect_failed` and `outcome_unknown`.
    public FakeProvider fail(LlmException error) { server.add(new FakeServer.Failure(error)); return this; }

    /// The next call answers dynamically from the adapted request.
    public FakeProvider respond(Function<ApiRequest, AssistantMessage> handler) { server.add(new FakeServer.Dynamic(handler)); return this; }

    /// The next call answers with a frame whose data is not JSON (streamed) or a non-JSON body (not streamed), at
    /// HTTP status 200: decoding fails and the core reports `malformed_response`.
    public FakeProvider malformed() { server.add(new FakeServer.Malformed()); return this; }

    /// The next call answers with 200 headers at once, then blocks reading the body until the returned handle is
    /// released (serving a small reply saying "released"), the body is closed — for example by cancelling the
    /// call — or the reading thread is interrupted. Works for both streamed and non-streamed calls.
    public Stall stall() {
        var body = new FakeServer.StallBody();
        server.add(new FakeServer.Stalled(body));
        return new Stall(body);
    }

    /// Streams at roughly this many chunks per second, for UI tests.
    public FakeProvider pacing(int tokensPerSecond) { server.pacing(tokensPerSecond); return this; }

    public Provider provider() { return provider; }

    /// The first model.
    public Model model() { return provider.models().getFirst(); }

    public Model model(String id) {
        return provider.models().stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("FakeProvider '" + provider.id() + "' has no model " + id));
    }

    /// The requests as the codec received them — conversations adapted to the target — in order; retries once.
    public List<ApiRequest> requests() { return server.requests(); }

    /// Chat requests the server actually received, retries included — unlike [#requests()], which counts each one
    /// once regardless of how many times it was resent.
    public int sends() { return server.sends(); }

    /// @throws AssertionError when scripted items are left
    public void assertAllRepliesConsumed() {
        int left = server.remaining();
        if (left > 0) throw new AssertionError(left + " scripted item(s) of FakeProvider '" + provider.id() + "' were not consumed");
    }

    /// Handle to a call stalled by [#stall()]: `release()` lets it complete, answering with text "released".
    public static final class Stall {
        private final FakeServer.StallBody body;

        Stall(FakeServer.StallBody body) { this.body = body; }

        public void release() { body.release(); }
        public boolean released() { return body.isReleased(); }
    }
}
