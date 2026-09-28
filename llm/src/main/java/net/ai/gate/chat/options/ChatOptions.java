package net.ai.gate.chat.options;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.ai.gate.cache.CacheMode;
import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.config.FieldDescriptor;
import net.ai.gate.config.FieldDescriptor.Kind;
import net.ai.gate.config.RetryPolicy;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.event.LlmListener;
import net.ai.gate.internal.serialization.ChatOptionsJson;
import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.JsonObject;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.model.Model;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.spi.protocol.ProviderOptions;
import org.jspecify.annotations.Nullable;

/// Immutable, thread-safe per-call settings. Unset fields inherit, field by field: call ▷ provider defaults ▷
/// runtime defaults ▷ catalog-derived values ▷ not sent. Nested policies inherit field by field too. Tags, headers
/// (case-insensitive names), listeners and provider options accumulate across scopes; a stop list set with
/// [Builder#stops(List)] — even an empty one — replaces the inherited list. Requests stay data: the cancel token,
/// listeners and payload hook are never serialized.
public final class ChatOptions {
    private static final ChatOptions NONE = new Builder().build();

    private final @Nullable Double temperature, topP;
    private final @Nullable Integer topK, maxTokens;
    private final @Nullable Long seed;
    private final @Nullable List<String> stop;
    private final @Nullable ReasoningLevel reasoning;
    private final @Nullable ReasoningHandoff reasoningHandoff;
    private final @Nullable ToolChoice toolChoice;
    private final @Nullable Boolean parallelToolCalls, strict;
    private final @Nullable OutputFormat output;
    private final @Nullable CacheRetention cacheRetention;
    private final @Nullable String sessionId;
    private final @Nullable CacheMode responseCache;
    private final @Nullable Set<String> strictCodes;
    private final @Nullable HistoryPolicy historyPolicy;
    private final @Nullable TimeoutPolicy timeouts;
    private final @Nullable RetryPolicy retry;
    private final @Nullable CancelToken cancel;
    private final Map<String, String> headers, tags;
    private final List<LlmListener> listeners;
    private final Map<Class<? extends ProviderOptions>, ProviderOptions> providerOptions;
    private final @Nullable UnaryOperator<JsonObject> payload;

    private ChatOptions(Builder b) {
        temperature = b.temperature; topP = b.topP; topK = b.topK; maxTokens = b.maxTokens; seed = b.seed; stop = b.stop == null ? null : List.copyOf(b.stop);
        reasoning = b.reasoning; reasoningHandoff = b.reasoningHandoff; toolChoice = b.toolChoice;
        parallelToolCalls = b.parallelToolCalls; strict = b.strict; output = b.output; cacheRetention = b.cacheRetention;
        sessionId = b.sessionId; responseCache = b.responseCache; timeouts = b.timeouts; retry = b.retry; cancel = b.cancel;
        strictCodes = b.strictCodes == null ? null : Set.copyOf(b.strictCodes); historyPolicy = b.historyPolicy;
        headers = frozen(b.headers); tags = frozen(b.tags); listeners = List.copyOf(b.listeners);
        providerOptions = frozen(b.providerOptions); payload = b.payload;
    }

    public static ChatOptions none() { return NONE; }
    public static Builder builder() { return new Builder(); }

    // generation — portable
    public OptionalDouble temperature() { return temperature == null ? OptionalDouble.empty() : OptionalDouble.of(temperature); }
    public OptionalDouble topP() { return topP == null ? OptionalDouble.empty() : OptionalDouble.of(topP); }
    public OptionalInt topK() { return topK == null ? OptionalInt.empty() : OptionalInt.of(topK); }
    /// Unset: derived from the catalog and sent only where the API requires a value.
    public OptionalInt maxTokens() { return maxTokens == null ? OptionalInt.empty() : OptionalInt.of(maxTokens); }
    public List<String> stop() { return stop == null ? List.of() : stop; }
    /// The list as set in this scope — present-but-empty means an explicit clear; absent means unset (a wider
    /// scope decides). [#stop()] collapses both to an empty list.
    public Optional<List<String>> stops() { return Optional.ofNullable(stop); }
    public OptionalLong seed() { return seed == null ? OptionalLong.empty() : OptionalLong.of(seed); }
    /// Mapped per model to the nearest supported level; absent means the model's default (nothing is sent).
    public Optional<ReasoningLevel> reasoning() { return Optional.ofNullable(reasoning); }
    /// Default `KEEP`.
    public Optional<ReasoningHandoff> reasoningHandoff() { return Optional.ofNullable(reasoningHandoff); }
    public Optional<ToolChoice> toolChoice() { return Optional.ofNullable(toolChoice); }
    public Optional<Boolean> parallelToolCalls() { return Optional.ofNullable(parallelToolCalls); }
    public Optional<OutputFormat> output() { return Optional.ofNullable(output); }
    // caching
    /// Default `SHORT`.
    public Optional<CacheRetention> cacheRetention() { return Optional.ofNullable(cacheRetention); }
    /// Cache routing and affinity key where the API has one.
    public Optional<String> sessionId() { return Optional.ofNullable(sessionId); }
    /// How this call uses the runtime's response cache; default `READ_WRITE` when one is configured.
    public Optional<CacheMode> responseCache() { return Optional.ofNullable(responseCache); }
    // operational
    public Optional<TimeoutPolicy> timeouts() { return Optional.ofNullable(timeouts); }
    public Optional<RetryPolicy> retry() { return Optional.ofNullable(retry); }
    public Optional<CancelToken> cancel() { return Optional.ofNullable(cancel); }
    /// Extra request headers; names compare case-insensitively.
    public Map<String, String> headers() { return headers; }
    /// Copied onto every event, log line and JFR record of the call.
    public Map<String, String> tags() { return tags; }
    /// Added to the runtime's listeners for this call.
    public List<LlmListener> listeners() { return listeners; }
    /// Soft adaptations fail instead of warning.
    public boolean strict() { return Boolean.TRUE.equals(strict); }
    /// Whether `strict` was set explicitly in this scope, and to what. [#strict()] collapses an unset value to `false`.
    public Optional<Boolean> strictSetting() { return Optional.ofNullable(strict); }
    /// Warning codes that fail the call while it is prepared, even when it is not strict — e.g. `option_adapted` for a
    /// host that reserves exactly `maxTokens`. Empty when unset.
    public Set<String> strictCodes() { return strictCodes == null ? Set.of() : strictCodes; }
    /// The set as given in this scope; absent means unset. A set given in a narrower scope replaces a wider one.
    public Optional<Set<String>> strictCodesSetting() { return Optional.ofNullable(strictCodes); }
    /// Default `ALLOW_ADAPTATION`.
    public Optional<HistoryPolicy> historyPolicy() { return Optional.ofNullable(historyPolicy); }
    // provider-specific and escape hatch
    /// Read only by codecs of the option's API family; inert with `option_not_applicable` elsewhere.
    public <T extends ProviderOptions> Optional<T> provider(Class<T> type) { return Optional.ofNullable(type.cast(providerOptions.get(type))); }
    public List<ProviderOptions> providerOptions() { return List.copyOf(providerOptions.values()); }
    /// The last edit of the wire body; may not touch auth, model or stream fields.
    public Optional<UnaryOperator<JsonObject>> payload() { return Optional.ofNullable(payload); }

    /// Field by field: values set in `higher` win; tags, headers, listeners and provider options accumulate.
    public ChatOptions overriddenBy(ChatOptions higher) {
        if (higher == NONE) return this;
        if (this == NONE) return higher;
        var b = toBuilder();
        var h = higher;
        if (h.temperature != null) b.temperature = h.temperature;
        if (h.topP != null) b.topP = h.topP;
        if (h.topK != null) b.topK = h.topK;
        if (h.maxTokens != null) b.maxTokens = h.maxTokens;
        if (h.seed != null) b.seed = h.seed;
        if (h.stop != null) b.stop = new ArrayList<>(h.stop);
        if (h.reasoning != null) b.reasoning = h.reasoning;
        if (h.reasoningHandoff != null) b.reasoningHandoff = h.reasoningHandoff;
        if (h.toolChoice != null) b.toolChoice = h.toolChoice;
        if (h.parallelToolCalls != null) b.parallelToolCalls = h.parallelToolCalls;
        if (h.strict != null) b.strict = h.strict;
        if (h.output != null) b.output = h.output;
        if (h.cacheRetention != null) b.cacheRetention = h.cacheRetention;
        if (h.sessionId != null) b.sessionId = h.sessionId;
        if (h.responseCache != null) b.responseCache = h.responseCache;
        if (h.strictCodes != null) b.strictCodes = h.strictCodes;
        if (h.historyPolicy != null) b.historyPolicy = h.historyPolicy;
        if (h.timeouts != null) b.timeouts = b.timeouts == null ? h.timeouts : b.timeouts.overriddenBy(h.timeouts);
        if (h.retry != null) b.retry = b.retry == null ? h.retry : b.retry.overriddenBy(h.retry);
        if (h.cancel != null) b.cancel = h.cancel;
        if (h.payload != null) b.payload = h.payload;
        b.headers.putAll(h.headers);
        b.tags.putAll(h.tags);
        b.listeners.addAll(h.listeners);
        b.providerOptions.putAll(h.providerOptions);
        return b.build();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.temperature = temperature; b.topP = topP; b.topK = topK; b.maxTokens = maxTokens; b.seed = seed;
        b.stop = stop == null ? null : new ArrayList<>(stop); b.reasoning = reasoning; b.reasoningHandoff = reasoningHandoff; b.toolChoice = toolChoice;
        b.parallelToolCalls = parallelToolCalls; b.strict = strict; b.output = output; b.cacheRetention = cacheRetention;
        b.sessionId = sessionId; b.responseCache = responseCache; b.timeouts = timeouts; b.retry = retry; b.cancel = cancel;
        b.strictCodes = strictCodes; b.historyPolicy = historyPolicy;
        b.headers.putAll(headers); b.tags.putAll(tags); b.listeners.addAll(listeners); b.providerOptions.putAll(providerOptions);
        b.payload = payload;
        return b;
    }

    /// The form of every value [Builder#set(String, String)] accepts: the model's parameters, then the runtime
    /// options. Keys match `set`; no I/O.
    public static List<FieldDescriptor> fields(Model model) {
        var fields = new ArrayList<>(model.parameters());
        fields.add(FieldDescriptor.builder("topK", Kind.INTEGER).label("Top K").group("Sampling").range(BigDecimal.ONE, null).build());
        fields.add(FieldDescriptor.builder("seed", Kind.INTEGER).label("Seed").group("Sampling").build());
        fields.add(FieldDescriptor.builder("stop", Kind.TEXT).label("Stop sequences").group("Generation").help("Comma-separated").build());
        fields.add(choice("reasoningHandoff", "Foreign reasoning", "Generation", ReasoningHandoff.values(), ReasoningHandoff.KEEP));
        fields.add(choice("historyPolicy", "Foreign history", "Generation", HistoryPolicy.values(), HistoryPolicy.ALLOW_ADAPTATION));
        fields.add(FieldDescriptor.builder("parallelToolCalls", Kind.BOOLEAN).label("Parallel tool calls").group("Tools").build());
        fields.add(FieldDescriptor.builder("sessionId", Kind.TEXT).label("Session id").group("Caching").help("Cache routing key where the API has one").build());
        fields.add(choice("responseCache", "Response cache", "Caching", CacheMode.values(), CacheMode.READ_WRITE));
        fields.add(FieldDescriptor.builder("timeout", Kind.DURATION).label("Total timeout").group("Operation").help("ISO-8601, e.g. PT2M").build());
        fields.add(FieldDescriptor.builder("strict", Kind.BOOLEAN).label("Strict").group("Operation").defaultValue("false")
                .help("Fail instead of adapting a setting the model or API cannot honour").build());
        fields.add(FieldDescriptor.builder("strictCodes", Kind.TEXT).label("Fatal warning codes").group("Operation")
                .help("Comma-separated, e.g. option_adapted,cache_hint_ignored").build());
        return List.copyOf(fields);
    }

    private static FieldDescriptor choice(String key, String label, String group, Enum<?>[] values, Enum<?> defaultValue) {
        return FieldDescriptor.builder(key, Kind.CHOICE).label(label).group(group).defaultValue(defaultValue.name().toLowerCase(Locale.ROOT))
                .choices(Arrays.stream(values).map(v -> v.name().toLowerCase(Locale.ROOT)).toList()).build();
    }

    /// The canonical JSON form (`ai-gate.options/1`) of the portable members; `cancel`, `listeners` and `payload`
    /// are operational and omitted.
    /// @throws IllegalArgumentException naming `providerOptions` or `output` when either is not representable
    public JsonObject toJson() { return ChatOptionsJson.write(this); }

    /// Reads the canonical form.
    /// @throws IllegalArgumentException naming the member that does not fit
    public static ChatOptions fromJson(JsonObject json) { return ChatOptionsJson.read(json); }

    @Override public String toString() {
        var parts = new ArrayList<String>();
        if (temperature != null) parts.add("temperature=" + temperature);
        if (maxTokens != null) parts.add("maxTokens=" + maxTokens);
        if (reasoning != null) parts.add("reasoning=" + reasoning);
        if (cacheRetention != null) parts.add("cacheRetention=" + cacheRetention);
        if (output != null) parts.add("output=" + output);
        if (Boolean.TRUE.equals(strict)) parts.add("strict");
        if (!tags.isEmpty()) parts.add("tags=" + tags);
        if (!providerOptions.isEmpty()) parts.add("provider=" + providerOptions.values());
        return "ChatOptions[" + String.join(", ", parts) + "]";
    }

    private static <K, V> Map<K, V> frozen(Map<K, V> map) {
        if (map.isEmpty()) return Map.of();
        if (map instanceof TreeMap<K, V> t) {
            var copy = new TreeMap<K, V>(t.comparator());
            copy.putAll(map);
            return Collections.unmodifiableMap(copy);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /// Not thread-safe. One setter per accessor; singular methods add (`tag`, `header`, `listener`, `stop`; `provider`
    /// replaces options of the same class), plural ones replace. Consumer-builders edit `timeouts` and `retry` as
    /// partial policies, so setting one field keeps the inherited others. `build()` reports every invalid
    /// `set(key, raw)` value at once.
    public static final class Builder {
        private @Nullable Double temperature, topP;
        private @Nullable Integer topK, maxTokens;
        private @Nullable Long seed;
        private @Nullable List<String> stop;
        private @Nullable ReasoningLevel reasoning;
        private @Nullable ReasoningHandoff reasoningHandoff;
        private @Nullable ToolChoice toolChoice;
        private @Nullable Boolean parallelToolCalls, strict;
        private @Nullable OutputFormat output;
        private @Nullable CacheRetention cacheRetention;
        private @Nullable String sessionId;
        private @Nullable CacheMode responseCache;
        private @Nullable Set<String> strictCodes;
        private @Nullable HistoryPolicy historyPolicy;
        private @Nullable TimeoutPolicy timeouts;
        private @Nullable RetryPolicy retry;
        private @Nullable CancelToken cancel;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER), tags = new LinkedHashMap<>();
        private final List<LlmListener> listeners = new ArrayList<>();
        private final Map<Class<? extends ProviderOptions>, ProviderOptions> providerOptions = new LinkedHashMap<>();
        private @Nullable UnaryOperator<JsonObject> payload;
        private final List<String> problems = new ArrayList<>();

        private Builder() { }

        /// Checked against `[0, 2]`; APIs check narrower ranges and never rescale.
        public Builder temperature(double value) { temperature = range(value, 0, 2, "temperature"); return this; }
        public Builder topP(double value) { topP = range(value, 0, 1, "topP"); return this; }
        public Builder topK(int value) { topK = Checks.positive(value, "topK"); return this; }
        public Builder maxTokens(int tokens) { maxTokens = Checks.positive(tokens, "maxTokens"); return this; }
        public Builder stop(String sequence) {
            if (stop == null) stop = new ArrayList<>();
            stop.add(Checks.notBlank(sequence, "Stop sequence"));
            return this;
        }
        /// Replaces the stop list; an empty list clears an inherited one.
        public Builder stops(List<String> replaced) {
            stop = new ArrayList<>();
            replaced.forEach(this::stop);
            return this;
        }
        public Builder seed(long value) { seed = value; return this; }
        /// `null` clears the level set in this builder; the wider scopes then decide.
        public Builder reasoning(@Nullable ReasoningLevel level) { reasoning = level; return this; }
        public Builder reasoningHandoff(ReasoningHandoff mode) { reasoningHandoff = mode; return this; }
        public Builder toolChoice(ToolChoice choice) { toolChoice = choice; return this; }
        public Builder parallelToolCalls(boolean allowed) { parallelToolCalls = allowed; return this; }
        public Builder output(OutputFormat format) { output = format; return this; }
        public Builder output(Class<?> recordType) { return output(OutputFormat.of(recordType)); }
        public Builder cacheRetention(CacheRetention retention) { cacheRetention = retention; return this; }
        public Builder sessionId(String id) { sessionId = Checks.notBlank(id, "Session id"); return this; }
        public Builder responseCache(CacheMode mode) { responseCache = mode; return this; }
        public Builder timeouts(TimeoutPolicy policy) { timeouts = policy; return this; }
        /// Edits a partial policy: only the fields touched are set; the rest inherit.
        public Builder timeouts(Consumer<TimeoutPolicy.Builder> edit) {
            var b = timeouts == null ? TimeoutPolicy.builder() : timeouts.toBuilder();
            edit.accept(b);
            timeouts = b.build();
            return this;
        }
        public Builder retry(RetryPolicy policy) { retry = policy; return this; }
        public Builder retry(Consumer<RetryPolicy.Builder> edit) {
            var b = retry == null ? RetryPolicy.builder() : retry.toBuilder();
            edit.accept(b);
            retry = b.build();
            return this;
        }
        public Builder cancel(CancelToken token) { cancel = token; return this; }
        /// Protected headers (credentials, content type, protocol versions) are rejected.
        public Builder header(String name, String value) { headers.put(Checks.header(name), Checks.headerValue(value, name)); return this; }
        public Builder tag(String key, String value) { tags.put(Checks.notBlank(key, "Tag key"), value); return this; }
        public Builder listener(LlmListener listener) { listeners.add(listener); return this; }
        public Builder strict() { return strict(true); }
        /// Explicit `false` overrides a strict wider scope.
        public Builder strict(boolean value) { strict = value; return this; }
        /// Replaces the set; an empty set clears an inherited one.
        public Builder strictCodes(Set<String> warningCodes) {
            warningCodes.forEach(code -> Checks.notBlank(code, "Warning code"));
            strictCodes = Set.copyOf(warningCodes);
            return this;
        }
        public Builder historyPolicy(HistoryPolicy policy) { historyPolicy = policy; return this; }
        public Builder provider(ProviderOptions options) { providerOptions.put(options.getClass(), options); return this; }
        public Builder providers(List<? extends ProviderOptions> replaced) { providerOptions.clear(); replaced.forEach(this::provider); return this; }
        public Builder payload(UnaryOperator<JsonObject> edit) { payload = edit; return this; }

        /// Sets a form value by its `FieldDescriptor` key from a canonical string: `.` decimals, ISO-8601 durations,
        /// enum names in any case. Invalid values are reported by `build()`.
        public Builder set(String key, String raw) {
            try {
                switch (key) {
                    case "temperature" -> temperature(Double.parseDouble(raw));
                    case "topP" -> topP(Double.parseDouble(raw));
                    case "topK" -> topK(Integer.parseInt(raw));
                    case "maxTokens" -> maxTokens(Integer.parseInt(raw));
                    case "seed" -> seed(Long.parseLong(raw));
                    case "stop" -> Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(this::stop);
                    case "reasoning" -> reasoning(ReasoningLevel.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
                    case "reasoningHandoff" -> reasoningHandoff(ReasoningHandoff.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
                    case "cacheRetention" -> cacheRetention(CacheRetention.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
                    case "responseCache" -> responseCache(CacheMode.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
                    case "parallelToolCalls" -> parallelToolCalls(bool(raw));
                    case "sessionId" -> sessionId(raw);
                    case "timeout" -> timeouts(t -> t.total(Duration.parse(raw)));
                    case "strict" -> strict(bool(raw));
                    case "strictCodes" -> strictCodes(Set.copyOf(Arrays.stream(raw.split(",")).map(String::strip).filter(c -> !c.isEmpty()).toList()));
                    case "historyPolicy" -> historyPolicy(HistoryPolicy.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
                    default -> problems.add(key + ": unknown option");
                }
            } catch (IllegalArgumentException e) {
                problems.add(key + ": " + e.getMessage());
            }
            return this;
        }

        /// @throws IllegalArgumentException listing every invalid `set(key, raw)` value
        public ChatOptions build() {
            if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid options: " + String.join("; ", problems));
            return new ChatOptions(this);
        }

        private static double range(double value, double min, double max, String what) {
            if (!(value >= min && value <= max)) throw new IllegalArgumentException(what + " must be in [" + min + ", " + max + "]: " + value);
            return value;
        }

        private static boolean bool(String raw) {
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("not a boolean: " + raw);
            };
        }

    }
}
