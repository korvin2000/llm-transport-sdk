# AI Gate (`net.ai.gate:ai-gate`)

One portable Java API over LLM providers and gateways — the architecture of
[`docs/proposals/final-architecture.md`](../docs/proposals/final-architecture.md), built as one Gradle module and one
JPMS module (`net.ai.gate`).

**Status: working transport.** The facade, value types, SPIs, configuration, execution core, auth chain, catalog,
response cache, events and testing kit, the four vendor wire protocols (OpenAI Responses and Chat Completions,
Anthropic Messages, Gemini `generateContent`), OAuth flows including the ChatGPT subscription (Codex), generated
images and audio, the models.dev feed, Gemini cached contents and custom TLS are implemented and verified offline
against scripted endpoints and an in-process OAuth issuer; `./gradlew liveTest` runs opt-in smoke tests against the
real endpoints.

```java
var fake = FakeProvider.create().reply("Records are transparent carriers of immutable data.");
try (Llm llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).build()) {
    Model model = llm.model("fake", "fake");                      // catalog entry: limits, prices, reasoning levels
    AssistantMessage reply = llm.complete(model, "Explain Java records in one sentence.");
    System.out.println(reply.text() + " " + reply.usage());
}
```

With a real provider the shape is the same — `Llm.create()` discovers the bundled presets and reads keys from the
environment:

```java
try (Llm llm = Llm.create()) {                                   // ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY…
    var reply = llm.complete(llm.model("anthropic", "claude-sonnet-4-6"), "Explain Java records in one sentence.");
    System.out.println(reply.text());
}
```

### ChatGPT subscription (Codex)

A ChatGPT Plus/Pro subscription can call the Codex models without an API key. Sign in once — in the browser (the
callback lands on `127.0.0.1:1455`) or with a device code — and keep the login in a durable store:

```java
try (Llm llm = Llm.builder().discoverProviders().credentials(CredentialStore.file(Path.of("credentials.json"))).build()) {
    llm.auth().login("openai-codex", AuthType.OAUTH, AuthInteraction.console());   // once; refreshed automatically
    var reply = llm.complete(llm.model("openai-codex", "gpt-5.5"), "Explain Java records in one sentence.");
}
```

**This is not an official API for third-party clients.** The preset (`OpenAi.codex()`) follows the Codex CLI: the
ChatGPT backend at `https://chatgpt.com/backend-api/codex`, a streaming-only Responses dialect, the account id from
the access token as `chatgpt-account-id`. OpenAI may change or restrict any of it; exhausted plan limits fail with
`RateLimitedException` (`quota_exhausted`). Claude Pro/Max subscriptions are deliberately not supported — Anthropic
limits them to its own applications; use an API key.

## Build

JDK 26 and Gradle 9.7 (wrapper included; a missing JDK 26 is provisioned by the toolchain resolver).

```bash
./gradlew build
./gradlew liveTest            # opt-in, billable: OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, AI_GATE_CREDENTIALS
./gradlew updateModelCatalog  # network: regenerates models.json from models.dev
```

`build` compiles with `-Xlint:all -Werror`, runs the Java and Kotlin tests (JUnit 6, ArchUnit), compiles and runs an
external consumer against the jar on the module path and on the class path, and produces the jar, sources jar and
Javadoc (Markdown `///` comments). The main artifact has no runtime dependency beyond the JDK; Kotlin is test-only.

## Layout

Packages are grouped by noun, at most 20 top-level types each (enforced). Public API:

```
net.ai.gate
├── Llm, Provider            the runtime facade and the backend it is built from
├── chat/                    Conversation, the three Message kinds, StopReason
│   ├── content/             Content parts (text, media, reasoning, refusal, unknown), ToolCall, ToolResult
│   ├── tool/                Tool, FunctionTool, ProviderTool, ToolChoice
│   ├── stream/              ChatStream and its sealed ChatEvents
│   └── options/             ChatOptions (call ▷ provider ▷ runtime), OutputFormat, ReasoningHandoff
├── model/                   Model, ModelRef, Capability/Capabilities, Modality, SupportLevel, ReasoningLevel, Prices
├── metadata/                Usage, Cost, ResponseInfo, RateLimits, Warning
├── catalog/                 ModelCatalog, CatalogOptions, RefreshReport, ModelsDevFeed; internal/ holds models.json
├── providers/               Providers (every preset) and the secret-free ProvidersConfig format
├── config/                  TimeoutPolicy, RetryPolicy (partial, inheriting), HttpOptions, WireLog, FieldDescriptor
├── cache/                   ResponseCache SPI, CacheMode, CacheRetention
├── diagnostics/             PreparedRequest (preview, curl), ConnectionTest, ConnectionReport
├── lifecycle/               CancelToken, Registration
├── error/                   LlmException and its eight subclasses, ErrorCode
├── auth/                    Auth, credentials, CredentialStore, ApiKeyAuth, Environment, Secret …
│   ├── oauth/               OAuthAuth, OAuthConfig, OAuthCredential
│   └── interaction/         AuthInteraction, AuthPrompt, AuthNotice, RedirectInteraction
├── event/                   LlmListener and the sealed, content-free events
├── json/                    immutable JSON tree, record binding, JSON Schema derivation
├── spi/                     for protocol and provider authors
│   ├── protocol/            WireApi, StreamDecoder, ApiRequest, contexts, Frame, ApiCompat, ProviderOptions
│   ├── http/                HttpTransport, HttpCall, HttpReply, TransportOptions, WireInterceptor
│   ├── catalog/             ModelSource, ProviderHttp, CatalogFeed, FeedHttp
│   └── provider/            ProviderBundle (ServiceLoader), ProviderApi, ProviderApiContext, RawApi
├── vendors/                 openai/, anthropic/, google/ — presets, options, compat flags, hosted tools;
│                            each keeps its codecs and bundle in its own internal/ package
├── testing/                 FakeProvider (scripted, behind the real runtime), RecordingListener, LlmErrors
└── internal/                not exported: core, http, cache, catalog, json, serialization, validation,
                             auth/{store, interaction, oauth}
```

Rules enforced by `ArchitectureTest`: vendors, the testing kit and data bundles use only the public API; runtime
internals know no vendor; vendor families do not depend on each other; the JSON tree and its implementation depend
on nothing else in the SDK; events mention only value types; no public signature mentions an internal type; no
`util`/`impl`/`manager` packages. Sealed hierarchies that span packages (`Credential` → `auth.oauth.OAuthCredential`)
rely on the named module and are verified on both the module path and the class path.

## Contracts the tests verify

| Area | Verified behavior |
|---|---|
| Options | Field-by-field inheritance across three scopes, including partial `TimeoutPolicy`/`RetryPolicy`; explicit `strict(false)` and empty stop lists override; headers merge case-insensitively; protected and credential-bearing headers are rejected |
| Call lifetime | Cancellation before send, on cache replay, during body and error-body reads and in backoff; the total deadline covers every phase; one `Finished` event per call; an outside interrupt stays pending; `close()` is bounded |
| Streaming | One view, one iterator; `close()` from any thread ends a blocked stream once with the partial reply; idle timeout; framing, event and accumulation bounds; premature end of stream and malformed frames fail with `partial()` |
| Retries | Only documented not-processed statuses and pre-send failures; `Retry-After`; ambiguous post-send failures surface `outcomeUnknown()`; a 401 on OAuth refreshes the rejected token exactly once, shared across concurrent calls |
| Response cache | Keys cover the credential namespace (store, scope, account) and the effective request including headers; views never share entries; cassettes recorded by a runtime replay offline in another process; interceptors disable caching |
| Auth | Visible sources; stores, scopes and views isolate users; failed refresh never falls back to an environment key; store failures keep `credential_store`; the file store coordinates instances and processes and validates its schema |
| Catalog | Custom beats fresh, newest non-absent wins, prices merge component by component; live listings run with each view's credentials and availability is per view; snapshots survive restarts |
| Diagnostics | `preview()` never sends; `toCurl()` quotes every literal and leaves only credential placeholders expandable; `describe()` redacts; error bodies are bounded; the connection test is staged, non-billable and reports unverifiable credentials as `NOT_SUPPORTED` |
| Hand-off | Foreign reasoning becomes `<thinking>` text or is dropped, signatures never cross, tool-call ids are normalized without collisions and results follow, cache breakpoints are remapped past dropped turns |
| Portability | `Model`, `Conversation` and `ChatOptions` have canonical JSON forms; `ProvidersConfig` states only differences from presets — compat flags included — has no credential field and rejects what it cannot represent |
| Adaptation | Models that reject sampling parameters (catalog, or OpenAI reasoning models while reasoning) get none and a warning; forced tool choice becomes `auto` where Claude cannot force a tool; foreign generated media is omitted (audio as its transcript) |
| Boundaries | ArchUnit rules above; an external consumer compiles and runs on the module path and the class path; internals are not exported |

## Implemented

| Area | State |
|---|---|
| Facade, builders, three option scopes, catalog-derived defaults, strict/soft adaptation, hand-off rules | implemented |
| Engine: deadlines and idle watchdog, retries with `Retry-After`, cancellation trees, events, JFR, wire log | implemented |
| Streaming: SSE/NDJSON framing, accumulation, partial tool arguments, `result()` equals `complete()` | implemented |
| Response cache (memory, directory cassettes, `OFFLINE`), cost with price tiers, `preview()` / `toCurl()`, `test()` | implemented |
| Auth chain with sources, memory/file/scoped stores, API-key login, console and redirect interactions | implemented |
| Catalog merge, feeds, live listings (`GET models`), background refresh, snapshot file | implemented |
| Canonical JSON forms of models, conversations and options; `ProvidersConfig` with `defaults` | implemented |
| `OpenAi.RESPONSES` (stateless: encrypted reasoning replay, citations, hosted tools), `OpenAi.CHAT_COMPLETIONS` (compat flags: reasoning formats, max-tokens field, developer role, reasoning replay, Anthropic-style cache markers, session headers) | implemented |
| `Anthropic.MESSAGES`: adaptive or budget thinking, signed/redacted thinking replay, ≤ 4 cache markers with 1 h TTL, hosted tools with their betas, structured output | implemented |
| `Gemini.GENERATE_CONTENT`: thinking level or budget, thought signatures on any part, function calls/responses, JSON-schema output, `cachedContent` | implemented |
| `OAuthAuth.standard`: PKCE S256 via loopback, pasted code or web redirect; device code (RFC 8628 and OpenAI's dialect); refresh rotation; revocation; extra authorization parameters; the account from an access-token claim, sent as a header | implemented |
| `OpenAi.codex()`: ChatGPT Plus/Pro subscription through the Codex backend — browser or device login, `OpenAiResponsesCompat` dialect (streaming only, required instructions, no `max_output_tokens`, session headers), `complete()` collected from the event stream, plan limits as `quota_exhausted` (not retried), Codex models in the catalog | implemented |
| Generated media: Gemini `inlineData` images and audio, Responses `image_generation_call` (replayed as its item), Chat Completions `message.audio` / `delta.audio` (replayed as the transcript) | implemented |
| Anthropic citations (`Content.Citation` per text block, `citations_delta` in streams); forced `tool_choice` adapted to `auto` under budget thinking and on Opus 5.5 / Fable 5.1 / Mythos 5.1 | implemented |
| Sampling rules: `Capability.TEMPERATURE` from models.dev, OpenAI reasoning models, Claude thinking — dropped with `option_dropped`, never a failure; reply warnings logged at `INFO` | implemented |
| Mistral tool-call ids (`OpenAiCompletionsCompat.ToolCallIdFormat.MISTRAL`: nine letters and digits, calls and results paired) | implemented |
| `compat` in `ProvidersConfig` (per provider and per model, canonical `toJson()` / `fromJson()` of every `ApiCompat`) | implemented |
| models.dev feed (runtime refresh) and `./gradlew updateModelCatalog` (regenerates `models.json`), `Gemini.CACHES`, custom TLS (`HttpOptions.sslContext()`) | implemented |
| Confidential OAuth clients and the client-credentials grant | not supported — rejected explicitly, never silently dropped |

A codec is pure — it maps `ApiRequest` to an `HttpCall` and replies or stream frames to `AssistantMessage` /
`ChatEvent`s — while the core owns I/O, credentials, retries, deadlines, cancellation, redaction and events. Shared
mapping steps live in `spi.protocol.Codecs`; `testing/FakeWireApi` remains the smallest complete example.

## Known limits

See [the reliability review](REVIEW.md) for ranked findings, regression evidence, and remaining follow-up work.

- `models.json` is generated from models.dev (text models of the preset providers; OpenRouter is listed live). Its
  prices are the feed's, not an invoice; the runtime feed refreshes them unless `CatalogOptions.offline()`/`noFeeds()`.
- Thinking formats that depend on the model generation are chosen by model id: Claude models after 4.5 think
  adaptively, Gemini 3 and `-latest` aliases take a `thinkingLevel`; `AnthropicOptions.thinkingBudget` and
  `GeminiOptions.thinkingBudget` force a budget.
- OAuth supports public clients only; the loopback listener waits 5 minutes and answers only its callback path.
- The JDK transport's connect timeout is a client-level setting taken from the runtime's default `TimeoutPolicy`;
  per-call `connect` values reach only injected transports.
- Call context is passed explicitly (`Call`) rather than through a `ScopedValue`; the architecture's sketch of a
  scoped call context is recorded as a deliberate simplification.
- Response-cache namespaces: the runtime's own credential store is `runtime` (so recorded cassettes replay across
  processes); every other root store passed to `withCredentials` gets an identity per instance.
- Provider file references (`Content.fileRef`) carry only the provider's file id; binding them to an account is left
  to the vendor upload APIs of the next slice.

- The Codex backend is reached over SSE only (the Codex CLI's WebSocket transport is not used); the login needs
  port 1455 on the loopback free. An existing Codex CLI login (`~/.codex/auth.json`) is not imported: both clients
  would rotate one refresh token and sign each other out.
- Generated media is decoded, but requesting it has no portable option: use the hosted tool
  `OpenAiTools.imageGeneration()`, Gemini's `responseModalities` / `speechConfig` or Chat Completions' `modalities` /
  `audio` through `ChatOptions.payload(…)`. Streamed Chat Completions audio is `pcm16`; a whole reply's format is the
  one it names, else `wav`. Gemini streams audio in several parts.
- Gemini 3 accepts media inside `functionResponse.parts`; tool-result media is still sent as sibling parts.
- OpenRouter's `reasoning_details` are not replayed; Groq and xAI accept fewer effort values than OpenAI — the
  catalog's reasoning levels clamp them.

## Next steps

1. Run `./gradlew liveTest` with real keys and a Codex login; fix what the live endpoints disagree with.
2. Provider upload APIs (files) with account binding of `Content.fileRef`.
