package net.ai.gate.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.ai.gate.error.LlmException;

/// Immutable per-source outcome of a catalog refresh; every failed source keeps its previous data.
public final class RefreshReport {
    private final Map<String, Optional<LlmException>> byProvider;
    private final List<LlmException> feedErrors;

    private RefreshReport(Map<String, Optional<LlmException>> byProvider, List<LlmException> feedErrors) {
        this.byProvider = Collections.unmodifiableMap(new LinkedHashMap<>(byProvider));
        this.feedErrors = List.copyOf(feedErrors);
    }

    public static RefreshReport of(Map<String, Optional<LlmException>> byProvider, List<LlmException> feedErrors) {
        return new RefreshReport(byProvider, feedErrors);
    }

    /// Live-listing outcome per provider that was refreshed: empty on success.
    public Map<String, Optional<LlmException>> byProvider() { return byProvider; }
    public List<LlmException> feedErrors() { return feedErrors; }
    public boolean ok() { return feedErrors.isEmpty() && byProvider.values().stream().allMatch(Optional::isEmpty); }

    @Override public String toString() { return "RefreshReport[providers=" + byProvider.keySet() + ", ok=" + ok() + "]"; }
}
