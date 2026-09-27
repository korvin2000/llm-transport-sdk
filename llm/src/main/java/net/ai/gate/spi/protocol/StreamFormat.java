package net.ai.gate.spi.protocol;

/// How a streamed body is framed. Binary event streams arrive with the package that needs them.
public enum StreamFormat { SSE, NDJSON }
