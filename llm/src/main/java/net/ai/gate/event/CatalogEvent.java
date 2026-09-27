package net.ai.gate.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/// Model catalog maintenance.
public sealed interface CatalogEvent extends LlmEvent {

    /// A refresh finished; the new snapshot is already published.
    final class Refreshed extends EventBase implements CatalogEvent {
        private final List<String> providers, failures;
        private final int modelsChanged;

        private Refreshed(List<String> providers, int modelsChanged, List<String> failures, Instant at) {
            super(at, Map.of());
            this.providers = List.copyOf(providers); this.modelsChanged = modelsChanged; this.failures = List.copyOf(failures);
        }

        public static Refreshed of(List<String> providers, int modelsChanged, List<String> failures, Instant at) {
            return new Refreshed(providers, modelsChanged, failures, at);
        }

        /// Providers whose live listing was attempted.
        public List<String> providers() { return providers; }
        public int modelsChanged() { return modelsChanged; }
        /// Sources that failed and kept their previous data: provider ids and feed ids.
        public List<String> failures() { return failures; }
        @Override public String toString() { return "CatalogEvent.Refreshed[changed=" + modelsChanged + ", failures=" + failures + "]"; }
    }
}
