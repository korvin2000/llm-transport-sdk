package net.ai.gate.auth;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.ai.gate.internal.auth.store.FileStore;
import net.ai.gate.internal.auth.store.MemoryStore;
import net.ai.gate.internal.auth.store.ScopedStore;

/// **SPI** (host). One credential per key (a provider id within a scope). [#update] is the only write path: atomic
/// per key, and across processes where the store supports it. OAuth refresh runs inside `update`, so concurrent
/// calls and processes cannot double-refresh, and a rotated refresh token is persisted in the same step.
/// Thread-safe; failures become `AuthenticationException(credential_store)`.
public interface CredentialStore {
    Optional<Credential> read(String key);

    /// Keys and types; never secrets.
    List<Entry> list();

    /// Applies `change` atomically to the current value; an empty result deletes. Returns the stored value.
    Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change);

    default void delete(String key) { update(key, _ -> Optional.empty()); }

    /// A view over keys starting with `prefix` + `/`: one per user or tenant.
    default CredentialStore scoped(String prefix) { return new ScopedStore(this, prefix); }

    static CredentialStore inMemory() { return new MemoryStore(); }

    /// JSON file with owner-only permissions where supported, a lock file and atomic replacement. Not encrypted.
    static CredentialStore file(Path path) { return new FileStore(path); }

    record Entry(String key, AuthType type) { }
}
