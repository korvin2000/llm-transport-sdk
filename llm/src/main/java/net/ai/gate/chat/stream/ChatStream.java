package net.ai.gate.chat.stream;

import java.util.Iterator;
import java.util.stream.Stream;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.LlmException;
import org.jetbrains.annotations.ApiStatus;

/// A live reply: single consumer, single iteration. `iterator()`, `textDeltas()` and `events()` are mutually
/// exclusive views of one sequence; a second view throws `IllegalStateException`. Bounded: the producer is
/// back-pressured by the blocking socket read. `close()` is thread-safe, idempotent, cancels an unfinished call and
/// releases the connection. Never retried after the first event; a failure is thrown once by the iterator, with
/// [LlmException#partial()].
@ApiStatus.NonExtendable
public interface ChatStream extends Iterable<ChatEvent>, AutoCloseable {
    /// Blocks per event on the calling thread.
    @Override Iterator<ChatEvent> iterator();

    /// Text-only view; other events are still aggregated.
    Iterable<String> textDeltas();

    /// A `java.util.stream` view; closing it closes this stream.
    Stream<ChatEvent> events();

    /// Immutable snapshot of what arrived so far; never blocks.
    AssistantMessage partial();

    /// The aggregate — equal to what `complete()` returns for the same exchange. Drains the rest first.
    AssistantMessage result();

    @Override void close();
}
