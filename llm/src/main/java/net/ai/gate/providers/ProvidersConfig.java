package net.ai.gate.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.ai.gate.vendors.anthropic.AnthropicCompat;
import net.ai.gate.vendors.openai.OpenAiCompatible;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat;
import net.ai.gate.vendors.openai.OpenAiResponsesCompat;
import org.jspecify.annotations.Nullable;

/// Versioned, secret-free provider configuration (`ai-gate.providers/1`). Each entry names a preset or a template
/// (`openai-compatible`, `anthropic-compatible`, which require `baseUrl`) and states only differences: `id`, `name`,
/// `baseUrl`, `headers`, `models`, `defaults`, `compat`. There is no credential field — credentials live in a
/// `CredentialStore` under the provider id. `providers` must be an array of objects; duplicate ids fail. Unknown
/// presets fail naming the known ones; unknown fields fail unless prefixed `x-`; no classes are loaded by name.
///
/// `compat` holds the flags that differ from the preset's, in the canonical form of the compat type of the default
/// API (`openai-completions`: `OpenAiCompletionsCompat`, `openai-responses`: `OpenAiResponsesCompat`,
/// `anthropic-messages`: `AnthropicCompat`); a model entry may carry its own for its API. Flags merge onto the preset's
/// field by field, so a configuration can set flags but not unset one a preset sets.
///
/// ```json
/// { "schema": "ai-gate.providers/1",
///   "providers": [ { "id": "corp-gw", "preset": "openai-compatible", "baseUrl": "https://llm-gw.corp.example/v1",
///                    "headers": { "X-Tenant": "team-42" }, "models": [ { "id": "gpt-5.1", "contextWindow": 400000 } ],
///                    "defaults": { "schema": "ai-gate.options/1", "cacheRetention": "none" },
///                    "compat": { "reasoningFormat": "qwen", "developerRole": false } },
///                  { "id": "work-anthropic", "preset": "anthropic" } ] }
/// ```
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
        var preset = text(entry, "preset");
        if (preset == null) throw new IllegalArgumentException("'preset' is required");
        var explicitId = text(entry, "id");
        var id = explicitId != null ? explicitId : preset;
        var baseUrl = text(entry, "baseUrl");
        var base = template(preset, id, baseUrl, presets);
        var api = base.defaultApi().id();
        var builder = base.toBuilder().id(id);
        if (baseUrl != null) builder.baseUrl(URI.create(baseUrl));
        var name = text(entry, "name");
        if (name != null) builder.name(name);
        entry.object("headers").members().forEach((k, v) -> builder.header(k, ((JsonString) v).value()));
        if (entry.get("models").orElse(null) instanceof JsonArray models)
            for (var value : models.values()) {
                var json = (JsonObject) value;
                var model = Model.fromJson(json.without("compat").with("provider", id));
                if (json.get("compat").orElse(null) instanceof JsonValue flags)
                    model = model.toBuilder().compat(compat(model.api().orElse(api), flags)).build();
                builder.model(model);
            }
        if (entry.get("defaults").orElse(null) instanceof JsonObject defaults) builder.defaults(ChatOptions.fromJson(defaults));
        if (entry.get("compat").orElse(null) instanceof JsonValue flags) builder.compat(compat(api, flags));
        return builder.build();
    }

    /// The compat type of an API — a plain switch, no registry.
    private static ApiCompat compat(String api, JsonValue flags) {
        if (!(flags instanceof JsonObject json)) throw new IllegalArgumentException("'compat' must be an object");
        return switch (api) {
            case "openai-completions" -> OpenAiCompletionsCompat.fromJson(json);
            case "openai-responses" -> OpenAiResponsesCompat.fromJson(json);
            case "anthropic-messages" -> AnthropicCompat.fromJson(json);
            default -> throw new IllegalArgumentException("'compat' is not defined for the API " + api);
        };
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

    /// @throws IllegalArgumentException naming `provider` when it cannot be read back as it is: no preset, or compat
    /// flags of another type than its API reads, or unsetting a flag of the preset
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
        var api = (comparisonBase != null ? comparisonBase : provider).defaultApi().id();
        var compat = difference(provider.id(), provider.compat().orElse(null), comparisonBase == null ? null : comparisonBase.compat().orElse(null), api);
        var entry = new LinkedHashMap<String, JsonValue>();
        entry.put("id", Json.valueOf(provider.id()));
        entry.put("preset", Json.valueOf(preset));
        if (!provider.name().equals(base == null ? provider.id() : base.name())) entry.put("name", Json.valueOf(provider.name()));
        if (base == null || !base.baseUrl().equals(provider.baseUrl())) entry.put("baseUrl", Json.valueOf(provider.baseUrl()));
        var headers = new LinkedHashMap<String, String>(provider.headers());
        if (base != null) base.headers().forEach(headers::remove);
        if (!headers.isEmpty()) entry.put("headers", Json.valueOf(headers));
        var models = provider.models().stream().filter(m -> m.source() == Model.Source.CUSTOM).map(m -> {
            var json = m.toJson().without("provider").without("source");
            var flags = difference(provider.id() + "' model '" + m.id(), m.compat().orElse(null), null, m.api().orElse(api));
            return (JsonValue) (flags == null ? json : json.with("compat", flags));
        }).toList();
        if (!models.isEmpty()) entry.put("models", JsonArray.of(models));
        // ChatOptions has no equals(); compare the canonical JSON form instead of object identity.
        var baseDefaults = comparisonBase == null ? ChatOptions.none() : comparisonBase.defaults();
        if (!provider.defaults().toJson().equals(baseDefaults.toJson())) entry.put("defaults", provider.defaults().toJson());
        if (compat != null) entry.put("compat", compat);
        return JsonObject.of(entry);
    }

    /// The flags of `compat` that `base` does not already set to the same value; `null` when there are none.
    private static @Nullable JsonObject difference(String owner, @Nullable ApiCompat compat, @Nullable ApiCompat base, String api) {
        var mine = compat == null ? JsonObject.of(Map.of()) : compat.toJson();
        var theirs = base == null ? JsonObject.of(Map.of()) : base.toJson();
        if (compat != null && !compat.api().equals(api) || base != null && compat != null && base.getClass() != compat.getClass())
            throw new IllegalArgumentException("Provider '" + owner + "' has compat flags for " + (compat == null ? "?" : compat.api())
                    + ", but its API " + api + " reads other ones");
        for (var name : theirs.members().keySet())
            if (!mine.members().containsKey(name))
                throw new IllegalArgumentException("Provider '" + owner + "' unsets the compat flag '" + name + "' of its preset, which "
                        + SCHEMA + " cannot express");
        var changed = new LinkedHashMap<String, JsonValue>(mine.members());
        changed.entrySet().removeIf(e -> e.getValue().equals(theirs.members().get(e.getKey())));
        return changed.isEmpty() ? null : JsonObject.of(changed);
    }

    private static @Nullable String text(JsonObject object, String name) {
        return object.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }
}
