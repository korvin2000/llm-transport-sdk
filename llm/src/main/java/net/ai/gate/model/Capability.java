package net.ai.gate.model;

/// A feature a model may support; see [Capabilities]. `TEMPERATURE` covers the sampling parameters (temperature,
/// top-p, top-k): `UNSUPPORTED` models reject them, so they are left out with a warning.
public enum Capability {
    STREAMING, TOOLS, PARALLEL_TOOLS, STRUCTURED_OUTPUT, JSON_MODE, VISION, DOCUMENTS,
    AUDIO_INPUT, AUDIO_OUTPUT, IMAGE_OUTPUT, REASONING, PROMPT_CACHING, EMBEDDINGS, TEMPERATURE
}
