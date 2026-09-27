# LLM Transport SDK — Architecture and API Proposal

**Status:** proposal draft v0.1 (engineering skeleton, not an implementation)
**Language:** Java 17 baseline (Loom-friendly; Java 21 syntax appears only in examples where marked)
**Inputs:** `docs/requirements/detailed-astra.md`, `detailed-fable.md`, `detailed-opus.md`, `short-gemini.md`
**Method:** the `java-sdk-design` workflow (consumer model → usage first → surface partition → design-it-twice → configuration → contracts → extension ladder → layout → verification)

> Everything here is a **proposal**. Type, package and artifact names are placeholders chosen for
> consistency; the root package `dev.llmtransport` is a placeholder. Code blocks are compile-shaped
> sketches (signatures and contracts), not implementations.

---

## 0. How to read this document

| You are… | Read |
|---|---|
| deciding whether the design fits | §1 (what the requirements agree on, what was cut), §2 (philosophy), §4 (usage) |
| an application developer who will call the SDK | §3 (glossary), §4 (usage), §10 (configuration), §11 (execution contracts), §14 (errors) |
| a UI / workflow-builder integrator | §4.6–§4.9, §12 (auth & OAuth), §13 (UI integration guide) |
| a provider / dialect author | §8 (SPI), §9 (provider modules, adding a provider) |
| planning the implementation | §5–§7 (architecture, layout, API sketch), §16 (roadmap), §17 (verification) |

Assumptions made while reading the request:

1. "It should **not** be lightweight, modular, flexible…" is read as a typo for "it should be
   lightweight, modular, flexible…"; every other sentence of the request and all four requirement
   documents demand a lean core.
2. The output file name was given without a directory, so the document lives at the repository root.
3. Java is the only authored language; Kotlin is a consumer we keep friendly, not a second surface.

---

## 1. Requirements synthesis

### 1.1 The four documents, one paragraph each

- **astra** (≈175 KB, ~230 requirement IDs) — a *requirements baseline* that deliberately names no
  classes or packages. Its stance is epistemic honesty: never conflate unknown / unsupported / unset
  with `false` / `0` / "default"; keep native fidelity (opaque reasoning blobs, unknown fields) next
  to the normalized view; make every side effect (network, cost, browser opening, retry) explicit
  and policy-governed. Strong on OAuth policy (external browser, no listener reachable from outside
  by default, no consent-free browser opening), on layered connection tests ("reachable ≠ authorized
  ≠ usable"), and on UI rules ("no execution on rendering", explicit fan-out semantics).
- **fable** (≈156 KB) — a six-layer architecture (L0 cross-cutting → L5 facade) with ~16 SPIs, a
  declarative *compatibility profile* for the OpenAI-compatible long tail, dual raw + semantic
  streaming, a full OAuth module (PKCE + loopback, device code, refresh rotation, token-store SPI),
  tri-state capabilities with provenance, and explicit gateway (proxy-building) requirements.
- **opus** (≈122 KB) — five layers (transport → native → services → unified → facade), the
  "**dialect, not vendor**" principle (~8 wire dialects cover nearly every endpoint), presets and
  quirks as *data*, "never send what the user did not set", a `TransformationReport` per response, a
  YAML config format with `${env:…}` interpolation, ~22 SPIs, and a well-scoped P0 (openai-chat,
  openai-responses, anthropic-messages, gemini, ollama-native).
- **gemini** (≈22 KB) — a checklist baseline: dual sync/async execution, pluggable HTTP engine +
  interceptor pipeline, an auth matrix (API key, OAuth PKCE / client credentials, SigV4, GCP service
  account, key rotation), a timeout taxonomy (connect / read / TTFB / stream-idle), retry with
  `Retry-After`, normalized model metadata with pricing, a unified request builder, per-vendor
  reasoning handling, a unified exception tree, OpenTelemetry `gen_ai.*` spans.

### 1.2 Common ground (what all four agree on)

| Theme | Consensus |
|---|---|
| Position in the stack | A **transport / connection layer**, not an agent framework: no tool-execution loop, no planning, no RAG / memory / templating, no UI. Everything a UI needs (descriptors, events, status) must be exposed. |
| Dialect ≠ provider | A handful of wire dialects (OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, Gemini `generateContent`, Ollama native) serve dozens of providers; provider differences are mostly base URL, auth header, env var and quirks — **data**, not code. |
| Layering + escape hatches | Facade over a unified model over dialect codecs over an HTTP transport; each lower layer reachable through a documented door (typed provider options, raw JSON, raw HTTP). No lowest common denominator. |
| Unified model | Messages of content parts (text, image, document, tool call, tool result, reasoning, unknown); tool definitions / calls / results; response format (text, JSON, JSON Schema); reasoning config (effort, budget); usage incl. cache and reasoning tokens; open finish reason; unknown fields and enum values preserved, never fatal. |
| Streaming | Sealed semantic events (start, text delta, reasoning delta, tool-call start / delta / done, usage, finish, unknown / raw); an accumulator yields the *same* response type as the non-streaming call; one consumer; cancellation reaches the socket; never retry after visible output. |
| Auth | Per-endpoint strategy: API key in a provider-specific header, bearer token, OAuth 2.0 / 2.1 (auth code + PKCE with loopback redirect or paste fallback; device code; client credentials; refresh with rotation, single-flight, proactive refresh); cloud IAM via optional modules; a secret type that redacts; secrets persisted by *reference*; token-store SPI (memory / file / OS keychain); host callbacks for "open browser", "show device code", "cancel". |
| Discovery | Model listing; `ModelInfo` with **tri-state** capabilities (supported / unsupported / unknown — "missing data is UNKNOWN, never UNSUPPORTED"); pricing unknown ≠ zero; provenance and freshness; a layered connection test that is non-billable by default. |
| Resilience | Timeouts: connect, first byte, stream idle, total. Retries: exponential backoff + full jitter, `Retry-After`, only retryable classes (network-before-send, 408 / 429 / 5xx), one total deadline. Unsupported-parameter policy (fail / warn-and-drop). |
| Errors | One root exception; structured fields (HTTP status, provider code, request id, retryable, retry-after); categories: transport, timeout, auth, permission, rate limit, invalid request (incl. context length), overloaded / server, content filtered, stream interrupted, cancelled. |
| Config | Precedence per-call > builder > environment > preset defaults; immutable resolved snapshot; "never send what the user did not set"; serializable, secret-free endpoint profiles for workflow save / load; explicit (never implicit) environment reading. |
| Java | 17 or 21 baseline; JDK `HttpClient` as default transport behind an SPI (OkHttp for Android / SOCKS); JSON library undecided in every document; JSpecify; JPMS-friendly; clients thread-safe and `AutoCloseable`. |
| Testing | Fakes, record / replay, a conformance suite per dialect. |

### 1.3 Divergences and the decision taken here

| Question | Positions in the docs | Decision |
|---|---|---|
| Java baseline | 17 (fable, gemini) vs 21 (astra, opus) | **17** for the library; examples may use 21 pattern `switch`. Nothing in the core needs 21 (§5.4). |
| JSON | Jackson hard dependency vs `JsonCodec` SPI vs internal facade | **Small internal JSON tree + parser / writer in core; zero third-party dependencies.** POJO binding lives in an optional Jackson module (§7.10). |
| Layers | 5–6 architectural layers, each exposed | **3 disclosure layers** (unified API → typed provider options → raw) over 4 internal layers (§5.1). |
| SPIs | 16–22 extension points | **6**: `Dialect` (with `ChatCodec` / `StreamDecoder`), `HttpTransport`, `WireInterceptor`, `TokenStore`, `BrowserLauncher`, `Credentials.Supplier`. Everything else is a built-in option or internal (§8). |
| Provider extension | `Provider` SPI interface vs presets-as-data | **`LlmProvider` is an immutable data class**; a provider that speaks an existing dialect is *zero code*. Only a new dialect is code (§9.5). |
| Gateway / bidirectional codecs | first-class (fable, opus, astra) | **Out of scope for v1**; codecs are pure `encode` / `decode` functions so a gateway module can reuse them later (§1.5). |
| Routing, fallback chains, circuit breaker, key pools, rate limiter | [S] / [C] in the detailed docs | **Not in core.** Hosts compose clients and observe events; may become an optional module (§1.5). |
| Transformation report | per response (opus), warnings (others) | `ChatResponse.warnings()` + `RequestEvent.OptionDropped`; no audit-log object on the hot path (§7.3). |
| Streaming consumption styles | "all three offered" (iterator, publisher, callback) | **Sync closeable iterable is primary**; `Flow.Publisher` and `CompletableFuture` on a separate `async()` view in phase 2; no callback form (§11). |
| Config files (YAML, interpolation) | opus | **Not in core.** `Endpoint` is a plain value a host persists in its own format; a file-config module is optional later (§10). |
| Multi-endpoint router facade (`llm.chat("or:model")`) | opus, fable sketches | **One client per endpoint**, sharing an injected transport. A router facade would hide which endpoint is billed (§4.10). |
| Subscription OAuth presets (Codex, Claude Pro / Max, Copilot) | fable / opus flag ToS risk | **Mechanism is generic; no bundled consumer-account presets.** Hosts may configure `OAuthConfig` themselves (§12.6). |

### 1.4 Ideas from the inputs that are kept verbatim in spirit

- Dialect-not-vendor; presets and quirks as data (opus, fable).
- "Never send what the user did not set" (opus): unset ≠ provider default.
- Tri-state capability model with provenance (fable, opus, astra).
- Layered, non-billable connection test (astra).
- Retry ownership and "outcome unknown" after a post-send failure (astra).
- OAuth policy: external browser, host decides to open it, no externally reachable listener by
  default, rotated refresh tokens persisted atomically, single-flight refresh (astra, fable, opus).
- UI rules: no execution on rendering, explicit fan-out semantics, secret-free export, locale-safe
  numbers (astra, opus).
- Timeout taxonomy including stream-idle, grounded in the JDK `HttpClient` gap (fable, gemini).
- Prompt-cache prefix determinism through stable JSON key ordering (opus).

### 1.5 Deliberately cut from v1

| Cut | Why | Door left open |
|---|---|---|
| Gateway / proxy primitives, inbound decoding | Doubles the codec surface; a different product | Codecs are pure; `WireRequest` / `WireResponse` are library-owned types |
| Routing, fallback, circuit breaker, client-side rate limiter, key pools | Host policy; hides billing decisions; key pools may breach ToS | Events expose every attempt; hosts compose clients |
| Realtime / WebSocket / audio, video and image generation, batch jobs, files API, assistants | Not needed by the two named consumers in v1 | New sub-API per noun (`client.files()`), same pattern |
| Multi-source catalog merge with per-field confidence | A metadata engine before one call works | `ModelInfo.source()` / `fetchedAt()` now; optional `catalog` module later |
| Tool execution loop, MCP client, context compaction, templates | Anti-goals | The transport carries tool definitions / calls / results; hosts loop |
| OpenTelemetry / Micrometer in core | Dependencies | A listener in an optional module maps `LlmEvent` → spans / metrics |
| Config-file format with interpolation | Host concern | `Endpoint` / `LlmProvider` are plain values; `ConfigField` descriptors drive host forms |

### 1.6 Anti-goals (to appear in the README)

Not an agent framework (no tool execution, loops, planning). Not a prompt-template engine. Not a
conversation store. Not a retrieval or vector library. Not a UI or CLI. Not a proxy server. No cost
accounting beyond returning `Usage` and `Pricing`. No hidden provider or model switching. No global
mutable state.

---

## 2. Philosophy

1. **Small surface, deep modules.** A consumer learns ~8 nouns (§3.3). Everything else is hidden or
   reachable through one documented door.
2. **Common path first.** One factory + one call with defaults; configuration is a superset of the
   same entry point; the 1 % case goes one layer down, never into internals.
3. **Hide wiring, expose consequence.** Callers never assemble codecs, framers, retry loops or token
   refreshers. Callers *do* see and decide anything that costs money, opens a browser, retries a
   call, drops an option, or owns a resource.
4. **Dialect is code, provider is data.** Adding DeepSeek is a preset; adding a new wire format is a
   codec. Upgrading to a newer OpenAI Responses version is a new codec object selected by the preset.
5. **Honest unknowns.** Unknown capability, price, usage or limit is `UNKNOWN` / absent, never
   `false` / `0`. Unknown content parts, stream events and enum values are preserved as `Unknown(raw)`.
6. **Never send what the user did not set.** The unified request has no invented defaults for
   sampling parameters; codecs serialize only set fields.
7. **No silent semantic change.** An option the dialect cannot express fails the request unless the
   client was configured to drop it, and then the drop is reported.
8. **Sync first.** Blocking calls and a closeable stream are the contract; async is a separate view.
   Clients are thread-safe and shared; models are immutable.
9. **Secrets are types.** `ApiKey`, `BearerToken`, `OAuthTokens` redact in `toString`, logs, events
   and exceptions. Configuration values persist secrets by reference only.
10. **Additive evolution.** SPI interfaces grow by `default` methods; wire-derived models are final
    classes with builders, not records; sealed hierarchies always carry an `Unknown` variant.

---

## 3. Consumer model

### 3.1 Definition

> **A library for sending chat (and later embedding) requests to any LLM provider or gateway —
> handling endpoint configuration, authentication including OAuth, streaming and discovery — and
> returning typed responses, synchronously or as an event stream.**

### 3.2 Consumers

| Consumer | Needs | Skill |
|---|---|---|
| AI coding agent (CLI / desktop) | many endpoints, streaming tool calls, reasoning continuity, cancel, usage, OAuth login from a terminal or desktop | Java or Kotlin, experienced |
| Node-based workflow builder (desktop or web) | list providers, render config forms, test a connection, list models + capabilities, run a node with progress, persist secret-free config | Java, mixed |
| Library author building on top | stable model types, fakes, events | experienced |
| Provider / dialect author | small SPI, contract test kit | experienced |

Runtime: JVM 17+, desktop and server. Android only through an OkHttp transport module (not a v1 target).

### 3.3 Glossary (nouns → public types)

| Concept | One line | Public type |
|---|---|---|
| **Provider** | a backend family described as data: id, dialect, default base URL, auth methods, env var, config fields, quirks | `LlmProvider` |
| **Endpoint** | *where and how* to reach a provider: provider + base URL + credentials + headers + default model | `Endpoint` |
| **Client** | the configured, shareable entry point to one endpoint | `LlmClient` |
| **Request / Response** | an immutable description of a chat call and its typed result | `ChatRequest`, `ChatResponse`, `ChatStream` |
| **Message / Content** | one turn with a role and content parts | `Message`, `Content` (sealed) |
| **Tool** | a definition the model may call, the call, the result | `ToolDefinition`, `ToolCall`, `ToolResult` |
| **Credentials** | what is presented to the endpoint (API key, bearer, OAuth) | `Credentials` (sealed) |
| **Event** | something the client observed (request lifecycle, auth) for UIs and metrics | `LlmEvent` (sealed), `LlmListener` |
| *Dialect* (extenders only) | a wire-format family with its codec | `Dialect`, `ChatCodec` (SPI) |

Everything else (`ModelInfo`, `Usage`, `TimeoutPolicy`, `JsonValue`, …) is reached from these by
IDE completion.

### 3.4 Progressive disclosure

| Layer | Who | Surface |
|---|---|---|
| 1 | most callers | `OpenAi.client(ApiKey.fromEnv())` → `client.chat().send("gpt-5", "…")` |
| 2 | configured callers | `LlmClient.builder(endpoint).timeouts(…).retry(…).listener(…)`; `ChatRequest.builder(model)…` |
| 3 | advanced | typed provider options `request.option(AnthropicOptions.THINKING_BUDGET, 4096)`, `response.raw()`, `client.raw().post(path, json)`, `client.chat().prepare(request)` (encode without sending) |

---

## 4. Usage first (caller code before types)

All snippets are Java 17 unless marked; they are the README examples and are compiled and executed
as tests in the `examples` module (§17).

### 4.1 Simple — the 80 % case

```java
// Provider module factory; reading the environment is explicit in the name (OPENAI_API_KEY).
try (LlmClient client = OpenAi.client(ApiKey.fromEnv())) {
    ChatResponse r = client.chat().send("gpt-5", "Summarize this file: " + text);
    System.out.println(r.text());
    System.out.println(r.usage());          // Usage[input=812, output=140, cached=absent, reasoning=absent]
}
```

Nothing to wire: the endpoint uses the provider's default base URL, the JDK HTTP transport, default
timeouts and retry, no listeners. Forgetting the API key is impossible (`fromEnv()` throws with the
variable name); forgetting the model is impossible (`send(model, text)`).

### 4.2 Configurable — a superset of Simple, same entry point

```java
Endpoint endpoint = Endpoint.builder(Anthropic.PROVIDER)
    .id("work-anthropic")                                  // host-assigned name: events, token store key
    .baseUrl(URI.create("https://llm-gw.corp.example/anthropic"))
    .credentials(ApiKey.of(secretFromVault))
    .header("X-Tenant", "team-42")
    .defaultModel("claude-sonnet-4-5")
    .build();

LlmClient client = LlmClient.builder(endpoint)
    .timeouts(t -> t.connect(Duration.ofSeconds(5)).streamIdle(Duration.ofSeconds(90)))
    .retry(r -> r.maxAttempts(4))
    .unsupportedOptions(UnsupportedOptionPolicy.DROP_WITH_WARNING)
    .listener(event -> metrics.record(event))
    .build();

ChatRequest request = ChatRequest.builder("claude-sonnet-4-5")   // required value in the factory
    .system("You are terse.")
    .user("Explain seams in one sentence.")
    .temperature(0.2)
    .maxOutputTokens(300)
    .reasoning(Reasoning.effort(Effort.LOW))
    .build();

ChatResponse r = client.chat().send(request);
```

### 4.3 Streaming

```java
try (ChatStream stream = client.chat().stream(request)) {         // AutoCloseable: close() cancels
    for (ChatEvent event : stream) {                                // blocks per event, caller thread
        if (event instanceof ChatEvent.TextDelta d)      out.print(d.text());
        if (event instanceof ChatEvent.ReasoningDelta d) ui.showThinking(d.text());
    }
    ChatResponse full = stream.response();   // aggregate == what send() would have returned
    Usage usage = full.usage();
}
```

```java
// Java 21 flavour
for (ChatEvent e : stream) {
    switch (e) {
        case ChatEvent.TextDelta d        -> out.print(d.text());
        case ChatEvent.ToolCallCompleted c -> pending.add(c.toolCall());
        case ChatEvent.Finished f          -> log.info("finish={} usage={}", f.reason(), f.response().usage());
        default                            -> { }                    // Unknown, UsageUpdated, Started, …
    }
}
```

Cancellation from another thread (a UI "Stop" button): `stream.close()` is thread-safe; the
iterating thread then receives `CancelledException` from `next()` and any partial text already
delivered stays valid.

### 4.4 Tool calling (the host executes tools; the SDK only transports them)

```java
ToolDefinition readFile = ToolDefinition.builder("read_file")
    .description("Read a UTF-8 text file from the workspace")
    .parameters(JsonSchema.parse("""
        {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""))
    .build();

List<Message> history = new ArrayList<>(List.of(Message.user("What does build.gradle do?")));

while (true) {
    ChatResponse r = client.chat().send(ChatRequest.builder("gpt-5")
        .messages(history).tool(readFile).toolChoice(ToolChoice.auto()).build());
    history.add(r.message());                                     // round-trips reasoning + provider data
    if (r.toolCalls().isEmpty()) { System.out.println(r.text()); break; }
    for (ToolCall call : r.toolCalls()) {
        String path = call.arguments().getString("path");         // JsonObject; argumentsJson() also available
        history.add(Message.toolResult(ToolResult.of(call.id(), Files.readString(Path.of(path)))));
    }
}
```

### 4.5 Structured output

```java
ChatResponse r = client.chat().send(ChatRequest.builder("gemini-2.5-pro")
    .user("Extract the invoice.")
    .responseFormat(ResponseFormat.jsonSchema(invoiceSchema).strict())
    .build());
JsonObject invoice = r.json();                 // parsed once; throws InvalidResponseException if not JSON
// Optional module llm-transport-jackson: Invoice inv = Jackson.parse(r, Invoice.class);
```

### 4.6 OAuth login — desktop or CLI host (loopback redirect)

```java
Endpoint endpoint = Endpoint.builder(OpenAiCompatible.OPENROUTER)
    .credentials(Credentials.oauth())                    // provider preset supplies OAuthConfig defaults
    .build();

LlmClient client = LlmClient.builder(endpoint)
    .tokenStore(TokenStore.inFile(Path.of(home, ".myagent", "tokens.json")))
    .browserLauncher(BrowserLauncher.desktop())          // default when java.desktop is available
    .build();

if (client.auth().status().state() == AuthState.LOGIN_REQUIRED) {
    try (LoginSession login = client.auth().login()) {   // PKCE + loopback listener on 127.0.0.1:<ephemeral>
        switch (login.prompt()) {                        // sealed: what the user must do
            case AuthPrompt.OpenBrowser p  -> System.out.println("Opened " + p.url());     // launcher already did
            case AuthPrompt.EnterCode p    -> System.out.println("Visit " + p.verificationUrl() + " and enter " + p.userCode());
        }
        login.await(Duration.ofMinutes(5));              // blocks; tokens saved to the TokenStore
    }
}
client.chat().send("openrouter/auto", "hello");          // Bearer <access token>; refresh is automatic
```

### 4.7 OAuth login — web-hosted host (the host owns the redirect)

```java
// Server side: the browser is the user's remote browser; no loopback listener is started.
LoginSession login = client.auth().login(LoginOptions.externalRedirect(URI.create("https://app.example/oauth/cb")));
URI toOpen = ((AuthPrompt.OpenBrowser) login.prompt()).url();
sessions.put(login.state(), login);                      // login.state() is the CSRF state parameter
respondWith(redirect(toOpen));

// Later, in the /oauth/cb handler:
LoginSession login = sessions.remove(queryParam("state"));
login.complete(requestUri);                              // validates state, exchanges the code, stores tokens
login.close();
```

### 4.8 Discovery for a workflow-builder UI (no I/O until asked)

```java
ProviderRegistry providers = ProviderRegistry.discover();            // ServiceLoader over provider modules
for (LlmProvider p : providers.all()) {
    ui.addProviderChoice(p.id(), p.displayName());
    for (ConfigField f : p.configFields()) ui.addFormField(p.id(), f); // label, kind (TEXT/SECRET/URL/…), default, help
}

// Form submitted: values arrive as strings; secrets go through the host's secret store.
LlmProvider provider = providers.require(form.providerId());
Endpoint.Builder b = Endpoint.builder(provider).id(form.name());
for (ConfigField f : provider.configFields()) {
    if (f.kind() == ConfigField.Kind.SECRET) continue;                 // secrets are resolved below, never as text
    form.value(f.key()).ifPresent(v -> b.field(f, v));                 // parses and routes to the typed setter
}
Endpoint endpoint = b.credentials(ApiKey.of(secrets.resolve(form.secretRef("apiKey")))).build();
// build() throws IllegalArgumentException naming every invalid field — show it next to the form.

try (LlmClient client = LlmClient.builder(endpoint).httpTransport(sharedTransport).build()) {
    ConnectionCheck check = client.testConnection();          // non-billable: connect → auth → list models
    ui.showStatus(check.stage(), check.ok(), check.message(), check.latency());

    for (ModelInfo m : client.models().list()) {              // one network call, cached by the host if desired
        ui.addModel(m.id(), m.displayName().orElse(m.id().value()),
                    m.capabilities().support(Capability.TOOLS),      // SUPPORTED / UNSUPPORTED / UNKNOWN
                    m.contextWindow(),                               // OptionalLong
                    m.pricing().map(Pricing::inputPerMillion));      // Optional<BigDecimal>
    }
}
```

### 4.9 Events for progress and status (observation only)

```java
Registration reg = client.listeners().add(event -> {
    switch (event) {                                                   // sealed LlmEvent (Java 21 switch shown)
        case RequestEvent.Started s     -> ui.progress(s.requestId(), "sending to " + s.model());
        case RequestEvent.FirstToken f  -> ui.progress(f.requestId(), "streaming… (" + f.latency().toMillis() + " ms)");
        case RequestEvent.Retrying r    -> ui.progress(r.requestId(), "retry " + r.attempt() + " in " + r.delay());
        case RequestEvent.Completed c   -> ui.done(c.requestId(), c.usage());
        case RequestEvent.Failed f      -> ui.failed(f.requestId(), f.error().code());
        case AuthEvent.TokenRefreshed t -> ui.status("token refreshed for " + t.endpointId());
        default -> { }
    }
});
reg.close();   // unregister; all registrations are released on client.close()
```

Listeners run synchronously on the calling thread, never on an I/O thread, may not alter the call,
and an exception thrown by a listener is logged and swallowed.

### 4.10 Several endpoints in one host

```java
HttpTransport transport = HttpTransport.jdk();            // host-owned; shared pool
Map<String, LlmClient> clients = new HashMap<>();
for (Endpoint e : config.endpoints()) {
    clients.put(e.id(), LlmClient.builder(e).httpTransport(transport).listener(ui).build());
}
// … clients.get("work-anthropic").chat().send(…)
// close: each client (does not close the borrowed transport), then transport.close()
```

There is no router facade on purpose: the host always knows which endpoint is billed.

### 4.11 Failure

```java
try {
    client.chat().send(request);
} catch (RateLimitedException e) {                 // retries exhausted; carries retryAfter()
    scheduleLater(e.retryAfter().orElse(Duration.ofSeconds(10)));
} catch (AuthenticationException e) {              // 401/403, or code "login_required" for OAuth endpoints
    if (e.loginRequired()) ui.askToLogin(client);
} catch (InvalidRequestException e) {              // 400, unsupported option, context length
    if (e.code().equals("context_length_exceeded")) compactHistory();
} catch (LlmException e) {                         // root: code(), retryable(), statusCode(), requestId(), endpointId()
    log.warn("llm call failed: {} (request {})", e.code(), e.requestId().orElse("n/a"));
}
```

### 4.12 Lifecycle

```java
try (LlmClient client = OpenAi.client(ApiKey.fromEnv())) {   // owns its default transport
    …
}   // close(): idempotent; releases owned transport, executor, registrations; later calls throw IllegalStateException
```

### 4.13 Escape hatches (Layer 3)

```java
// Typed, namespaced provider option; fails at send() if the dialect cannot express it
ChatRequest req = ChatRequest.builder("claude-sonnet-4-5")
    .option(AnthropicOptions.THINKING_BUDGET, 8192)
    .option(AnthropicOptions.BETA, List.of("interleaved-thinking-2025-05-14"))
    .build();

// Preview the wire request without sending (dry run for UIs and tests)
PreparedRequest prepared = client.chat().prepare(req);
System.out.println(prepared.path() + "\n" + prepared.body().toPrettyString());   // headers redacted

// Raw access with library-owned types; auth, timeouts, retries and interceptors still apply
RawResponse credits = client.raw().get("/api/v1/credits");                     // @ApiStatus.Experimental
JsonValue body = credits.body();
```

### 4.14 Kotlin (no wrapper needed)

```kotlin
OpenAi.client(ApiKey.fromEnv()).use { client ->
    val r = client.chat().send("gpt-5") { it.system("Be terse.").user("Why seams?").maxOutputTokens(200) }
    println(r.text())
    client.chat().stream(ChatRequest.of("gpt-5", "Count to 3")).use { s ->
        for (e in s) if (e is ChatEvent.TextDelta) print(e.text())
    }
}
```

### 4.15 Extension — a new provider without code

```java
LlmProvider myGateway = LlmProvider.builder("corp-gw", OpenAi.CHAT_COMPLETIONS)   // id + dialect
    .displayName("Corp LLM Gateway")
    .defaultBaseUrl(URI.create("https://llm-gw.corp.example/v1"))
    .auth(AuthMethod.bearer())
    .apiKeyEnvVar("CORP_GW_API_KEY")
    .quirk(OpenAiQuirks.MAX_TOKENS_FIELD, "max_tokens")             // typed quirk declared by the dialect
    .quirk(OpenAiQuirks.STREAM_USAGE, false)
    .build();
LlmClient client = LlmClient.create(Endpoint.of(myGateway, ApiKey.fromEnv("CORP_GW_API_KEY")));
```

A new *dialect* (a genuinely new wire format) implements `Dialect` + `ChatCodec` and passes the
contract test kit; see §8 and §9.5.

**Wrong-usage check.** Forgetting the model or the key is impossible; forgetting to close a stream is
visible (`AutoCloseable` in every example); confusing key and organization is impossible (`ApiKey`,
typed option); the core never guesses a provider or a model; nothing performs I/O until a verb
(`send`, `stream`, `login`, `testConnection`, `list`) is called.

---

## 5. Architecture overview

### 5.1 Layers (internal), disclosure layers (public)

```
              PUBLIC API  (dev.llmtransport.*)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  LlmClient  ─ chat()  models()  auth()  testConnection()  listeners()  raw() │  facade + sub-APIs
 │  Endpoint / LlmProvider / Credentials         ChatRequest … ChatStream        │  unified model (immutable)
 │  LlmEvent / LlmListener                        LlmException hierarchy        │
 └──────────────────────────────┬───────────────────────────────────────────────┘
                                │ calls
 ┌──────────────────────────────▼───────────────────────────────────────────────┐
 │  internal.client   RequestExecutor: resolve options → encode → authenticate  │  execution
 │                    → interceptors → transport → decode | stream → events     │  (retry, deadline,
 │                    OptionResolver, EventDispatcher, ChatAccumulator          │   unsupported policy)
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  internal.auth     Authenticator (header/query/bearer), OAuthEngine          │  credentials
 │                    (PKCE, loopback listener, device poll, refresh single-     │
 │                    flight), FileTokenStore, InMemoryTokenStore               │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  SPI  Dialect / ChatCodec / StreamDecoder          (per wire format, pure)   │  codecs
 │       llm-transport-openai   : Responses, ChatCompletions codecs + presets   │
 │       llm-transport-anthropic: Messages codec                                │
 │       llm-transport-gemini   : generateContent codec                         │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  SPI  HttpTransport (JdkHttpTransport default) · WireInterceptor             │  transport
 │  internal.stream   SseFramer, NdjsonFramer      internal.json  parser/writer │
 └──────────────────────────────────────────────────────────────────────────────┘
```

Public disclosure is three-deep (unified API → typed provider options and quirks → raw JSON/HTTP);
the four internal layers are never a public concept.

### 5.2 One request, end to end

```
ChatRequest ──► OptionResolver (per-call > endpoint defaults > client policy; unset stays unset)
            ──► ChatCodec.encode(request, EncodeContext{provider quirks, endpoint options})
                    │  pure; may throw InvalidRequestException(unsupported_option) or drop + Warning per policy
            ──► WireRequest (method, path, headers, JSON body)
            ──► Authenticator.apply(credentials)      (header / query param / Bearer from TokenStore, refresh if due)
            ──► WireInterceptor chain (per attempt)   (host headers, tracing ids, request signing)
            ──► HttpTransport.execute / stream
            ──► retry loop (RetryPolicy, one total deadline; never after first stream event)
            ──► ChatCodec.decode(WireResponse)  |  StreamDecoder.feed(frame) → ChatEvent* → ChatAccumulator
            ──► events: Started · Sent · FirstToken · Retrying · Completed / Failed   (listeners, isolated)
            ──► ChatResponse (message, text, toolCalls, usage, finishReason, warnings, raw)
```

### 5.3 Design it twice — candidates compared

| Candidate | Shape | Verdict |
|---|---|---|
| A. Minimal | `client.send(Request) → sealed Result`; streaming via a flag; one `Provider` interface doing HTTP itself | rejected: every caller pays for the streaming `switch`; each provider re-implements retry/auth/SSE; no place for UI discovery |
| B. Common-caller (chosen) | `client.chat().send/stream/prepare`, sub-API per noun, consumer-builders, provider = data, dialect = pure codec, core owns execution | one-line simple case; a provider is a preset; changes concentrate in codecs; discovery is data the UI can read without I/O |
| C. Ports & adapters | as B with transport, clock, credentials, token store, browser launcher, executor injected | merged into B as the SPI tier (§8); core stays pure and testable with fakes |
| D. Multi-endpoint router (`Llm.chat("or:model")`) | one facade over N endpoints, string model refs | rejected for core: hides which endpoint is billed, couples parsing of model refs to the SDK; trivial for a host to build over B |

Trade-off accepted with B: a provider that needs *behaviour* beyond quirk flags (e.g. a signing
scheme) uses a `WireInterceptor` or graduates to its own dialect; the preset mechanism is not a
plugin system.

### 5.4 Platform and dependency rules

| Rule | Decision |
|---|---|
| Java baseline | 17 (`sealed`, records for small values, pattern `instanceof`). No `synchronized` across I/O; `ReentrantLock` only → scales on virtual threads (21+). |
| Core dependencies | JDK only, plus `org.jspecify:jspecify` (annotations) and `org.jetbrains:annotations` (`@ApiStatus`). Logging via `System.Logger`. |
| HTTP | `java.net.http.HttpClient` in core as `JdkHttpTransport`; documented workarounds: stream-idle timeout enforced by the framer watchdog (the JDK has no read timeout), HTTP/1.1 forced for cleartext `http://` local servers (avoids h2c upgrade confusion), no SOCKS (use the OkHttp module). |
| JSON | internal `dev.llmtransport.json` tree (`JsonValue`, `JsonObject`, `JsonArray`, …) with a bounded parser (depth, size) and a writer with **stable key order** (prompt-cache prefix determinism). Numbers keep their lexical form (`BigDecimal` accessor). No Jackson in core. |
| Loopback OAuth listener | `com.sun.net.httpserver` (module `jdk.httpserver`), bound to `127.0.0.1`, started only by `login()`. |
| Browser | `java.awt.Desktop` when `java.desktop` is present and not headless; otherwise the prompt is only reported. |
| Modules | `module-info.java` exporting API + SPI packages only; `Automatic-Module-Name` for classpath users; no split packages between artifacts. |
| Nullness | `@NullMarked` on every public package; `Optional`/`OptionalLong` only for normally-absent returns; empty collections never null. |
| Build | Gradle (Kotlin DSL), version catalog, `japicmp` on every release, JUnit 5, `examples` compiled as tests. |

---

## 6. Project structure

### 6.1 Artifacts

| Artifact | Contents | Depends on |
|---|---|---|
| `llm-transport-core` | API, SPI, internal execution, JDK transport, JSON, OAuth engine, token stores | JDK, jspecify, jetbrains-annotations |
| `llm-transport-openai` | `OpenAi` (Responses + Chat Completions dialects, options, quirks), `OpenAiCompatible` presets (OpenRouter, DeepSeek, xAI Grok, Qwen/DashScope, Mistral, Groq, Ollama `/v1`, LM Studio, vLLM, LiteLLM, Azure OpenAI, custom) | core |
| `llm-transport-anthropic` | `Anthropic` (Messages dialect, options incl. thinking, cache control, betas) | core |
| `llm-transport-gemini` | `Gemini` (generateContent dialect, options) | core |
| `llm-transport-testing` | `FakeDialect`, `ScriptedTransport`, `RecordingListener`, `LlmTesting` factories, `DialectContractTest`, `HttpTransportContractTest` | core, JUnit 5 |
| `llm-transport-bom` | version alignment | — |
| `examples` (not published) | README examples as tests, Java + Kotlin | all |
| later: `llm-transport-okhttp`, `llm-transport-jackson`, `llm-transport-kotlin`, `llm-transport-otel`, `llm-transport-ollama` (native NDJSON dialect), `llm-transport-aws` (SigV4 interceptor) | optional adapters | core |

### 6.2 Repository tree

```
llm-transport-sdk/
├── settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
├── docs/  (this proposal, requirements, extending.md, CHANGELOG.md)
├── llm-transport-bom/
├── llm-transport-core/
│   └── src/main/java/dev/llmtransport/                      (see §6.3)
│   └── src/main/java/module-info.java
│   └── src/test/java/…                                       (contract + unit tests)
├── llm-transport-openai/
│   └── src/main/java/dev/llmtransport/openai/
│       ├── OpenAi.java · OpenAiCompatible.java · OpenAiOptions.java · OpenAiQuirks.java
│       └── internal/  ResponsesCodec.java · ChatCompletionsCodec.java · ResponsesStreamDecoder.java …
├── llm-transport-anthropic/  … dev/llmtransport/anthropic/ Anthropic.java · AnthropicOptions.java · internal/
├── llm-transport-gemini/     … dev/llmtransport/gemini/    Gemini.java · GeminiOptions.java · internal/
├── llm-transport-testing/    … dev/llmtransport/testing/
└── examples/                 src/test/java + src/test/kotlin
```

### 6.3 Core package tree (API / SPI / internal)

```
dev.llmtransport                      API  LlmClient, Endpoint, ConnectionCheck, ModelId, Usage, OptionKey, QuirkKey,
                                           LlmException + AuthenticationException, RateLimitedException,
                                           RequestTimeoutException, InvalidRequestException, InvalidResponseException,
                                           ProviderException, TransportException, CancelledException, ContentFilteredException
dev.llmtransport.chat                 API  ChatApi, ChatRequest, ChatResponse, ChatStream, ChatEvent, PreparedRequest,
                                           Message, Content, Role, FinishReason, ResponseFormat, Reasoning, Effort, Warning, CacheHint
dev.llmtransport.tool                 API  ToolDefinition, ToolCall, ToolResult, ToolChoice
dev.llmtransport.model                API  ModelsApi, ModelInfo, Capability, SupportLevel, Capabilities, Pricing, MetadataSource
dev.llmtransport.embedding            API  (phase 2) EmbeddingsApi, EmbeddingRequest, EmbeddingResponse
dev.llmtransport.auth                 API  AuthApi, Credentials, ApiKey, BearerToken, OAuthTokens, AuthMethod, AuthStatus, AuthState,
                                           OAuthConfig, OAuthGrant, LoginSession, LoginOptions, AuthPrompt, RedirectMode
dev.llmtransport.provider             API  LlmProvider, ProviderRegistry, ConfigField, DialectId
dev.llmtransport.config               API  TimeoutPolicy, RetryPolicy, Backoff, HttpOptions, UnsupportedOptionPolicy
dev.llmtransport.event                API  LlmEvent, RequestEvent, AuthEvent, LlmListener, Registration, Listeners
dev.llmtransport.json                 API  JsonValue, JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull, JsonSchema
dev.llmtransport.raw                  API  RawApi (@Experimental), RawResponse, RawStream
dev.llmtransport.spi                  SPI  Dialect, ChatCodec, StreamDecoder, ModelListCodec, EmbeddingCodec, EncodeContext, DecodeContext,
                                           StreamFormat, WireRequest, WireResponse, WireFrame, WireBody, Headers, HttpTransport,
                                           TransportOptions, WireInterceptor, TokenStore, BrowserLauncher, ProviderBundle
dev.llmtransport.internal.client      —    DefaultLlmClient, DefaultChatApi, DefaultModelsApi, DefaultAuthApi, RequestExecutor,
                                           OptionResolver, ChatAccumulator, EventDispatcher, DefaultChatStream
dev.llmtransport.internal.http        —    JdkHttpTransport, HttpOptionsApplier, Redaction
dev.llmtransport.internal.stream      —    SseFramer, NdjsonFramer, IdleWatchdog
dev.llmtransport.internal.json        —    JsonParser, JsonWriter
dev.llmtransport.internal.auth        —    Authenticator, OAuthEngine, Pkce, LoopbackListener, DeviceFlow, RefreshCoordinator,
                                           InMemoryTokenStore, FileTokenStore
dev.llmtransport.internal.registry    —    ServiceLoaderProviders
```

Rules: packages by noun, never by layer; import depth ≤ 3 below the root; `internal` is not
exported by `module-info` and carries `@ApiStatus.Internal`; one public concept per file with nested
types only for tightly coupled pairs (`ChatRequest.Builder`, `ChatEvent.TextDelta`, `Content.Text`).

### 6.4 Surface partition (deletion test applied)

| Tier | Types | Deletion-test note |
|---|---|---|
| API root | `LlmClient`, `Endpoint`, `ModelId`, `Usage`, `OptionKey`, exceptions | glossary |
| API sub-packages | `chat`, `tool`, `model`, `auth`, `provider`, `config`, `event`, `json`, `raw` | each groups ≥ 2 operations or ≥ 3 value types sharing a noun |
| SPI | `Dialect`, `ChatCodec`, `StreamDecoder`, `HttpTransport`, `WireInterceptor`, `TokenStore`, `BrowserLauncher` | each has a shipped default *and* a second real implementation path (provider modules, OkHttp, file/keychain store, headless launcher, test fakes) |
| Internal | everything under `internal` | `RequestExecutor`: deleting it recreates the retry/auth/event loop in every dialect → keep. `MessageFactory`-style helpers: deleted, folded into `Message` statics. `EventBus`: deleted; `EventDispatcher` is a private list + isolation. |

---

## 7. Core API sketch (stub signatures)

Conventions used below: record-style accessors (`timeout()`, not `getTimeout()`); static factories
over constructors; builders for ≥ 4 parameters or ≥ 2 optional ones; `final` classes unless designed
for extension; API interfaces that consumers must not implement are `@ApiStatus.NonExtendable`;
every public type states *immutable*, *thread-safe* or *not thread-safe*.

### 7.1 Facade

```java
package dev.llmtransport;

/** Thread-safe; share one instance per endpoint. Owns resources it created; close() is idempotent.
 *  Not for implementation by consumers (methods may be added); use llm-transport-testing fakes. */
@ApiStatus.NonExtendable
public interface LlmClient extends AutoCloseable {
    static LlmClient create(Endpoint endpoint)  { return builder(endpoint).build(); }   // all defaults
    static Builder   builder(Endpoint endpoint) { … }

    Endpoint   endpoint();
    ChatApi    chat();
    ModelsApi  models();
    AuthApi    auth();
    Listeners  listeners();
    /** Non-billable, layered check: CONFIG → CONNECT → AUTH → MODELS. Performs network I/O. */
    ConnectionCheck testConnection();
    /** Escape hatch: raw HTTP with the endpoint's auth, timeouts, retry and interceptors. */
    @ApiStatus.Experimental RawApi raw();
    /** Copy of this client's policies for another endpoint. Owned transport is NOT shared; injected one is. */
    Builder toBuilder();
    @Override void close();

    /** Not thread-safe. build() validates everything and performs no I/O. */
    final class Builder {
        public Builder endpoint(Endpoint endpoint);
        public Builder timeouts(TimeoutPolicy policy);
        public Builder timeouts(Consumer<TimeoutPolicy.Builder> edit);          // edits the current value
        public Builder retry(RetryPolicy policy);
        public Builder retry(Consumer<RetryPolicy.Builder> edit);
        public Builder http(HttpOptions options);                                // proxy, TLS; default transport only
        public Builder http(Consumer<HttpOptions.Builder> edit);
        public Builder httpTransport(HttpTransport transport);                   // injected → borrowed, never closed here
        public Builder unsupportedOptions(UnsupportedOptionPolicy policy);       // default FAIL
        public Builder tokenStore(TokenStore store);                             // default in-memory
        public Builder browserLauncher(BrowserLauncher launcher);                // default desktop-or-none
        public Builder listener(LlmListener listener);                           // singular adds
        public Builder interceptor(WireInterceptor interceptor);                 // singular adds, ordered
        public LlmClient build();                                                // IllegalArgumentException listing all violations
    }
}

/** Immutable. Result of testConnection(): stops at the first failing stage. */
public final class ConnectionCheck {
    public enum Stage { CONFIG, CONNECT, AUTH, MODELS }
    public Stage stage();  public boolean ok();  public String message();  public Duration latency();
    public OptionalInt modelCount();  public Optional<LlmException> error();
}
```

### 7.2 Endpoint, provider, registry

```java
package dev.llmtransport;

/** Immutable, thread-safe. Where and how to reach one provider. Safe to keep in host config
 *  (credentials excluded via toBuilder().credentials(…) at load time; toString redacts). */
public final class Endpoint {
    public static Endpoint of(LlmProvider provider, Credentials credentials) { … }
    public static Builder  builder(LlmProvider provider) { … }

    public String            id();              // default: provider id; used in events and as TokenStore key
    public LlmProvider       provider();
    public Dialect           dialect();         // provider default unless overridden
    public URI               baseUrl();
    public Credentials       credentials();
    public Map<String,String> headers();
    public Optional<ModelId> defaultModel();
    public <T> Optional<T>   option(OptionKey<T> key);   // endpoint-level provider options (org, project, api-version…)
    public Builder           toBuilder();

    public static final class Builder {
        public Builder id(String id);
        public Builder baseUrl(URI url);
        public Builder credentials(Credentials credentials);
        public Builder header(String name, String value);            // singular adds
        public Builder headers(Map<String,String> headers);          // plural replaces
        public Builder defaultModel(String model);  public Builder defaultModel(ModelId model);
        public Builder dialect(Dialect dialect);                     // e.g. OpenAi.CHAT_COMPLETIONS instead of RESPONSES
        public <T> Builder option(OptionKey<T> key, T value);
        public Builder field(ConfigField field, String rawValue);    // UI form path: parses + routes to the typed setter
        public Builder oauth(Consumer<OAuthConfig.Builder> edit);    // override the provider's OAuth defaults
        public Endpoint build();      // validates: credentials type ∈ provider.authMethods(), baseUrl absolute, …
    }
}
```

```java
package dev.llmtransport.provider;

/** Immutable, thread-safe. A provider is DATA: everything about a backend family that is not a wire format. */
public final class LlmProvider {
    public static Builder builder(String id, Dialect dialect) { … }

    public String              id();               // "openai", "anthropic", "deepseek", "corp-gw"
    public String              displayName();
    public Dialect             dialect();
    public URI                 defaultBaseUrl();
    public List<AuthMethod>    authMethods();      // first = preferred
    public Optional<String>    apiKeyEnvVar();     // for ApiKey.fromEnv() on this provider
    public Optional<OAuthConfig> oauthDefaults();
    public List<ConfigField>   configFields();     // for UI forms; always includes BASE_URL and the auth field(s)
    public <T> Optional<T>     quirk(QuirkKey<T> key);
    public List<ModelInfo>     knownModels();      // bundled fallback catalog; may be empty; source = BUNDLED
    public Builder             toBuilder();        // presets are cloned and adjusted, never mutated

    public static final class Builder { /* displayName, defaultBaseUrl, auth(AuthMethod), apiKeyEnvVar,
        oauthDefaults(OAuthConfig), configField(ConfigField), quirk(QuirkKey<T>, T), knownModel(ModelInfo), build() */ }
}

/** Immutable. Describes one form field so a UI can render it without knowing the provider. */
public final class ConfigField {
    public enum Kind { TEXT, SECRET, URL, NUMBER, BOOLEAN, CHOICE }
    public static final ConfigField BASE_URL, API_KEY;                       // core fields
    public static <T> ConfigField of(OptionKey<T> key, Kind kind, String label) { … }   // provider field
    public String key(); public String label(); public Kind kind(); public boolean required();
    public Optional<String> defaultValue(); public Optional<String> help(); public List<String> choices();
    public Optional<String> group();                                         // "Connection", "Advanced"
}

/** Thread-safe. Explicit registration always wins over ServiceLoader discovery. */
public final class ProviderRegistry {
    public static ProviderRegistry discover() { … }        // ServiceLoader<ProviderBundle> over provider modules
    public static ProviderRegistry empty() { … }
    public ProviderRegistry register(LlmProvider provider); // returns a new registry (immutable style)
    public List<LlmProvider>     all();
    public Optional<LlmProvider> find(String id);
    public LlmProvider           require(String id);       // IllegalArgumentException naming known ids and the module to add
}
```

`Dialect`, `AuthMethod`, `QuirkKey` are declared by dialect modules (`OpenAi.RESPONSES`,
`OpenAiQuirks.MAX_TOKENS_FIELD`) — see §8 and §9.

### 7.3 Chat model

```java
package dev.llmtransport.chat;

@ApiStatus.NonExtendable
public interface ChatApi {
    /** Blocks until complete or the total deadline; retries per RetryPolicy. @throws LlmException */
    ChatResponse send(ChatRequest request);
    default ChatResponse send(String model, String userText)                       { return send(ChatRequest.of(model, userText)); }
    default ChatResponse send(String model, Consumer<ChatRequest.Builder> spec)     { … }
    /** Uses Endpoint.defaultModel(); @throws IllegalStateException if none configured. */
    default ChatResponse send(String userText)                                     { … }
    /** Single-use, closeable, bounded; close() cancels; never retried after the first event. */
    ChatStream stream(ChatRequest request);
    /** Encode only — no I/O, no credentials. For previews, dry runs and tests. */
    PreparedRequest prepare(ChatRequest request);
}

/** Immutable, thread-safe. Only explicitly set fields are ever serialized. */
public final class ChatRequest {
    public static ChatRequest of(String model, String userText) { … }
    public static Builder builder(String model) { … }
    public static Builder builder(ModelId model) { … }

    public ModelId model();  public List<Message> messages();  public Optional<String> system();
    public List<ToolDefinition> tools();  public Optional<ToolChoice> toolChoice();
    public Optional<ResponseFormat> responseFormat();  public Optional<Reasoning> reasoning();
    public OptionalDouble temperature();  public OptionalDouble topP();  public OptionalInt maxOutputTokens();
    public List<String> stop();  public OptionalLong seed();  public Optional<Duration> timeout();
    public <T> Optional<T> option(OptionKey<T> key);  public Map<OptionKey<?>,Object> options();
    public Builder toBuilder();

    /** Not thread-safe. Singular adds, plural replaces; last write wins for scalars. */
    public static final class Builder {
        public Builder system(String text);
        public Builder user(String text);           public Builder user(Content... parts);
        public Builder assistant(String text);
        public Builder message(Message m);          public Builder messages(List<Message> ms);
        public Builder tool(ToolDefinition t);      public Builder tools(List<ToolDefinition> ts);
        public Builder toolChoice(ToolChoice c);
        public Builder responseFormat(ResponseFormat f);
        public Builder reasoning(Reasoning r);
        public Builder temperature(double t);       public Builder topP(double p);
        public Builder maxOutputTokens(int n);      public Builder stop(String s);  public Builder stop(List<String> ss);
        public Builder seed(long seed);
        public Builder timeout(Duration perCallTotal);          // per-call override of TimeoutPolicy.total
        public <T> Builder option(OptionKey<T> key, T value);   // typed provider/dialect option
        public ChatRequest build();
    }
}

public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

/** Immutable. One turn. */
public final class Message {
    public static Message user(String text);  public static Message user(Content... parts);
    public static Message assistant(String text);  public static Message system(String text);
    public static Message toolResult(ToolResult result);  public static Message toolResults(List<ToolResult> results);
    public static Builder builder(Role role);
    public Role role();  public List<Content> content();  public Optional<String> name();
    public String text();                                   // concatenated Text parts, "" if none
    public Optional<CacheHint> cacheHint();                 // explicit prompt-cache breakpoint (Anthropic-style)
    public Message withCacheHint(CacheHint hint);
}

/** Sealed content parts; Unknown carries the raw provider JSON so history round-trips losslessly. */
public sealed interface Content permits Content.Text, Content.Image, Content.Document, Content.ToolCall,
                                       Content.ToolResult, Content.Reasoning, Content.Unknown {
    static Text     text(String s);
    static Image    image(URI url);          static Image    image(byte[] bytes, String mediaType);
    static Document document(byte[] bytes, String mediaType, String fileName);
    static Content  file(Path path);         // MIME sniffed by extension + magic bytes → Image or Document

    record Text(String text) implements Content {}
    final class Image implements Content     { public Optional<URI> url(); public Optional<byte[]> bytes(); public String mediaType(); public Optional<Detail> detail(); }
    final class Document implements Content  { … }
    final class ToolCall implements Content  { public dev.llmtransport.tool.ToolCall call(); }
    final class ToolResult implements Content{ public dev.llmtransport.tool.ToolResult result(); }
    /** Provider-bound: text may be redacted; signature/opaque data must be replayed to the same provider. */
    final class Reasoning implements Content { public Optional<String> text(); public Optional<String> signature(); public JsonValue providerData(); }
    final class Unknown implements Content   { public String type(); public JsonValue raw(); }
}

/** Immutable, thread-safe. Evolving model → final class with accessors, not a record. */
public final class ChatResponse {
    public Optional<String> id();               // provider response id (Responses API continuation etc.)
    public ModelId model();                     // as reported by the provider
    public Message message();                   // assistant turn; append it to history as-is
    public String text();                       // convenience; never replaces structured access
    public JsonObject json();                   // parsed text; @throws InvalidResponseException
    public List<ToolCall> toolCalls();
    public Optional<Content.Reasoning> reasoning();
    public FinishReason finishReason();
    public Usage usage();
    public List<Warning> warnings();            // dropped options, stripped parts, provider warnings
    public RawResponse raw();                   // status, headers (redacted), body JSON
    public String endpointId();
}

/** Open value type: providers add reasons. */
public final class FinishReason {
    public static final FinishReason STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, CANCELLED, UNKNOWN;
    public static FinishReason of(String raw);  public String raw();
}

public record Warning(String code, String message) {}      // "option_dropped", "reasoning_stripped", …

/** Explicit prompt-cache breakpoint on a message; ignored with a Warning where the dialect has no explicit cache control. */
public final class CacheHint { public static CacheHint ephemeral(); public Optional<Duration> ttl(); }

public final class Reasoning {                              // unset ≠ off
    public static Reasoning effort(Effort e);  public static Reasoning budget(int tokens);  public static Reasoning off();
    public Optional<Effort> effort();  public OptionalInt budgetTokens();  public boolean enabled();
}
public enum Effort { MINIMAL, LOW, MEDIUM, HIGH, MAX }     // codecs map to provider scales; unmapped → unsupported_option

public final class ResponseFormat {
    public static ResponseFormat text();  public static ResponseFormat json();
    public static ResponseFormat jsonSchema(JsonSchema schema);
    public ResponseFormat strict();  public ResponseFormat named(String name);
}
```

```java
package dev.llmtransport;

/** Immutable. Absent means "the provider did not report it" — never 0. */
public final class Usage {
    public OptionalLong inputTokens();  public OptionalLong outputTokens();
    public OptionalLong cachedInputTokens();  public OptionalLong cacheWriteTokens();  public OptionalLong reasoningTokens();
    public OptionalLong totalTokens();          // reported or derived only when both parts are present
    public JsonValue raw();
    public static Usage none();
}
public record ModelId(String value) { public static ModelId of(String v) { … } }
```

### 7.4 Streaming

```java
package dev.llmtransport.chat;

/** Single consumer, single iteration (second iterator() throws IllegalStateException). Bounded: the
 *  reader is back-pressured by the blocking socket read; no unbounded queue. close() is thread-safe,
 *  idempotent, cancels the HTTP request and releases the connection. */
@ApiStatus.NonExtendable
public interface ChatStream extends Iterable<ChatEvent>, AutoCloseable {
    @Override Iterator<ChatEvent> iterator();
    Stream<ChatEvent> toStream();                 // closing the Stream closes this
    /** After completion: the aggregate, equal to send()'s result. Before completion: drains the rest, then returns. */
    ChatResponse response();
    Optional<ChatResponse> partial();             // best-effort snapshot at any time (for UIs after failure)
    @Override void close();
}

/** Sealed. Every variant is immutable and carries the stream-local index it belongs to where relevant. */
public sealed interface ChatEvent permits ChatEvent.Started, ChatEvent.TextDelta, ChatEvent.ReasoningDelta,
        ChatEvent.ToolCallStarted, ChatEvent.ToolCallDelta, ChatEvent.ToolCallCompleted,
        ChatEvent.UsageUpdated, ChatEvent.Finished, ChatEvent.Unknown {
    record Started(Optional<String> responseId, Optional<ModelId> model)              implements ChatEvent {}
    record TextDelta(int index, String text)                                          implements ChatEvent {}
    record ReasoningDelta(int index, String text)                                     implements ChatEvent {}
    record ToolCallStarted(int index, String id, String name)                         implements ChatEvent {}
    record ToolCallDelta(int index, String argumentsJsonFragment)                     implements ChatEvent {}
    record ToolCallCompleted(int index, ToolCall toolCall)                            implements ChatEvent {}
    record UsageUpdated(Usage usage)                                                  implements ChatEvent {}
    record Finished(FinishReason reason, ChatResponse response)                       implements ChatEvent {}
    record Unknown(String type, JsonValue raw)                                        implements ChatEvent {}
}

/** Encoded but not sent. Headers are redacted; body is the exact JSON that would go on the wire. */
public final class PreparedRequest { public String method(); public String path(); public Headers headers(); public JsonValue body(); public List<Warning> warnings(); }
```

Mid-stream transport failure surfaces from `next()` as the library exception (`TransportException`
with code `stream_interrupted`, or `CancelledException`); events already delivered stay valid and
`partial()` still works. Records are used for events because their shape is their meaning; adding a
variant is a documented minor change and consumers are told to keep a `default` branch.

### 7.5 Tools

```java
package dev.llmtransport.tool;

/** Immutable. A function the model may call. Hosted/provider tools go through provider modules
 *  (e.g. AnthropicTools.computerUse()) which return a ToolDefinition carrying providerData. */
public final class ToolDefinition {
    public static Builder builder(String name) { … }
    public String name();  public Optional<String> description();  public JsonSchema parameters();  public boolean strict();
    public JsonValue providerData();
    public static final class Builder { /* description, parameters(JsonSchema), strict(), providerData(JsonValue), build() */ }
}
public final class ToolCall {                       // immutable; id() is synthesized when the provider gives none (Gemini)
    public String id();  public String name();  public JsonObject arguments();  public String argumentsJson();
    public static ToolCall of(String id, String name, JsonObject arguments);
}
public final class ToolResult {
    public static ToolResult of(String callId, String text);  public static ToolResult of(String callId, JsonValue json);
    public static ToolResult error(String callId, String message);
    public String callId();  public List<Content> content();  public boolean isError();
}
public final class ToolChoice {
    public static ToolChoice auto();  public static ToolChoice none();  public static ToolChoice required();  public static ToolChoice tool(String name);
}
```

### 7.6 Models and capabilities

```java
package dev.llmtransport.model;

@ApiStatus.NonExtendable
public interface ModelsApi {
    /** Network call to the provider's list endpoint; falls back to provider.knownModels() only if the
     *  provider has no list endpoint (then source() == BUNDLED). Never merges silently. */
    List<ModelInfo> list();
    ModelInfo get(ModelId id);                 // @throws InvalidRequestException(code "model_not_found")
}

/** Immutable, evolving → final class + builder. Absent/UNKNOWN means not reported, never "no". */
public final class ModelInfo {
    public ModelId id();  public Optional<String> displayName();  public Optional<String> family();
    public OptionalLong contextWindow();  public OptionalLong maxOutputTokens();
    public Capabilities capabilities();  public Optional<Pricing> pricing();
    public MetadataSource source();  public Optional<Instant> fetchedAt();  public Optional<Instant> deprecatedAt();
    public JsonValue raw();
    public static Builder builder(ModelId id) { … }
}
public enum Capability { CHAT, STREAMING, TOOLS, PARALLEL_TOOLS, STRUCTURED_OUTPUT, JSON_MODE, VISION, DOCUMENTS, AUDIO_INPUT,
                         REASONING, PROMPT_CACHING, SYSTEM_PROMPT, EMBEDDINGS }
public enum SupportLevel { SUPPORTED, UNSUPPORTED, UNKNOWN }
public final class Capabilities { public SupportLevel support(Capability c); public Set<Capability> supported(); public static Capabilities unknown(); }
public enum MetadataSource { LIVE, BUNDLED, CONFIGURED }
/** Per million tokens, decimal, currency-tagged; a missing component is absent, never zero. */
public final class Pricing { public Optional<BigDecimal> inputPerMillion(); public Optional<BigDecimal> outputPerMillion();
    public Optional<BigDecimal> cachedInputPerMillion(); public Currency currency(); public Optional<BigDecimal> estimate(Usage usage); }
```

### 7.7 Authentication

```java
package dev.llmtransport.auth;

/** What is presented to the endpoint. Secrets redact in toString; never serialized. */
public sealed interface Credentials permits ApiKey, BearerToken, Credentials.OAuth, Credentials.None, Credentials.Supplied {
    static ApiKey      apiKey(String value);
    static BearerToken bearer(String token);
    static Credentials none();
    /** OAuth using the provider's oauthDefaults() (or Endpoint.Builder.oauth(…)); tokens live in the client's TokenStore. */
    static Credentials oauth();
    /** Host-managed rotation or cloud IAM: the supplier is asked once per attempt; must be fast and thread-safe. */
    static Credentials supplied(Supplier<? extends Credentials> supplier);

    final class None implements Credentials {}
    final class OAuth implements Credentials { public Optional<OAuthConfig> overrides(); }
    final class Supplied implements Credentials { public Supplier<? extends Credentials> supplier(); }
}
public final class ApiKey implements Credentials {
    public static ApiKey of(String value);                          // rejects blank
    public static ApiKey fromEnv();                                 // provider's apiKeyEnvVar(); resolved at Endpoint.build()
    public static ApiKey fromEnv(String variable);                  // explicit; throws with the variable name if missing
    public String fingerprint();  @Override public String toString(); // "ApiKey[sk-…a1b2]"
}
public final class BearerToken implements Credentials { public static BearerToken of(String token); }

/** How a provider accepts credentials; declared by presets, validated at Endpoint.build(). */
public final class AuthMethod {
    public static AuthMethod bearer();                       // Authorization: Bearer <key>
    public static AuthMethod header(String name);            // x-api-key: <key>
    public static AuthMethod queryParam(String name);        // ?key=… (opt-in; excluded from logs)
    public static AuthMethod none();
    public static AuthMethod oauth(OAuthConfig defaults);
}

@ApiStatus.NonExtendable
public interface AuthApi {
    AuthStatus status();                                     // no I/O: reads the TokenStore and expiry
    /** Interactive login. Picks AUTHORIZATION_CODE (PKCE + loopback) when a browser can be used,
     *  else DEVICE_CODE if the provider supports it. @throws IllegalStateException for non-OAuth endpoints. */
    LoginSession login();
    LoginSession login(LoginOptions options);
    /** Force a refresh now (single-flight). @throws AuthenticationException(code "refresh_failed"). */
    AuthStatus refresh();
    /** Revoke where supported, then delete stored tokens. Never throws on a missing token. */
    void logout();
}

public final class AuthStatus { public AuthState state(); public Optional<Instant> expiresAt(); public Optional<String> account(); public Set<String> scopes(); }
public enum AuthState { NOT_APPLICABLE /* API key etc. */, AUTHENTICATED, EXPIRING /* refresh due */, LOGIN_REQUIRED }

/** One interactive login. Not thread-safe except cancel()/close(). Owns the loopback listener (if any). */
public interface LoginSession extends AutoCloseable {
    String state();                                          // OAuth state parameter (CSRF); key for external redirect handling
    AuthPrompt prompt();                                     // what the user must do; also emitted as AuthEvent.PromptIssued
    /** Blocks until tokens are stored, the timeout elapses, or cancel(). @throws AuthenticationException */
    OAuthTokens await(Duration timeout);
    /** External-redirect mode only: hand the full callback URI (or the code) back to the session. */
    void complete(URI callbackUri);   void complete(String code);
    void cancel();
    @Override void close();                                  // cancels if pending; stops the listener
}
public sealed interface AuthPrompt permits AuthPrompt.OpenBrowser, AuthPrompt.EnterCode {
    record OpenBrowser(URI url, boolean launcherOpenedIt) implements AuthPrompt {}
    record EnterCode(URI verificationUrl, String userCode, Optional<URI> completeUrl, Duration expiresIn) implements AuthPrompt {}
}
public final class LoginOptions {
    public static LoginOptions defaults();
    public static LoginOptions externalRedirect(URI redirectUri);   // host handles the callback; no listener
    public static LoginOptions deviceCode();
    public LoginOptions grant(OAuthGrant grant);  public LoginOptions scopes(Set<String> scopes);  public LoginOptions account(String hint);
}
public enum OAuthGrant { AUTHORIZATION_CODE, DEVICE_CODE, CLIENT_CREDENTIALS }

/** Immutable. Provider presets supply defaults; hosts override per endpoint. */
public final class OAuthConfig {
    public static Builder builder(String clientId) { … }
    public String clientId();  public Optional<String> clientSecret();          // confidential clients only; redacted
    public URI authorizationEndpoint();  public URI tokenEndpoint();  public Optional<URI> deviceAuthorizationEndpoint();
    public Optional<URI> revocationEndpoint();  public Set<String> scopes();  public Set<OAuthGrant> grants();
    public RedirectMode redirect();  public Map<String,String> extraAuthorizationParams();
    public Optional<Function<JsonObject, OAuthTokens>> tokenResponseMapper();   // non-standard responses (OpenRouter returns {"key": …})
    public static final class Builder { /* … always PKCE S256; build() validates https except loopback … */ }
}
public sealed interface RedirectMode permits RedirectMode.Loopback, RedirectMode.External {
    static Loopback loopback();                                     // 127.0.0.1, ephemeral port, path "/callback"
    static Loopback loopback(int fixedPort, String path);           // providers with fixed registered redirect URIs
    static External external(URI redirectUri);
    record Loopback(OptionalInt port, String path, Optional<String> successHtml) implements RedirectMode {}
    record External(URI redirectUri) implements RedirectMode {}
}
/** Immutable; secrets redacted. */
public final class OAuthTokens { public String accessToken(); public Optional<String> refreshToken(); public Optional<Instant> expiresAt();
    public Set<String> scopes(); public Optional<String> account(); public JsonValue raw(); }
```

### 7.8 Events

```java
package dev.llmtransport.event;

@FunctionalInterface public interface LlmListener { void onEvent(LlmEvent event); }
public interface Registration extends AutoCloseable { @Override void close(); }
public interface Listeners { Registration add(LlmListener l); }

/** Sealed, immutable, secret-free. Consumers keep a default branch: variants may be added in minors. */
public sealed interface LlmEvent permits RequestEvent, AuthEvent {
    String endpointId();  Instant at();
}
public sealed interface RequestEvent extends LlmEvent permits RequestEvent.Started, RequestEvent.Sent, RequestEvent.FirstToken,
        RequestEvent.Retrying, RequestEvent.OptionDropped, RequestEvent.Completed, RequestEvent.Failed, RequestEvent.Cancelled {
    String requestId();                                     // SDK-local id; provider id lives on the response
    record Started(String endpointId, Instant at, String requestId, String operation, ModelId model, boolean streaming) implements RequestEvent {}
    record Sent(String endpointId, Instant at, String requestId, int attempt)                           implements RequestEvent {}
    record FirstToken(String endpointId, Instant at, String requestId, Duration latency)                 implements RequestEvent {}
    record Retrying(String endpointId, Instant at, String requestId, int attempt, Duration delay, LlmException cause) implements RequestEvent {}
    record OptionDropped(String endpointId, Instant at, String requestId, String option, String reason)  implements RequestEvent {}
    record Completed(String endpointId, Instant at, String requestId, Duration latency, Usage usage, FinishReason reason, Optional<String> providerRequestId) implements RequestEvent {}
    record Failed(String endpointId, Instant at, String requestId, Duration latency, LlmException error, boolean outcomeUnknown) implements RequestEvent {}
    record Cancelled(String endpointId, Instant at, String requestId, boolean partialOutput)             implements RequestEvent {}
}
public sealed interface AuthEvent extends LlmEvent permits AuthEvent.PromptIssued, AuthEvent.LoginCompleted, AuthEvent.LoginFailed,
        AuthEvent.TokenRefreshed, AuthEvent.RefreshFailed, AuthEvent.LoggedOut { … }
```

### 7.9 Configuration objects

```java
package dev.llmtransport.config;

/** Immutable. Defaults: connect 10 s · firstByte 120 s · streamIdle 60 s · total 10 min. */
public final class TimeoutPolicy {
    public static TimeoutPolicy defaults();  public static TimeoutPolicy forLocalModels();   // longer firstByte (model load)
    public static Builder builder();  public Builder toBuilder();
    public Duration connect();  public Duration firstByte();  public Duration streamIdle();  public Duration total();
    public static final class Builder { /* connect, firstByte, streamIdle, total, noTotalTimeout(); zero/negative rejected */ }
}
/** Immutable. Defaults: maxAttempts 3; retry on network-before-send, 408, 409, 429, 500, 502, 503, 504, 529;
 *  honours Retry-After; full-jitter exponential backoff 500 ms ×2 capped at 8 s; never after a stream event;
 *  never after an ambiguous post-send failure. */
public final class RetryPolicy {
    public static RetryPolicy defaults();  public static RetryPolicy none();
    public static Builder builder();  public Builder toBuilder();
    public int maxAttempts();  public Backoff backoff();  public Set<Integer> retryOnStatus();  public Duration maxRetryAfter();
    public static final class Builder { /* maxAttempts(≥1), backoff(Backoff), retryOnStatus(Set), maxRetryAfter(Duration) */ }
}
public final class Backoff { public static Backoff exponential(Duration initial, double multiplier, Duration cap); public static Backoff fixed(Duration d); }
/** Consumed by the default JdkHttpTransport only; an injected transport ignores it (build() warns). */
public final class HttpOptions { /* proxy(ProxySelector | URI), trustStore(Path, char[]), clientCertificate(...), insecureSkipTlsVerification() (logs WARN at build), userAgent(String), http1Only() */ }
public enum UnsupportedOptionPolicy { FAIL, DROP_WITH_WARNING }
```

### 7.10 JSON, options, raw

```java
package dev.llmtransport.json;
/** Immutable JSON tree; the only JSON type on public signatures. Stable key order on write. */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
    static JsonValue parse(String json);   String toJson();   String toPrettyString();
}
public final class JsonObject implements JsonValue { public Optional<JsonValue> get(String k); public String getString(String k); public Map<String,JsonValue> members(); public static Builder builder(); }
public final class JsonSchema { public static JsonSchema parse(String json); public static JsonSchema of(JsonObject o); public JsonObject asObject(); }
```

```java
package dev.llmtransport;
/** Typed, namespaced key for provider/dialect-specific options; declared as constants by provider modules. */
public final class OptionKey<T> {
    public static <T> OptionKey<T> of(String namespace, String name, Class<T> type) { … }
    public String namespace();  public String name();  public Class<T> type();
}
/** Same shape as OptionKey, but describes a PROVIDER trait read by a codec (set on presets, not per request). */
public final class QuirkKey<T> { public static <T> QuirkKey<T> of(String namespace, String name, Class<T> type, T defaultValue) { … } }
```

```java
package dev.llmtransport.raw;
@ApiStatus.Experimental
public interface RawApi {
    RawResponse get(String path);
    RawResponse post(String path, JsonValue body);
    RawStream   postStream(String path, JsonValue body);   // framed SSE/NDJSON as WireFrame, closeable
}
public final class RawResponse { public int status(); public Headers headers(); public JsonValue body(); public String bodyText(); }
```

Jackson binding (`Jackson.parse(response, Invoice.class)`, `JsonSchemas.of(Invoice.class)`) lives in the
optional `llm-transport-jackson` module so the core never exposes or depends on Jackson types.

---

## 8. SPI (implemented by dialect authors and hosts)

Every SPI type: 1–3 abstract methods, library-owned types only, grows by `default` methods, ships a
default implementation and a test fake, documents the five contract rows (thread, ordering, error
isolation, blocking, lifecycle).

### 8.1 Dialect and codecs (the only way to add a wire format)

```java
package dev.llmtransport.spi;

/** Stateless, thread-safe. A wire-format family. One instance per version (OpenAi.RESPONSES, OpenAi.CHAT_COMPLETIONS). */
public interface Dialect {
    DialectId id();                                          // "openai-responses", "anthropic-messages"
    ChatCodec chat();
    default Optional<ModelListCodec> models()      { return Optional.empty(); }
    default Optional<EmbeddingCodec> embeddings()  { return Optional.empty(); }   // phase 2
}

/** Pure functions; no I/O, no credentials (the core adds auth). Thread-safe. */
public interface ChatCodec {
    /** @throws InvalidRequestException(code "unsupported_option") unless ctx.policy() == DROP_WITH_WARNING, then ctx.warn(…). */
    WireRequest  encode(ChatRequest request, EncodeContext ctx);
    ChatResponse decode(WireResponse response, DecodeContext ctx);
    /** Translate a non-2xx or malformed response into the library hierarchy; keep the raw body (redacted) as detail. */
    LlmException decodeError(WireResponse response, DecodeContext ctx);
    StreamFormat streamFormat();                             // SSE or NDJSON; the core frames it
    /** New stateful decoder per stream: frames in, events out; must emit Finished exactly once. */
    StreamDecoder newStreamDecoder(DecodeContext ctx);
}
public interface StreamDecoder {
    List<ChatEvent> feed(WireFrame frame);                   // may return empty (keep-alive) or several events
    List<ChatEvent> end();                                   // stream closed by the server without a terminal event
}
public enum StreamFormat { SSE, NDJSON }

/** What a codec may read: provider quirks, endpoint options, request options, policy; and a warning sink. */
public interface EncodeContext { LlmProvider provider(); Endpoint endpoint(); UnsupportedOptionPolicy policy(); void warn(Warning w); }
public interface DecodeContext { LlmProvider provider(); Endpoint endpoint(); ModelId requestedModel(); void warn(Warning w); }

/** Library-owned wire types (never OkHttp/JDK types on SPI signatures). Immutable. */
public final class WireRequest  { public String method(); public String path(); public Map<String,String> query(); public Headers headers(); public Optional<JsonValue> body(); public Builder toBuilder(); }
public final class WireResponse { public int status(); public Headers headers(); public byte[] body(); public JsonValue json(); }
public final class WireFrame    { public Optional<String> event(); public String data(); public Optional<String> id(); }   // one SSE event or one NDJSON line
public final class Headers      { /* case-insensitive, immutable; toString redacts Authorization, x-api-key, api-key, x-goog-api-key */ }
```

Contract table:

| Item | `Dialect` / `ChatCodec` | `StreamDecoder` |
|---|---|---|
| Thread | any; must be stateless | confined to the consuming thread |
| Ordering | — | frames in arrival order; events in emission order |
| Error isolation | exceptions are translated by the core to `InvalidResponseException` (decode) or propagate (`InvalidRequestException` from encode) | a throw ends the stream with `InvalidResponseException`; delivered events stay valid |
| Blocking | never | never |
| Lifecycle | singleton per dialect instance | one per stream; `end()` called at most once |

### 8.2 HTTP transport

```java
/** Thread-safe. Default: HttpTransport.jdk(). Injected transports are borrowed; created ones are owned by the client. */
public interface HttpTransport extends AutoCloseable {
    WireResponse execute(WireRequest request, TransportOptions options) throws IOException;
    /** Returns a body reader; the core frames SSE/NDJSON and enforces streamIdle. */
    WireBody stream(WireRequest request, TransportOptions options) throws IOException;
    @Override default void close() {}
    static HttpTransport jdk() { … }   static HttpTransport jdk(HttpOptions options) { … }
}
public final class TransportOptions { public URI baseUrl(); public Duration connect(); public Duration firstByte(); public Duration total(); }
public interface WireBody extends AutoCloseable { int status(); Headers headers(); InputStream body(); void cancel(); }
```

`IOException` is allowed here (natural for implementers); the core translates it to
`TransportException` and decides retryability by stage (before vs after the request was sent).

### 8.3 Interceptor (may alter; per attempt)

```java
/** Runs once per attempt, inside retries, after authentication. Registration order in, reverse out.
 *  Exceptions propagate and are translated at the boundary. Sees library-owned wire types only. */
public interface WireInterceptor {
    WireResponse intercept(Chain chain) throws IOException;
    interface Chain { WireRequest request(); Endpoint endpoint(); WireResponse proceed(WireRequest request) throws IOException; }
}
```

Use cases: extra headers per call, tracing ids, AWS SigV4 signing (future `llm-transport-aws`),
request/response logging with redaction. Streaming responses pass through interceptors as headers
only; the body is not buffered.

### 8.4 Token store and browser launcher (host-fed)

```java
/** Thread-safe. Keys are Endpoint.id(). Implementations must persist a rotated refresh token
 *  atomically (write-then-rename) — some providers invalidate the previous one immediately. */
public interface TokenStore {
    Optional<OAuthTokens> load(String endpointId);
    void save(String endpointId, OAuthTokens tokens);
    void delete(String endpointId);
    static TokenStore inMemory() { … }
    /** JSON file, 0600 on POSIX, advisory file lock across processes. Not encrypted: prefer an OS keychain module. */
    static TokenStore inFile(Path file) { … }
}
/** Called on the login() caller thread; must return quickly; must not throw (failures are reported as AuthPrompt.OpenBrowser(launcherOpenedIt=false)). */
@FunctionalInterface
public interface BrowserLauncher {
    boolean open(URI url);
    static BrowserLauncher desktop() { … }     // java.awt.Desktop if supported and not headless
    static BrowserLauncher none() { return url -> false; }
}
```

### 8.5 Extension ladder applied

| Need | Rung | Type |
|---|---|---|
| tune timeouts, retries, unsupported-option handling, proxy, TLS | 1 built-in option | `TimeoutPolicy`, `RetryPolicy`, `UnsupportedOptionPolicy`, `HttpOptions` |
| add a provider on an existing dialect | 1 built-in (data) | `LlmProvider.builder(...)` |
| swap HTTP stack, store tokens in a keychain, open the browser differently, rotate credentials | 2 host-fed dependency | `HttpTransport`, `TokenStore`, `BrowserLauncher`, `Credentials.supplied` |
| show progress, metrics, tracing, audit | 3 observation | `LlmListener` + `LlmEvent` |
| add headers, sign requests, log wire traffic | 4 interception | `WireInterceptor` |
| provider-only knobs; raw JSON/HTTP | 5 escape hatch | `OptionKey<T>`, `QuirkKey<T>`, `response.raw()`, `client.raw()` |
| a new wire format | SPI | `Dialect` + `ChatCodec` + contract tests |

Not offered: a generic plugin registry; callback-style streaming; raw SSE lines on the API (only via `raw()`).

---

## 9. Provider modules

### 9.1 `llm-transport-openai`

```java
package dev.llmtransport.openai;

public final class OpenAi {
    public static final Dialect RESPONSES;                  // /v1/responses — default for OpenAi.PROVIDER
    public static final Dialect CHAT_COMPLETIONS;           // /v1/chat/completions — the compatibility dialect
    public static final LlmProvider PROVIDER;               // id "openai", https://api.openai.com/v1, bearer, OPENAI_API_KEY
    public static LlmClient client(ApiKey key) { return LlmClient.create(Endpoint.of(PROVIDER, key)); }
    public static Endpoint.Builder endpoint() { return Endpoint.builder(PROVIDER); }
}
public final class OpenAiOptions {                          // typed, namespaced "openai"
    public static final OptionKey<String>  ORGANIZATION, PROJECT;              // endpoint level
    public static final OptionKey<Boolean> STORE, PARALLEL_TOOL_CALLS;         // request level
    public static final OptionKey<String>  PREVIOUS_RESPONSE_ID, SERVICE_TIER, REASONING_SUMMARY;
    public static final OptionKey<JsonObject> EXTRA_BODY;                      // deep-merged last; escape hatch
}
public final class OpenAiQuirks {                           // read by the Chat Completions codec; set on presets
    public static final QuirkKey<String>  MAX_TOKENS_FIELD;                    // "max_completion_tokens" | "max_tokens"
    public static final QuirkKey<Boolean> STREAM_USAGE, SUPPORTS_DEVELOPER_ROLE, STRICT_SCHEMA, TOOL_CALL_STREAMING;
    public static final QuirkKey<ReasoningDialect> REASONING;                 // NONE | REASONING_EFFORT | REASONING_OBJECT | DEEPSEEK | QWEN_ENABLE_THINKING | OPENROUTER
}
public final class OpenAiCompatible {                       // presets: data only, all on CHAT_COMPLETIONS unless noted
    public static final LlmProvider OPENROUTER, DEEPSEEK, XAI, QWEN, MISTRAL, GROQ, OLLAMA, LM_STUDIO, VLLM, LITELLM, AZURE_OPENAI;
    public static LlmProvider custom(String id, URI baseUrl) { … }            // bearer auth, generic quirks
}
```

| Preset id | Base URL (default) | Auth | Env var | Notable quirks |
|---|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | bearer | `OPENAI_API_KEY` | dialect RESPONSES; `CHAT_COMPLETIONS` selectable per endpoint |
| `openrouter` | `https://openrouter.ai/api/v1` | bearer, OAuth PKCE (issues an API key) | `OPENROUTER_API_KEY` | `HTTP-Referer`/`X-Title` headers as options; REASONING=OPENROUTER |
| `deepseek` | `https://api.deepseek.com/v1` | bearer | `DEEPSEEK_API_KEY` | REASONING=DEEPSEEK (`reasoning_content`) |
| `xai` | `https://api.x.ai/v1` | bearer | `XAI_API_KEY` | RESPONSES also available |
| `qwen` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | bearer | `DASHSCOPE_API_KEY` | REASONING=QWEN_ENABLE_THINKING |
| `mistral` | `https://api.mistral.ai/v1` | bearer | `MISTRAL_API_KEY` | STRICT_SCHEMA=false |
| `groq` | `https://api.groq.com/openai/v1` | bearer | `GROQ_API_KEY` | |
| `ollama` | `http://127.0.0.1:11434/v1` | none | — | `TimeoutPolicy.forLocalModels()` suggested; http1Only |
| `lm-studio` | `http://127.0.0.1:1234/v1` | none | — | |
| `vllm` | `http://127.0.0.1:8000/v1` | none / bearer | — | |
| `litellm` | user-supplied | bearer | — | |
| `azure-openai` | `https://<resource>.openai.azure.com/openai/v1` | header `api-key` or bearer (Entra via `Credentials.supplied`) | `AZURE_OPENAI_API_KEY` | `AzureOptions.API_VERSION`, `DEPLOYMENT` |

Base URLs and env vars are data in the preset and must be re-verified against provider
documentation at implementation time (all four requirement docs flag them as volatile).

### 9.2 `llm-transport-anthropic`

```java
public final class Anthropic {
    public static final Dialect MESSAGES;                   // /v1/messages, typed SSE events
    public static final LlmProvider PROVIDER;               // id "anthropic", https://api.anthropic.com, header x-api-key + anthropic-version, ANTHROPIC_API_KEY; also accepts bearer
    public static LlmClient client(ApiKey key);  public static Endpoint.Builder endpoint();
}
public final class AnthropicOptions {
    public static final OptionKey<Integer>      THINKING_BUDGET;           // maps Reasoning.budget(); explicit key wins
    public static final OptionKey<List<String>> BETA;                      // anthropic-beta header values
    public static final OptionKey<String>       API_VERSION;               // default "2023-06-01"
    public static final OptionKey<JsonObject>   EXTRA_BODY;
}
public final class AnthropicTools { public static ToolDefinition computerUse(int w, int h); public static ToolDefinition bash(); public static ToolDefinition textEditor(); }
```

Message `cacheHint()` maps to `cache_control: {"type":"ephemeral"}` on the last part of the message;
`Content.Reasoning.signature()` is replayed verbatim; the `system` field is lifted from
`ChatRequest.system()`; Anthropic-compatible endpoints (DeepSeek `/anthropic`, others) are presets on
`Anthropic.MESSAGES`.

### 9.3 `llm-transport-gemini`

```java
public final class Gemini {
    public static final Dialect GENERATE_CONTENT;           // models/{model}:generateContent | :streamGenerateContent?alt=sse
    public static final LlmProvider PROVIDER;               // id "gemini", https://generativelanguage.googleapis.com/v1beta, header x-goog-api-key, GEMINI_API_KEY
    public static LlmClient client(ApiKey key);
}
public final class GeminiOptions { public static final OptionKey<Integer> THINKING_BUDGET; public static final OptionKey<Boolean> INCLUDE_THOUGHTS; public static final OptionKey<JsonObject> SAFETY_SETTINGS, EXTRA_BODY; }
```

Gemini gives tool calls no ids: the codec synthesizes stable ids (`call_<index>_<hash>`) and maps
them back on `ToolResult`. Vertex AI (different base URL, OAuth bearer via `Credentials.supplied`)
is a preset on the same dialect in a later phase.

### 9.4 Provider bundle registration (ServiceLoader, defaults only)

```java
package dev.llmtransport.spi;
public interface ProviderBundle { List<LlmProvider> providers(); List<Dialect> dialects(); }
// META-INF/services/dev.llmtransport.spi.ProviderBundle → dev.llmtransport.openai.internal.OpenAiBundle
```

`ProviderRegistry.discover()` is the only consumer of `ServiceLoader`; `Endpoint.builder(OpenAi.PROVIDER)`
never touches it. Duplicate ids across bundles fail loudly at `discover()` listing both modules.

### 9.5 Adding a provider, a dialect, or a new API version

| Task | Steps | Code |
|---|---|---|
| Provider on an existing dialect | `LlmProvider.builder(id, dialect)` with base URL, auth, env var, quirks; optionally contribute it to a bundle | none beyond the preset |
| New wire format | implement `Dialect` + `ChatCodec` + `StreamDecoder` in `…/internal`; expose a `public final class Foo { static final Dialect DIALECT; static final LlmProvider PROVIDER; }`; extend `DialectContractTest`; register a `ProviderBundle` | one module |
| New version of an existing API (e.g. Responses v2) | add a second `Dialect` constant (`OpenAi.RESPONSES_V2`) with its own codec; switch the preset's default when stable; old constant stays until deprecated | codec only |
| Provider-specific hosted tool or option | add `OptionKey`/`ToolDefinition` factories in the provider module; codec reads them | provider module only |

The unified model (`ChatRequest`, `Content`, `ChatEvent`) never changes for a provider addition;
provider-only concepts travel through `OptionKey`, `providerData()` and `Unknown(raw)`.

---

## 10. Configuration

### 10.1 Shape and scopes

```
Endpoint (persistable, per backend)            LlmClient.Builder (policy, per client)      ChatRequest (per call)
├── provider (LlmProvider preset)              ├── timeouts  TimeoutPolicy                 ├── model (required)
├── id                                          ├── retry     RetryPolicy                   ├── messages / system / tools …
├── baseUrl          default: provider's        ├── http      HttpOptions (default transport)├── sampling: temperature, topP, maxOutputTokens, stop, seed
├── credentials      required unless none       ├── httpTransport (borrowed)                ├── reasoning, responseFormat, toolChoice
├── headers                                     ├── unsupportedOptions  default FAIL         ├── timeout (per-call total)
├── defaultModel                                ├── tokenStore  default inMemory()           └── option(OptionKey<T>, T)
├── dialect          default: provider's        ├── browserLauncher  default desktop-or-none
├── option(OptionKey<T>, T)                     ├── listener*, interceptor*
└── oauth overrides                             └── build()  — validates, no I/O
```

- **Precedence, field by field:** per-call > endpoint default (`defaultModel`, endpoint options) >
  client policy > built-in default. *Omitted means inherit*; absence is `Optional`, never a sentinel.
- **Environment is explicit:** `ApiKey.fromEnv()` / `fromEnv("NAME")`. Nothing else reads the
  environment or system properties. A future `llm-transport-config` module may map files/env onto
  the builders; the core never does.
- **Nesting depth ≤ 2** (`LlmClient.Builder → TimeoutPolicy`). Consumer-builder lambdas *edit* the
  current value (`.timeouts(t -> t.connect(a)).timeouts(t -> t.streamIdle(b))` keeps both).
- **`build()` is pure.** `testConnection()`, `login()`, `models().list()` are the explicit I/O verbs.
- **Resolved view:** `client.chat().prepare(request)` shows exactly what would be sent; `Endpoint`
  and policies have full `toString()` (redacted) for "effective configuration" displays.

### 10.2 Defaults (documented values; changing one is a behavioural break)

| Knob | Default | Rationale |
|---|---|---|
| `TimeoutPolicy.connect` | 10 s | fail fast on unreachable hosts |
| `TimeoutPolicy.firstByte` | 120 s | reasoning models can think for minutes before the first byte; `forLocalModels()` → 300 s (model load) |
| `TimeoutPolicy.streamIdle` | 60 s | pauses during tool/reasoning phases; the JDK has no read timeout, so the framer enforces it |
| `TimeoutPolicy.total` | 10 min | one deadline across attempts and backoff; `noTotalTimeout()` is the only way to disable |
| `RetryPolicy.maxAttempts` | 3 | on network-before-send, 408/409/429/5xx/529; `Retry-After` honoured up to `maxRetryAfter` 60 s |
| `RetryPolicy.backoff` | exponential 500 ms ×2, cap 8 s, full jitter | standard |
| `UnsupportedOptionPolicy` | `FAIL` | no silent semantic change; UIs that apply one node config to many models opt into `DROP_WITH_WARNING` |
| `TokenStore` | in-memory | nothing touches disk unless asked |
| `BrowserLauncher` | desktop if available, else none | never opens anything before `login()` is called |
| `HttpTransport` | JDK, HTTP/2 for https, HTTP/1.1 for cleartext | avoids h2c upgrade confusion with local servers |
| TLS | verification on; `insecureSkipTlsVerification()` logs WARN at build | safe default, explicit unsafe name |
| Logging | `System.Logger`, no bodies, no secrets; `HttpOptions.logWire(Redaction)` opt-in | privacy |
| Sampling parameters | **none sent** | provider defaults apply; unset ≠ default |

### 10.3 Footgun decisions

| Probe | Decision |
|---|---|
| `timeout(Duration.ZERO)` / negative | rejected at the setter; `noTotalTimeout()` is the named opt-out |
| `maxAttempts(0)` | rejected; `RetryPolicy.none()` = 1 attempt |
| `ApiKey.of("")` / `of(null)` | rejected at the call site |
| `Endpoint.builder(p).build()` without credentials on a provider whose auth ≠ none | rejected listing accepted `AuthMethod`s |
| OAuth credentials on a provider without `oauthDefaults()` and no `Endpoint.Builder.oauth(…)` | rejected |
| `baseUrl` with `http://` to a non-loopback host | allowed, WARN at build |
| many retries + `noTotalTimeout()` | WARN at build |
| `queryParam` auth method | opt-in on the preset; the key never appears in logs, events, `PreparedRequest`, exceptions |
| `Credentials.supplied` supplier throwing / returning null | translated to `AuthenticationException(code "credentials_unavailable")`, not retried |
| Confusable parameters | `ApiKey` vs `OptionKey<String>` for organization; `Duration` everywhere; no positional booleans (`strict()`, `insecureSkipTlsVerification()` are named methods) |
| `Endpoint.toString()` / events / exceptions | never include key material; `ApiKey.fingerprint()` only |

### 10.4 Configuration examples

**Coding agent, several endpoints from its own config file (host format, not the SDK's):**

```json
{ "endpoints": [
    { "id": "openai",     "provider": "openai",     "apiKey": "env:OPENAI_API_KEY", "defaultModel": "gpt-5" },
    { "id": "router",     "provider": "openrouter", "auth": "oauth" },
    { "id": "local",      "provider": "ollama",     "baseUrl": "http://127.0.0.1:11434/v1", "defaultModel": "qwen3:8b" },
    { "id": "corp",       "provider": "corp-gw",    "apiKey": "keychain:corp-llm", "headers": { "X-Team": "42" } } ] }
```

```java
ProviderRegistry registry = ProviderRegistry.discover().register(corpGateway);
for (HostEndpointConfig c : hostConfig.endpoints()) {
    Endpoint.Builder b = Endpoint.builder(registry.require(c.provider())).id(c.id());
    c.baseUrl().ifPresent(u -> b.baseUrl(URI.create(u)));
    c.defaultModel().ifPresent(b::defaultModel);
    b.credentials(switch (c.authKind()) {            // host resolves references; the SDK sees values or OAuth
        case "oauth"    -> Credentials.oauth();
        case "keychain" -> ApiKey.of(keychain.read(c.ref()));
        default         -> ApiKey.fromEnv(c.ref()); });
    clients.put(c.id(), LlmClient.builder(b.build())
        .httpTransport(sharedTransport)
        .tokenStore(TokenStore.inFile(tokensPath))
        .timeouts(c.isLocal() ? TimeoutPolicy.forLocalModels() : TimeoutPolicy.defaults())
        .listener(telemetry).build());
}
```

**Workflow builder node** — see §4.8; the node persists `{providerId, baseUrl, secretRef, options…}`
and rebuilds the `Endpoint` on load; `ConfigField` descriptors drive the form; `prepare()` powers a
"preview request" button without spending money.

**Corporate gateway with mTLS and a custom header:**

```java
LlmClient client = LlmClient.builder(Endpoint.of(corpGateway, ApiKey.fromEnv("CORP_GW_API_KEY")))
    .http(h -> h.clientCertificate(Path.of("/etc/pki/app.p12"), pkcs12Password).trustStore(Path.of("/etc/pki/corp-ca.p12"), caPassword))
    .interceptor(chain -> chain.proceed(chain.request().toBuilder().header("X-Trace-Id", Tracing.currentId()).build()))
    .build();
```

---

## 11. Execution contracts

### 11.1 Sync (primary)

`send()` blocks until a response, the total deadline, or an interrupt. Retries happen inside per
`RetryPolicy`; the caller sees one outcome. Interruption aborts the transport call, throws
`CancelledException` (cause `InterruptedException`) and re-sets the interrupt flag. Thread-safe.

### 11.2 Retries, idempotency, deadlines

- Retry only: connection failures *before* the request body was sent; responses the provider documents
  as retryable (408, 409, 429, 500, 502, 503, 504, 529); `Retry-After` honoured, capped by `maxRetryAfter`.
- A timeout or reset *after* sending a non-idempotent chat call is **not retried**; it surfaces as
  `RequestTimeoutException` / `TransportException` with `outcomeUnknown() == true` (code `outcome_unknown`)
  so a host can decide (double-billing risk).
- Never retry after the first stream event; the stream fails instead (`partial()` available).
- Only replayable bodies are retried (all JSON bodies are); every retry emits `RequestEvent.Retrying`.
- One `total` deadline covers attempts and backoff sleeps.
- Auth: on 401 with OAuth credentials, one forced refresh then one retry (documented exception to the
  rule above because the request was rejected before execution).

### 11.3 Streaming

Contract as in §7.4: single-use, bounded, closeable, cancellation reaches the socket, framing across
network chunks is the library's job (`SseFramer` keeps partial lines; `NdjsonFramer` splits on `\n`),
`streamIdle` enforced by a watchdog that cancels the body read, terminal `Finished` exactly once,
`response()` after iteration equals `send()`'s result (byte-identical JSON of `message()`).

### 11.4 Cancellation for UIs

| Operation | How to cancel | Effect |
|---|---|---|
| `stream()` | `stream.close()` from any thread | socket closed, `CancelledException` from `next()`, `RequestEvent.Cancelled(partialOutput)` |
| `send()` | interrupt the calling thread | transport aborted, `CancelledException` |
| `login()` | `session.cancel()` / `close()` | listener stopped, `await` throws `AuthenticationException(code "login_cancelled")` |
| `async()` (phase 2) | `future.cancel(true)` / `Subscription.cancel()` | propagates to the transport (documented, tested) |

Closing a socket is not proof the provider stopped billing; the event says `partialOutput` only.

### 11.5 Threading and ownership

| Resource | Created by | Closed by |
|---|---|---|
| default `HttpTransport` | `LlmClient.build()` | `client.close()` |
| injected `HttpTransport` | host | host (never the client) |
| `ChatStream` body | `stream()` | caller (`close()`), or `client.close()` aborts it |
| `LoginSession` loopback listener | `login()` | `session.close()`; `client.close()` cancels pending sessions |
| listener `Registration` | `listeners().add` | caller, or all released on `client.close()` |
| `TokenStore` | host (or in-memory default) | host |
| async executor (phase 2) | client lazily, or injected | client if owned |

Every public type states its thread-safety in Javadoc: clients, sub-APIs, transports, registries and
codecs are thread-safe; models, config and events immutable; builders and `LoginSession` confined.
No `synchronized` across I/O; `ReentrantLock` for the refresh single-flight; no platform-thread
pools created unless `async()` is used without an injected executor.

### 11.6 Async view (phase 2, separate contract)

```java
AsyncLlmClient async = client.async();
CompletableFuture<ChatResponse> f = async.chat().send(request);        // completes on the client executor, never on I/O threads
Flow.Publisher<ChatEvent> p = async.chat().stream(request);            // demand-driven; cancel() aborts the request
```

Same exceptions as sync (`CompletionException` wrapping documented); Kotlin `suspend`/`Flow`
adapters in `llm-transport-kotlin` delegate to this view.

---

## 12. Authentication and OAuth design

### 12.1 Credential application (every attempt)

```
Credentials ─┬─ ApiKey/BearerToken ──► AuthMethod of the provider: Authorization: Bearer | <header>: | ?<param>=
             ├─ None ───────────────► nothing
             ├─ Supplied ───────────► supplier.get() → recurse (fast, thread-safe; result never cached by the SDK)
             └─ OAuth ──────────────► TokenStore.load(endpointId) ─► absent → AuthenticationException(login_required)
                                                                   ├► expiring (≤ 90 s skew) → RefreshCoordinator (single-flight) → save
                                                                   └► Authorization: Bearer <access>
```

Refresh: proactive before expiry, single-flight across threads, one forced refresh + retry on 401,
rotated refresh token persisted before the old one is discarded, `AuthEvent.TokenRefreshed` /
`RefreshFailed`. A failed refresh with a rejected refresh token deletes the stored tokens and yields
`login_required` — the UI can react to the event.

### 12.2 Interactive login state machine (`LoginSession`)

```
 login() ──► CREATED ──► PROMPTED ──────────────► CALLBACK_RECEIVED ──► EXCHANGING ──► DONE (tokens saved)
              │           │  AuthPrompt.OpenBrowser  ▲ loopback listener,   │ code + PKCE verifier
              │           │  (launcher tried)        │ or complete(uri)     │ or device poll success
              │           └─ AuthPrompt.EnterCode ───┘ (device: poll)       │
              └────────── cancel()/timeout ──────────────────────────────► CANCELLED / FAILED (AuthenticationException)
```

- **Authorization code + PKCE (S256)** is chosen when `RedirectMode.Loopback` is configured and a
  browser can be reached (launcher or host). The loopback listener binds `127.0.0.1` only, serves one
  request, validates `state`, returns a minimal success page (customizable), and stops. Fixed ports are
  supported for providers whose registered redirect URIs are fixed.
- **External redirect** (`LoginOptions.externalRedirect(uri)`): no listener; the host receives the
  callback and calls `complete(uri)`. `state()` lets the host correlate sessions.
- **Device code**: polls per `interval`, honours `slow_down`, `authorization_pending`,
  `expired_token`; the prompt carries the user code and verification URL(s).
- **Client credentials** (`OAuthGrant.CLIENT_CREDENTIALS`): non-interactive; `login()` returns a
  session whose `prompt()` is absent and `await()` completes immediately.
- Consent: nothing is opened until the host calls `login()`; the launcher result is reported so a
  headless host can display the URL instead. No externally reachable listener is ever started.
- Discovery (RFC 8414) and dynamic client registration are *not* in v1; presets carry endpoints.

### 12.3 Token storage

`TokenStore.inMemory()` (default), `TokenStore.inFile(path)` (JSON, 0600, cross-process advisory
lock, atomic rename), and later `llm-transport-keychain` (Windows Credential Manager, macOS Keychain,
Linux Secret Service). Keys are `Endpoint.id()`; one account per endpoint id — multi-account hosts
use distinct endpoint ids.

### 12.4 Provider-specific shapes handled without special code paths

| Provider | Mechanism | How it fits |
|---|---|---|
| OpenRouter | PKCE; code exchange returns `{"key": …}` (a user-scoped API key, no refresh) | `OAuthConfig.tokenResponseMapper` maps to `OAuthTokens(accessToken=key, expiresAt=absent)` |
| Azure OpenAI / Vertex AI / Bedrock bearer | short-lived cloud tokens from vendor SDKs | `Credentials.supplied(() -> BearerToken.of(vendorSdk.token()))`; SigV4 later via a `WireInterceptor` module |
| Corporate gateway | client credentials | `OAuthGrant.CLIENT_CREDENTIALS` with `clientSecret` |
| Remote MCP-style servers | OAuth 2.1 + RFC 9728 discovery | out of scope (not an LLM endpoint) |

### 12.5 Secret hygiene

`ApiKey`, `BearerToken`, `OAuthTokens`, `OAuthConfig.clientSecret` redact in `toString`; `Headers`
redacts auth headers and query keys; `PreparedRequest`, events and exceptions carry fingerprints
only; wire logging is opt-in and passes through the same redaction; `Endpoint` is never serialized
by the SDK.

### 12.6 Policy note on consumer-subscription logins

The requirement docs (fable, opus) record that OAuth tokens from consumer products (ChatGPT/Codex,
Claude Pro/Max, GitHub Copilot, Gemini CLI) are undocumented for third parties and that at least one
vendor has enforced its terms against third-party harnesses. The SDK therefore ships **no presets**
for those flows; the generic `OAuthConfig` lets a host that is authorized configure them itself.

---

## 13. UI and workflow-builder integration guide

| Need (from the node-flow requirements) | SDK answer | I/O? |
|---|---|---|
| List providers, render a connection form | `ProviderRegistry.discover().all()`, `LlmProvider.configFields()` (`Kind`, required, default, help, group) | no |
| Validate a form | `Endpoint.Builder.field(...)` + `build()` → `IllegalArgumentException` listing every invalid field | no |
| "Test connection" button | `client.testConnection()` → `ConnectionCheck{stage: CONFIG/CONNECT/AUTH/MODELS, ok, message, latency}` | yes, non-billable |
| "Log in" button, device-code dialog, web callback | `client.auth().login()` / `LoginOptions.externalRedirect`; `AuthPrompt`; `AuthEvent.*` | yes |
| Auth status chip | `client.auth().status()` | no |
| Model picker with capability chips, context size, price | `client.models().list()` → `ModelInfo` (tri-state `SupportLevel`, `OptionalLong`, `Optional<Pricing>`, `source()`, `fetchedAt()`) | yes, once; host caches |
| Parameter form applicability | `Capabilities.support(Capability.X)`; unsupported options → `FAIL` or `DROP_WITH_WARNING` + `Warning`/`OptionDropped` | no |
| Preview / dry run a node | `client.chat().prepare(request)` (path, redacted headers, exact body, warnings) | no |
| Run a node with progress | `stream()` events for text; `RequestEvent.Started/FirstToken/Retrying/Completed/Failed` for status; `Usage` and `Pricing.estimate(usage)` for cost | yes |
| Stop button | `stream.close()`; `partial()` for what was received | — |
| Save / load a workflow | host persists provider id, base URL, options, secret *reference*, default model; `Endpoint` rebuilt on load; `ApiKey` never serialized | no |
| Fan-out ("one request per file" vs "all files in one request") | host decision; the SDK exposes per-request `Usage` so counts and budgets can be shown before and after | — |
| Locale-safe numbers | typed values on all builders (`double`, `int`, `Duration`); the UI parses locale input | — |
| No execution on rendering | nothing in `Endpoint`, `LlmProvider`, `ModelInfo`, `prepare()`, `status()` performs I/O; only verbs do | — |

`ConnectionCheck` stops at the first failing stage and reports which one, so "reachable but wrong
key" and "authorized but no models visible" are distinguishable.

---

## 14. Error model

One unchecked root; subclasses only where callers branch; machine-readable fields; translated at the
boundary with the cause kept; no third-party or checked exceptions on the API.

```java
public class LlmException extends RuntimeException {
    public String code();                     // stable: see table
    public boolean retryable();
    public boolean outcomeUnknown();          // the request may have executed remotely
    public OptionalInt statusCode();
    public Optional<String> providerCode();   // provider's own error type/code
    public Optional<String> requestId();      // provider request id for support tickets
    public String endpointId();  public Optional<ModelId> model();  public int attempts();
    public Optional<JsonValue> rawBody();     // redacted
}
```

| Exception | Codes (examples) | Typical HTTP | Retryable |
|---|---|---|---|
| `TransportException` | `connect_failed`, `tls_failed`, `stream_interrupted`, `outcome_unknown` | — | before send: yes; after: no |
| `RequestTimeoutException` | `connect_timeout`, `first_byte_timeout`, `stream_idle_timeout`, `deadline_exceeded` | — | per stage, never after send |
| `AuthenticationException` | `invalid_credentials`, `login_required`, `refresh_failed`, `permission_denied`, `credentials_unavailable`, `login_cancelled` | 401, 403 | no (one refresh+retry for OAuth 401) |
| `RateLimitedException` | `rate_limited`, `quota_exhausted`, `insufficient_credits` | 429, 402 | `rate_limited` yes (`retryAfter()`), others no |
| `InvalidRequestException` | `invalid_request`, `unsupported_option`, `context_length_exceeded`, `model_not_found`, `request_too_large`, `invalid_schema` | 400, 404, 413, 422 | no |
| `ContentFilteredException` | `content_filtered` (provider refused/blocked; `partial()` may exist) | 200/400 | no |
| `ProviderException` | `overloaded`, `server_error`, `bad_gateway` | 500, 502, 503, 504, 529 | yes |
| `InvalidResponseException` | `malformed_response`, `unexpected_stream_end`, `not_json` (from `response.json()`) | 200 | no |
| `CancelledException` | `cancelled`, `interrupted` | — | no |

Subclass-specific accessors, only where callers branch: `RateLimitedException.retryAfter()`,
`AuthenticationException.loginRequired()` (true for code `login_required`),
`InvalidRequestException.option()` (the offending option for `unsupported_option`),
`ContentFilteredException.partial()`.

Failure atomicity: a failed call leaves the client usable. Messages are for humans; callers branch on
`code()`. The testing artifact ships factories so hosts can construct every exception in their tests.

---

## 15. Evolution and compatibility

- **Versioning:** SemVer; `0.x` = no promise (stated in README). `japicmp` runs on every release
  against the previous minor; each intended break is listed in `CHANGELOG.md`.
- **API interfaces** (`LlmClient`, `ChatApi`, …) are `@ApiStatus.NonExtendable`: methods may be added
  in minors. **SPI interfaces** grow only via `default` methods; a new abstract method is a major.
- **Sealed hierarchies** (`Content`, `ChatEvent`, `LlmEvent`, `Credentials`, `AuthPrompt`) may gain
  variants in a minor; consumers are documented to keep `default ->`; every wire-derived one carries
  `Unknown`.
- **Wire-derived models** (`ChatResponse`, `ModelInfo`, `Usage`, `OAuthTokens`) are final classes with
  builders; fields are added freely. Records are used only for small stable values and events.
- **Defaults** (§10.2) are behaviour: changing one is a minor at most with a changelog entry and a
  named-test change, never silently.
- **Dialect versions** are new `Dialect` constants; a preset's default dialect switch is a documented
  minor; the old constant is deprecated with `since`/`forRemoval` and removed in the next major.
- **Experimental** (`raw()`, `AsyncLlmClient` initially) is annotated and excluded from the promise
  until two real consumers shaped it.
- **One-version rule:** no `v1`/`v2` packages; extend in place.

---

## 16. Roadmap (minimal skeleton first)

| Phase | Deliverable | Exit criterion |
|---|---|---|
| **0 — skeleton** | core API/SPI types with stubs, `JdkHttpTransport`, internal JSON, SSE/NDJSON framers, `RequestExecutor` (timeouts, retry, events), `ApiKey`/bearer auth, `OpenAi.CHAT_COMPLETIONS` codec + `OpenAiCompatible` presets, `ModelsApi.list`, `testConnection`, `llm-transport-testing` (fakes, `DialectContractTest`) | §4.1–4.4, 4.8–4.12 examples run against a fake and against one real OpenAI-compatible endpoint |
| **1 — the three native dialects** | `OpenAi.RESPONSES`, `Anthropic.MESSAGES`, `Gemini.GENERATE_CONTENT`; tools, structured output, reasoning, images/documents, `Content.Reasoning` continuity, `prepare()`, warnings/`OptionDropped` | contract tests green for four codecs; history round-trips per provider |
| **2 — OAuth and discovery depth** | `AuthApi` (PKCE + loopback, external redirect, device code, client credentials, refresh single-flight), `TokenStore.inFile`, `BrowserLauncher`, `AuthEvent`s, OpenRouter PKCE preset, `Pricing`/bundled `knownModels()`, `EmbeddingsApi` | §4.6–4.7 examples run against OpenRouter and a local mock IdP |
| **3 — views and adapters** | `client.async()` (`CompletableFuture`, `Flow.Publisher`), `llm-transport-kotlin`, `llm-transport-okhttp`, `llm-transport-jackson`, `llm-transport-otel` listener, `llm-transport-ollama` native NDJSON dialect, keychain token store | japicmp clean across the minor line |
| later | Vertex/Bedrock presets (+ SigV4 interceptor module), files/batch sub-APIs, config-file module, gateway module reusing codecs | on demand |

Each phase ships without the next: a host can use phase 0 alone (API keys, OpenAI-compatible).

---

## 17. Verification plan (tests are the spec)

- README examples (§4) compile in `examples` (Java + Kotlin) and run against `llm-transport-testing`.
- Named tests pin each documented rule, for example:
  - `simple example sends one request with default timeouts and no sampling parameters in the body`
  - `build fails listing all violations when maxAttempts is 0 and total timeout is zero`
  - `unset temperature is absent from the wire body`
  - `unsupported option fails with unsupported_option under FAIL and is dropped with a Warning and an OptionDropped event under DROP_WITH_WARNING`
  - `429 is retried up to maxAttempts then surfaces RateLimitedException with retryAfter`
  - `timeout after the request was sent is not retried and reports outcome_unknown`
  - `stream delivers whole SSE events when network chunks split a line`
  - `stream is never retried after the first event`
  - `closing a stream mid-way cancels the request, releases the connection and emits Cancelled(partialOutput=true)`
  - `stream response equals send response for the same scripted body`
  - `unknown content part, event type and finish reason are preserved, not rejected`
  - `missing usage is absent, not zero; unknown capability is UNKNOWN, not UNSUPPORTED`
  - `listener exception is logged and does not fail the request`
  - `injected transport is not closed when the client is closed`
  - `ApiKey never appears in toString, logs, events, PreparedRequest or exception messages`
  - `login with loopback validates state, exchanges the code with the PKCE verifier and saves tokens`
  - `expired token triggers exactly one refresh under concurrent calls`
  - `rotated refresh token is persisted before the old one is discarded`
  - `external redirect login completes via complete(uri) without starting a listener`
  - `testConnection stops at AUTH for a wrong key and reports the stage`
  - `prepare performs no I/O and redacts headers`
  - `kotlin caller uses builder and consumer-builder without platform types`
  - `japicmp reports no binary incompatibilities against the previous minor`
- `DialectContractTest` and `HttpTransportContractTest` run against every shipped implementation and the fakes.
- Recorded fixtures per dialect (request/response/stream cassettes) for offline conformance.
- Footgun probes (§10.3) each have a test.

What this document does **not** verify: nothing has been compiled or executed; provider endpoint
facts in §9 are data to be re-validated at implementation time.

---

## 18. Open decisions (small, with recommendations)

| Decision | Recommendation | Why it is still open |
|---|---|---|
| Root package / group id | `dev.llmtransport` | organization naming |
| Internal JSON vs shaded Jackson | internal (zero deps, stable key order, bounded parser) | implementation cost ≈ 600 lines + tests; revisit if binding needs grow |
| `Endpoint.id()` default when unset | provider id | multi-endpoint hosts must set ids explicitly; validation could require it |
| Embeddings in phase 0 or 2 | 2 | the two named consumers need chat first |
| `ChatEvent` as records vs classes | records (shape is meaning; adding a variant is fine, changing one is not) | if event fields are expected to grow often, switch to final classes before 1.0 |
| Ollama native dialect vs `/v1` compat only | compat in phase 0, native in phase 3 | native adds model pull/progress that a UI may want |

---

## Appendix A — Mapping of requirement-document vocabulary to this proposal

| Requirement docs | Here |
|---|---|
| Endpoint / EndpointConfig / EndpointSpec | `Endpoint` |
| Provider / ProviderProfile / Preset / compatibility profile | `LlmProvider` (+ `QuirkKey` values) |
| Dialect / WireApi / DialectCodec | `Dialect`, `ChatCodec`, `StreamDecoder` |
| ModelRef / ModelInfo / Capability tri-state | `ModelId`, `ModelInfo`, `Capabilities`, `SupportLevel` |
| Credential / CredentialProvider / AuthProvider / TokenStore / OAuthFlow | `Credentials`, `Credentials.supplied`, `TokenStore`, `LoginSession` |
| StreamEvent / accumulator | `ChatEvent`, `ChatStream.response()` |
| TransformationReport / unsupported-param policy | `Warning`, `RequestEvent.OptionDropped`, `UnsupportedOptionPolicy` |
| EndpointInfo / probe / ping | `testConnection()` → `ConnectionCheck`; account info via provider modules over `raw()` |
| dryRun / prepared invocation plan | `ChatApi.prepare()` → `PreparedRequest` |
| Interceptor / middleware | `WireInterceptor` |
| EventListener callbacks (`onRequestStart`…) | `LlmListener` + sealed `LlmEvent` |
| Router / fallback / circuit breaker / key pool / rate limiter | not in core (host composition) |
| Gateway codecs | not in v1; codecs are pure to allow it later |

## Appendix B — Checklist against the request

| Request item | Where |
|---|---|
| Read, analyze, generalize the four requirement docs | §1 |
| Wide range of LLM calls; hide OpenAI Responses / Anthropic Messages / Gemini / Grok / DeepSeek / Qwen | §7.3, §8.1, §9 |
| Provider/gateway connection and authentication incl. OAuth | §7.2, §7.7, §12 |
| UI integration: browser hooks, events, progress, information | §4.6–4.9, §7.8, §8.4, §13 |
| Coding-agent and node-workflow usage | §4, §10.4, §13 |
| Hierarchical design, API and providers separated, minimal start then growth | §5, §6, §9.5, §16 |
| OO: factories, abstractions, facades, interfaces; plug a provider; update an API version | §7.1–7.2, §8, §9.5 |
| Project structure, configuration endpoints and examples, factories, stubs | §6, §9.1 table, §10, §7 |
| Lightweight, no god classes, defaults over configuration | §2, §5.4, §6.4, §10.2 |
