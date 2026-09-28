package net.ai.gate.internal.core;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.stream.ChatStream;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.TransportException;
import net.ai.gate.event.RequestEvent;
import net.ai.gate.internal.cache.Exchange;
import net.ai.gate.internal.http.FrameReader;
import net.ai.gate.json.JsonNull;
import net.ai.gate.lifecycle.Registration;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.Frame;
import net.ai.gate.spi.protocol.StreamDecoder;
import org.jspecify.annotations.Nullable;

/// A live reply pulled on the consuming thread: frame → decoder → accumulator → consumer. The socket read is the
/// back-pressure; nothing is buffered beyond the current frame's events. One lock owns the stream state: the
/// consumer steps under it, and `close()` from another thread first cancels the call (which aborts a blocked read)
/// and then ends the stream under the same lock — never two threads draining. Completion needs the protocol's
/// terminal evidence: a premature end of stream is a failure, thrown once with the partial reply — priced by the
/// usage observed so far. Listeners get a content-free `Progress` event at most every `Progress.INTERVAL`.
final class DefaultChatStream implements ChatStream {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");
    /// Recording for the response cache stops beyond this many characters of frame data; the stream continues.
    static final long MAX_RECORDED_CHARS = 64L << 20;

    private final Engine engine;
    private final Call call;
    private final Engine.Prepared prepared;
    private final @Nullable HttpReply reply;
    private final Iterator<Frame> frames;
    private final StreamDecoder decoder;
    private final Accumulator accumulator;
    private final boolean fromCache;
    private final Engine.@Nullable CachePlan cache;
    private final Watchdog watchdog;
    private final Registration cancelHook;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<ChatEvent> pending = new ArrayDeque<>();
    private @Nullable List<Frame> recorded;
    private long recordedChars, lastProgress = System.nanoTime();
    private boolean viewTaken, iteratorTaken, failureThrown;
    private volatile boolean ended;
    private volatile @Nullable AssistantMessage result;
    private @Nullable RuntimeException failure;

    DefaultChatStream(Engine engine, Call call, Engine.Prepared prepared, @Nullable HttpReply reply, Iterator<Frame> frames,
                      @Nullable FrameReader activity, boolean fromCache, Engine.@Nullable CachePlan cache) {
        this.engine = engine; this.call = call; this.prepared = prepared; this.reply = reply; this.frames = frames;
        this.fromCache = fromCache; this.cache = cache;
        this.decoder = prepared.api().streamDecoder(prepared.context());
        this.accumulator = new Accumulator(prepared.model().ref(), prepared.api().id());
        this.recorded = cache != null ? new ArrayList<>() : null;
        this.watchdog = reply == null || activity == null ? new Watchdog(() -> { }, null, null, System::nanoTime)
                : new Watchdog(reply::close, call.deadline(), call.timeouts.streamIdle(), activity::lastActivity);
        this.cancelHook = call.token.onCancel(this::abort);
    }

    @Override public Iterator<ChatEvent> iterator() {
        takeView();
        takeIterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return advance(); }
            @Override public ChatEvent next() {
                if (!advance()) throw new NoSuchElementException();
                return pending.removeFirst();
            }
        };
    }

    @Override public Iterable<String> textDeltas() {
        takeView();
        return () -> {
            takeIterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    while (advance()) {
                        if (pending.getFirst() instanceof ChatEvent.TextDelta) return true;
                        pending.removeFirst();
                    }
                    return false;
                }
                @Override public String next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return ((ChatEvent.TextDelta) pending.removeFirst()).text();
                }
            };
        };
    }

    @Override public Stream<ChatEvent> events() {
        var iterator = iterator();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(this::close);
    }

    @Override public AssistantMessage partial() {
        var done = result;
        return done != null ? done : accumulator.snapshot();
    }

    @Override public AssistantMessage result() {
        lock.lock();
        try {
            while (!ended) {
                if (!advance()) break;
                pending.clear();
            }
            if (failure != null) throw failure;
            var done = result;
            if (done == null) throw new IllegalStateException("The stream ended without a result");
            return done;
        } finally {
            lock.unlock();
        }
    }

    /// Cancels an unfinished call and ends the stream: on the consuming thread at once, from another thread after
    /// the blocked read has been aborted. Emits `Finished(CANCELLED)` exactly once; never throws.
    @Override public void close() {
        if (ended) return;
        call.token.cancel();
        lock.lock();
        try {
            if (ended) return;
            try {
                call.checkActive();
            } catch (RuntimeException cancelled) {
                try { fail(cancelled); } catch (RuntimeException ignored) { /* reported through Finished(CANCELLED) */ }
            }
        } finally {
            lock.unlock();
        }
    }

    private void takeView() {
        lock.lock();
        try {
            if (viewTaken) throw new IllegalStateException("A ChatStream is consumed once, through one view");
            viewTaken = true;
        } finally {
            lock.unlock();
        }
    }

    private void takeIterator() {
        lock.lock();
        try {
            if (iteratorTaken) throw new IllegalStateException("A ChatStream view is iterated once");
            iteratorTaken = true;
        } finally {
            lock.unlock();
        }
    }

    /// True while an event is pending; pulls frames until one is, or the stream ends. A failure is thrown once —
    /// here, when another thread ended the stream, or from [#step] on the consuming thread.
    private boolean advance() {
        lock.lock();
        try {
            while (pending.isEmpty() && !ended) step();
            if (pending.isEmpty() && failure != null && !failureThrown) {
                failureThrown = true;
                throw failure;
            }
            return !pending.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private void step() {
        try {
            call.checkActive();
            boolean hasNext = frames.hasNext();
            call.watchdogFired(watchdog.fired());
            call.checkActive();   // an aborted body may report EOF instead of throwing
            if (hasNext) {
                var frame = frames.next();
                record(frame);
                deliver(decoder.onFrame(frame));
                progress();
            } else {
                deliver(decoder.onEnd());
                if (!ended) throw new TransportException(LlmException.Details.builder(ErrorCode.STREAM_INTERRUPTED,
                        "The stream ended without its terminal event").outcomeUnknown(true).build());
            }
        } catch (RuntimeException e) {
            fail(e instanceof LlmException || e instanceof UncheckedIOException ? e
                    : new InvalidResponseException(LlmException.Details.builder(ErrorCode.MALFORMED_RESPONSE,
                            prepared.api().id() + " stream: " + e.getMessage()).build(), e));
        }
    }

    private void record(Frame frame) {
        var frames = recorded;
        if (frames == null) return;
        recordedChars += frame.data().length();
        if (recordedChars > MAX_RECORDED_CHARS) {
            recorded = null;
            LOG.log(System.Logger.Level.WARNING, "Stream " + call.requestId + " exceeds the recording limit; the reply is not cached");
            return;
        }
        frames.add(frame);
    }

    private void deliver(List<ChatEvent> events) {
        for (var event : events) {
            if (ended) return;
            var delivered = accumulator.accept(event);
            if (!(delivered instanceof ChatEvent.Started)) call.firstOutput();
            if (delivered instanceof ChatEvent.Done done) {
                var finished = engine.finish(call, prepared, done.message(), fromCache);
                pending.add(ChatEvent.Done.of(finished));
                complete(finished);
            } else {
                pending.add(delivered);
            }
        }
    }

    private void progress() {
        long now = System.nanoTime();
        if (ended || now - lastProgress < RequestEvent.Progress.INTERVAL.toNanos()) return;
        lastProgress = now;
        call.progress(accumulator.outputTokens(), accumulator.outputChars());
    }

    private void complete(AssistantMessage message) {
        result = message;
        end();
        var frames = recorded;
        if (frames != null) Engine.store(cache, () -> new Exchange(200, null, frames, prepared.call().body().orElse(JsonNull.INSTANCE)));
    }

    /// Records the failure, ends the stream and throws the failure with call facts; the consumer sees it once.
    private void fail(RuntimeException error) {
        call.watchdogFired(watchdog.fired());
        end();
        var partial = accumulator.snapshot();
        var thrown = call.fail(error, partial.toBuilder().usage(Engine.priced(prepared, partial.usage(), !fromCache)).build());
        failure = thrown;
        failureThrown = true;
        pending.clear();
        throw thrown;
    }

    private void end() {
        ended = true;
        watchdog.close();
        cancelHook.close();
        if (reply != null) reply.close();
    }

    /// From any thread: closing the body unblocks the reader, which then fails as cancelled.
    private void abort() {
        if (reply != null) reply.close();
    }
}
