package net.ai.gate.spi.catalog;

import java.io.IOException;
import java.util.List;

import net.ai.gate.model.Model;

/// **SPI**. Live model listing of a provider (vendor `/models`, OpenRouter, Ollama, gateways), fetched through the
/// core's authenticated, deadline-bound client. Runs on the refresh thread; failures keep the previous data.
@FunctionalInterface
public interface ModelSource {
    List<Model> fetch(ProviderHttp http) throws IOException;
}
