package net.ai.gate.metadata;

/// Immutable input-token count of a prepared request.
/// @param method [#ENDPOINT] (exact), [#TOKENIZER] or [#ESTIMATE]
/// @param marginTokens how far an inexact count may be off, by the counter's own declaration; `0` when exact
public record TokenCount(long inputTokens, boolean exact, String method, long marginTokens) {
    public static final String ENDPOINT = "provider-endpoint", TOKENIZER = "local-tokenizer", ESTIMATE = "estimate";
}
