package net.ai.gate.internal.auth.store;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;

/// `CredentialStore.inMemory()`: updates are serialized per key, so waiters of an in-flight OAuth refresh see its
/// result instead of refreshing again.
public final class MemoryStore implements CredentialStore {
    private final ConcurrentHashMap<String, Credential> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override public Optional<Credential> read(String key) { return Optional.ofNullable(values.get(key)); }

    @Override public List<Entry> list() {
        return values.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue().type()))
                .sorted(Comparator.comparing(Entry::key)).toList();
    }

    @Override public Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change) {
        synchronized (locks.computeIfAbsent(key, _ -> new Object())) {
            var result = change.apply(read(key));
            result.ifPresentOrElse(v -> values.put(key, v), () -> values.remove(key));
            return result;
        }
    }
}
