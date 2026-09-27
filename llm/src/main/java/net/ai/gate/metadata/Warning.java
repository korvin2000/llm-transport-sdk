package net.ai.gate.metadata;

/// Immutable non-fatal note. On replies, events and previews it reports an adaptation (`option_not_applicable`,
/// `option_dropped`, `option_adapted`, `reasoning_clamped`, `reasoning_converted`, `reasoning_dropped`,
/// `tool_call_id_normalized`, `image_omitted`, `history_adapted`, `max_tokens_clamped`, `max_tokens_defaulted`,
/// `unlisted_model`, `untested_api_version`, `cache_hint_ignored`). As a preview note it reports a derived value
/// (`max_tokens_from_catalog`, `reasoning_mapped`, `cache_markers`, `prompt_cache_default`). A reply's warnings are
/// also logged at `INFO` on the `net.ai.gate` logger.
public record Warning(String code, String message) {
    @Override public String toString() { return code + ": " + message; }
}
