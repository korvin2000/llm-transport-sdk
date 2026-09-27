package net.ai.gate.internal.auth.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.interaction.AuthNotice;
import net.ai.gate.auth.interaction.AuthPrompt;
import net.ai.gate.auth.interaction.RedirectInteraction;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthConfig.Grant;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.lifecycle.CancelToken;

/// `OAuthAuth.standard(config)` for public clients: authorization code with PKCE S256 (RFC 7636) through a
/// `127.0.0.1` loopback listener (RFC 8252), an external redirect whose code the user pastes, or a web host's
/// `RedirectInteraction`; device authorization with polling (RFC 8628); refresh with token rotation (RFC 6749 §6);
/// revocation (RFC 7009); OpenAI's device dialect (`OAuthConfig.DeviceDialect`). `state` is checked on every
/// callback; the account comes from the configured access-token claim or else the ID token, and never changes on
/// refresh.
public final class StandardOAuth implements OAuthAuth {
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5), REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Token endpoints only: no redirects, so codes and tokens never leave the configured origin.
    private static final class Http {
        static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private final OAuthConfig config;

    public StandardOAuth(OAuthConfig config) { this.config = config; }

    public OAuthConfig config() { return config; }

    @Override public String name() { return "OAuth (" + config.tokenEndpoint().getHost() + ")"; }

    @Override public OAuthCredential login(AuthInteraction ui, CancelToken cancel) {
        if (config.clientSecretKey().isPresent() || config.grants().equals(Set.of(Grant.CLIENT_CREDENTIALS)))
            throw failure(ErrorCode.INVALID_REQUEST, "confidential clients and the client-credentials grant are not supported; use a public client", null);
        boolean browser = config.grants().contains(Grant.AUTHORIZATION_CODE), device = config.deviceAuthorizationEndpoint().isPresent();
        if (browser && device && !(ui instanceof RedirectInteraction)) {
            var choice = ui.prompt(new AuthPrompt.Select("How do you want to sign in?", List.of(
                    new AuthPrompt.Option("browser", "In this browser", Optional.empty()),
                    new AuthPrompt.Option("device", "With a code on another device", Optional.empty()))));
            browser = !choice.equals("device");
        }
        return browser ? authorizationCode(ui, cancel) : deviceCode(ui, cancel);
    }

    private OAuthCredential authorizationCode(AuthInteraction ui, CancelToken cancel) {
        var verifier = randomToken(32);
        var state = randomToken(16);
        var configured = config.redirectUri().orElse(null);
        Loopback listener = null;
        URI redirect;
        try {
            if (ui instanceof RedirectInteraction r) redirect = r.redirectUri();
            else if (configured != null && !Loopback.is(configured)) redirect = configured;
            else {
                listener = Loopback.open(configured != null ? configured.getPort() : config.loopbackPort().orElse(0),
                        configured != null && !configured.getPath().isEmpty() ? configured.getPath() : "/callback");
                redirect = listener.uri(configured != null ? configured.getHost() : "127.0.0.1");
            }
            var query = new LinkedHashMap<String, String>();
            query.put("response_type", "code");
            query.put("client_id", config.clientId());
            query.put(config.redirectParameter(), redirect.toString());
            query.put("code_challenge", base64Url(sha256(verifier)));
            query.put("code_challenge_method", "S256");
            query.put("state", state);
            if (!config.scopes().isEmpty()) query.put("scope", String.join(" ", config.scopes()));
            query.putAll(config.authorizationParameters());
            var separator = config.authorizationEndpoint().getRawQuery() == null ? "?" : "&";
            ui.notify(new AuthNotice.OpenUrl(URI.create(config.authorizationEndpoint() + separator + form(query)),
                    Optional.of("Sign in and approve access in the browser.")));
            var answer = listener != null ? listener.await(cancel, LOGIN_TIMEOUT)
                    : ui.prompt(new AuthPrompt.Code("Paste the authorization code or the full redirect URL"));
            var code = code(answer, state);
            return token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirect.toString(),
                    "client_id", config.clientId(), "code_verifier", verifier), null, ErrorCode.LOGIN_CANCELLED);
        } catch (IOException e) {
            throw failure(ErrorCode.LOGIN_CANCELLED, e.getMessage(), e);
        } finally {
            if (listener != null) listener.close();
        }
    }

    /// Accepts a callback URL, `code#state` (as some consoles display it) or a bare code.
    private static String code(String answer, String state) {
        var value = answer.strip();
        Map<String, String> params;
        boolean callback = value.contains("?") || value.startsWith("http") || value.contains("code=") || value.contains("error=");
        if (value.contains("?") || value.startsWith("http")) params = query(URI.create(value).getRawQuery());
        else if (callback) params = query(value);
        else {
            var hash = value.indexOf('#');
            params = hash < 0 ? Map.of("code", value) : Map.of("code", value.substring(0, hash), "state", value.substring(hash + 1));
        }
        if ((callback || params.containsKey("state")) && !state.equals(params.get("state")))
            throw failure(ErrorCode.LOGIN_CANCELLED, "the callback state does not match this login", null);
        if (params.containsKey("error"))
            throw failure(ErrorCode.LOGIN_CANCELLED, params.get("error") + ": " + params.getOrDefault("error_description", "no details"), null);
        var code = params.get("code");
        if (code == null || code.isBlank()) throw failure(ErrorCode.LOGIN_CANCELLED, "no authorization code was returned", null);
        return code;
    }

    /// RFC 8628, or OpenAI's dialect: JSON requests, a fixed verification page, `403`/`404` while pending, and an
    /// authorization code with its verifier to exchange instead of tokens.
    private OAuthCredential deviceCode(AuthInteraction ui, CancelToken cancel) {
        var endpoint = config.deviceAuthorizationEndpoint().orElseThrow();
        boolean openAi = config.deviceDialect() == OAuthConfig.DeviceDialect.OPENAI, json = openAi || config.jsonTokenRequests();
        var request = new LinkedHashMap<String, String>();
        request.put("client_id", config.clientId());
        if (!openAi && !config.scopes().isEmpty()) request.put("scope", String.join(" ", config.scopes()));
        var grant = post(endpoint, request, json, ErrorCode.LOGIN_CANCELLED);
        var verification = openAi ? endpoint.resolve("/codex/device") : URI.create(grant.optString("verification_uri")
                .or(() -> grant.optString("verification_url")).orElseThrow(() ->
                        failure(ErrorCode.LOGIN_CANCELLED, "the device authorization response has no verification URI", null)));
        var expiresAt = Instant.now().plusSeconds(grant.optLong("expires_in").orElse(900));
        ui.notify(new AuthNotice.DeviceCode(verification, grant.string("user_code"), expiresAt));
        grant.optString("verification_uri_complete").ifPresent(u -> ui.notify(new AuthNotice.OpenUrl(URI.create(u), Optional.empty())));
        long interval = Math.max(1, grant.optLong("interval").orElseGet(() -> grant.optString("interval").map(v -> Long.parseLong(v.strip())).orElse(5L)));
        var poll = openAi ? Map.of("device_auth_id", grant.string("device_auth_id"), "user_code", grant.string("user_code"))
                : Map.of("grant_type", DEVICE_GRANT, "device_code", grant.string("device_code"), "client_id", config.clientId());
        while (Instant.now().isBefore(expiresAt)) {
            sleep(Duration.ofSeconds(interval), cancel);
            var reply = send(openAi ? endpoint.resolve("token") : config.tokenEndpoint(), poll, json, ErrorCode.LOGIN_CANCELLED);
            if (reply.status() / 100 == 2) return !openAi ? credential(reply.body(), null)
                    : token(Map.of("grant_type", "authorization_code", "code", reply.body().string("authorization_code"),
                            "redirect_uri", endpoint.resolve("/deviceauth/callback").toString(), "client_id", config.clientId(),
                            "code_verifier", reply.body().string("code_verifier")), null, ErrorCode.LOGIN_CANCELLED);
            var error = reply.body().optString("error").or(() -> reply.body().object("error").optString("code")).orElse("");
            switch (error) {
                case "authorization_pending", "deviceauth_authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                case "access_denied" -> throw failure(ErrorCode.LOGIN_CANCELLED, "access was denied", null);
                default -> {
                    if (!openAi || reply.status() != 403 && reply.status() != 404) throw failure(ErrorCode.LOGIN_CANCELLED, describe(reply), null);
                }
            }
        }
        throw failure(ErrorCode.LOGIN_CANCELLED, "the device code expired before it was approved", null);
    }

    /// Rotation: a response without a new refresh token keeps the old one; the account stays bound.
    @Override public OAuthCredential refresh(OAuthCredential credential) {
        checkBinding(credential);
        var refresh = credential.refresh().orElseThrow(() -> failure(ErrorCode.REFRESH_FAILED, "no refresh token is stored", null));
        var request = new LinkedHashMap<String, String>();
        request.put("grant_type", "refresh_token");
        request.put("refresh_token", refresh.reveal());
        request.put("client_id", config.clientId());
        return token(request, credential, ErrorCode.REFRESH_FAILED);
    }

    @Override public ResolvedAuth toAuth(OAuthCredential credential) {
        checkBinding(credential);
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + credential.access().reveal());
        config.accountHeader().ifPresent(name -> credential.account().ifPresent(account -> headers.put(name, account)));
        return ResolvedAuth.headers(headers, "OAuth");
    }

    /// The refresh token when there is one (revoking it ends the grant), else the access token. A credential of
    /// another issuer or client cannot be revoked here: nothing is sent, and the caller's local logout proceeds.
    @Override public void revoke(OAuthCredential credential) {
        var endpoint = config.revocationEndpoint().orElse(null);
        if (endpoint == null || !bound(credential)) return;
        var token = credential.refresh().orElse(credential.access());
        post(endpoint, Map.of("token", token.reveal(), "token_type_hint", credential.refresh().isPresent() ? "refresh_token" : "access_token",
                "client_id", config.clientId()), config.jsonTokenRequests(), ErrorCode.INVALID_REQUEST);
    }

    private OAuthCredential token(Map<String, String> request, @Nullable OAuthCredential previous, ErrorCode onFailure) {
        return credential(post(config.tokenEndpoint(), request, config.jsonTokenRequests(), onFailure), previous);
    }

    private OAuthCredential credential(JsonObject body, @Nullable OAuthCredential previous) {
        var mapper = config.tokenResponseMapper().orElse(null);
        if (mapper != null) {
            var mapped = mapper.apply(body);
            checkBinding(mapped);
            return mapped;
        }
        var access = body.optString("access_token").orElseThrow(() -> failure(ErrorCode.REFRESH_FAILED, "the token response has no access_token", null));
        var account = account(body).orElse(previous == null ? null : previous.account().orElse(null));
        if (account == null && !config.accountClaim().isEmpty())
            throw failure(ErrorCode.LOGIN_CANCELLED, "the access token names no account (" + String.join(" / ", config.accountClaim()) + ")", null);
        var b = OAuthCredential.builder(Secret.of(access), issuer(), config.clientId())
                .refresh(body.optString("refresh_token").map(Secret::of).orElse(previous == null ? null : previous.refresh().orElse(null)))
                .expiresAt(body.optLong("expires_in").isPresent() ? Instant.now().plusSeconds(body.optLong("expires_in").getAsLong()) : null)
                .account(account)
                .scopes(body.optString("scope").map(s -> Set.of(s.split(" "))).orElse(previous == null ? config.scopes() : previous.scopes()));
        return previous == null ? b.build() : b.extra(previous.extra()).build();
    }

    /// The configured claim of the access token, else `email` or `sub` of the ID token.
    private Optional<String> account(JsonObject body) {
        var path = config.accountClaim();
        if (path.isEmpty()) return claims(body.optString("id_token")).flatMap(c -> c.optString("email").or(() -> c.optString("sub")));
        return claims(body.optString("access_token")).flatMap(c -> {
            for (var name : path.subList(0, path.size() - 1)) c = c.object(name);
            return c.optString(path.getLast()).filter(s -> !s.isEmpty());
        });
    }

    /// The claims of a JWT; its signature is not checked — the token came straight from the issuer over TLS.
    private static Optional<JsonObject> claims(Optional<String> token) {
        return token.map(t -> t.split("\\.")).filter(p -> p.length == 3).flatMap(p -> {
            try {
                return Json.parse(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)) instanceof JsonObject o
                        ? Optional.of(o) : Optional.empty();
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    private String issuer() { return config.authorizationEndpoint().getScheme() + "://" + config.authorizationEndpoint().getAuthority(); }

    private boolean bound(OAuthCredential credential) {
        return issuer().equals(credential.issuer()) && config.clientId().equals(credential.clientId());
    }

    private void checkBinding(OAuthCredential credential) {
        if (!bound(credential))
            throw failure(ErrorCode.LOGIN_REQUIRED, "the stored credential belongs to another issuer or client; sign in again", null);
    }

    private record Reply(int status, JsonObject body) { }

    private JsonObject post(URI endpoint, Map<String, String> request, boolean json, ErrorCode onFailure) {
        var reply = send(endpoint, request, json, onFailure);
        if (reply.status() / 100 != 2) throw failure(onFailure, describe(reply), null);
        return reply.body();
    }

    private Reply send(URI endpoint, Map<String, String> request, boolean json, ErrorCode onFailure) {
        var body = json ? Json.valueOf(request).toJson() : form(request);
        var http = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                .header("Content-Type", json ? "application/json" : "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = Http.CLIENT.send(http, HttpResponse.BodyHandlers.ofString());
            var text = response.body();
            JsonObject reply;
            try {
                reply = text.isBlank() ? Json.object() : Json.parse(text) instanceof JsonObject o ? o : Json.object("error", text);
            } catch (IllegalArgumentException e) {
                reply = Json.object("error", "invalid_response", "error_description", text.substring(0, Math.min(200, text.length())));
            }
            return new Reply(response.statusCode(), reply);
        } catch (IOException e) {
            throw failure(onFailure, endpoint.getHost() + " is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(ErrorCode.LOGIN_CANCELLED, "interrupted", e);
        }
    }

    private static String describe(Reply reply) {
        var body = reply.body();
        var error = body.optString("error").or(() -> body.object("error").optString("message")).orElse("HTTP " + reply.status());
        return body.optString("error_description").map(d -> error + ": " + d).orElse(error);
    }

    private static void sleep(Duration duration, CancelToken cancel) {
        var until = Instant.now().plus(duration);
        try {
            while (Instant.now().isBefore(until)) {
                if (cancel.isCancelled()) throw failure(ErrorCode.LOGIN_CANCELLED, "cancelled", null);
                Thread.sleep(Math.min(250, Math.max(1, Duration.between(Instant.now(), until).toMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(ErrorCode.LOGIN_CANCELLED, "interrupted", e);
        }
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    static Map<String, String> query(@Nullable String raw) {
        var params = new LinkedHashMap<String, String>();
        if (raw == null) return params;
        for (var pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String randomToken(int bytes) {
        var data = new byte[bytes];
        RANDOM.nextBytes(data);
        return base64Url(data);
    }

    private static String base64Url(byte[] data) { return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JDK has SHA-256
        }
    }

    static AuthenticationException failure(ErrorCode code, @Nullable String reason, @Nullable Throwable cause) {
        return new AuthenticationException(LlmException.Details.builder(code, "OAuth: " + reason).build(), cause);
    }
}
