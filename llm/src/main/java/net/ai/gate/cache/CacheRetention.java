package net.ai.gate.cache;

/// Provider-side prompt caching. `SHORT` is the default at every scope.
public enum CacheRetention {
    /// No cache markers, keys or retention hints are sent.
    NONE,
    /// The provider's default cache lifetime (for example five minutes).
    SHORT,
    /// Extended retention where the model supports it (for example one hour), else `SHORT` with a warning.
    LONG
}
