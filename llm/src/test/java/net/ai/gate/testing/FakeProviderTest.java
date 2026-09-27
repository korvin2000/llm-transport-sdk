package net.ai.gate.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.ai.gate.Llm;
import net.ai.gate.auth.Environment;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.TransportException;
import net.ai.gate.lifecycle.CancelToken;
import org.junit.jupiter.api.Test;

/// [FakeProvider]'s request bookkeeping and lifecycle fixtures, exercised through a real [Llm] runtime so only the
/// wire endpoint is simulated.
class FakeProviderTest {
    private static Llm runtime(FakeProvider fake) {
        return Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).build();
    }

    @Test
    void concurrentEqualBodiesFromDifferentConversationsBothAppearInRequestsAndSends() throws InterruptedException {
        var fake = FakeProvider.create().reply("one").reply("two");
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var results = new ConcurrentLinkedQueue<String>();
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            Runnable call = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(llm.complete(model, Conversation.of("identical text")).text());
            };
            var t1 = new Thread(call);
            var t2 = new Thread(call);
            t1.start();
            t2.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads reached the call");
            go.countDown();
            t1.join(5_000);
            t2.join(5_000);
            assertFalse(t1.isAlive());
            assertFalse(t2.isAlive());
            assertEquals(2, fake.requests().size(), "two distinct prepared requests, even though their bodies are equal");
            assertEquals(2, fake.sends());
            assertEquals(Set.of("one", "two"), Set.copyOf(results));
        }
        fake.assertAllRepliesConsumed();
    }

    @Test
    void repeatedPreviewNeverSendsAndStaysBounded() {
        var fake = FakeProvider.create();
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            for (int i = 0; i < 300; i++) {
                var preview = llm.preview(model, Conversation.of("preview text " + i), ChatOptions.none());
                assertTrue(preview.sendable(), preview.problems().toString());
            }
            assertTrue(fake.requests().isEmpty(), "preview() never sends");
            assertEquals(0, fake.sends());
        }
    }

    @Test
    void truncatedStreamReportsStreamInterruptedWithThePartialReply() {
        var fake = FakeProvider.create().reply(r -> r.text("partial text").truncated());
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            try (var stream = llm.stream(model, Conversation.of("go"))) {
                var error = assertThrows(TransportException.class, () -> { for (var event : stream) { } });
                assertEquals(ErrorCode.STREAM_INTERRUPTED, error.code());
                assertTrue(error.partial().isPresent());
                assertEquals("partial text", error.partial().orElseThrow().text());
            }
        }
    }

    @Test
    void truncatedNonStreamReportsMalformedResponse() {
        var fake = FakeProvider.create().reply(r -> r.text("some text").truncated());
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var error = assertThrows(InvalidResponseException.class, () -> llm.complete(model, Conversation.of("go")));
            assertEquals(ErrorCode.MALFORMED_RESPONSE, error.code());
        }
    }

    @Test
    void malformedReportsMalformedResponseForStreamAndComplete() {
        var fake = FakeProvider.create().malformed().malformed();
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var completeError = assertThrows(InvalidResponseException.class, () -> llm.complete(model, Conversation.of("go")));
            assertEquals(ErrorCode.MALFORMED_RESPONSE, completeError.code());
            try (var stream = llm.stream(model, Conversation.of("go"))) {
                var streamError = assertThrows(InvalidResponseException.class, () -> { for (var event : stream) { } });
                assertEquals(ErrorCode.MALFORMED_RESPONSE, streamError.code());
            }
        }
    }

    @Test
    void stallBlocksUntilReleasedThenAnswersReleased() throws InterruptedException {
        var fake = FakeProvider.create();
        var stall = fake.stall();
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var result = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            var thread = new Thread(() -> {
                result.set(llm.complete(model, Conversation.of("go")).text());
                done.countDown();
            });
            thread.start();
            Thread.sleep(200);
            assertTrue(thread.isAlive(), "the call should still be blocked on the stalled body");
            assertFalse(stall.released());
            stall.release();
            assertTrue(done.await(5, TimeUnit.SECONDS), "the call should complete once released");
            thread.join(5_000);
            assertEquals("released", result.get());
            assertTrue(stall.released());
        }
    }

    @Test
    void cancellingAStalledStreamEndsTheReaderWithRequestCancelled() throws InterruptedException {
        var fake = FakeProvider.create();
        fake.stall();
        var run = CancelToken.create();
        try (var llm = runtime(fake)) {
            var model = llm.model("fake", "fake");
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try (var stream = llm.stream(model, Conversation.of("go"), ChatOptions.builder().cancel(run).build())) {
                    for (var event : stream) { }
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            thread.start();
            awaitBlocked(thread);
            run.cancel();
            thread.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(thread.isAlive(), "the reader thread should end once cancelled");
            assertInstanceOf(RequestCancelledException.class, failure.get());
        }
    }

    /// Polls (no fixed sleep) until `thread` is parked waiting for the stalled body to be released or closed.
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) fail("the reader thread never blocked on the stalled body; was " + thread.getState());
            Thread.sleep(5);
        }
    }
}
