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
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import net.ai.gate.testing.RecordingListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// The stream contract: one view, one iterator; close from any thread ends the stream exactly once; failures keep
/// the partial reply and are thrown once.
@Timeout(20)
class StreamContractTest {
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
