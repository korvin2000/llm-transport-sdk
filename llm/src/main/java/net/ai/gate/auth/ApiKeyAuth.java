package net.ai.gate.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.interaction.AuthPrompt;
import net.ai.gate.config.FieldDescriptor;
import net.ai.gate.internal.auth.KeyAuth;
import org.jspecify.annotations.Nullable;

/// **SPI** (provider). How a provider accepts keys: presets use the factories; unusual schemes implement this.
/// Thread-safe. `resolve` and `configured` are local — except that a dynamic token supplier may fetch a token.
public interface ApiKeyAuth {
    /// `Anthropic API key`.
    String name();

    /// Stored credential first, then environment and ambient sources; empty means not configured.
    Optional<ResolvedAuth> resolve(AuthInput input);

    /// Side-effect-free check for `status()` and `available()`: never fetches tokens or touches the network.
    default boolean configured(AuthInput input) { return input.stored().isPresent(); }

    /// The key and provider settings, for static "connect provider" forms. The key's field key is `apiKey`.
    default List<FieldDescriptor> fields() {
        return List.of(FieldDescriptor.builder("apiKey", FieldDescriptor.Kind.SECRET).label(name()).required().group("Connection").build());
    }

    /// Prompts for `fields()`; empty when the user leaves a required field blank.
    default Optional<ApiKeyCredential> login(AuthInteraction ui) {
        var settings = new LinkedHashMap<String, String>();
        @Nullable Secret key = null;
        for (var field : fields()) {
            var answer = (field.kind() == FieldDescriptor.Kind.SECRET
                    ? ui.prompt(new AuthPrompt.SecretText(field.label()))
                    : ui.prompt(new AuthPrompt.Text(field.label(), field.defaultValue()))).strip();
            if (answer.isEmpty()) {
                if (field.required()) return Optional.empty();
            } else if (field.key().equals("apiKey")) {
                key = Secret.of(answer);
            } else {
                settings.put(field.key(), answer);
            }
        }
        return key == null ? Optional.empty() : Optional.of(new ApiKeyCredential(key, settings));
    }

    /// `Authorization: Bearer <key>`, the key from the store or the first variable set.
    static ApiKeyAuth bearer(String name, String... envVars) { return KeyAuth.bearer(name, List.of(envVars)); }

    /// The key as the value of `header`, e.g. `x-api-key`.
    static ApiKeyAuth header(String name, String header, String... envVars) { return KeyAuth.header(name, header, List.of(envVars)); }

    /// Keyless local servers.
    static ApiKeyAuth none() { return KeyAuth.none(); }

    /// Short-lived tokens from cloud identity (Entra ID, Google ADC): cached until expiry minus a skew, single-flight.
    static ApiKeyAuth dynamic(String name, TokenSupplier supplier) { return KeyAuth.dynamic(name, supplier); }
}
