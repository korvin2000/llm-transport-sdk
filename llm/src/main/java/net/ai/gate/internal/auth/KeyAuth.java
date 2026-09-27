package net.ai.gate.internal.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.ApiKeyCredential;
import net.ai.gate.auth.AuthInput;
import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.TokenSupplier;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.config.FieldDescriptor;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import org.jspecify.annotations.Nullable;

/// The shipped key strategies: bearer, named header, keyless and dynamic tokens. Thread-safe; dynamic tokens are
/// cached until expiry minus a skew and fetched single-flight.
public final class KeyAuth implements ApiKeyAuth {
    private enum Kind { BEARER, HEADER, NONE, DYNAMIC }

    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final KeyAuth NONE = new KeyAuth("No key", Kind.NONE, "", List.of(), null);

    private final String name, header;
    private final Kind kind;
    private final List<String> envVars;
    private final @Nullable TokenSupplier supplier;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private TokenSupplier.@Nullable AccessToken cached; // guarded by tokenLock

    private KeyAuth(String name, Kind kind, String header, List<String> envVars, @Nullable TokenSupplier supplier) {
        this.name = name; this.kind = kind; this.header = header; this.envVars = List.copyOf(envVars); this.supplier = supplier;
    }

    public static KeyAuth bearer(String name, List<String> envVars) { return new KeyAuth(name, Kind.BEARER, "Authorization", envVars, null); }
    public static KeyAuth header(String name, String header, List<String> envVars) { return new KeyAuth(name, Kind.HEADER, header, envVars, null); }
    public static KeyAuth none() { return NONE; }
    public static KeyAuth dynamic(String name, TokenSupplier supplier) { return new KeyAuth(name, Kind.DYNAMIC, "Authorization", List.of(), supplier); }

    @Override public String name() { return name; }
    /// The variables read, in order; named in "login required" messages.
    public List<String> envVars() { return envVars; }
    public boolean keyless() { return kind == Kind.NONE; }

    @Override public Optional<ResolvedAuth> resolve(AuthInput input) {
        return switch (kind) {
            case NONE -> Optional.of(ResolvedAuth.none("keyless"));
            case DYNAMIC -> Optional.of(ResolvedAuth.headers(Map.of(header, "Bearer " + token().reveal()), name));
            case BEARER, HEADER -> key(input).map(k -> ResolvedAuth.headers(Map.of(header, value(k.secret)), k.source));
        };
    }

    /// Ambient identity (dynamic) counts as configured: checking it would mean fetching a token.
    @Override public boolean configured(AuthInput input) { return kind == Kind.NONE || kind == Kind.DYNAMIC || key(input).isPresent(); }

    /// Where the key would come from, without resolving it.
    public Optional<String> source(AuthInput input) {
        return switch (kind) {
            case NONE -> Optional.of("keyless");
            case DYNAMIC -> Optional.of(name);
            case BEARER, HEADER -> key(input).map(k -> k.source);
        };
    }

    /// Credentials as environment-variable placeholders, for `preview()` and `toCurl()`.
    public Map<String, String> placeholders() {
        var variable = "$" + (envVars.isEmpty() ? "API_KEY" : envVars.getFirst());
        return switch (kind) {
            case NONE -> Map.of();
            case DYNAMIC -> Map.of(header, "Bearer $ACCESS_TOKEN");
            case BEARER, HEADER -> Map.of(header, value(variable));
        };
    }

    @Override public List<FieldDescriptor> fields() { return kind == Kind.BEARER || kind == Kind.HEADER ? ApiKeyAuth.super.fields() : List.of(); }

    @Override public Optional<ApiKeyCredential> login(AuthInteraction ui) {
        return kind == Kind.BEARER || kind == Kind.HEADER ? ApiKeyAuth.super.login(ui) : Optional.empty();
    }

    private String value(String key) { return kind == Kind.HEADER ? key : "Bearer " + key; }

    private record Key(String secret, String source) { }

    private Optional<Key> key(AuthInput input) {
        if (input.stored().isPresent()) return Optional.of(new Key(input.stored().get().key().reveal(), "stored credential"));
        for (var variable : envVars) {
            var value = input.environment().get(variable);
            if (value.isPresent()) return Optional.of(new Key(value.get(), variable));
        }
        return Optional.empty();
    }

    private Secret token() {
        try {
            tokenLock.lockInterruptibly();
            try {
                var now = Instant.now();
                if (cached != null && cached.expiresAt().map(e -> now.plus(SKEW).isBefore(e)).orElse(true)) return cached.token();
                cached = Objects.requireNonNull(supplier).fetch();
                return cached.token();
            } finally {
                tokenLock.unlock();
            }
        } catch (InterruptedException | IOException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AuthenticationException(LlmException.Details.builder(ErrorCode.REFRESH_FAILED,
                    name + ": fetching an access token failed: " + e.getMessage()).build(), e);
        }
    }

    @Override public String toString() { return "KeyAuth[" + name + ", " + kind + (envVars.isEmpty() ? "" : ", " + envVars) + "]"; }
}
