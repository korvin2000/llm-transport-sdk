package net.ai.gate.internal.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import net.ai.gate.json.Json;
import net.ai.gate.model.Capabilities;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.Prices;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.model.SupportLevel;
import org.junit.jupiter.api.Test;

class ModelJsonTest {
    @Test
    void roundTripsThroughCanonicalJson() {
        var model = Model.builder("anthropic", "claude-x").name("Claude X").api("anthropic-messages")
                .input(Modality.TEXT, Modality.IMAGE).output(Modality.TEXT)
                .contextWindow(200_000).maxOutputTokens(8_192)
                .reasoningLevels(ReasoningLevel.LOW, ReasoningLevel.HIGH)
                .capabilities(Capabilities.of(Capability.TOOLS, Capability.VISION).with(Capability.AUDIO_INPUT, SupportLevel.UNSUPPORTED))
                .prices(Prices.usd().input("3").output("15").cacheRead("0.3")
                        .tier(200_000, Prices.usd().input("6").output("30").build()).build())
                .deprecatedAt(Instant.parse("2027-01-01T00:00:00Z")).updatedAt(Instant.parse("2026-09-27T00:00:00Z"))
                .source(Model.Source.CATALOG).build();
        assertEquals(model, Model.fromJson(model.toJson()));
    }

    @Test
    void idMustBeAString() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(Json.object("provider", "anthropic")));
        assertEquals("id: absent or not a string", error.getMessage());
        var error2 = assertThrows(IllegalArgumentException.class,
                () -> Model.fromJson(Json.object("provider", "anthropic", "id", 7)));
        assertEquals("id: absent or not a string", error2.getMessage());
    }

    @Test
    void contextWindowMustBeAnInteger() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "contextWindow", new BigDecimal("1.5"))));
        assertEquals("contextWindow: 1.5 is not an integer", error.getMessage());
    }

    @Test
    void modelLimitsMustBePositive() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m", "contextWindow", -5)));
        assertTrue(error.getMessage().startsWith("contextWindow:"), error.getMessage());
        var error2 = assertThrows(IllegalArgumentException.class,
                () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m", "maxOutputTokens", 0)));
        assertTrue(error2.getMessage().startsWith("maxOutputTokens:"), error2.getMessage());
    }

    @Test
    void inputMustBeAnArrayOfStrings() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m", "input", 123)));
        assertEquals("input: expected an array of strings", error.getMessage());
    }

    @Test
    void modalitiesMustBeKnown() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "input", Json.array("telepathy"))));
        assertTrue(error.getMessage().contains("input") && error.getMessage().contains("telepathy"), error.getMessage());
    }

    @Test
    void reasoningLevelsMustBeKnown() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "reasoning", Json.array("superhard"))));
        assertTrue(error.getMessage().contains("reasoning") && error.getMessage().contains("superhard"), error.getMessage());
    }

    @Test
    void capabilityValueMustBeAString() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "capabilities", Json.object("vision", 1))));
        assertEquals("capabilities.vision: expected a string", error.getMessage());
    }

    @Test
    void capabilityNameMustBeKnown() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "capabilities", Json.object("telepathy", "supported"))));
        assertTrue(error.getMessage().contains("capabilities") && error.getMessage().contains("telepathy"), error.getMessage());
    }

    @Test
    void supportLevelMustBeKnown() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(
                Json.object("provider", "anthropic", "id", "m", "capabilities", Json.object("vision", "superduper"))));
        assertTrue(error.getMessage().contains("capabilities.vision") && error.getMessage().contains("superduper"), error.getMessage());
    }

    @Test
    void sourceMustBeKnown() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m", "source", "madeup")));
        assertTrue(error.getMessage().contains("source") && error.getMessage().contains("madeup"), error.getMessage());
    }

    @Test
    void priceTiersMustBeObjects() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m",
                "prices", Json.object("currency", "USD", "tiers", Json.array(123)))));
        assertEquals("prices.tiers[0]: expected an object", error.getMessage());
    }

    @Test
    void priceTierThresholdMustBeAnInteger() {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.fromJson(Json.object("provider", "anthropic", "id", "m",
                "prices", Json.object("currency", "USD", "tiers", Json.array(Json.object("above", new BigDecimal("1.5")))))));
        assertTrue(error.getMessage().contains("tiers[0]") && error.getMessage().contains("is not an integer"), error.getMessage());
    }
}
