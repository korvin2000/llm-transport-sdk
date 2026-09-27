package net.ai.gate.internal.auth.store;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;

/// `store.scoped(prefix)`: one user's or tenant's keys inside a shared store.
public final class ScopedStore implements CredentialStore {
    private final CredentialStore delegate;
    private final String prefix;

    public ScopedStore(CredentialStore delegate, String prefix) {
        if (prefix.isBlank() || prefix.contains("/")) throw new IllegalArgumentException("Scope must be non-blank without '/': " + prefix);
        this.delegate = delegate;
        this.prefix = prefix + "/";
    }

    /// The full scope path, e.g. `tenant/alice/`.
    public String scope() { return (delegate instanceof ScopedStore s ? s.scope() : "") + prefix; }

    @Override public Optional<Credential> read(String key) { return delegate.read(prefix + key); }

    @Override public List<Entry> list() {
        return delegate.list().stream().filter(e -> e.key().startsWith(prefix))
                .map(e -> new Entry(e.key().substring(prefix.length()), e.type())).toList();
    }

    @Override public Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change) {
        return delegate.update(prefix + key, change);
    }

    /// The scope path of any store: empty for unscoped ones.
    public static String scopeOf(CredentialStore store) { return store instanceof ScopedStore s ? s.scope() : ""; }

    /// The store behind every scope: the one whose identity separates tenants of different stores.
    public static CredentialStore rootOf(CredentialStore store) { return store instanceof ScopedStore s ? rootOf(s.delegate) : store; }
}
