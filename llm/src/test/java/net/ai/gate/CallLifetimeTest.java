package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.ai.gate.cache.ResponseCache;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.TokenSupplier;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import net.ai.gate.testing.RecordingListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// One call lifetime: cancellation and the total deadline cover every blocking phase — before send, on cache
/// replay, while a body is read, while an error body is read — with exactly one terminal event.
@Timeout(20)
class CallLifetimeTest {
    @Test
    void aDeadlineCanInterruptWaitingForAnotherTokenFetch() throws Exception {
        var fetching = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fake = FakeProvider.create().reply("first");
        var auth = ApiKeyAuth.dynamic("shared identity", () -> {
            fetching.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new InterruptedIOException("test did not release fetch");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("fetch interrupted");
            }
            return new TokenSupplier.AccessToken(Secret.of("test-token"), Optional.empty());
        });
        try (var llm = Fixtures.runtime(fake.provider().toBuilder().auth(auth).build())) {
            var model = llm.model("fake", "fake");
            var first = llm.completeAsync(model, Conversation.of("first"));
            assertTrue(fetching.await(2, TimeUnit.SECONDS));
            var error = new AtomicReference<Throwable>();
            var waiter = Thread.ofVirtual().start(() -> {
                try {
                    llm.complete(model, Conversation.of("second"), ChatOptions.builder().timeouts(t -> t.total(Duration.ofMillis(100))).build());
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            try {
                waiter.join(Duration.ofSeconds(1));
                assertFalse(waiter.isAlive(), "a waiter must respect its own deadline while another call holds the token lock");
                assertInstanceOf(RequestTimeoutException.class, error.get());
            } finally {
                release.countDown();
                waiter.join(Duration.ofSeconds(2));
            }
            assertEquals("first", first.get(2, TimeUnit.SECONDS).text());
        }
    }

    @Test
    void ambiguousServerFailuresDoNotRepeatGeneration() {
        for (int status : new int[] {500, 502}) {
            var sends = new java.util.concurrent.atomic.AtomicInteger();
            var fake = FakeProvider.create().reply("duplicated generation");
            var transport = fake.provider().transport().orElseThrow();
            var provider = fake.provider().toBuilder().transport((request, options) -> {
                if (sends.incrementAndGet() == 1) return Fixtures.error(status, "server_error", "upstream failed");
                return transport.send(request, options);
            }).build();
            try (var llm = Fixtures.runtime(provider)) {
                var error = assertThrows(ProviderException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
                assertTrue(error.outcomeUnknown(), "HTTP " + status + " does not prove the request was not processed");
                assertFalse(error.retryable());
                assertEquals(1, sends.get());
            }
        }
    }

    @Test
    void totalDeadlineInterruptsCredentialResolution() {
        var fake = FakeProvider.create().reply("never");
        var auth = ApiKeyAuth.dynamic("slow identity", () -> {
            try {
                Thread.sleep(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("identity request interrupted");
            }
            return new TokenSupplier.AccessToken(Secret.of("test-token"), Optional.empty());
        });
        var provider = fake.provider().toBuilder().auth(auth).build();
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            long start = System.nanoTime();
            var error = assertThrows(RequestTimeoutException.class, () -> llm.complete(llm.model("fake", "fake"),
                    Conversation.of("hi"), ChatOptions.builder().timeouts(t -> t.total(Duration.ofMillis(100))).build()));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0,
                    "the deadline must interrupt the supplier, not wait for it to return");
            assertFalse(Thread.currentThread().isInterrupted(), "the SDK's timeout interrupt must not escape");
            assertFalse(error.outcomeUnknown());
            assertTrue(fake.requests().isEmpty());
            assertEquals(1, events.events(RequestEvent.Finished.class).size());
        }
    }

    @Test
    void cancelledBeforeStartIsNeverSent() {
        var fake = FakeProvider.create().reply("never");
        var token = CancelToken.create();
        token.cancel();
        try (var llm = Fixtures.runtime(fake.provider())) {
            var error = assertThrows(RequestCancelledException.class,
                    () -> llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().cancel(token).build()));
            assertFalse(error.outcomeUnknown(), "nothing left the runtime");
            assertTrue(fake.requests().isEmpty());
        }
    }

    @Test
    void aCancelledTokenIsNotServedFromTheCache() {
        var fake = FakeProvider.create().reply("cached");
        try (var llm = Llm.builder().provider(fake.provider()).environment(net.ai.gate.auth.Environment.none()).catalog(c -> c.offline())
                .responseCache(ResponseCache.inMemory(10)).build()) {
            var model = llm.model("fake", "fake");
            llm.complete(model, "q");
            var token = CancelToken.create();
            token.cancel();
            assertThrows(RequestCancelledException.class, () -> llm.complete(model, Conversation.of("q"), ChatOptions.builder().cancel(token).build()));
        }
    }

    @Test
    void cancellationWhileTheBodyIsReadAbortsTheCallAndFinishesOnce() throws Exception {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(200, "{}"))).build();
        var events = new RecordingListener();
        var token = CancelToken.create();
        var thrown = new AtomicReference<Throwable>();
        var started = new CountDownLatch(1);
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            var thread = Thread.ofVirtual().start(() -> {
                started.countDown();
                try {
                    llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().cancel(token).build());
                    fail("the stalled call completed");
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            token.cancel();
            thread.join(Duration.ofSeconds(5));
            assertFalse(thread.isAlive(), "the reader was unblocked by the cancellation");
            var error = assertInstanceOf(RequestCancelledException.class, thrown.get());
            assertTrue(error.outcomeUnknown(), "the request had left the runtime");
            var finished = events.events(RequestEvent.Finished.class);
            assertEquals(1, finished.size());
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, finished.getFirst().outcome());
        }
    }

    @Test
    void theTotalDeadlineCoversAStalledErrorBody() {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(500, "{}"))).build();
        try (var llm = Fixtures.runtime(provider)) {
            var options = ChatOptions.builder().timeouts(t -> t.total(Duration.ofMillis(300))).build();
            var error = assertThrows(RequestTimeoutException.class, () -> llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), options));
            assertTrue(error.outcomeUnknown());
        }
    }

    @Test
    void theTotalDeadlineCoversAStalledReplyBody() {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(200, "{}"))).build();
        try (var llm = Fixtures.runtime(provider)) {
            var options = ChatOptions.builder().timeouts(t -> t.total(Duration.ofMillis(300))).build();
            assertThrows(RequestTimeoutException.class, () -> llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), options));
        }
    }

    @Test
    void anExternalInterruptCancelsTheCallAndStaysPending() throws Exception {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(200, "{}"))).build();
        var result = new AtomicReference<Object>();
        try (var llm = Fixtures.runtime(provider)) {
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    llm.complete(llm.model("fake", "fake"), "hi");
                } catch (LlmException e) {
                    result.set(new Object[] {e, Thread.currentThread().isInterrupted()});
                }
            });
            Thread.sleep(100);
            thread.interrupt();
            thread.join(Duration.ofSeconds(5));
            var pair = assertInstanceOf(Object[].class, result.get());
            var error = assertInstanceOf(RequestCancelledException.class, pair[0]);
            assertInstanceOf(InterruptedIOException.class, error.getCause());
            assertEquals(Boolean.TRUE, pair[1], "an interrupt from outside stays pending for the caller");
        }
    }

    @Test
    void cancellingTheAsyncFutureCancelsTheCall() throws Exception {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(200, "{}"))).build();
        var events = new RecordingListener();
        try (var llm = Fixtures.runtime(provider)) {
            llm.addListener(events);
            var future = llm.completeAsync(llm.model("fake", "fake"), Conversation.of("hi"));
            Thread.sleep(100);
            assertTrue(future.cancel(true));
            for (int i = 0; i < 100 && events.events(RequestEvent.Finished.class).isEmpty(); i++) Thread.sleep(20);
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, events.events(RequestEvent.Finished.class).getFirst().outcome());
        }
    }

    @Test
    void closingTheRuntimeCancelsInFlightCallsWithinTheBudget() throws Exception {
        var stalled = new Fixtures.Stalled();
        var provider = FakeProvider.create().provider().toBuilder().transport(Fixtures.transport(_ -> stalled.reply(200, "{}"))).build();
        var llm = Fixtures.runtime(provider);
        var thrown = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try { llm.complete(llm.model("fake", "fake"), "hi"); } catch (Throwable e) { thrown.set(e); }
        });
        Thread.sleep(100);
        long t = System.nanoTime();
        llm.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - t).compareTo(Duration.ofSeconds(6)) < 0, "close is bounded");
        thread.join(Duration.ofSeconds(5));
        assertInstanceOf(RequestCancelledException.class, thrown.get());
    }
}
