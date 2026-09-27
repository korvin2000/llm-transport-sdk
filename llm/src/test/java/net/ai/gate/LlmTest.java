package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.cache.CacheMode;
import net.ai.gate.cache.ResponseCache;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.diagnostics.ConnectionReport;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.TransportException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.model.Model;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.LlmErrors;
import net.ai.gate.testing.RecordingListener;
import org.junit.jupiter.api.Test;

/// The facade end to end: a real runtime over a scripted provider, so only the wire endpoint is simulated.
class LlmTest {
    static Llm runtime(FakeProvider... fakes) {
        var builder = Llm.builder().environment(Environment.none()).catalog(c -> c.offline());
        for (var fake : fakes) builder.provider(fake.provider());
        return builder.build();
    }

    @Test
    void simpleCompleteReturnsTextUsageAndCost() {
        var fake = FakeProvider.create().reply(r -> r.text("Records are transparent carriers.").usage(20, 10));
        try (var llm = runtime(fake)) {
            var reply = llm.complete(llm.model("fake", "fake"), "Explain Java records.");
            assertEquals("Records are transparent carriers.", reply.text());
            assertEquals(StopReason.STOP, reply.stopReason());
            assertEquals(20, reply.usage().input().orElseThrow());
            assertEquals(new BigDecimal("0.00004"), reply.usage().cost().orElseThrow().total().stripTrailingZeros());
            assertTrue(reply.usage().spent());
            assertEquals(1, reply.info().attempts());
        }
        fake.assertAllRepliesConsumed();
    }

    @Test
    void unknownProviderFailsNamingTheKnownOnes() {
        try (var llm = runtime(FakeProvider.create())) {
            var error = assertThrows(IllegalArgumentException.class, () -> llm.model("anthropc", "claude"));
            assertTrue(error.getMessage().contains("[fake]"), error.getMessage());
        }
    }

    @Test
    void unlistedModelIsCallableAndReported() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake-next");
            assertEquals(Model.Source.UNLISTED, model.source());
            assertTrue(llm.complete(model, "hi").warnings().stream().anyMatch(w -> w.code().equals("unlisted_model")));
        }
    }

    @Test
    void streamDeliversDeltasAndItsResultEqualsTheCompleteResult() {
        var fake = FakeProvider.create();
        fake.reply(r -> r.text("Let me read ünïcödé files.").toolCall("read_file", Json.object("path", "src/Main.java")));
        fake.reply(r -> r.text("Let me read ünïcödé files.").toolCall("read_file", Json.object("path", "src/Main.java")));
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var complete = llm.complete(model, "read");
            var text = new StringBuilder();
            var partialPaths = new ArrayList<String>();
            AssistantMessage streamed;
            try (var stream = llm.stream(model, Conversation.of("read"))) {
                for (var event : stream) {
                    switch (event) {
                        case ChatEvent.TextDelta d -> text.append(d.text());
                        case ChatEvent.ToolCallDelta d -> d.partialArguments().get("path").ifPresent(p -> partialPaths.add(p.toString()));
                        default -> { }
                    }
                }
                streamed = stream.result();
            }
            assertEquals("Let me read ünïcödé files.", text.toString());
            assertTrue(partialPaths.size() > 1, "partial arguments grow while streaming: " + partialPaths);
            assertEquals(complete.content(), streamed.content());
            assertEquals(complete.stopReason(), streamed.stopReason());
            assertEquals(StopReason.TOOL_USE, streamed.stopReason());
            assertEquals("src/Main.java", streamed.toolCalls().getFirst().arguments().string("path"));
        }
    }

    record ReadFile(String path) { }

    @Test
    void hostOwnsTheToolLoop() {
        var fake = FakeProvider.create()
                .reply(r -> r.toolCall("read_file", Json.object("path", "a.txt")))
                .reply("The file says hello.");
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var chat = Conversation.builder().tool(Tool.of("read_file", "Read a file", ReadFile.class)).user("Summarize a.txt").build();
            var reply = llm.complete(model, chat);
            while (reply.hasToolCalls()) {
                var results = reply.toolCalls().stream().map(c -> ToolResult.of(c, "hello from " + c.arguments(ReadFile.class).path())).toList();
                chat = chat.append(reply, results);
                reply = llm.complete(model, chat);
            }
            assertEquals("The file says hello.", reply.text());
            assertEquals(3, chat.messages().size());
            var second = fake.requests().get(1).conversation().messages().getLast();
            assertEquals("hello from a.txt", assertInstanceOf(ToolResultMessage.class, second).results().getFirst().text());
        }
        fake.assertAllRepliesConsumed();
    }

    record Invoice(String number, List<Integer> amounts) { }

    @Test
    void structuredOutputBindsRecordsAndReportsTruncation() {
        var fake = FakeProvider.create()
                .reply("{\"number\": \"INV-7\", \"amounts\": [3, 4]}")
                .reply(r -> r.text("{\"number\": \"INV").stopReason(StopReason.LENGTH));
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            assertEquals(new Invoice("INV-7", List.of(3, 4)), llm.complete(model, Conversation.of("extract"), Invoice.class));
            var error = assertThrows(InvalidResponseException.class, () -> llm.complete(model, Conversation.of("extract"), Invoice.class));
            assertEquals(ErrorCode.OUTPUT_TRUNCATED, error.code());
            assertTrue(error.partial().isPresent());
            assertTrue(fake.requests().getFirst().options().output().orElseThrow() instanceof OutputFormat.Typed);
        }
    }

    @Test
    void notProcessedResponsesAreRetriedHonouringRetryAfter() {
        var events = new RecordingListener();
        var fake = FakeProvider.create().fail(LlmErrors.rateLimited(Duration.ofMillis(20))).reply("ok");
        try (var llm = runtime(fake)) {
            llm.addListener(events);
            var reply = llm.complete(llm.model("fake", "fake"), "hi");
            assertEquals("ok", reply.text());
            assertEquals(2, reply.info().attempts());
            var retry = events.events(RequestEvent.Retrying.class).getFirst();
            assertEquals(ErrorCode.RATE_LIMITED, retry.errorCode());
            assertEquals(Duration.ofMillis(20), retry.delay());
            assertEquals(1, fake.requests().size(), "a retry resends the same request");
        }
    }

    @Test
    void exhaustedRetriesThrowTheTypedException() {
        var fake = FakeProvider.create().fail(LlmErrors.overloaded()).fail(LlmErrors.overloaded()).fail(LlmErrors.overloaded());
        try (var llm = runtime(fake)) {
            var error = assertThrows(ProviderException.class, () -> llm.complete(llm.model("fake", "fake"), Conversation.of("hi"),
                    ChatOptions.builder().retry(r -> r.backoff(Duration.ofMillis(1), 1, Duration.ofMillis(1))).build()));
            assertEquals(ErrorCode.OVERLOADED, error.code());
            assertEquals(3, error.attempts());
            assertTrue(error.requestId().isPresent());
        }
    }

    @Test
    void ambiguousFailuresAreNotRetriedAndReportOutcomeUnknown() {
        var fake = FakeProvider.create().fail(LlmErrors.connectionReset()).reply("never sent");
        try (var llm = runtime(fake)) {
            var error = assertThrows(TransportException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
            assertTrue(error.outcomeUnknown());
            assertEquals(1, error.attempts());
        }
    }

    @Test
    void eventsArriveInOrderAndListenerFailuresAreIsolated() {
        var events = new RecordingListener();
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            llm.addListener(_ -> { throw new IllegalStateException("a broken listener"); });
            var reply = llm.complete(llm.model("fake", "fake"), Conversation.of("hi"),
                    ChatOptions.builder().listener(events).tag("run", "42").build());
            assertEquals("ok", reply.text());
            var kinds = events.events(RequestEvent.class).stream().map(e -> e.getClass().getSimpleName()).toList();
            assertEquals(List.of("Started", "FirstOutput", "Finished"), kinds);
            var finished = events.events(RequestEvent.Finished.class).getFirst();
            assertEquals(RequestEvent.Finished.Outcome.COMPLETED, finished.outcome());
            assertEquals("42", finished.tags().get("run"));
            assertEquals(reply.info().requestId(), finished.requestId());
        }
    }

    @Test
    void cancellingTheRunTokenStopsAStreamAndKeepsThePartialReply() throws Exception {
        var events = new RecordingListener();
        var fake = FakeProvider.create().pacing(200).reply("one two three four five six seven eight nine ten eleven twelve");
        var run = CancelToken.create();
        try (var llm = runtime(fake)) {
            try (var stream = llm.stream(llm.model("fake", "fake"), Conversation.of("count"),
                    ChatOptions.builder().cancel(run.child()).listener(events).build())) {
                var iterator = stream.iterator();
                assertTrue(iterator.hasNext());
                iterator.next();
                run.cancel();
                var error = assertThrows(RequestCancelledException.class, () -> { while (iterator.hasNext()) iterator.next(); });
                assertEquals(StopReason.ABORTED, error.partial().orElseThrow().stopReason());
            }
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, events.events(RequestEvent.Finished.class).getFirst().outcome());
        }
    }

    @Test
    void responseCacheReplaysWithoutBillingAndOfflineMissesFail() {
        var fake = FakeProvider.create().reply("cached answer");
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline())
                .responseCache(ResponseCache.inMemory(10)).build()) {
            var model = llm.model("fake", "fake");
            var first = llm.complete(model, "same question");
            var second = llm.complete(model, "same question");
            assertEquals(first.text(), second.text());
            assertTrue(second.info().fromCache());
            assertFalse(second.usage().spent());
            var miss = assertThrows(InvalidRequestException.class, () -> llm.complete(model, Conversation.of("new question"),
                    ChatOptions.builder().responseCache(CacheMode.OFFLINE).build()));
            assertEquals(ErrorCode.CACHE_MISS, miss.code());
        }
        fake.assertAllRepliesConsumed();
    }

    @Test
    void previewShowsTheExactRequestWithoutSendingIt() {
        var fake = FakeProvider.create();
        try (var llm = runtime(fake)) {
            var preview = llm.preview(llm.model("fake", "fake"), Conversation.builder().system("Be terse.").user("hi").build(),
                    ChatOptions.builder().temperature(0.2).build());
            assertTrue(preview.sendable(), preview.problems().toString());
            assertEquals("https://fake.invalid/fake/v1/chat", preview.uri().toString());
            var body = (JsonObject) preview.body();
            assertEquals("short", body.string("cache"));
            assertTrue(body.get("max_tokens").isEmpty(), "unset values are not sent");
            assertTrue(preview.notes().stream().anyMatch(n -> n.code().equals("prompt_cache_default")));
            assertTrue(preview.toCurl().startsWith("curl -X POST"));
            assertTrue(fake.requests().isEmpty(), "preview never sends");
        }
    }

    @Test
    void completeAsyncRunsOnAVirtualThread() throws Exception {
        var fake = FakeProvider.create().reply("async");
        try (var llm = runtime(fake)) {
            assertEquals("async", llm.completeAsync(llm.model("fake", "fake"), Conversation.of("hi")).get().text());
        }
    }

    @Test
    void closedRuntimeRejectsCallsAndViewsShareIt() {
        var llm = runtime(FakeProvider.create());
        var view = llm.withCredentials(CredentialStore.inMemory());
        view.close();
        assertEquals(1, llm.models().all("fake").stream().filter(m -> m.id().equals("fake")).count());
        llm.close();
        llm.close();
        assertThrows(IllegalStateException.class, () -> llm.model("fake", "fake"));
        assertThrows(IllegalStateException.class, () -> view.model("fake", "fake"));
    }

    @Test
    void connectionTestIsStagedAndNonBillable() {
        var fake = FakeProvider.create();
        try (var llm = runtime(fake)) {
            var report = llm.test(llm.model("fake", "fake"));
            assertTrue(report.ok(), report.toString());
            assertEquals(ConnectionReport.Status.SKIPPED, report.steps().getLast().status());
            assertTrue(fake.requests().isEmpty());
        }
    }

    @Test
    void describeIsRedactedConfiguration() {
        try (var llm = runtime(FakeProvider.create())) {
            var description = llm.describe().toJson();
            assertTrue(description.contains("\"id\":\"fake\""), description);
            assertTrue(description.contains("keyless"), description);
        }
    }
}
