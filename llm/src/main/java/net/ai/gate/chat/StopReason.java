package net.ai.gate.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.ai.gate.internal.validation.Checks;

/// Immutable open value type: why a reply ended. APIs may report reasons without a constant; the raw value is kept.
public final class StopReason {
    private static final Map<String, StopReason> KNOWN = new ConcurrentHashMap<>();

    public static final StopReason STOP = known("stop"), LENGTH = known("length"), TOOL_USE = known("tool_use"),
            CONTENT_FILTER = known("content_filter"), REFUSAL = known("refusal"), ABORTED = known("aborted"),
            ERROR = known("error"), OTHER = known("other"),
            // the reply is a compaction summary (Llm.compact), not an answer
            COMPACTION = known("compaction");

    private final String raw;

    private StopReason(String raw) { this.raw = raw; }

    private static StopReason known(String raw) {
        var reason = new StopReason(raw);
        KNOWN.put(raw, reason);
        return reason;
    }

    public static StopReason of(String raw) {
        var known = KNOWN.get(raw);
        return known != null ? known : new StopReason(Checks.notBlank(raw, "Stop reason"));
    }

    public String raw() { return raw; }

    @Override public boolean equals(Object o) { return o instanceof StopReason r && raw.equals(r.raw); }
    @Override public int hashCode() { return raw.hashCode(); }
    @Override public String toString() { return raw; }
}
