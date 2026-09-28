package net.ai.gate.chat.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import net.ai.gate.cache.CacheMode;
import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.json.JsonString;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.spi.protocol.ProviderOptions;
import org.junit.jupiter.api.Test;

/// The canonical JSON form of the portable members of [ChatOptions] (`ai-gate.options/1`).
class ChatOptionsJsonTest {
    private static final String SCHEMA = "ai-gate.options/1";

    @Test
    void portableFieldsRoundTripIncludingPartialPoliciesAndExplicitEmptyStop() {
        var options = ChatOptions.builder().temperature(0.7).topP(0.9).topK(40).maxTokens(1024)
                .stops(List.of("STOP", "END")).seed(42).reasoning(ReasoningLevel.HIGH).reasoningHandoff(ReasoningHandoff.DROP)
                .toolChoice(ToolChoice.only("search")).parallelToolCalls(false).strict(true)
                .output(OutputFormat.jsonSchema(JsonSchema.of(Json.object("type", "object", "properties", Json.object()))))
                .cacheRetention(CacheRetention.LONG).sessionId("sess-1").responseCache(CacheMode.REFRESH)
                .timeouts(t -> t.connect(Duration.ofSeconds(3))) // partial: streamIdle/total stay unset
                .retry(r -> r.maxAttempts(5)) // partial: backoff fields stay unset
                .header("X-Trace", "abc").tag("env", "prod").build();

        var json = options.toJson();
        assertEquals(SCHEMA, json.string("schema"));
        assertEquals(Json.object("connect", "PT3S"), json.get("timeouts").orElseThrow(), "only the set timeout field is written");
        assertEquals(Json.object("maxAttempts", 5), json.get("retry").orElseThrow(), "only the set retry field is written");

        var restored = ChatOptions.fromJson(json);
        assertEquals(json, restored.toJson(), "reading and re-writing reproduces the same canonical form");

        // ChatOptions has no equals(); check the accessors that the JSON comparison alone would not pin down.
        assertEquals(List.of("STOP", "END"), restored.stops().orElseThrow());
        assertEquals(Boolean.TRUE, restored.strictSetting().orElseThrow());
        assertEquals(Duration.ofSeconds(3), restored.timeouts().orElseThrow().connect());
        assertEquals(Duration.ofMinutes(5), restored.timeouts().orElseThrow().streamIdle(), "unset field inherits the documented default");
        assertEquals(5, restored.retry().orElseThrow().maxAttempts());
    }

    @Test
    void noTotalTimeoutRoundTrips() {
        var options = ChatOptions.builder().timeouts(TimeoutPolicy.builder().noTotalTimeout().build()).build();
        assertEquals(Json.object("total", "none"), options.toJson().get("timeouts").orElseThrow());
        var restored = ChatOptions.fromJson(options.toJson());
        assertTrue(restored.timeouts().orElseThrow().total().isEmpty());
    }

    @Test
    void explicitEmptyStopIsDistinctFromUnset() {
        var cleared = ChatOptions.builder().stops(List.of()).build();
        assertEquals(List.of(), cleared.stops().orElseThrow());
        assertEquals(Json.array(), cleared.toJson().get("stop").orElseThrow());
        assertEquals(List.of(), ChatOptions.fromJson(cleared.toJson()).stops().orElseThrow());

        var unset = ChatOptions.builder().build();
        assertTrue(unset.stops().isEmpty());
        assertTrue(unset.toJson().get("stop").isEmpty());
    }

    @Test
    void unrepresentableFieldsThrowNamingTheField() {
        record Point(int x, int y) { }
        var typedOutput = ChatOptions.builder().output(Point.class).build();
        var outputError = assertThrows(IllegalArgumentException.class, typedOutput::toJson);
        assertTrue(outputError.getMessage().startsWith("output:"), outputError.getMessage());

        record CustomProviderOptions(String api) implements ProviderOptions { }
        var withProviderOptions = ChatOptions.builder().provider(new CustomProviderOptions("acme")).build();
        var providerError = assertThrows(IllegalArgumentException.class, withProviderOptions::toJson);
        assertTrue(providerError.getMessage().startsWith("providerOptions:"), providerError.getMessage());
    }

    @Test
    void unknownMemberIsRejectedButXPrefixedIsAccepted() {
        var unknown = (JsonObject) Json.parse("{\"schema\":\"" + SCHEMA + "\",\"bogus\":true}");
        var error = assertThrows(IllegalArgumentException.class, () -> ChatOptions.fromJson(unknown));
        assertTrue(error.getMessage().startsWith("bogus:"), error.getMessage());

        var extension = (JsonObject) Json.parse("{\"schema\":\"" + SCHEMA + "\",\"x-color\":\"blue\"}");
        var options = ChatOptions.fromJson(extension);
        assertEquals(Json.object("schema", SCHEMA), options.toJson(), "the x- field is accepted but has no slot to round-trip through");
    }

    @Test
    void strictCodesAndHistoryPolicyUseVersionTwoOnlyWhenSet() {
        var options = ChatOptions.builder().strictCodes(Set.of("option_adapted", "cache_hint_ignored")).historyPolicy(HistoryPolicy.REJECT_LOSSY).build();
        var json = options.toJson();
        assertEquals("ai-gate.options/2", json.string("schema"));
        assertEquals(List.of("cache_hint_ignored", "option_adapted"), json.array("strictCodes").stream().map(v -> ((JsonString) v).value()).toList());
        var read = ChatOptions.fromJson(json);
        assertEquals(options.strictCodes(), read.strictCodes());
        assertEquals(HistoryPolicy.REJECT_LOSSY, read.historyPolicy().orElseThrow());
        assertEquals(SCHEMA, ChatOptions.builder().maxTokens(5).build().toJson().string("schema"), "older readers keep reading plain options");
        assertEquals(Set.of(), ChatOptions.builder().strictCodes(Set.of("x")).build().overriddenBy(ChatOptions.builder().strictCodes(Set.of()).build()).strictCodes(),
                "a narrower scope replaces the set");
    }
}
