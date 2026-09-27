package net.ai.gate.internal.auth.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;

import net.ai.gate.auth.Secret;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.interaction.AuthNotice;
import net.ai.gate.auth.interaction.AuthPrompt;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.lifecycle.CancelToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The flows against an in-process issuer: PKCE through the loopback listener, pasted codes with state checks,
/// device authorization with polling, refresh rotation and revocation — and the ChatGPT (Codex) variants: a fixed
/// loopback redirect, extra authorization parameters, the account from an access-token claim, OpenAI's device dialect.
class StandardOAuthTest {
    private HttpServer issuer;
    private final Map<String, Map<String, String>> requests = new ConcurrentHashMap<>();
    private final Map<String, String> challenges = new ConcurrentHashMap<>();
    private final AtomicInteger polls = new AtomicInteger(), codexPolls = new AtomicInteger();
    private URI base;

    @BeforeEach
    void start() throws IOException {
        issuer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var form = body.startsWith("{") ? ((JsonObject) Json.parse(body)).members().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> ((JsonString) e.getValue()).value())) : StandardOAuth.query(body);
            var path = exchange.getRequestURI().getPath();
            requests.put(path + ":" + form.getOrDefault("grant_type", ""), form);
            boolean codex = "app-codex".equals(form.get("client_id"));
            var reply = switch (path + ":" + form.getOrDefault("grant_type", "")) {
                case "/token:authorization_code" -> !verifies(form) ? "{\"error\":\"invalid_grant\"}"
                        : codex ? tokens(jwt("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-7\"}}"), "refresh-c") : tokens("access-1", "refresh-1");
                case "/token:refresh_token" -> codex ? tokens(jwt("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-7\"}}"), "refresh-c2")
                        : "{\"access_token\":\"access-2\",\"expires_in\":3600}";
                case "/api/accounts/deviceauth/usercode:" -> "{\"device_auth_id\":\"dev-1\",\"user_code\":\"CODE-12\",\"interval\":\"1\"}";
                case "/api/accounts/deviceauth/token:" -> codexPolls.incrementAndGet() < 2 ? "" : "{\"authorization_code\":\"code-d\",\"code_verifier\":\"verifier-d\"}";
                case "/device:" -> "{\"device_code\":\"dev\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://issuer.example/device\",\"interval\":1,\"expires_in\":30}";
                case "/token:urn:ietf:params:oauth:grant-type:device_code" -> polls.incrementAndGet() < 2 ? "{\"error\":\"authorization_pending\"}" : tokens("access-d", null);
                case "/revoke:" -> "";
                default -> "{\"error\":\"unexpected " + path + "\"}";
            };
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            int status = reply.isEmpty() && path.endsWith("/deviceauth/token") ? 404 : reply.contains("\"error\"") ? 400 : 200;   // 404: not approved yet
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        issuer.start();
        base = URI.create("http://127.0.0.1:" + issuer.getAddress().getPort());
    }

    @AfterEach
    void stop() { issuer.stop(0); }

    private boolean verifies(Map<String, String> form) { return challenge(form.get("code_verifier")).equals(challenges.get(form.get("code"))); }

    private static String challenge(String verifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String jwt(String claims) {
        return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".s";
    }

    private static String tokens(String access, String refresh) {
        var claims = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"email\":\"ada@example.com\"}".getBytes(StandardCharsets.UTF_8));
        return Json.object("access_token", access, "refresh_token", refresh, "expires_in", 3600, "id_token", "h." + claims + ".s").toJson();
    }

    private OAuthConfig.Builder config() {
        return OAuthConfig.builder("client-1").authorizationEndpoint(base.resolve("/authorize")).tokenEndpoint(base.resolve("/token"))
                .revocationEndpoint(base.resolve("/revoke")).scopes(Set.of("chat"));
    }

    /// Plays the browser: approves the authorization request by calling the redirect with a code.
    private AuthInteraction browser(String stateOverride) {
        return new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) { throw new AssertionError("no prompt expected: " + prompt); }

            @Override public void notify(AuthNotice notice) {
                if (!(notice instanceof AuthNotice.OpenUrl open)) return;
                var query = StandardOAuth.query(open.url().getRawQuery());
                challenges.put("code-1", query.get("code_challenge"));
                assertEquals("S256", query.get("code_challenge_method"));
                var callback = URI.create(query.get("redirect_uri") + "?code=code-1"
                        + ("omit".equals(stateOverride) ? "" : "&state=" + (stateOverride != null ? stateOverride : query.get("state"))));
                Thread.startVirtualThread(() -> {
                    try (var http = HttpClient.newHttpClient()) {
                        http.send(HttpRequest.newBuilder(callback).build(), HttpResponse.BodyHandlers.discarding());
                    } catch (Exception ignored) {
                        // the listener closes after the first callback
                    }
                });
            }
        };
    }

    @Test
    void authorizationCodeWithPkceThroughTheLoopbackThenRefreshAndRevoke() {
        var oauth = new StandardOAuth(config().build());
        var credential = oauth.login(browser(null), CancelToken.create());
        assertEquals("access-1", credential.access().reveal());
        assertEquals(Optional.of("ada@example.com"), credential.account());
        assertEquals(base.getScheme() + "://" + base.getAuthority(), credential.issuer());
        assertEquals("client-1", requests.get("/token:authorization_code").get("client_id"));

        var refreshed = oauth.refresh(credential);
        assertEquals("access-2", refreshed.access().reveal());
        assertEquals(credential.refresh(), refreshed.refresh(), "no rotated token: the old one stays");
        assertEquals(credential.account(), refreshed.account(), "the principal is kept");

        oauth.revoke(refreshed);
        assertEquals(Map.of("token", "refresh-1", "token_type_hint", "refresh_token", "client_id", "client-1"), requests.get("/revoke:"));
    }

    @Test
    void aForgedStateIsRejected() {
        var oauth = new StandardOAuth(config().build());
        var error = assertThrows(AuthenticationException.class, () -> oauth.login(browser("forged"), CancelToken.create()));
        assertEquals(ErrorCode.LOGIN_CANCELLED, error.code());
    }

    @Test
    void aCallbackWithoutStateIsRejectedBeforeExchangingTheCode() {
        var oauth = new StandardOAuth(config().build());
        var error = assertThrows(AuthenticationException.class, () -> oauth.login(browser("omit"), CancelToken.create()));
        assertEquals(ErrorCode.LOGIN_CANCELLED, error.code());
        assertTrue(requests.isEmpty(), "a callback without state must never reach the token endpoint");
    }

    @Test
    void credentialsFromAnotherIssuerOrClientAreRejectedBeforeUse() {
        var oauth = new StandardOAuth(config().build());
        var wrongIssuer = OAuthCredential.builder(Secret.of("foreign-access"), "https://other.example", "client-1")
                .refresh(Secret.of("foreign-refresh")).build();
        var wrongClient = OAuthCredential.builder(Secret.of("foreign-access"), base.toString(), "other-client")
                .refresh(Secret.of("foreign-refresh")).build();
        for (var credential : List.of(wrongIssuer, wrongClient)) {
            assertEquals(ErrorCode.LOGIN_REQUIRED,
                    assertThrows(AuthenticationException.class, () -> oauth.toAuth(credential)).code());
            assertEquals(ErrorCode.LOGIN_REQUIRED,
                    assertThrows(AuthenticationException.class, () -> oauth.refresh(credential)).code());
            oauth.revoke(credential);   // nothing to revoke here; a local logout must still be able to drop it
        }
        assertTrue(requests.isEmpty(), "foreign tokens must never reach this issuer");
    }

    @Test
    void pastedCodesForExternalRedirectsAreChecked() {
        var oauth = new StandardOAuth(config().redirectUri(URI.create("https://app.example/callback")).build());
        var urls = new CopyOnWriteArrayList<URI>();
        var ui = new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) {
                var query = StandardOAuth.query(urls.getLast().getRawQuery());
                challenges.put("code-1", query.get("code_challenge"));
                return "code-1#" + query.get("state");
            }

            @Override public void notify(AuthNotice notice) { if (notice instanceof AuthNotice.OpenUrl u) urls.add(u.url()); }
        };
        assertEquals("access-1", oauth.login(ui, CancelToken.create()).access().reveal());
        assertEquals("https://app.example/callback", requests.get("/token:authorization_code").get("redirect_uri"));
    }

    @Test
    void deviceCodePollsUntilApproved() {
        var oauth = new StandardOAuth(OAuthConfig.builder("client-1").authorizationEndpoint(base.resolve("/authorize"))
                .tokenEndpoint(base.resolve("/token")).deviceAuthorizationEndpoint(base.resolve("/device")).build());
        var codes = new CopyOnWriteArrayList<String>();
        var ui = new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) { return "device"; }

            @Override public void notify(AuthNotice notice) { if (notice instanceof AuthNotice.DeviceCode d) codes.add(d.userCode()); }
        };
        var credential = oauth.login(ui, CancelToken.create());
        assertEquals("access-d", credential.access().reveal());
        assertEquals(List.of("ABCD-EFGH"), codes);
        assertEquals(2, polls.get());
    }

    /// `OpenAi.codex()`'s configuration against the in-process issuer.
    private OAuthConfig.Builder codex(URI redirect) {
        return OAuthConfig.builder("app-codex").authorizationEndpoint(base.resolve("/oauth/authorize")).tokenEndpoint(base.resolve("/token"))
                .redirectUri(redirect).deviceAuthorizationEndpoint(base.resolve("/api/accounts/deviceauth/usercode"), OAuthConfig.DeviceDialect.OPENAI)
                .authorizationParameter("codex_cli_simplified_flow", "true").authorizationParameter("originator", "ai-gate")
                .accountClaim("https://api.openai.com/auth", "chatgpt_account_id").accountHeader("chatgpt-account-id");
    }

    @Test
    void chatGptLoginUsesTheFixedLoopbackAndTakesTheAccountFromTheAccessToken() throws IOException {
        int port;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = probe.getLocalPort(); }
        var redirect = URI.create("http://127.0.0.1:" + port + "/auth/callback");
        var oauth = new StandardOAuth(codex(redirect).build());
        var urls = new CopyOnWriteArrayList<URI>();
        var browser = browser(null);
        var credential = oauth.login(new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) { return "browser"; }
            @Override public void notify(AuthNotice notice) {
                if (notice instanceof AuthNotice.OpenUrl u) urls.add(u.url());
                browser.notify(notice);
            }
        }, CancelToken.create());

        var query = StandardOAuth.query(urls.getFirst().getRawQuery());
        assertEquals(redirect.toString(), query.get("redirect_uri"));
        assertEquals("true", query.get("codex_cli_simplified_flow"));
        assertEquals("ai-gate", query.get("originator"));
        assertEquals(Optional.of("acct-7"), credential.account());
        assertEquals("acct-7", oauth.toAuth(credential).headers().get("chatgpt-account-id"));
        assertEquals("Bearer " + credential.access().reveal(), oauth.toAuth(credential).headers().get("Authorization"));

        var refreshed = oauth.refresh(credential);
        assertEquals(Optional.of("refresh-c2"), refreshed.refresh().map(Secret::reveal), "rotated");
        assertEquals(credential.account(), refreshed.account());
    }

    @Test
    void chatGptDeviceCodeExchangesTheApprovedCodeAndVerifier() {
        challenges.put("code-d", challenge("verifier-d"));
        var oauth = new StandardOAuth(codex(URI.create("http://127.0.0.1:1/auth/callback")).build());
        var notices = new CopyOnWriteArrayList<AuthNotice>();
        var credential = oauth.login(new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) { return "device"; }
            @Override public void notify(AuthNotice notice) { notices.add(notice); }
        }, CancelToken.create());

        assertEquals(Optional.of("acct-7"), credential.account());
        var code = (AuthNotice.DeviceCode) notices.getFirst();
        assertEquals(base.resolve("/codex/device"), code.verificationUri());
        assertEquals("CODE-12", code.userCode());
        assertEquals(Map.of("client_id", "app-codex"), requests.get("/api/accounts/deviceauth/usercode:"));
        assertEquals(Map.of("device_auth_id", "dev-1", "user_code", "CODE-12"), requests.get("/api/accounts/deviceauth/token:"));
        var exchange = requests.get("/token:authorization_code");
        assertEquals(base.resolve("/deviceauth/callback").toString(), exchange.get("redirect_uri"));
        assertEquals("verifier-d", exchange.get("code_verifier"));
        assertEquals(2, codexPolls.get(), "a 404 means: not approved yet");
    }

    @Test
    void mapperHandlesNonStandardResponses() {
        var oauth = new StandardOAuth(config().jsonTokenRequests()
                .tokenResponseMapper(json -> OAuthCredential.builder(Secret.of(json.string("access_token") + "-mapped"), base.toString(), "client-1").build()).build());
        var credential = oauth.login(browser(null), CancelToken.create());
        assertEquals("access-1-mapped", credential.access().reveal());
        assertEquals("Bearer access-1-mapped", oauth.toAuth(credential).headers().get("Authorization"));
    }

    @Test
    void aMapperWithTheWrongBindingFailsDuringLogin() {
        var oauth = new StandardOAuth(config().tokenResponseMapper(json ->
                OAuthCredential.builder(Secret.of(json.string("access_token")), "https://other.example", "client-1").build()).build());
        assertEquals(ErrorCode.LOGIN_REQUIRED,
                assertThrows(AuthenticationException.class, () -> oauth.login(browser(null), CancelToken.create())).code());
    }
}
