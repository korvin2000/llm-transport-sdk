package net.ai.gate.spi.protocol;

import java.util.Set;

import net.ai.gate.cache.CacheRetention;
import org.jetbrains.annotations.ApiStatus;

/// Immutable facts about what one wire API — with the compat flags of a provider and model — does with a request:
/// the answers a host needs before it sends, which the catalog cannot hold. Built by each [WireApi]; unknown facts
/// take the conservative value.
/// @param outputCap whether `maxTokens` is enforced on the wire
/// @param outputCapMinimum the smallest output limit the API accepts (lower values are raised to it); `1` when none
/// @param maxCacheMarkers explicit prompt-cache markers honoured per request; `0` without explicit markers
/// @param retentions the `CacheRetention` values the API maps natively
/// @param reportedUsageFields usage buckets the API reports: `input`, `output`, `reasoning`, `cache_read`, `cache_write`,
///        `cache_write_5m`, `cache_write_1h`
/// @param usageMayBePartial usage can arrive before the final frame, so a cut stream may still report it
/// @param nativeReasoningReplay same-origin reasoning is replayed in its native, verifiable form
/// @param continuation the API can continue from server-side state instead of the full history
/// @param hostedTools the API runs provider-hosted tools (search, code execution)
@ApiStatus.Experimental
public record ApiFeatures(String api, OutputCap outputCap, int outputCapMinimum, PromptCache promptCache, int maxCacheMarkers,
                          Set<CacheRetention> retentions, boolean streamingRequired, boolean streamingSupported,
                          boolean parallelToolCallsControllable, Set<String> reportedUsageFields, boolean usageMayBePartial,
                          boolean nativeReasoningReplay, boolean continuation, boolean hostedTools) {
    public enum OutputCap { ENFORCED, UNSUPPORTED, UNKNOWN }

    /// How prompt caching is controlled.
    public enum PromptCache { NONE, AUTOMATIC, EXPLICIT_MARKERS, NAMED_RESOURCE }

    public ApiFeatures {
        retentions = Set.copyOf(retentions);
        reportedUsageFields = Set.copyOf(reportedUsageFields);
    }

    /// Nothing known beyond the id: no guarantees a host could rely on.
    public static ApiFeatures unknown(String api) {
        return new ApiFeatures(api, OutputCap.UNKNOWN, 1, PromptCache.NONE, 0, Set.of(), false, true, false, Set.of(), true, false, false, false);
    }
}
