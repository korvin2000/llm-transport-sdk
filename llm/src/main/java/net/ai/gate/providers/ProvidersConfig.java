package net.ai.gate.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.ai.gate.Provider;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.Model;
import net.ai.gate.spi.protocol.ApiCompat;
import net.ai.gate.vendors.anthropic.Anthropic;
import net.ai.gate.vendors.openai.OpenAiCompatible;
import org.jspecify.annotations.Nullable;

/// Versioned, secret-free provider configuration (`ai-gate.providers/1`). Each entry names a preset or a template
/// (`openai-compatible`, `anthropic-compatible`, which require `baseUrl`) and states only differences: `id`, `name`,
/// `baseUrl`, `headers`, `models`, `defaults`. There is no credential field — credentials live in a
/// `CredentialStore` under the provider id. `providers` must be an array of objects; duplicate ids fail. Unknown
/// presets fail naming the known ones; unknown fields fail unless prefixed `x-`; no classes are loaded by name.
///
/// ```json
/// { "schema": "ai-gate.providers/1",
///   "providers": [ { "id": "corp-gw", "preset": "openai-compatible", "baseUrl": "https://llm-gw.corp.example/v1",
///                    "headers": { "X-Tenant": "team-42" }, "models": [ { "id": "gpt-5.1", "contextWindow": 400000 } ],
///                    "defaults": { "schema": "ai-gate.options/1", "cacheRetention": "none" } },
///                  { "id": "work-anthropic", "preset": "anthropic" } ] }
/// ```
///
/// **Pending (roadmap slice 1):** `compat` flags in the format — a provider whose `compat()` differs from its
/// preset's or template's cannot yet be written, and a `compat` member fails on read.
public final class ProvidersConfig {
    public static final String SCHEMA = "ai-gate.providers/1";

    private static final Set<String> FIELDS = Set.of("id", "preset", "name", "baseUrl", "headers", "models", "defaults", "compat");
    private static final Set<String> TEMPLATES = Set.of("openai-compatible", "anthropic-compatible");

    private ProvidersConfig() { }

    /// @throws IllegalArgumentException listing every invalid entry and field
    public static List<Provider> read(String json, List<Provider> presets) {
        if (!(Json.parse(json) instanceof JsonObject document) || !SCHEMA.equals(text(document, "schema")))
            throw new IllegalArgumentException("Not a " + SCHEMA + " document");
        var problems = new ArrayList<String>();
        var providers = new ArrayList<Provider>();
        var providersValue = document.get("providers").orElse(null);
        var entries = List.<JsonValue>of();
        if (providersValue instanceof JsonArray a) entries = a.values();
        else if (providersValue != null) problems.add("'providers' must be an array");
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof JsonObject entry)) {
                problems.add("providers[" + i + "]: expected an object");
                continue;
            }
            try {
                providers.add(read(entry, presets));
            } catch (RuntimeException e) {
                problems.add("providers[" + i + "]: " + e.getMessage());
            }
        }
        var seen = new HashSet<String>();
        var duplicates = new LinkedHashSet<String>();
        for (var p : providers) if (!seen.add(p.id())) duplicates.add(p.id());
        if (!duplicates.isEmpty()) problems.add("duplicate provider id(s): " + duplicates);
        if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid provider configuration: " + String.join("; ", problems));
        return List.copyOf(providers);
    }

    public static String write(List<Provider> providers) {
        var entries = new ArrayList<JsonValue>();
        for (var provider : providers) entries.add(write(provider));
        return Json.object("schema", SCHEMA, "providers", entries).toPrettyJson();
    }

    private static Provider read(JsonObject entry, List<Provider> presets) {
        for (var field : entry.members().keySet())
            if (!FIELDS.contains(field) && !field.startsWith("x-")) throw new IllegalArgumentException("unknown field '" + field + "'");
        if (entry.get("compat").isPresent()) throw new IllegalArgumentException("'compat' is not supported in " + SCHEMA + " yet");
        var preset = text(entry, "preset");
        if (preset == null) throw new IllegalArgumentException("'preset' is required");
        var explicitId = text(entry, "id");
        var id = explicitId != null ? explicitId : preset;
        var baseUrl = text(entry, "baseUrl");
        var builder = template(preset, id, baseUrl, presets).toBuilder().id(id);
        if (baseUrl != null) builder.baseUrl(URI.create(baseUrl));
        var name = text(entry, "name");
        if (name != null) builder.name(name);
        entry.object("headers").members().forEach((k, v) -> builder.header(k, ((JsonString) v).value()));
        if (entry.get("models").orElse(null) instanceof JsonArray models)
            for (var model : models.values()) builder.model(Model.fromJson(((JsonObject) model).with("provider", id)));
        if (entry.get("defaults").orElse(null) instanceof JsonObject defaults) builder.defaults(ChatOptions.fromJson(defaults));
        return builder.build();
    }

    private static Provider template(String preset, String id, @Nullable String baseUrl, List<Provider> presets) {
        if (TEMPLATES.contains(preset)) {
            if (baseUrl == null) throw new IllegalArgumentException("template '" + preset + "' requires 'baseUrl'");
            return preset.equals("openai-compatible") ? OpenAiCompatible.custom(id, URI.create(baseUrl)) : Anthropic.compatible(id, URI.create(baseUrl));
        }
        return presets.stream().filter(p -> p.preset().orElse(p.id()).equals(preset)).findFirst().orElseThrow(() ->
                new IllegalArgumentException("unknown preset '" + preset + "'; known: "
                        + presets.stream().map(p -> p.preset().orElse(p.id())).toList() + " and templates " + TEMPLATES));
    }

    /// @throws IllegalArgumentException naming `provider` when its `compat()` differs from its preset's or
    /// template's and so cannot yet be written (`compat` is pending, see the class Javadoc)
    private static JsonObject write(Provider provider) {
        var preset = provider.preset().orElseThrow(() -> new IllegalArgumentException(
                "Provider '" + provider.id() + "' is not derived from a preset or template and cannot be written"));
        var base = TEMPLATES.contains(preset) ? null
                : Providers.presets().stream().filter(p -> p.preset().orElse(p.id()).equals(preset)).findFirst().orElse(null);
        // For 'defaults'/'compat' only: templates need no lookup among presets, so instantiate one with this
        // provider's own id and baseUrl to compare against, even though 'base' itself stays null for them
        // (baseUrl must always be written for a template, since reading one back requires it).
        var comparisonBase = base != null ? base
                : TEMPLATES.contains(preset) ? template(preset, provider.id(), provider.baseUrl().toString(), List.of()) : null;
        var baseCompat = comparisonBase == null ? Optional.<ApiCompat>empty() : comparisonBase.compat();
        if (!provider.compat().equals(baseCompat)) throw new IllegalArgumentException("Provider '" + provider.id()
                + "' has compat flags that differ from its preset or template and cannot yet be written (" + SCHEMA + ")");
        var entry = new LinkedHashMap<String, JsonValue>();
        entry.put("id", Json.valueOf(provider.id()));
        entry.put("preset", Json.valueOf(preset));
        if (!provider.name().equals(base == null ? provider.id() : base.name())) entry.put("name", Json.valueOf(provider.name()));
        if (base == null || !base.baseUrl().equals(provider.baseUrl())) entry.put("baseUrl", Json.valueOf(provider.baseUrl()));
        var headers = new LinkedHashMap<String, String>(provider.headers());
        if (base != null) base.headers().forEach(headers::remove);
        if (!headers.isEmpty()) entry.put("headers", Json.valueOf(headers));
        var models = provider.models().stream().filter(m -> m.source() == Model.Source.CUSTOM)
                .map(m -> (JsonValue) m.toJson().without("provider").without("source")).toList();
        if (!models.isEmpty()) entry.put("models", JsonArray.of(models));
        // ChatOptions has no equals(); compare the canonical JSON form instead of object identity.
        var baseDefaults = comparisonBase == null ? ChatOptions.none() : comparisonBase.defaults();
        if (!provider.defaults().toJson().equals(baseDefaults.toJson())) entry.put("defaults", provider.defaults().toJson());
        return JsonObject.of(entry);
    }

    private static @Nullable String text(JsonObject object, String name) {
        return object.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }
}
