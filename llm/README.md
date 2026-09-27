# AI Gate (`net.ai.gate:ai-gate`)

One portable Java API over LLM providers and gateways — the architecture of
[`docs/proposals/final-architecture.md`](../docs/proposals/final-architecture.md), built as one Gradle module and one
JPMS module (`net.ai.gate`).

**Status: architectural baseline.** The facade, value types, SPIs, configuration, execution core, auth chain,
catalog, response cache, events and testing kit are implemented and verified offline against a scripted provider and
small transport fixtures. Vendor wire protocols and OAuth flows are explicit stubs (they throw
`UnsupportedOperationException` naming their roadmap slice); their mapping is documented in place.

```java
var fake = FakeProvider.create().reply("Records are transparent carriers of immutable data.");
try (Llm llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).build()) {
    Model model = llm.model("fake", "fake");                      // catalog entry: limits, prices, reasoning levels
    AssistantMessage reply = llm.complete(model, "Explain Java records in one sentence.");
    System.out.println(reply.text() + " " + reply.usage());
}
```

With a real provider the shape is the same — `Llm.create()` discovers the bundled presets and reads keys from the
environment — but the vendor codecs are still stubs, so such a call ends with `UnsupportedOperationException`.

## Build

JDK 26 and Gradle 9.7 (wrapper included; a missing JDK 26 is provisioned by the toolchain resolver).

```bash
./gradlew build
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
| Portability | `Model`, `Conversation` and `ChatOptions` have canonical JSON forms; `ProvidersConfig` states only differences from presets, has no credential field and rejects what it cannot represent |
| Boundaries | ArchUnit rules above; an external consumer compiles and runs on the module path and the class path; internals are not exported |

## Implemented vs. stubbed

| Area | State |
|---|---|
| Facade, builders, three option scopes, catalog-derived defaults, strict/soft adaptation, hand-off rules | implemented |
| Engine: deadlines and idle watchdog, retries with `Retry-After`, cancellation trees, events, JFR, wire log | implemented |
| Streaming: SSE/NDJSON framing, accumulation, partial tool arguments, `result()` equals `complete()` | implemented |
| Response cache (memory, directory cassettes, `OFFLINE`), cost with price tiers, `preview()` / `toCurl()`, `test()` | implemented |
| Auth chain with sources, memory/file/scoped stores, API-key login, console and redirect interactions | implemented |
| Catalog merge, feeds, live listings (`GET models`), background refresh, snapshot file | implemented |
| Canonical JSON forms of models, conversations and options; `ProvidersConfig` with `defaults` | implemented |
| `OpenAi.RESPONSES`, `OpenAi.CHAT_COMPLETIONS`, `Anthropic.MESSAGES`, `Gemini.GENERATE_CONTENT` codecs | **stub** — mapping in class docs |
| `OAuthAuth.standard` flows (PKCE loopback / redirect, device code, refresh, revocation) | **stub** |
| models.dev feed adapter, `Gemini.CACHES`, custom TLS, `compat` in `ProvidersConfig` | **stub** — rejected explicitly, never silently dropped |

`FakeProvider`'s codec (`testing/FakeWireApi`) is a complete, small `WireApi` and the reference for implementing the
real ones: a codec is pure — it maps `ApiRequest` to an `HttpCall` and replies to `AssistantMessage` / `ChatEvent`s —
while the core owns I/O, credentials, retries, deadlines, cancellation, redaction and events.

## Known limits of this baseline

- The bundled `models.json` holds illustrative entries; ids, limits and prices are not verified and not suitable for
  billing. Preset URLs and environment-variable names need checking against provider documentation before real use.
- The JDK transport's connect timeout is a client-level setting taken from the runtime's default `TimeoutPolicy`;
  per-call `connect` values reach only injected transports.
- Call context is passed explicitly (`Call`) rather than through a `ScopedValue`; the architecture's sketch of a
  scoped call context is recorded as a deliberate simplification.
- Response-cache namespaces: the runtime's own credential store is `runtime` (so recorded cassettes replay across
  processes); every other root store passed to `withCredentials` gets an identity per instance.
- Provider file references (`Content.fileRef`) carry only the provider's file id; binding them to an account is left
  to the vendor upload APIs of the next slice.

## Next steps (roadmap §19)

1. Chat Completions and Anthropic Messages codecs with golden fixtures and hand-off tests between them.
2. OAuth flows against a local fake authorization server; OpenRouter preset login.
3. Responses and Gemini codecs, the models.dev feed, catalog regeneration task.
