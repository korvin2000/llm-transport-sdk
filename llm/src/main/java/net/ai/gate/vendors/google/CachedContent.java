package net.ai.gate.vendors.google;

import java.time.Instant;
import java.util.Optional;

import net.ai.gate.metadata.Usage;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// Immutable description of a Gemini cached content — a remote resource with its own lifetime.
public final class CachedContent {
    private final String name;
    private final ModelRef model;
    private final @Nullable Instant expiresAt;
    private final Usage usage;

    private CachedContent(String name, ModelRef model, @Nullable Instant expiresAt, Usage usage) {
        this.name = name; this.model = model; this.expiresAt = expiresAt; this.usage = usage;
    }

    public static CachedContent of(String name, ModelRef model, @Nullable Instant expiresAt, Usage usage) {
        return new CachedContent(name, model, expiresAt, usage);
    }

    /// `cachedContents/…`; pass it to `GeminiOptions.cachedContent(…)`.
    public String name() { return name; }
    public ModelRef model() { return model; }
    public Optional<Instant> expiresAt() { return Optional.ofNullable(expiresAt); }
    /// The cached token count.
    public Usage usage() { return usage; }

    @Override public String toString() { return "CachedContent[" + name + ", " + model + ", expires=" + expiresAt + "]"; }
}
