package net.ai.gate.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/// Immutable per-capability support levels of a model; capabilities never stated are `UNKNOWN`.
public final class Capabilities {
    private static final Capabilities UNKNOWN = new Capabilities(new EnumMap<>(Capability.class));

    private final Map<Capability, SupportLevel> levels;

    private Capabilities(EnumMap<Capability, SupportLevel> levels) { this.levels = Collections.unmodifiableMap(levels); }

    public static Capabilities unknown() { return UNKNOWN; }

    /// `supported` as `SUPPORTED`, everything else `UNKNOWN`.
    public static Capabilities of(Capability... supported) {
        var levels = new EnumMap<Capability, SupportLevel>(Capability.class);
        for (var c : supported) levels.put(c, SupportLevel.SUPPORTED);
        return new Capabilities(levels);
    }

    /// A copy with one level changed; `UNKNOWN` removes the statement.
    public Capabilities with(Capability capability, SupportLevel level) {
        var copy = levels.isEmpty() ? new EnumMap<Capability, SupportLevel>(Capability.class) : new EnumMap<>(levels);
        if (level == SupportLevel.UNKNOWN) copy.remove(capability); else copy.put(capability, level);
        return new Capabilities(copy);
    }

    /// A copy in which every level `newer` states wins; its `UNKNOWN` never erases a known level.
    public Capabilities overriddenBy(Capabilities newer) {
        if (newer.levels.isEmpty()) return this;
        var copy = levels.isEmpty() ? new EnumMap<Capability, SupportLevel>(Capability.class) : new EnumMap<>(levels);
        copy.putAll(newer.levels);
        return new Capabilities(copy);
    }

    public SupportLevel support(Capability capability) { return levels.getOrDefault(capability, SupportLevel.UNKNOWN); }

    public Set<Capability> supported() {
        var set = EnumSet.noneOf(Capability.class);
        levels.forEach((c, l) -> { if (l == SupportLevel.SUPPORTED) set.add(c); });
        return Collections.unmodifiableSet(set);
    }

    /// Every stated level; capabilities absent from the map are `UNKNOWN`.
    public Map<Capability, SupportLevel> levels() { return levels; }

    @Override public boolean equals(Object o) { return o instanceof Capabilities c && levels.equals(c.levels); }
    @Override public int hashCode() { return levels.hashCode(); }
    @Override public String toString() { return "Capabilities" + levels; }
}
