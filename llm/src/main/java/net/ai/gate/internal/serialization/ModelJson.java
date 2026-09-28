package net.ai.gate.internal.serialization;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.Capabilities;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.Prices;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.model.SupportLevel;
import org.jspecify.annotations.Nullable;

/// The canonical JSON form of catalog entries, shared by bundled data, snapshots and provider configuration.
/// Absent fields stay absent; enum values are lower case. Compat flags are typed code, not data, and are not part
/// of this form.
public final class ModelJson {
    private ModelJson() { }

    public static JsonObject write(Model m) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("provider", Json.valueOf(m.providerId()));
        json.put("id", Json.valueOf(m.id()));
        if (!m.name().equals(m.id())) json.put("name", Json.valueOf(m.name()));
        m.api().ifPresent(a -> json.put("api", Json.valueOf(a)));
        if (!m.input().isEmpty()) json.put("input", names(m.input()));
        if (!m.output().isEmpty()) json.put("output", names(m.output()));
        m.contextWindow().ifPresent(v -> json.put("contextWindow", JsonNumber.of(v)));
        m.maxOutputTokens().ifPresent(v -> json.put("maxOutputTokens", JsonNumber.of(v)));
        if (!m.reasoningLevels().isEmpty()) json.put("reasoning", names(m.reasoningLevels()));
        if (!m.capabilities().levels().isEmpty()) {
            var levels = new LinkedHashMap<String, JsonValue>();
            m.capabilities().levels().forEach((c, l) -> levels.put(lower(c), Json.valueOf(lower(l))));
            json.put("capabilities", JsonObject.of(levels));
        }
        m.prices().ifPresent(p -> json.put("prices", prices(p)));
        m.deprecatedAt().ifPresent(d -> json.put("deprecatedAt", Json.valueOf(d)));
        m.updatedAt().ifPresent(u -> json.put("updatedAt", Json.valueOf(u)));
        json.put("source", Json.valueOf(lower(m.source())));
        return JsonObject.of(json);
    }

    /// `providerId` fills entries without a `provider` member; `source` fills entries without a `source` member.
    /// @throws IllegalArgumentException naming the member that does not fit
    public static Model read(JsonObject json, @Nullable String providerId, Model.Source source, @Nullable Instant updatedAt) {
        var provider = text(json, "provider");
        var b = Model.builder(provider != null ? provider : Objects.requireNonNull(providerId, "provider"), requiredString(json, "id"));
        b.name(text(json, "name")).api(text(json, "api"));
        strings(json, "input").forEach(s -> b.input(enumValue(Modality.class, s, "input")));
        strings(json, "output").forEach(s -> b.output(enumValue(Modality.class, s, "output")));
        number(json, "contextWindow").ifPresent(v -> setLimit(b::contextWindow, exactLong(v, "contextWindow"), "contextWindow"));
        number(json, "maxOutputTokens").ifPresent(v -> setLimit(b::maxOutputTokens, exactLong(v, "maxOutputTokens"), "maxOutputTokens"));
        b.reasoningLevels(strings(json, "reasoning").stream().map(s -> enumValue(ReasoningLevel.class, s, "reasoning")).toList());
        var capabilities = Capabilities.unknown();
        for (var e : json.object("capabilities").members().entrySet()) {
            var capability = enumValue(Capability.class, e.getKey(), "capabilities");
            var member = "capabilities." + e.getKey();
            if (!(e.getValue() instanceof JsonString level)) throw error(member, "expected a string");
            capabilities = capabilities.with(capability, enumValue(SupportLevel.class, level.value(), member));
        }
        b.capabilities(capabilities);
        if (json.get("prices").orElse(null) instanceof JsonObject p) b.prices(prices(p, "prices"));
        var deprecated = text(json, "deprecatedAt");
        if (deprecated != null) b.deprecatedAt(Instant.parse(deprecated));
        var updated = text(json, "updatedAt");
        b.updatedAt(updated != null ? Instant.parse(updated) : updatedAt);
        var declared = text(json, "source");
        return b.source(declared != null ? enumValue(Model.Source.class, declared, "source") : source).build();
    }

    public static JsonArray writeAll(List<Model> models) { return JsonArray.of(models.stream().map(ModelJson::write).toList()); }

    public static List<Model> readAll(JsonValue json, @Nullable String providerId, Model.Source source, @Nullable Instant updatedAt) {
        var models = new ArrayList<Model>();
        if (json instanceof JsonArray array) for (var v : array.values()) models.add(read((JsonObject) v, providerId, source, updatedAt));
        return models;
    }

    private static JsonObject prices(Prices p) {
        var json = new LinkedHashMap<String, JsonValue>();
        json.put("currency", Json.valueOf(p.currency().getCurrencyCode()));
        p.inputPerMillion().ifPresent(v -> json.put("input", JsonNumber.of(v)));
        p.outputPerMillion().ifPresent(v -> json.put("output", JsonNumber.of(v)));
        p.cacheReadPerMillion().ifPresent(v -> json.put("cacheRead", JsonNumber.of(v)));
        p.cacheWritePerMillion().ifPresent(v -> json.put("cacheWrite", JsonNumber.of(v)));
        p.cacheWriteLongPerMillion().ifPresent(v -> json.put("cacheWriteLong", JsonNumber.of(v)));
        if (!p.tiers().isEmpty())
            json.put("tiers", JsonArray.of(p.tiers().stream().map(t -> prices(t.prices()).with("above", t.inputTokensAbove())).toList()));
        return JsonObject.of(json);
    }

    private static Prices prices(JsonObject json, String path) {
        var b = Prices.builder(Currency.getInstance(json.string("currency")));
        number(json, "input").ifPresent(b::input);
        number(json, "output").ifPresent(b::output);
        number(json, "cacheRead").ifPresent(b::cacheRead);
        number(json, "cacheWrite").ifPresent(b::cacheWrite);
        number(json, "cacheWriteLong").ifPresent(b::cacheWriteLong);
        if (json.get("tiers").orElse(null) instanceof JsonArray tiers)
            for (int i = 0; i < tiers.values().size(); i++) {
                var tierPath = path + ".tiers[" + i + "]";
                if (!(tiers.values().get(i) instanceof JsonObject tier)) throw error(tierPath, "expected an object");
                var above = exactLong(number(tier, "above").orElseThrow(() -> error(tierPath, "missing 'above'")), tierPath + ".above");
                b.tier(above, prices(tier.with("currency", json.string("currency")), tierPath));
            }
        return b.build();
    }

    private static JsonArray names(Iterable<? extends Enum<?>> values) {
        var names = new ArrayList<JsonValue>();
        values.forEach(v -> names.add(Json.valueOf(lower(v))));
        return JsonArray.of(names);
    }

    /// @throws IllegalArgumentException naming `name` when it is present but not an array of strings
    private static List<String> strings(JsonObject json, String name) {
        var v = json.get(name).orElse(null);
        if (v == null) return List.of();
        if (!(v instanceof JsonArray a) || !a.values().stream().allMatch(x -> x instanceof JsonString))
            throw error(name, "expected an array of strings");
        return a.values().stream().map(x -> ((JsonString) x).value()).toList();
    }

    private static @Nullable String text(JsonObject json, String name) {
        return json.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }

    private static Optional<BigDecimal> number(JsonObject json, String name) {
        return json.get(name).orElse(null) instanceof JsonNumber n ? Optional.of(n.value()) : Optional.empty();
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    /// The constant of `type` named `value`, case-insensitively.
    /// @throws IllegalArgumentException naming `member` and `value` when there is no such constant
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String member) {
        for (var k : type.getEnumConstants()) if (k.name().equalsIgnoreCase(value)) return k;
        throw error(member, "'" + value + "' is not a valid " + type.getSimpleName());
    }

    /// @throws IllegalArgumentException naming `member` when it is absent or not a string
    private static String requiredString(JsonObject json, String member) {
        if (json.get(member).orElse(null) instanceof JsonString s) return s.value();
        throw error(member, "absent or not a string");
    }

    /// @throws IllegalArgumentException naming `member` when `value` has a fraction or does not fit a `long`
    private static long exactLong(BigDecimal value, String member) {
        try { return value.longValueExact(); } catch (ArithmeticException e) { throw error(member, value + " is not an integer"); }
    }

    /// Applies `setter`, wrapping a rejection from it (e.g. a non-positive limit) with `member`.
    private static void setLimit(Consumer<Long> setter, long value, String member) {
        try { setter.accept(value); } catch (IllegalArgumentException e) { throw error(member, e.getMessage()); }
    }

    private static IllegalArgumentException error(String member, String problem) {
        return new IllegalArgumentException(member + ": " + problem);
    }
}
