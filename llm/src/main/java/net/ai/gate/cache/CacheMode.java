package net.ai.gate.cache;

/// How a call uses the client-side [ResponseCache], when one is configured.
public enum CacheMode {
    /// Serve hits; store complete successful replies (default when a cache is configured).
    READ_WRITE,
    /// Skip reads, store results — "force re-run".
    REFRESH,
    /// Neither read nor write.
    BYPASS,
    /// Read only and never touch the network; a miss fails with `cache_miss` naming the request.
    OFFLINE
}
