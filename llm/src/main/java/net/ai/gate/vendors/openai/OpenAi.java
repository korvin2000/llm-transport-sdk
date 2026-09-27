package net.ai.gate.vendors.openai;

import java.net.URI;
import java.util.Set;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthConfig.DeviceDialect;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.vendors.openai.internal.CompletionsCodec;
import net.ai.gate.vendors.openai.internal.ModelList;
import net.ai.gate.vendors.openai.internal.ResponsesCodec;

/// OpenAI's wire APIs, the `openai` preset and the ChatGPT subscription preset `openai-codex`.
public final class OpenAi {
    /// Responses API; the default of `OpenAi.provider()`.
    public static final WireApi RESPONSES = ResponsesCodec.INSTANCE;
    /// Chat Completions; also the compatibility API of most gateways.
    public static final WireApi CHAT_COMPLETIONS = CompletionsCodec.INSTANCE;

    /// Sent as `originator` in the Codex login and requests; the Codex client sends its own name there.
    private static final String ORIGINATOR = "ai-gate";

    private OpenAi() { }

    /// `https://api.openai.com/v1`, keys from `OPENAI_API_KEY`, live `/models` listing.
    public static Provider provider() {
        return Provider.builder("openai", RESPONSES).api(CHAT_COMPLETIONS).name("OpenAI").preset("openai")
                .baseUrl("https://api.openai.com/v1").auth(ApiKeyAuth.bearer("OpenAI API key", "OPENAI_API_KEY"))
                .modelSource(ModelList.INSTANCE).apiKeyUrl(URI.create("https://platform.openai.com/api-keys")).build();
    }

    /// A ChatGPT Plus/Pro subscription through the Codex backend (`https://chatgpt.com/backend-api/codex`), signed in
    /// with `llm.auth().login("openai-codex", AuthType.OAUTH, ui)` in a browser (loopback `127.0.0.1:1455`) or with a
    /// device code. The account id from the access token travels as `chatgpt-account-id`; the backend speaks a
    /// streaming-only Responses dialect without `max_output_tokens`; exhausted plan limits fail with
    /// `quota_exhausted`. Not an official API for third-party clients: OpenAI may change it at any time.
    public static Provider codex() {
        var oauth = OAuthConfig.builder("app_EMoamEEZ73f0CkXaXp7hrann").authorizationEndpoint(URI.create("https://auth.openai.com/oauth/authorize"))
                .tokenEndpoint(URI.create("https://auth.openai.com/oauth/token")).redirectUri(URI.create("http://127.0.0.1:1455/auth/callback"))
                .deviceAuthorizationEndpoint(URI.create("https://auth.openai.com/api/accounts/deviceauth/usercode"), DeviceDialect.OPENAI)
                .scopes(Set.of("openid", "profile", "email", "offline_access")).authorizationParameter("id_token_add_organizations", "true")
                .authorizationParameter("codex_cli_simplified_flow", "true").authorizationParameter("originator", ORIGINATOR)
                .accountClaim("https://api.openai.com/auth", "chatgpt_account_id").accountHeader("chatgpt-account-id").build();
        return Provider.builder("openai-codex", RESPONSES).name("OpenAI Codex (ChatGPT subscription)").preset("openai-codex")
                .baseUrl("https://chatgpt.com/backend-api/codex").auth(OAuthAuth.standard(oauth)).header("originator", ORIGINATOR)
                .compat(OpenAiResponsesCompat.builder().streamingOnly(true).maxOutputTokens(false).sessionHeaders(true)
                        .defaultInstructions("You are a helpful assistant.").build())
                .build();
    }
}
