package net.ai.gate.spi.catalog;

import java.io.IOException;

import net.ai.gate.Provider;
import net.ai.gate.json.JsonValue;

/// Authenticated GET under a provider's base URL: credentials, deadlines, redaction and error mapping applied.
public interface ProviderHttp {
    Provider provider();

    JsonValue get(String relativePath) throws IOException;
}
