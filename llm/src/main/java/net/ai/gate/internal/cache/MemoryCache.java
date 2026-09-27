package net.ai.gate.internal.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.ai.gate.cache.ResponseCache;
import org.jspecify.annotations.Nullable;

/// `ResponseCache.inMemory(…)`: a size-bounded LRU with an optional time to live. Thread-safe.
public final class MemoryCache implements ResponseCache {
    private static final long NEVER = Long.MAX_VALUE;

    private record Item(byte[] bytes, long expiresAt) { }

    private final @Nullable Duration ttl;
    private final LinkedHashMap<String, Item> entries;

    public MemoryCache(int maxEntries, @Nullable Duration ttl) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        if (ttl != null && (ttl.isNegative() || ttl.isZero())) throw new IllegalArgumentException("ttl must be positive: " + ttl);
        this.ttl = ttl;
        this.entries = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) { return size() > maxEntries; }
        };
    }

    @Override public synchronized Optional<byte[]> get(String key) {
        var entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (entry.expiresAt != NEVER && System.nanoTime() - entry.expiresAt > 0) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.bytes.clone());
    }

    @Override public synchronized void put(String key, byte[] entry) {
        entries.put(key, new Item(entry.clone(), ttl == null ? NEVER : System.nanoTime() + ttl.toNanos()));
    }

    @Override public synchronized void remove(String key) { entries.remove(key); }

    @Override public synchronized void clear() { entries.clear(); }
}
