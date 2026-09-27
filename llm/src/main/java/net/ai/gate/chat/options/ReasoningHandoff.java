package net.ai.gate.chat.options;

/// What happens to readable reasoning from another model when a conversation continues on a new one.
/// Same-origin reasoning is always replayed natively; signatures never cross models.
public enum ReasoningHandoff {
    /// Passed on as delimited `<thinking>` text at the start of that assistant turn (default).
    KEEP,
    /// Omitted from the copy sent; the stored conversation keeps it.
    DROP
}
