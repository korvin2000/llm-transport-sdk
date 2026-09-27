package net.ai.gate.lifecycle;

/// Handle of a registered listener or callback; `close()` removes it and is idempotent.
@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override void close();
}
