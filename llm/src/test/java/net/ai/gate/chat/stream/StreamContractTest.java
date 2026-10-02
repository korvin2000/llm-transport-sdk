package net.ai.gate.chat.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import net.ai.gate.testing.RecordingListener;
import net.ai.gate.vendors.openai.OpenAiCompatible;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// The stream contract: one view, one iterator; close from any thread ends the stream exactly once; failures keep
/// the partial reply and are thrown once.
@Timeout(20)
class StreamContractTest {
    @Test
    void aWatchdogInducedEofCannotCompleteTheStreamSuccessfully() {
        var closed = new CountDownLatch(1);
        var tail = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                try {
                    if (!closed.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("body was not closed");
                    return -1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted");
                }
            }
            @Override public void close() { closed.countDown(); }
        };
        var sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n\n";
        var provider = OpenAiCompatible.ollama().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)), tail)))).build();
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            var options = ChatOptions.builder().timeouts(t -> t.streamIdle(Duration.ofMillis(200))).build();
            try (var stream = llm.stream(llm.model("ollama", "test"), Conversation.of("hi"), options)) {
                var error = assertThrows(RequestTimeoutException.class, stream::result);
                assertEquals(net.ai.gate.error.ErrorCode.STREAM_IDLE_TIMEOUT, error.code());
                assertEquals("partial", error.partial().orElseThrow().text());
                assertEquals(RequestEvent.Finished.Outcome.FAILED, events.events(RequestEvent.Finished.class).getFirst().outcome());
            }
        }
    }

    @Test
    void usageAndLifecycleEventsAreNotTheFirstOutput() {
        var silent = sse("{\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":0}}",
                "{\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}", "[DONE]");
        var spoken = sse("{\"id\":\"d\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":0}}",
                "{\"id\":\"d\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}", "[DONE]");
        var bodies = new java.util.ArrayDeque<>(List.of(silent, spoken));
        var provider = OpenAiCompatible.ollama().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.ByteArrayInputStream(bodies.removeFirst())))).build();
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            var model = llm.model("ollama", "test");
            try (var stream = llm.stream(model, Conversation.of("hi"))) {
                var reply = stream.result();
                assertTrue(reply.info().timeToFirstOutput().isEmpty(), "usage alone is not output");
                assertTrue(events.events(RequestEvent.FirstOutput.class).isEmpty());
                assertTrue(events.events(RequestEvent.Finished.class).getFirst().timeToFirstOutput().isEmpty());
            }
            try (var stream = llm.stream(model, Conversation.of("hi"))) {
                var reply = stream.result();
                var first = reply.info().timeToFirstOutput().orElseThrow();
                assertTrue(first.compareTo(reply.info().latency()) <= 0);
                assertEquals(List.of(first), events.events(RequestEvent.FirstOutput.class).stream().map(RequestEvent.FirstOutput::latency).toList());
            }
        }
    }

    @Test
    void aPartialReplyKeepsItsCallFacts() {
        var body = sse("{\"id\":\"g\",\"provider\":\"Groq\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"half\"}}]}");
        var provider = OpenAiCompatible.ollama().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.ByteArrayInputStream(body)))).build();
        try (var llm = Fixtures.runtime(provider); var stream = llm.stream(llm.model("ollama", "test"), Conversation.of("hi"))) {
            var error = assertThrows(LlmException.class, stream::result);
            assertEquals(ErrorCode.STREAM_INTERRUPTED, error.code());
            var partial = error.partial().orElseThrow();
            assertEquals("half", partial.text());
            var info = partial.info();
            assertEquals(error.requestId().orElseThrow(), info.requestId());
            assertEquals("Groq", info.route().orElseThrow());
            assertEquals(1, info.attempts());
            assertEquals(ErrorCode.STREAM_INTERRUPTED, info.attemptsDetail().getFirst().error());
            assertTrue(info.timeToFirstOutput().orElseThrow().compareTo(info.latency()) <= 0);
        }
    }

    @Test
    void aRouteNamedInALaterChunkReachesTheInterruptedPartialAndTheStartedEvents() {
        var body = sse("{\"id\":\"g\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ha\"}}]}",
                "{\"id\":\"g\",\"provider\":\"Groq\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lf\"}}]}");
        var provider = OpenAiCompatible.ollama().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.ByteArrayInputStream(body)))).build();
        var started = new java.util.ArrayList<ChatEvent.Started>();
        try (var llm = Fixtures.runtime(provider); var stream = llm.stream(llm.model("ollama", "test"), Conversation.of("hi"))) {
            var error = assertThrows(LlmException.class, () -> stream.forEach(e -> {
                if (e instanceof ChatEvent.Started s) started.add(s);
            }));
            assertEquals(ErrorCode.STREAM_INTERRUPTED, error.code());
            var partial = error.partial().orElseThrow();
            assertEquals("half", partial.text());
            assertEquals("Groq", partial.info().route().orElseThrow());
            assertEquals("g", partial.responseId().orElseThrow());
            assertEquals(List.of(java.util.Optional.<String>empty(), java.util.Optional.of("Groq")), started.stream().map(ChatEvent.Started::route).toList());
        }
    }

    @Test
    void aRouteNamedInTheFirstChunkIsStatedOnceAndKeptByTheCompletedReply() {
        var body = sse("{\"id\":\"g\",\"provider\":\"Groq\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ha\"}}]}",
                "{\"id\":\"g\",\"provider\":\"Groq\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lf\"},\"finish_reason\":\"stop\"}]}", "[DONE]");
        var provider = OpenAiCompatible.ollama().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.ByteArrayInputStream(body)))).build();
        var started = new java.util.ArrayList<ChatEvent.Started>();
        try (var llm = Fixtures.runtime(provider); var stream = llm.stream(llm.model("ollama", "test"), Conversation.of("hi"))) {
            stream.forEach(e -> {
                if (e instanceof ChatEvent.Started s) started.add(s);
            });
            assertEquals(1, started.size());
            assertEquals("Groq", started.getFirst().route().orElseThrow());
            var reply = stream.result();
            assertEquals("half", reply.text());
            assertEquals("Groq", reply.info().route().orElseThrow());
        }
    }

    private static byte[] sse(String... data) {
        var text = new StringBuilder();
        for (var d : data) text.append("data: ").append(d).append("\n\n");
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aViewYieldsOneIteratorOnly() {
        var fake = FakeProvider.create().reply("one two");
        try (var llm = Fixtures.runtime(fake.provider()); var stream = llm.stream(llm.model("fake", "fake"), Conversation.of("count"))) {
            var deltas = stream.textDeltas();
            var text = new StringBuilder();
            deltas.forEach(text::append);
            assertEquals("one two", text.toString());
            assertThrows(IllegalStateException.class, deltas::iterator);
            assertThrows(IllegalStateException.class, stream::iterator);
            assertEquals("one two", stream.result().text());
        }
    }

    @Test
    void closeFromAnotherThreadEndsABlockedStreamOnce() throws Exception {
        var stalled = new Fixtures.Stalled();
        var sse = "event: start\ndata: {\"id\":\"r1\",\"model\":\"fake\"}\n\nevent: text\ndata: {\"index\":0,\"text\":\"partial\"}\n\n";
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        stalled.reply(200, "").body())))).build();
        var events = new RecordingListener();
        var consumerError = new AtomicReference<Throwable>();
        var firstDelta = new CountDownLatch(1);
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            var stream = llm.stream(llm.model("fake", "fake"), Conversation.of("count"));
            var consumer = Thread.ofVirtual().start(() -> {
                try {
                    for (var event : stream) if (event instanceof ChatEvent.TextDelta) firstDelta.countDown();
                } catch (Throwable e) {
                    consumerError.set(e);
                }
            });
            assertTrue(firstDelta.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            stream.close();
            consumer.join(Duration.ofSeconds(5));
            assertFalse(consumer.isAlive());
            var error = assertInstanceOf(RequestCancelledException.class, consumerError.get());
            assertEquals("partial", error.partial().orElseThrow().text());
            assertEquals(StopReason.ABORTED, error.partial().orElseThrow().stopReason());
            assertSame(error, assertThrows(RequestCancelledException.class, stream::result), "result() keeps the failure");
            assertEquals("partial", stream.partial().text());
            var finished = events.events(RequestEvent.Finished.class);
            assertEquals(1, finished.size());
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, finished.getFirst().outcome());
            stream.close();
            assertEquals(1, events.events(RequestEvent.Finished.class).size(), "close is idempotent");
        }
    }

    @Test
    void theIdleTimeoutEndsAStalledStreamWithThePartialReply() {
        var stalled = new Fixtures.Stalled();
        var sse = "event: text\ndata: {\"index\":0,\"text\":\"partial\"}\n\n";
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> HttpReply.of(200,
                Map.of("content-type", List.of("text/event-stream")), new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        stalled.reply(200, "").body())))).build();
        try (var llm = Fixtures.runtime(provider)) {
            var options = ChatOptions.builder().timeouts(t -> t.streamIdle(Duration.ofMillis(200))).build();
            try (var stream = llm.stream(llm.model("fake", "fake"), Conversation.of("count"), options)) {
                var error = assertThrows(RequestTimeoutException.class, () -> stream.forEach(_ -> { }));
                assertEquals(net.ai.gate.error.ErrorCode.STREAM_IDLE_TIMEOUT, error.code());
                assertEquals("partial", error.partial().orElseThrow().text());
            }
        }
    }

    @Test
    void closingAnUnreadStreamOnTheSameThreadFinishesAsCancelled() {
        var fake = FakeProvider.create().reply("one two three");
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(fake.provider())) {
            llm.addListener(events);
            llm.stream(llm.model("fake", "fake"), Conversation.of("count")).close();
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, events.events(RequestEvent.Finished.class).getFirst().outcome());
        }
    }
}
