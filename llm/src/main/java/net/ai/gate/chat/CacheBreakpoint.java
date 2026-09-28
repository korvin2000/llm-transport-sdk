package net.ai.gate.chat;

import net.ai.gate.cache.CacheRetention;
import org.jspecify.annotations.Nullable;

/// An explicit prompt-cache prefix end: after `index` messages (`0` = after system and tools), with its own
/// retention, or the call's `cacheRetention` when `null`. APIs without cache markers ignore per-marker retention
/// (`cache_hint_ignored`).
public record CacheBreakpoint(int index, @Nullable CacheRetention retention) {
    public CacheBreakpoint {
        if (index < 0) throw new IllegalArgumentException("Cache breakpoint index must not be negative: " + index);
        if (retention == CacheRetention.NONE) throw new IllegalArgumentException("A cache breakpoint's retention is SHORT or LONG; omit the breakpoint instead");
    }
}
