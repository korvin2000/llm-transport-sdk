package net.ai.gate.spi.provider;

import net.ai.gate.json.JsonValue;
import net.ai.gate.spi.http.HttpCall;
import org.jetbrains.annotations.ApiStatus;

/// Built-in escape hatch: relative GET/POST under any provider's base URL with its credentials.
@ApiStatus.Experimental
public interface RawApi {
    ProviderApi<RawApi> KEY = ProviderApi.of(ProviderApi.ANY_API, "raw", RawApi.class, context -> new RawApi() {
        @Override public JsonValue get(String path) { return context.exchange(HttpCall.get(path), ProviderApiContext.Replay.SAFE); }

        @Override public JsonValue post(String path, JsonValue body) { return context.exchange(HttpCall.post(path, body), ProviderApiContext.Replay.UNSAFE); }
    });

    JsonValue get(String path);

    JsonValue post(String path, JsonValue body);
}
