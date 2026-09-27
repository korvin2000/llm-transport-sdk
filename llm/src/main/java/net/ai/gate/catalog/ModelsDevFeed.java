package net.ai.gate.catalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.ai.gate.json.JsonBoolean;
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
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.catalog.FeedHttp;

/// The public model-metadata database the bundled catalog is generated from; refreshed at runtime (on by default,
/// off with `CatalogOptions.noFeeds()` or `offline()`). Requests carry no credentials, prompts or usage.
///
/// Mapping: feed provider ids are mapped explicitly to preset ids ([#PROVIDERS]); limits, modalities, capabilities,
/// prices (USD per million, context tiers) and `last_updated` are taken as published; `deprecated` entries carry
/// their last update as `deprecatedAt`; `temperature: false` marks models that reject sampling parameters. Reasoning
/// levels come from `reasoning_options`: effort values map by name (`none` → `OFF`); a toggle, or a budget that may
/// be zero, means thinking can be switched off, as it always can on Anthropic models. The [#CODEX] models are also
/// listed for the ChatGPT subscription preset `openai-codex`, with its context window and without per-token prices.
public final class ModelsDevFeed implements CatalogFeed {
    public static final URI URL = URI.create("https://models.dev/api.json");

    /// Feed provider id → preset id; unmapped providers are skipped.
    public static final Map<String, String> PROVIDERS = Map.of("anthropic", "anthropic", "openai", "openai", "google", "google",
            "deepseek", "deepseek", "openrouter", "openrouter", "xai", "xai", "alibaba", "qwen", "mistral", "mistral", "groq", "groq");

    /// OpenAI models a ChatGPT subscription serves through the Codex backend (the Codex client's model list).
    public static final Set<String> CODEX = Set.of("gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra",
            "gpt-5.6-luna", "gpt-5.5");
    private static final long CODEX_CONTEXT = 272_000;

    private static final Map<String, Modality> MODALITIES = Map.of("text", Modality.TEXT, "image", Modality.IMAGE,
            "audio", Modality.AUDIO, "video", Modality.VIDEO, "pdf", Modality.DOCUMENT);

    public ModelsDevFeed() { }

    @Override public String id() { return "models.dev"; }

    @Override public List<Model> fetch(FeedHttp http) throws IOException { return parse(http.get(URL)); }

    /// The feed document as catalog entries (source `FEED`) of the mapped providers; malformed entries are skipped.
    /// @throws IllegalArgumentException when the document is not a JSON object
    public static List<Model> parse(JsonValue document) {
        if (!(document instanceof JsonObject providers)) throw new IllegalArgumentException("The models.dev document is not a JSON object");
        var models = new ArrayList<Model>();
        PROVIDERS.forEach((feedId, presetId) -> {
            for (var entry : providers.object(feedId).object("models").members().values())
                if (entry instanceof JsonObject m && m.optString("id").isPresent()) {
                    try {
                        var model = model(presetId, m);
                        models.add(model);
                        if (feedId.equals("openai") && CODEX.contains(model.id()))
                            models.add(model.withProviderId("openai-codex").toBuilder().prices(null)
                                    .contextWindow(Math.min(model.contextWindow().orElse(CODEX_CONTEXT), CODEX_CONTEXT)).build());
                    } catch (RuntimeException e) {
                        // one malformed entry must not discard the feed
                    }
                }
        });
        return List.copyOf(models);
    }

    private static Model model(String provider, JsonObject m) {
        var b = Model.builder(provider, m.string("id")).source(Model.Source.FEED);
        m.optString("name").ifPresent(b::name);
        var limit = m.object("limit");
        limit.optLong("context").stream().filter(n -> n > 0).forEach(b::contextWindow);
        limit.optLong("output").stream().filter(n -> n > 0).forEach(b::maxOutputTokens);
        var modalities = m.object("modalities");
        var input = modalities(modalities, "input");
        var output = modalities(modalities, "output");
        b.input(input.toArray(Modality[]::new)).output(output.toArray(Modality[]::new));

        var capabilities = new ArrayList<Capability>();
        if (m.bool("tool_call")) capabilities.add(Capability.TOOLS);
        if (m.bool("structured_output")) capabilities.add(Capability.STRUCTURED_OUTPUT);
        if (m.bool("reasoning")) capabilities.add(Capability.REASONING);
        if (input.contains(Modality.IMAGE)) capabilities.add(Capability.VISION);
        if (input.contains(Modality.DOCUMENT)) capabilities.add(Capability.DOCUMENTS);
        if (input.contains(Modality.AUDIO)) capabilities.add(Capability.AUDIO_INPUT);
        if (output.contains(Modality.IMAGE)) capabilities.add(Capability.IMAGE_OUTPUT);
        if (output.contains(Modality.AUDIO)) capabilities.add(Capability.AUDIO_OUTPUT);
        var supported = Capabilities.of(capabilities.toArray(Capability[]::new));
        if (m.get("temperature").orElse(null) instanceof JsonBoolean t)
            supported = supported.with(Capability.TEMPERATURE, t.value() ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED);
        b.capabilities(supported);
        if (m.bool("reasoning")) b.reasoningLevels(levels(provider, m.objects("reasoning_options")));

        var cost = m.object("cost");
        if (cost.get("input").isPresent() || cost.get("output").isPresent()) {
            var prices = prices(cost);
            for (var tier : cost.objects("tiers"))
                if ("context".equals(tier.object("tier").optString("type").orElse(null)) && tier.object("tier").optLong("size").isPresent())
                    prices.tier(tier.object("tier").optLong("size").getAsLong(), prices(tier).build());
            b.prices(prices.build());
        }
        var updated = m.optString("last_updated").map(ModelsDevFeed::date);
        updated.ifPresent(b::updatedAt);
        if ("deprecated".equals(m.optString("status").orElse(null))) b.deprecatedAt(updated.orElse(Instant.EPOCH));   // the feed has no date
        return b.build();
    }

    private static Set<Modality> modalities(JsonObject modalities, String direction) {
        var set = EnumSet.noneOf(Modality.class);
        for (var value : modalities.array(direction))
            if (value instanceof JsonString s && MODALITIES.containsKey(s.value())) set.add(MODALITIES.get(s.value()));
        return set;
    }

    private static List<ReasoningLevel> levels(String provider, List<JsonObject> options) {
        var levels = EnumSet.noneOf(ReasoningLevel.class);
        boolean switchable = provider.equals("anthropic"), budget = false;
        for (var option : options) {
            switch (option.optString("type").orElse("")) {
                case "effort" -> {
                    for (var value : option.array("values"))
                        if (value instanceof JsonString s) {
                            if (s.value().equals("none")) levels.add(ReasoningLevel.OFF);
                            else level(s.value()).ifPresent(levels::add);
                        }
                }
                case "budget_tokens" -> {
                    budget = true;
                    if (option.optLong("min").orElse(0) == 0) switchable = true;
                }
                case "toggle" -> switchable = true;
                default -> { }
            }
        }
        if (levels.isEmpty() && (switchable || budget)) levels.addAll(EnumSet.of(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
        if (switchable) levels.add(ReasoningLevel.OFF);
        return List.copyOf(levels);
    }

    private static Optional<ReasoningLevel> level(String name) {
        try {
            return Optional.of(ReasoningLevel.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Prices.Builder prices(JsonObject cost) {
        Function<String, @Nullable BigDecimal> price = name -> cost.get(name).orElse(null) instanceof JsonNumber n ? n.value() : null;
        return Prices.usd().input(price.apply("input")).output(price.apply("output"))
                .cacheRead(price.apply("cache_read")).cacheWrite(price.apply("cache_write"));
    }

    /// `2025-06-17` as the start of that day (UTC); partial dates are ignored.
    private static @Nullable Instant date(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
