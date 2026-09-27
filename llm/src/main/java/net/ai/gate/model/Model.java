package net.ai.gate.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import net.ai.gate.config.FieldDescriptor;
import net.ai.gate.config.FieldDescriptor.Kind;
import net.ai.gate.internal.serialization.ModelJson;
import net.ai.gate.json.JsonObject;
import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable, thread-safe catalog entry: a model of one provider with its wire API, limits, reasoning levels and
/// prices. Absent or `UNKNOWN` means "not known" — never zero and never "no". Obtain entries from
/// `llm.model(provider, id)`; describe gateway or local models with [#builder(String, String)].
public final class Model {
    /// Where the freshest data of an entry came from. `CUSTOM` (host-configured) always wins a merge.
    public enum Source { BUNDLED, CATALOG, FEED, LIVE, CUSTOM, UNLISTED }

    private final ModelRef ref;
    private final @Nullable String name, api;
    private final Set<Modality> input, output;
    private final @Nullable Long contextWindow, maxOutputTokens;
    private final List<ReasoningLevel> reasoningLevels;
    private final Capabilities capabilities;
    private final @Nullable Prices prices;
    private final @Nullable ApiCompat compat;
    private final @Nullable Instant deprecatedAt, updatedAt;
    private final Source source;

    private Model(Builder b) {
        ref = b.ref; name = b.name; api = b.api; input = freeze(b.input); output = freeze(b.output);
        contextWindow = b.contextWindow; maxOutputTokens = b.maxOutputTokens; reasoningLevels = List.copyOf(b.reasoningLevels);
        capabilities = b.capabilities; prices = b.prices; compat = b.compat; deprecatedAt = b.deprecatedAt;
        updatedAt = b.updatedAt; source = b.source;
    }

    /// A host-described model (source `CUSTOM` unless set otherwise).
    public static Builder builder(String providerId, String modelId) { return new Builder(new ModelRef(providerId, modelId)); }

    public ModelRef ref() { return ref; }
    public String providerId() { return ref.providerId(); }
    public String id() { return ref.modelId(); }
    public String name() { return name != null ? name : ref.modelId(); }
    /// The `WireApi` id this model is called with; absent means the provider's default API.
    public Optional<String> api() { return Optional.ofNullable(api); }
    /// Input modalities; empty means unknown.
    public Set<Modality> input() { return input; }
    public Set<Modality> output() { return output; }
    public OptionalLong contextWindow() { return contextWindow == null ? OptionalLong.empty() : OptionalLong.of(contextWindow); }
    public OptionalLong maxOutputTokens() { return maxOutputTokens == null ? OptionalLong.empty() : OptionalLong.of(maxOutputTokens); }
    /// Supported levels in ascending order; empty means no reasoning control is known.
    public List<ReasoningLevel> reasoningLevels() { return reasoningLevels; }
    public Capabilities capabilities() { return capabilities; }
    public Optional<Prices> prices() { return Optional.ofNullable(prices); }
    /// Compat flags overriding the provider's, field by field.
    public Optional<ApiCompat> compat() { return Optional.ofNullable(compat); }
    public Optional<Instant> deprecatedAt() { return Optional.ofNullable(deprecatedAt); }
    public Source source() { return source; }
    /// Timestamp of the newest contributing data.
    public Optional<Instant> updatedAt() { return Optional.ofNullable(updatedAt); }

    /// The parameter form of this model, derived from the fields above; keys match `ChatOptions.Builder.set`. No I/O.
    public List<FieldDescriptor> parameters() {
        var fields = new ArrayList<FieldDescriptor>();
        fields.add(FieldDescriptor.builder("maxTokens", Kind.INTEGER).label("Max output tokens").group("Generation")
                .unit("tokens").range(BigDecimal.ONE, maxOutputTokens == null ? null : BigDecimal.valueOf(maxOutputTokens)).build());
        if (!reasoningLevels.isEmpty())
            fields.add(FieldDescriptor.builder("reasoning", Kind.CHOICE).label("Reasoning").group("Generation")
                    .choices(reasoningLevels.stream().map(l -> l.name().toLowerCase(Locale.ROOT)).toList()).build());
        fields.add(FieldDescriptor.builder("temperature", Kind.DECIMAL).label("Temperature").group("Sampling")
                .range(BigDecimal.ZERO, BigDecimal.TWO).build());
        fields.add(FieldDescriptor.builder("topP", Kind.DECIMAL).label("Top P").group("Sampling")
                .range(BigDecimal.ZERO, BigDecimal.ONE).build());
        fields.add(FieldDescriptor.builder("cacheRetention", Kind.CHOICE).label("Prompt caching").group("Caching")
                .choices(List.of("none", "short", "long")).defaultValue("short").build());
        return List.copyOf(fields);
    }

    /// The canonical JSON form (`ai-gate.catalog/1` entry): bundled data, snapshots and provider configuration share it.
    /// Absent fields stay absent; enum values are lower case; compat flags are typed code and not part of it.
    public JsonObject toJson() { return ModelJson.write(this); }

    /// Reads the canonical form; `provider` and `id` are required, `source` defaults to `CUSTOM`.
    /// @throws IllegalArgumentException naming the member that does not fit
    public static Model fromJson(JsonObject json) { return ModelJson.read(json, null, Source.CUSTOM, null); }

    public Builder toBuilder() { return copy(ref); }

    /// The same entry under another provider id — a preset copied with `toBuilder().id(…)` keeps its models.
    public Model withProviderId(String providerId) { return copy(new ModelRef(providerId, ref.modelId())).build(); }

    private Builder copy(ModelRef target) {
        var b = new Builder(target);
        b.name = name; b.api = api; b.input.addAll(input); b.output.addAll(output);
        b.contextWindow = contextWindow; b.maxOutputTokens = maxOutputTokens; b.reasoningLevels = reasoningLevels;
        b.capabilities = capabilities; b.prices = prices; b.compat = compat; b.deprecatedAt = deprecatedAt;
        b.updatedAt = updatedAt; b.source = source;
        return b;
    }

    private List<@Nullable Object> state() {
        return Arrays.asList(ref, name, api, input, output, contextWindow, maxOutputTokens, reasoningLevels, capabilities,
                prices, compat, deprecatedAt, updatedAt, source);
    }

    @Override public boolean equals(Object o) { return o instanceof Model m && state().equals(m.state()); }
    @Override public int hashCode() { return state().hashCode(); }

    @Override public String toString() {
        return "Model[" + ref + (api == null ? "" : ", api=" + api) + (contextWindow == null ? "" : ", context=" + contextWindow)
                + ", source=" + source + "]";
    }

    private static Set<Modality> freeze(Set<Modality> set) {
        return set.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(set));
    }

    /// Not thread-safe. Nullable setters clear a field (used by merges and `toBuilder()`).
    public static final class Builder {
        private final ModelRef ref;
        private @Nullable String name, api;
        private final Set<Modality> input = EnumSet.noneOf(Modality.class), output = EnumSet.noneOf(Modality.class);
        private @Nullable Long contextWindow, maxOutputTokens;
        private List<ReasoningLevel> reasoningLevels = List.of();
        private Capabilities capabilities = Capabilities.unknown();
        private @Nullable Prices prices;
        private @Nullable ApiCompat compat;
        private @Nullable Instant deprecatedAt, updatedAt;
        private Source source = Source.CUSTOM;

        private Builder(ModelRef ref) { this.ref = ref; }

        public Builder name(@Nullable String value) { name = value; return this; }
        public Builder api(@Nullable String wireApiId) { api = wireApiId; return this; }
        public Builder input(Modality... modalities) { input.addAll(List.of(modalities)); return this; }
        public Builder output(Modality... modalities) { output.addAll(List.of(modalities)); return this; }
        public Builder contextWindow(long tokens) { contextWindow = limit(tokens); return this; }
        public Builder maxOutputTokens(long tokens) { maxOutputTokens = limit(tokens); return this; }
        public Builder reasoningLevels(ReasoningLevel... levels) { return reasoningLevels(List.of(levels)); }
        public Builder reasoningLevels(List<ReasoningLevel> levels) { reasoningLevels = levels.stream().sorted().distinct().toList(); return this; }
        public Builder capabilities(Capabilities value) { capabilities = value; return this; }
        /// Marks `supported` as `SUPPORTED`, keeping the other levels.
        public Builder supports(Capability... supported) {
            for (var c : supported) capabilities = capabilities.with(c, SupportLevel.SUPPORTED);
            return this;
        }
        public Builder prices(@Nullable Prices value) { prices = value; return this; }
        public Builder compat(@Nullable ApiCompat value) { compat = value; return this; }
        public Builder deprecatedAt(@Nullable Instant value) { deprecatedAt = value; return this; }
        public Builder updatedAt(@Nullable Instant value) { updatedAt = value; return this; }
        public Builder source(Source value) { source = value; return this; }
        public Model build() { return new Model(this); }

        private static long limit(long tokens) {
            if (tokens <= 0) throw new IllegalArgumentException("Token limit must be positive: " + tokens);
            return tokens;
        }
    }
}
