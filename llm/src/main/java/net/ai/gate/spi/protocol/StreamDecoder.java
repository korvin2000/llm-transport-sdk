package net.ai.gate.spi.protocol;

import java.util.List;

import net.ai.gate.chat.stream.ChatEvent;

/// **SPI**. Turns frames of one stream into events. Confined to the consuming thread; frames arrive in order and
/// events are delivered in emission order. The core accumulates the events; decoders keep only protocol state.
public interface StreamDecoder {
    /// None for keep-alives, several for composite frames.
    List<ChatEvent> onFrame(Frame frame);

    /// The server closed the stream: emit `Done` exactly once — with stop reason, usage and ids — or throw when the
    /// protocol's terminal evidence is missing.
    List<ChatEvent> onEnd();
}
