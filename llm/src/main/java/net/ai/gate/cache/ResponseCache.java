package net.ai.gate.cache;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import net.ai.gate.internal.cache.DirectoryCache;
import net.ai.gate.internal.cache.MemoryCache;

/// **SPI** (host). Exact-match cache of complete, successful exchanges; opt-in. Thread-safe. Keys are SHA-256
/// hashes over provider, credential scope, API revision and the canonical request; entries use the versioned
/// exchange format (`ai-gate.exchange/1`), which doubles as the test-cassette format. Failures of a cache are logged
/// and the call proceeds uncached.
public interface ResponseCache {
    Optional<byte[]> get(String key);

    void put(String key, byte[] entry);

    default void remove(String key) { }

    default void clear() { }

    /// LRU, in memory.
    static ResponseCache inMemory(int maxEntries) { return new MemoryCache(maxEntries, null); }

    static ResponseCache inMemory(int maxEntries, Duration ttl) { return new MemoryCache(maxEntries, ttl); }

    /// One readable JSON file per entry: test cassettes and development.
    static ResponseCache directory(Path directory) { return new DirectoryCache(directory); }
}
