package net.ai.gate.spi.provider;

import java.util.function.Function;

import net.ai.gate.Provider;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonValue;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;

/// Core execution for provider APIs: every exchange gets the provider's credentials, deadline, cancellation, retry
/// classification, redaction and events, and its reply is read inside that lifetime. Only URIs relative to the
/// provider's base URL are accepted.
public interface ProviderApiContext {
    Provider provider();

    JsonMapper jsonMapper();

    /// Sends one call and reads its reply with `reader` while the call is still guarded by its deadline and cancel
    /// token; the reply is closed afterwards. Non-2xx replies throw the mapped `LlmException` before `reader` runs.
    <T> T exchange(HttpCall call, Replay replay, Function<HttpReply, T> reader);

    /// The JSON body of a successful reply.
    default JsonValue exchange(HttpCall call, Replay replay) { return exchange(call, replay, HttpReply::json); }

    /// `SAFE` permits retries (reads, idempotent writes).
    enum Replay { SAFE, UNSAFE }
}
