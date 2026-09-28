package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import net.ai.gate.auth.Environment;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.options.HistoryPolicy;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.diagnostics.ConnectionReport.Kind;
import net.ai.gate.diagnostics.ConnectionReport.Status;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.metadata.TokenCount;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.protocol.ApiFeatures;
import net.ai.gate.spi.protocol.Tokenizer;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import net.ai.gate.testing.LlmErrors;
import net.ai.gate.testing.RecordingListener;
import org.junit.jupiter.api.Test;

/// What an autonomous host relies on beyond a reply: exact accounting after failures and cancellation, a call
/// handle whose outcome survives cancellation, prepare-once execution, capability facts and probes, the attempt
/// ledger, history policy, and descriptors for a settings UI.
class AgentHostTest {
    private static final Conversation HI = Conversation.of("hi");

    @Test
    void anInterruptedStreamKeepsTheUsageObservedSoFar() {
        var fake = FakeProvider.create().reply(r -> r.text("Hello there").usage(40, 5).truncated());
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(fake.provider()); var stream = llm.stream(fake.model(), HI, ChatOptions.builder().listener(events).build())) {
            var updates = new ArrayList<ChatEvent.UsageUpdate>();
            var error = assertThrows(LlmException.class, () -> { for (var e : stream) if (e instanceof ChatEvent.UsageUpdate u) updates.add(u); });
            assertEquals(ErrorCode.STREAM_INTERRUPTED, error.code());
            var usage = error.partial().orElseThrow().usage();
            assertEquals(40, usage.input().orElseThrow(), "input reported before the cut");
            assertFalse(usage.finalForCall());
            assertEquals(1, updates.size());
            assertFalse(updates.getFirst().observed().finalForCall());
            assertEquals(usage.input(), events.events(RequestEvent.Finished.class).getFirst().usage().input(), "the event carries it too");
        }
    }

    @Test
    void theFinalUsageIsTheDecodersNeverASumOfUpdates() {
        var fake = FakeProvider.create().reply(r -> r.text("a").usage(40, 5)).reply(r -> r.text("a").usage(40, 5));
        try (var llm = Fixtures.runtime(fake.provider())) {
            var streamed = llm.stream(fake.model(), HI).result().usage();
            assertEquals(llm.complete(fake.model(), HI).usage(), streamed);
            assertTrue(streamed.finalForCall());
            assertTrue(streamed.cost().isPresent());
        }
    }

    @Test
    void aStartedCallSettlesItsOutcomeWithTheReplyAndItsAttempts() throws Exception {
        var fake = FakeProvider.create().reply("done");
        try (var llm = Fixtures.runtime(fake.provider())) {
            var call = llm.start(fake.model(), HI, ChatOptions.none());
            var outcome = await(call.outcome());
            assertSame(await(call.reply()), outcome.reply());
            assertEquals(call.requestId(), outcome.requestId());
            assertEquals(CallOutcome.Cancellation.NONE, outcome.cancellation());
            var attempt = outcome.attempts().getFirst();
            assertEquals(1, outcome.attempts().size());
            assertTrue(attempt.sent());
            assertEquals(200, attempt.httpStatus());
            assertNull(attempt.error());
            assertEquals(outcome.attempts(), outcome.reply().info().attemptsDetail());
        }
    }

    @Test
    void cancellingBeforeTheCallRunsSettlesWithoutSending() throws Exception {
        var queued = new ArrayList<Runnable>();
        var fake = FakeProvider.create().reply("never");
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).executor(queued::add).build()) {
            var call = llm.start(fake.model(), HI, ChatOptions.none());
            call.cancel();
            queued.forEach(Runnable::run);
            var outcome = await(call.outcome());
            assertEquals(CallOutcome.Cancellation.REQUESTED_BEFORE_SEND, outcome.cancellation());
            assertFalse(outcome.outcomeUnknown());
            assertTrue(outcome.attempts().isEmpty());
            assertEquals(0, fake.sends());
            var failure = assertThrows(ExecutionException.class, () -> await(call.reply()));
            assertInstanceOf(RequestCancelledException.class, failure.getCause());
        }
    }

    @Test
    void cancellingTheReplyAfterSendStillSettlesTheOutcomeWithThePartialAndItsUsage() throws Exception {
        var fake = FakeProvider.create().pacing(4).reply(r -> r.text("an answer that streams slowly, frame by frame").usage(40, 9));
        var progressed = new CountDownLatch(3);   // after the start, usage and first text frames
        try (var llm = Fixtures.runtime(fake.provider())) {
            var call = llm.start(fake.model(), HI, ChatOptions.builder().listener(e -> { if (e instanceof RequestEvent.Progress) progressed.countDown(); }).build());
            assertTrue(progressed.await(10, TimeUnit.SECONDS), "the stream reported progress");
            call.reply().toCompletableFuture().cancel(true);
            var outcome = await(call.outcome());
            assertEquals(CallOutcome.Cancellation.REQUESTED_AFTER_SEND, outcome.cancellation());
            assertTrue(outcome.outcomeUnknown(), "the provider may bill it");
            assertEquals(40, outcome.usage().input().orElseThrow());
            assertFalse(outcome.usage().finalForCall());
            assertTrue(outcome.partial().orElseThrow().text().startsWith("an"), outcome.partial().orElseThrow().text());
            assertTrue(outcome.attempts().getFirst().sent());
        }
    }

    @Test
    void anExecutorRejectionSettlesTheOutcomeExceptionallyAndFinishesTheCall() {
        var fake = FakeProvider.create().reply("never");
        var events = new RecordingListener();
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline())
                .executor(_ -> { throw new RejectedExecutionException("full"); }).listener(events).build()) {
            var call = llm.start(fake.model(), HI, ChatOptions.none());
            var failure = assertThrows(ExecutionException.class, () -> await(call.outcome()));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(RequestEvent.Finished.Outcome.FAILED, events.events(RequestEvent.Finished.class).getFirst().outcome());
        }
    }

    @Test
    void retriesAreRecordedAttemptByAttempt() {
        var fake = FakeProvider.create().fail(LlmErrors.rateLimited(Duration.ofMillis(5))).reply("ok");
        try (var llm = Fixtures.runtime(fake.provider())) {
            var attempts = llm.complete(fake.model(), "hi").info().attemptsDetail();
            assertEquals(2, attempts.size());
            assertEquals(429, attempts.getFirst().httpStatus());
            assertEquals(ErrorCode.RATE_LIMITED, attempts.getFirst().error());
            assertFalse(attempts.getFirst().outcomeUnknown(), "429 was not processed");
            assertEquals(2, attempts.get(1).index());
            assertNull(attempts.get(1).error());
        }
    }

    @Test
    void aPreparedCallIsInspectedOnceAndRunsAsPrepared() {
        var fake = FakeProvider.create().reply("one").reply("two");
        var options = ChatOptions.builder().maxTokens(100).build();
        try (var llm = Fixtures.runtime(fake.provider()); var other = Fixtures.runtime(FakeProvider.create().provider())) {
            var prepared = llm.prepare(fake.model(), HI, options, true);
            assertEquals(prepared.digest(), llm.prepare(fake.model(), HI, options, true).digest());
            assertNotEquals(prepared.digest(), llm.prepare(fake.model(), HI, options, false).digest());
            assertEquals(100, prepared.effectiveOptions().maxTokens().orElseThrow());
            assertTrue(prepared.request().sendable());
            assertEquals("one", llm.start(prepared).reply().toCompletableFuture().join().text());
            assertEquals("two", llm.complete(prepared).text(), "reusable: every execution is a new call");
            assertThrows(IllegalArgumentException.class, () -> other.complete(prepared));
        }
    }

    @Test
    void tokensAreCountedLocallyWithoutAnEndpoint() {
        var fake = FakeProvider.create();
        Tokenizer words = new Tokenizer() {
            @Override public boolean supports(Model model) { return model.id().equals("fake"); }
            @Override public long count(String text) { return text.isBlank() ? 0 : text.strip().split("\\s+").length; }
        };
        var ask = Conversation.builder().system("Be brief.").user("three words here").build();
        try (var plain = Fixtures.runtime(fake.provider());
             var counted = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).tokenizer(words).build()) {
            var estimate = plain.countTokens(plain.prepare(fake.model(), ask, ChatOptions.none(), false));
            assertEquals(TokenCount.ESTIMATE, estimate.method());
            assertFalse(estimate.exact());
            assertTrue(estimate.marginTokens() > 0);
            var local = counted.countTokens(counted.prepare(fake.model(), ask, ChatOptions.none(), false));
            assertEquals(new TokenCount(5, false, TokenCount.TOKENIZER, 8 * 2), local);
        }
    }

    @Test
    void probesEstablishUsageToolsAndCachingByExperiment() {
        var fake = FakeProvider.create()
                .reply(r -> r.text("OK").usage(10, 1))
                .reply(r -> r.toolCall("echo", Json.object("text", "ok")))
                .reply("ok")
                .reply(r -> r.text("OK").usage(Usage.builder().input(5).output(1).cacheRead(0).cacheWrite(4000).build()))
                .reply(r -> r.text("OK").usage(Usage.builder().input(5).output(1).cacheRead(4000).cacheWrite(0).build()));
        try (var llm = Fixtures.runtime(fake.provider())) {
            assertEquals(ApiFeatures.PromptCache.AUTOMATIC, llm.features(fake.model()).promptCache());
            var report = llm.test(fake.model(), t -> t.usageFields().toolRoundTrip().cacheRoundTrip());
            assertTrue(report.ok(), report.toString());
            var byKind = report.steps().stream().collect(java.util.stream.Collectors.toMap(s -> s.kind(), s -> s));
            assertEquals(Status.PASSED, byKind.get(Kind.USAGE).status());
            assertTrue(byKind.get(Kind.USAGE).message().contains("cache_read"), byKind.get(Kind.USAGE).message());
            assertEquals(Status.PASSED, byKind.get(Kind.TOOLS).status());
            assertTrue(byKind.get(Kind.CACHE).message().contains("4000"), byKind.get(Kind.CACHE).message());
            fake.assertAllRepliesConsumed();
        }
    }

    @Test
    void aFailedProbeSkipsOnlyThePlannedLaterOnes() {
        var fake = FakeProvider.create().reply("no tool call");
        try (var llm = Fixtures.runtime(fake.provider())) {
            var report = llm.test(fake.model(), t -> t.toolRoundTrip());
            assertEquals(Kind.TOOLS, report.firstFailure().orElseThrow().kind());
            assertEquals(List.of(Kind.CONFIGURATION, Kind.NETWORK, Kind.AUTHENTICATION, Kind.MODEL_ACCESS, Kind.INFERENCE, Kind.TOOLS),
                    report.steps().stream().map(s -> s.kind()).toList());
        }
    }

    @Test
    void historyPolicyDecidesWhatForeignTurnsMayLose() {
        var foreign = AssistantMessage.builder(new ModelRef("other", "model"), "other-api")
                .add(Content.Reasoning.of("thinking", "sig", false, JsonNull.INSTANCE)).text("answer").build();
        var history = Conversation.of("q").append(foreign).appendUser("next");
        var fake = FakeProvider.create().reply("ok");
        try (var llm = Fixtures.runtime(fake.provider())) {
            var issues = llm.check(fake.model(), history, ChatOptions.none());
            assertEquals(1, issues.size());
            assertEquals(1, issues.getFirst().messageIndex());
            assertEquals(0, issues.getFirst().partIndex());
            assertEquals("reasoning_converted", issues.getFirst().warning().code());

            var lossy = assertThrows(InvalidRequestException.class, () -> llm.complete(fake.model(), history,
                    ChatOptions.builder().historyPolicy(HistoryPolicy.REJECT_LOSSY).build()));
            assertTrue(lossy.getMessage().contains("messages[1].content[0]"), lossy.getMessage());
            assertThrows(InvalidRequestException.class, () -> llm.complete(fake.model(), Conversation.of("q").append(
                    AssistantMessage.builder(new ModelRef("other", "model"), "other-api").text("plain").build()),
                    ChatOptions.builder().historyPolicy(HistoryPolicy.SAME_ORIGIN_REQUIRED).build()));
            assertEquals("ok", llm.complete(fake.model(), history).text(), "ALLOW_ADAPTATION by default");
        }
    }

    @Test
    void strictCodesFailNamedWarningsWhilePreparing() {
        var fake = FakeProvider.create();
        var foreign = Conversation.of("q").append(AssistantMessage.builder(new ModelRef("other", "model"), "other-api")
                .add(Content.Reasoning.of("thinking", null, false, JsonNull.INSTANCE)).text("a").build());
        try (var llm = Fixtures.runtime(fake.provider())) {
            var error = assertThrows(InvalidRequestException.class, () -> llm.prepare(fake.model(), foreign,
                    ChatOptions.builder().strictCodes(Set.of("reasoning_converted")).build(), false));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, error.code());
            assertTrue(llm.prepare(fake.model(), foreign, ChatOptions.none(), false).request().warnings().stream()
                    .anyMatch(w -> w.code().equals("reasoning_converted")));
        }
    }

    @Test
    void aPartialReplyNeverReplaysOrAnswersItsCutOffToolCall() {
        var partial = AssistantMessage.builder(new ModelRef("fake", "fake"), "fake-chat").text("Let me check")
                .add(net.ai.gate.chat.content.ToolCall.of("call_1", "weather", "{\"ci")).incompletePart(1).build();
        var call = partial.toolCalls().getFirst();
        assertFalse(partial.complete());
        assertThrows(IllegalArgumentException.class, () -> HI.append(partial, List.of(ToolResult.of(call, "sunny"))));
        assertThrows(IllegalArgumentException.class, () -> HI.append(partial, List.of(ToolResult.of("call_1", "weather", List.of(Content.text("x")), true))));
        var fake = FakeProvider.create().reply("ok");
        try (var llm = Fixtures.runtime(fake.provider())) {
            llm.complete(fake.model(), HI.append(partial).appendUser("go on"));
            var sent = (net.ai.gate.chat.AssistantMessage) fake.requests().getFirst().conversation().messages().get(1);
            assertEquals(List.of(Content.text("Let me check")), sent.content(), "the cut-off call is omitted");
        }
    }

    @Test
    void settingsFormsComeFromDescriptorsAlone() {
        var fake = FakeProvider.create();
        var fields = ChatOptions.fields(fake.model());
        var keys = fields.stream().map(f -> f.key()).toList();
        assertEquals(keys.size(), Set.copyOf(keys).size(), "keys are unique: " + keys);
        var b = ChatOptions.builder();
        for (var field : fields) b.set(field.key(), switch (field.kind()) {
            case BOOLEAN -> "true";
            case INTEGER -> "7";
            case DECIMAL -> "0.5";
            case DURATION -> "PT30S";
            case CHOICE -> field.choices().getLast();
            default -> field.key().equals("strictCodes") ? "option_adapted" : "x";
        });
        var options = b.build();
        assertEquals(HistoryPolicy.ALLOW_ADAPTATION, options.historyPolicy().orElseThrow());
        assertEquals(Set.of("option_adapted"), options.strictCodes());
        assertEquals(options.toJson(), ChatOptions.fromJson(options.toJson()).toJson(), "every form value round-trips");
        assertTrue(fake.provider().fields().stream().anyMatch(f -> f.key().equals("baseUrl") && f.required()));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
}
