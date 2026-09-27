package net.ai.gate.model;

/// A feature a model may support; see [Capabilities].
public enum Capability {
    STREAMING, TOOLS, PARALLEL_TOOLS, STRUCTURED_OUTPUT, JSON_MODE, VISION, DOCUMENTS,
    AUDIO_INPUT, AUDIO_OUTPUT, IMAGE_OUTPUT, REASONING, PROMPT_CACHING, EMBEDDINGS
}
