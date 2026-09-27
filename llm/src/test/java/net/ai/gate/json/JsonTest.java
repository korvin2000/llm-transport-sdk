package net.ai.gate.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.ai.gate.internal.json.JsonReader;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.Prices;
import net.ai.gate.model.ReasoningLevel;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void writingKeepsMemberOrderAndLexicalNumbers() {
        var text = "{\"b\":1.50,\"a\":[true,null,\"line\\nbreak \\u00e9\"],\"n\":-2e10}";
        assertEquals(text.replace("\\u00e9", "é"), Json.parse(text).toJson());
        assertEquals("{\n  \"x\": [\n    1\n  ]\n}", Json.object("x", List.of(1)).toPrettyJson());
    }

    @Test
    void syntaxErrorsNameTheOffsetAndDepthIsBounded() {
        var error = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": [1, 2,]}"));
        assertTrue(error.getMessage().contains("offset 12"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(300) + "]".repeat(300)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} trailing"));
    }

    @Test
    void partialDocumentsYieldWhatIsKnownSoFar() {
        assertEquals(Json.object("path", "src/Ma"), JsonReader.parsePartialObject("{\"path\": \"src/Ma"));
        assertEquals(Json.object("a", 1, "b", List.of(1, 2)), JsonReader.parsePartialObject("{\"a\": 1, \"b\": [1, 2"));
        assertEquals(Json.object("a", 1), JsonReader.parsePartialObject("{\"a\": 1, \"flag\": tr"));
        assertEquals(Json.object(), JsonReader.parsePartialObject("not json"));
    }

    enum Status { OPEN, CLOSED }

    @Description("A line of an order")
    record Line(@Description("Article number") String sku, int quantity) { }

    record Order(String id, Status status, List<Line> lines, Optional<String> note, Instant created) { }

    @Test
    void recordsBindThroughCanonicalConstructors() {
        var order = new Order("o-1", Status.OPEN, List.of(new Line("A-7", 2)), Optional.empty(), Instant.parse("2026-09-27T10:15:30Z"));
        var json = Json.valueOf(order);
        assertEquals("{\"id\":\"o-1\",\"status\":\"OPEN\",\"lines\":[{\"sku\":\"A-7\",\"quantity\":2}],\"note\":null,"
                + "\"created\":\"2026-09-27T10:15:30Z\"}", json.toJson());
        assertEquals(order, Json.convert(json, Order.class));
        var error = assertThrows(IllegalArgumentException.class,
                () -> Json.convert(Json.parse("{\"id\":\"o\",\"status\":\"LOST\",\"lines\":[],\"created\":\"2026-01-01T00:00:00Z\"}"), Order.class));
        assertTrue(error.getMessage().startsWith("$.status"), error.getMessage());
    }

    @Test
    void schemasAreStrictCompatible() {
        var schema = Json.schemaOf(Order.class).asJson();
        assertEquals(Json.array("id", "status", "lines", "note", "created"), schema.get("required").orElseThrow());
        assertEquals(JsonBoolean.FALSE, schema.get("additionalProperties").orElseThrow());
        var properties = schema.object("properties");
        assertEquals(Json.array("string", "null"), properties.object("note").get("type").orElseThrow());
        assertEquals(Json.array("OPEN", "CLOSED"), properties.object("status").get("enum").orElseThrow());
        assertEquals("date-time", properties.object("created").string("format"));
        var line = properties.object("lines").object("items");
        assertEquals("A line of an order", line.string("description"));
        assertEquals("Article number", line.object("properties").object("sku").string("description"));
    }

    @Test
    void modelsHaveACanonicalJsonForm() {
        var model = Model.builder("anthropic", "claude-sonnet-5").api("anthropic-messages").input(Modality.TEXT, Modality.IMAGE)
                .contextWindow(200_000).reasoningLevels(ReasoningLevel.LOW, ReasoningLevel.HIGH).supports(Capability.TOOLS)
                .prices(Prices.usd().input("3").output("15").tier(200_000, Prices.usd().input("6").build()).build())
                .updatedAt(Instant.parse("2026-09-27T00:00:00Z")).build();
        assertEquals(model, Model.fromJson(model.toJson()));
    }

    @Test
    void malformedNumbersAreRejectedButValidLexicalFormsRoundTrip() {
        for (var bad : List.of("01", "1.", "-", ".5", "+1", "1e", "01.5")) {
            assertThrows(NumberFormatException.class, () -> JsonNumber.of(bad), bad);
            var error = assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
            assertTrue(error.getMessage().contains("offset"), error.getMessage());
        }
        for (var good : List.of("1.50", "-0", "1e10", "1E+2", "0.5", "3.14e-2")) {
            assertEquals(good, JsonNumber.of(good).toString());
            assertEquals(good, Json.parse(good).toString());
        }
    }

    @Test
    void numberFactoriesProduceGrammarValidLexicalForms() {
        for (var n : List.of(JsonNumber.of(new BigDecimal("1E+3")), JsonNumber.of(-0.0), JsonNumber.of(0L), JsonNumber.of(Long.MIN_VALUE)))
            assertEquals(n.toString(), JsonNumber.of(n.toString()).toString()); // re-parses cleanly: it is grammar-valid
        assertEquals("1000", JsonNumber.of(new BigDecimal("1E+3")).toString());
    }

    @Test
    void partialNumbersAtTheVeryEndDoNotThrow() {
        assertEquals(Json.object("a", 1), JsonReader.parsePartialObject("{\"a\": 1"));
        assertEquals(Json.object(), JsonReader.parsePartialObject("{\"a\": -"));
        assertEquals(Json.object(), JsonReader.parsePartialObject("{\"a\": 1."));
    }

    @Test
    void controlCharsAndBadEscapesFailButLoneSurrogatesPassThrough() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\nb\""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"\\uZZZZ\""));
        var s = (JsonString) Json.parse("\"\\ud800\"");
        assertEquals(0xD800, s.value().charAt(0));
    }

    record Letter(char code) { }
    record Numbers(int n, long big) { }

    @Test
    void charsBindFromOneCharacterStrings() {
        assertEquals(new Letter('A'), Json.convert(Json.object("code", "A"), Letter.class));
        var error = assertThrows(IllegalArgumentException.class, () -> Json.convert(Json.object("code", "AB"), Letter.class));
        assertTrue(error.getMessage().startsWith("$.code"), error.getMessage());
    }

    @Test
    void integerConversionsRejectNonExactValuesWithThePath() {
        var e1 = assertThrows(IllegalArgumentException.class,
                () -> Json.convert(Json.object("n", new BigDecimal("1.5"), "big", 1), Numbers.class));
        assertTrue(e1.getMessage().startsWith("$.n") && e1.getMessage().contains("does not fit"), e1.getMessage());
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> Json.convert(Json.object("n", 1, "big", new BigDecimal("1e30")), Numbers.class));
        assertTrue(e2.getMessage().startsWith("$.big") && e2.getMessage().contains("does not fit"), e2.getMessage());
    }
}
