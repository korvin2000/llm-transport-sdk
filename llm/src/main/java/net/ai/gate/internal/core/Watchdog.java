package net.ai.gate.internal.core;

import java.time.Duration;
import java.util.function.LongSupplier;

import net.ai.gate.error.ErrorCode;
import org.jspecify.annotations.Nullable;

/// Enforces the total deadline and the stream idle timeout on a body the JDK client cannot time out itself: a
/// virtual thread sleeps until the next limit and aborts the reply when one passes. The reading thread then fails,
/// and [#fired()] names the reason.
final class Watchdog implements AutoCloseable {
    private final @Nullable Thread thread;
    private volatile @Nullable ErrorCode fired;

    /// `deadline` in `System.nanoTime()` terms, or `null`; `idle` with `lastActivity`, or `null`.
    Watchdog(Runnable abort, @Nullable Long deadline, @Nullable Duration idle, LongSupplier lastActivity) {
        if (deadline == null && idle == null) {
            thread = null;
            return;
        }
        thread = Thread.ofVirtual().name("ai-gate-watchdog").start(() -> {
            try {
                while (true) {
                    long now = System.nanoTime();
                    if (deadline != null && now - deadline >= 0) { fire(ErrorCode.DEADLINE_EXCEEDED, abort); return; }
                    long idleLeft = idle == null ? Long.MAX_VALUE : idle.toNanos() - (now - lastActivity.getAsLong());
                    if (idleLeft <= 0) { fire(ErrorCode.STREAM_IDLE_TIMEOUT, abort); return; }
                    long wait = Math.min(idleLeft, deadline == null ? Long.MAX_VALUE : deadline - now);
                    Thread.sleep(Duration.ofNanos(Math.max(wait, 1_000_000)));
                }
            } catch (InterruptedException e) {
                // closed: the call ended first
            }
        });
    }

    private void fire(ErrorCode code, Runnable abort) {
        fired = code;
        abort.run();
    }

    @Nullable ErrorCode fired() { return fired; }

    @Override public void close() { if (thread != null) thread.interrupt(); }
}
