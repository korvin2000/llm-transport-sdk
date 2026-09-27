package net.ai.gate.model;

import java.util.Collection;

/// Portable reasoning effort, ordered from `OFF` to `MAX`; each call maps it to a level the target model supports.
public enum ReasoningLevel {
    OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /// This level if supported, else the nearest supported one; ties resolve upward to keep the caller's quality
    /// intent (`XHIGH` on `LOW, MEDIUM, HIGH` → `HIGH`; `MINIMAL` on `LOW, HIGH` → `LOW`). Empty `supported`: this.
    public ReasoningLevel nearest(Collection<ReasoningLevel> supported) {
        if (supported.isEmpty() || supported.contains(this)) return this;
        ReasoningLevel best = this;
        int bestDistance = Integer.MAX_VALUE;
        for (var level : supported) {
            int distance = Math.abs(level.ordinal() - ordinal());
            if (distance < bestDistance || distance == bestDistance && level.ordinal() > best.ordinal()) {
                best = level;
                bestDistance = distance;
            }
        }
        return best;
    }
}
