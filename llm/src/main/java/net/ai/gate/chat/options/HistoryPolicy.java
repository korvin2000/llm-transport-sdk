package net.ai.gate.chat.options;

/// How a call treats history the target (model, API) did not produce itself. Default `ALLOW_ADAPTATION`.
public enum HistoryPolicy {
    /// Every assistant turn must come from the target model through the target API.
    SAME_ORIGIN_REQUIRED,
    /// Foreign turns pass only when nothing is lost: no reasoning, part or media is omitted or converted (tool-call
    /// ids may still be rewritten, a lossless mapping).
    REJECT_LOSSY,
    /// Foreign turns are adapted, each conversion reported as a warning.
    ALLOW_ADAPTATION
}
