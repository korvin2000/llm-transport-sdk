# LLM Transport SDK — Architecture v2

*`final-architecture-mix.md` revised with the lessons of pi-ai and pi-telemetry (`/examples`)*

| | |
|---|---|
| **Status** | Proposal. A delta over [`final-architecture-mix.md`](final-architecture-mix.md) (below **FM**): every FM section not changed here stays valid as written (§4) |
| **Date** | 27 September 2026 |
| **Inputs** | [`goals.md`](../../goals.md) · FM · `examples/ai` = `@earendil-works/pi-ai` 0.87.1 (below **pi**) · `examples/telemetry` = `@earendil-works/pi-telemetry` · the `java-sdk-design` skill |
| **Method** | Design review by reading and argument: pi's sources, README and FM were read and compared. Nothing was compiled or run for this revision (§9) |

| You are… | Read |
|---|---|
| deciding whether to adopt the revision | §0, §2 |
| interested in what pi does well and badly | §1 |
| implementing | §3, §4, §5, §7 |

---

## 0. Verdict on one page

**FM is strong in contracts; pi is strong in shape.** FM specifies what is hardest to retrofit —
deadlines, retry and outcome certainty, cancellation, stream framing, OAuth security, redaction,
evolution rules, contract kits — far more rigorously than pi, which wraps vendor SDKs and passes
timeouts and retries "to providers/SDKs that support it". pi, however, is shaped around how its
consumer (a coding agent) really works: many providers at once, the model chosen per call from a
catalog, one serializable conversation that survives model switches, and replies that are appended
to it. FM is shaped around *one endpoint per client*, which pushes the multi-model bookkeeping into
every host — and both consumers named in `goals.md` (coding agent, workflow builder) are multi-model.

**Recommendation: keep FM's contracts, adopt pi's shape, and cut FM's surface where pi shows it is not
needed.** Three shifts:

1. **The model is data and names its provider.** One multi-provider `Llm`; `llm.complete(model, conversation)`
   dispatches deterministically to `model.providerId()` — no routing, no fallback. FM rejected a
   "multi-endpoint router" because it hides who is billed (FM §5.4); explicit dispatch by a provider
   named inside the model hides nothing.
2. **The reply is the history entry.** `complete()` returns an `AssistantMessage` carrying usage, cost,
   stop reason and its origin; `conversation.append(reply)` continues. The origin drives documented
   cross-provider hand-off rules. Replaces `ChatResponse` + `message()` + `continueWith(…)`.
3. **Provider heterogeneity is data, and so is auth.** A provider is a preset + typed compat flags + a
   bundled model catalog (context window, max output, reasoning levels, prices) + an auth strategy
   (resolution chain with a `source` label, optional OAuth) behind one UI protocol,
   `AuthInteraction { prompt, notify }`.

Simplifications, each argued in §2.2: per-call `ChatOptions` instead of five merge scopes and
`withOptions` views; `CancelToken` instead of the `ChatCall` state machine; `cacheRetention` +
`sessionId` as the everyday caching surface, with FM's response cache and explicit breakpoints kept as
opt-in layers; `CredentialStore.update(…)` instead of token-store and refresher rules; typed
`ProviderOptions` and `ApiCompat` classes instead of `OptionKey`/`QuirkKey`; one SPI pair (`WireApi`,
`StreamDecoder`) instead of five codec types; five exported packages instead of thirteen.

**Nothing a consumer relies on is removed.** Every FM feature — structured output, typed tools,
preview/curl, connection test, response cache and cassettes, provider-only APIs, interceptors,
generated forms, events, JFR — stays; what changes is *how* it is reached: fewer merge rules, fewer
types, and defaults taken from data. §5 lists every element that moved.

Rejected from pi: vendor SDKs inside adapters, failures only as data, a live mutable `partial` shared
across events, silently ignored settings, `0` for unknown prices and limits, base-URL sniffing as the
compat mechanism, subscription-OAuth presets, and the `stream`/`streamSimple` split.

```java
try (Llm llm = Llm.create()) {                                     // provider modules on the path; keys from the environment
    Model sonnet = llm.model("anthropic", "claude-sonnet-5");     // catalog entry: limits, prices, reasoning levels
    AssistantMessage reply = llm.complete(sonnet, "Explain Java records in two sentences.");
    System.out.println(reply.text());
    System.out.println(reply.usage());                             // Usage[input=21, output=64, cost=USD 0.00102]
}
```

### 0.1 Best of all worlds

| Taken from | What | Where |
|---|---|---|
| **pi** | Model as data naming its provider; one multi-provider runtime; the reply is an appendable message; origin-aware hand-off; bundled catalog with limits, prices and reasoning levels; cost on every reply; auth chain with a `source` label; one login protocol (`prompt`/`notify`); atomic credential `update`; typed compat flags; fake provider; payload hook; partial tool arguments; overflow detection; tiny telemetry contract | §3.2–§3.13 |
| **FM** | Core-owned I/O with pure codecs; connect/idle/total timeouts; retries only for not-processed responses and `outcomeUnknown`; OAuth security contract; typed error taxonomy; record-derived schemas for tools and structured output; `preview()`/`toCurl()`/`test()`; response cache and cassettes; provider-only APIs; interceptors; generated forms; zero runtime dependencies; contract kits; evolution rules | §4 (kept by reference) |
| **Java 26 + OOD** (new) | Sealed hierarchies with record patterns for events, prompts and content; typed option and compat classes instead of string bags or `hasApi()` narrowing; strategies (`WireApi`, `ApiKeyAuth`, `OAuthAuth`) instead of flags; blocking login prompts on virtual threads (one protocol for desktop, CLI and web); `CancelToken` trees; immutable values with `toBuilder()`; exceptions carrying the partial reply | §3.16 |

---

## 1. pi in brief

### 1.1 Shape

```
Models  (collection; CredentialStore, ModelsStore, AuthContext)
 ├─ Provider "anthropic"  = id + baseUrl + auth{apiKey?, oauth?} + models[] (+ fetchModels) + API implementation(s)
 ├─ Provider "openrouter" = …                         API implementation = ProviderStreams { stream, streamSimple }
 └─ …                                                 every provider is built by createProvider({ … })

Model   (plain data) = provider, id, api, baseUrl, contextWindow, maxTokens, reasoning, thinkingLevelMap,
                       input modalities, cost (+ tiers), compat flags, headers
Context (plain data) = systemPrompt?, tools?, messages[]          — JSON-serializable, portable across models

models.complete(model, context, options) → AssistantMessage (content, usage + cost, stopReason, origin) → messages.push(…)
models.stream(…) → async iterable: start · text/thinking/toolcall _start/_delta/_end · done | error, plus result()
```

Around this core pi adds a generated model catalog, OAuth logins (Anthropic, OpenAI Codex, GitHub
Copilot, OpenRouter), a faux provider for tests, image generation, classifiers, deferred responses and
a WebSocket transport. API implementations (400–1700 lines each) wrap the vendor SDKs.

### 1.2 What pi gets right

| # | Idea | Why it works | v2 |
|---|---|---|---|
| pi1 | The model object carries everything needed to call it | A UI's selection *is* the call argument; "which model" persists as a value | adopt: `Model`, `ModelRef` |
| pi2 | One collection over many providers, dispatch by `model.provider` | Switching models mid-session is one argument; no map of clients | adopt: `Llm` |
| pi3 | The reply is an `AssistantMessage` that is appended | No wrapper → message → continue ceremony; aborted replies can be appended too | adopt |
| pi4 | Origin-aware hand-off (`transformMessages`) | Signatures replayed only to the same model; foreign thinking converted; tool-call ids normalized (OpenAI ids ≥ 450 chars vs Anthropic `^[a-zA-Z0-9_-]{1,64}$`); images downgraded for non-vision models | adopt as a core rule table (§3.9) |
| pi5 | Bundled, generated catalog (context, max output, reasoning, modalities, prices with tiers) plus explicit `refresh()` | Provider list endpoints rarely return limits or prices; synchronous reads keep pickers instant | adopt; replaces FM's "live list first" |
| pi6 | Cost computed on every reply | Budgets need money, not tokens | adopt, but absent — not `0` — when unknown |
| pi7 | Catalog-driven defaults: `maxTokens` = model maximum clamped to the context window; reasoning levels mapped per model (`thinkingLevelMap`, `getSupportedThinkingLevels`) | "Guess the right default" from data, not code | adopt |
| pi8 | Auth resolution chain with a `source` label ("ANTHROPIC_API_KEY", "OAuth", "stored credential"); `getAvailable()` lists models of configured providers | Exactly what status chips and model pickers need | adopt |
| pi9 | `AuthInteraction { prompt, notify }` for every login: key entry, OAuth URL, device code, select, a pasted code racing a loopback callback | One UI adapter covers every provider flow, including non-standard ones | adopt; replaces `LoginSession`, `LoginPrompt`, `BrowserLauncher` |
| pi10 | `CredentialStore.modify(id, fn)` is the only write path; refresh runs inside it | Double refresh of rotated tokens is impossible by construction, across processes when the store locks | adopt as `update(…)` |
| pi11 | Provider-scoped settings stored with the key (`env: { CLOUDFLARE_ACCOUNT_ID … }`) | "Connect provider" is one flow; account settings travel with the credential | adopt: `ApiKeyCredential.settings()` |
| pi12 | Typed per-API compat flags on provider and model (`maxTokensField`, `thinkingFormat` with 11 variants, `supportsDeveloperRole`, `requiresToolResultName`, …) | The OpenAI-compatible world needs about twenty flags; typed, documented, overridable per model | adopt as typed classes; replaces `QuirkKey` |
| pi13 | Faux provider registered like any other: scripted replies, several models, pacing, simulated cache usage | Tests exercise the real collection, streaming and events | adopt: `FakeProvider` |
| pi14 | Small escape hatches: `onPayload` (inspect or replace the body), `onResponse`, `onProviderStreamEvent` | Covers unmodelled fields without per-provider `EXTRA_BODY` keys | adopt the payload hook |
| pi15 | Partial tool-call arguments parsed while streaming | UIs show "writing src/Main.java" before the call completes | adopt: `ToolCallDelta.partialArguments()` |
| pi16 | Context-overflow detection across providers, including silent truncation | The one error every agent must branch on (compaction) | adopt: `CONTEXT_OVERFLOW` |
| pi17 | Side-effect-free core; each provider is an opt-in import | Size and startup proportional to use | already in FM (modules) |
| pi18 | pi-telemetry: tiny vendor-neutral contract, no exporter, no-op default, conformance kit; pi-ai only propagates a context and owns no schema | Observability does not bloat the transport | adopt the principle (§3.12) |

### 1.3 Where pi is weak

| # | Weakness | Consequence | v2 |
|---|---|---|---|
| w1 | Adapters wrap vendor SDKs; `timeoutMs`/`maxRetries` apply "for providers/SDKs that support it" | Timeouts, retries, cancellation and error shapes differ per provider; large adapters | keep FM: the core owns I/O, codecs are pure |
| w2 | Two parallel pairs: `stream`/`streamSimple`, `complete`/`completeSimple` (portable vs provider-typed options) | Callers choose a tier first; typed options need `hasApi()` narrowing | one method; portable and typed provider options coexist in `ChatOptions` |
| w3 | Failures after a stream starts are data (`stopReason: "error"`); `complete()` never rejects | An unchecked stop reason is a silent failure | Java exceptions, with the partial message attached (`e.partial()`) |
| w4 | `partial` is a live object shared by queued events ("inspect it when handling, don't retain it") | Aliasing bugs; a documented pitfall | immutable events; `stream.partial()` returns a snapshot |
| w5 | Reasoning options on non-reasoning models are "silently ignored" | Meaning changes without trace | adapt with a `Warning`; `strict()` fails |
| w6 | Unknown prices and limits are `0` | Indistinguishable from "free" or "none" | absent (FM principle 4) |
| w7 | Compat auto-detected from `baseUrl` when not set | Magic; wrong behind proxies | explicit on presets; detection only in `OpenAiCompatible.custom(…)`, reported by `describe()` |
| w8 | One options bag of ~25 optional fields mixing auth, transport, hooks, sampling and `env` | Poor discoverability | grouped builder with typed sub-objects |
| w9 | Environment variables as configuration (Azure deployment maps, Cloudflare ids, `PI_CACHE_RETENTION`) | Hidden global inputs | explicit provider settings; the environment only in the auth chain, switchable (`Environment.none()`) |
| w10 | Consumer-subscription OAuth presets (Claude Pro/Max, ChatGPT Codex, Copilot) | Terms-of-service risk | FM stance: no presets; the `OAuthAuth` SPI lets an authorized host add them |
| w11 | Adding a provider is an eight-step, multi-file checklist (types union, generator, factory, tests, agent, docs, changelog) | Contradicts "provider = data" | FM's preset-as-data stays |
| w12 | Scope creep in one collection: images, classifiers, deferred responses, WebSocket transport, mid-conversation system messages with sections and tool diffs, persisted stream frames | 1900-line README | deferred; operations are added later as verbs |
| w13 | No structured-output API; tool arguments validated after the fact (TypeBox) | Every extraction task writes schema and parsing | keep FM's record-derived schemas and binding |
| w14 | No dry run, no connection test beyond "auth configured" | Debugging relies on `onPayload` | keep FM's `preview()`, `toCurl()`, `test()` |

### 1.4 pi-telemetry

`TelemetryContext.startSpan(options, span -> …)`: a span settles when its callback settles (no public
`end()`), the parent is passed explicitly (no ambient state), `NOOP_TELEMETRY_CONTEXT` is the default,
`InMemoryTelemetryContext` is the reference adapter, a runner-independent conformance suite checks
adapters, and typed attribute schemas are owned by the *domain* package (pi-agent-core), while pi-ai
only forwards a `telemetryContext`.

Transferable: the transport emits a small fixed vocabulary and owns no exporter; a no-op default; a
reference adapter and a conformance kit; content never enters telemetry by default. Not transferable
as is: callback-scoped spans do not fit a Java `ChatStream` that the caller drains after `stream()`
returns, and Java already has OpenTelemetry (with context propagation) and free JFR. v2 keeps FM's
listener + JFR, shrinks the event set, and names one attribute vocabulary shared by events, JFR, logs
and the optional OpenTelemetry module (§3.12).

---

## 2. Critical comparison

### 2.1 Scorecard

Judgement on a 1–5 scale, not measurement.

| Criterion | pi | FM | v2 |
|---|---|---|---|
| Simple path (lines, concepts) | 4 | 4 | 5 |
| Multi-provider work and model switching | 5 | 2 | 5 |
| Conversation model and cross-provider hand-off | 5 | 3 | 5 |
| Provider realism (compat flags, catalogs, auth chains) | 5 | 3 | 4 |
| Model metadata for UIs (limits, prices, reasoning levels) | 5 | 2 | 5 |
| Auth UX for UIs (one login protocol, status source) | 5 | 3 | 5 |
| Auth security contract | 3 | 5 | 5 |
| Execution contracts (timeouts, retries, outcome certainty, cancellation) | 2 | 5 | 5 |
| Error model | 2 | 5 | 5 |
| Structured output and typed tools | 2 | 5 | 5 |
| Debugging (preview, curl, connection test, wire log) | 2 | 5 | 5 |
| Testing tools | 4 | 5 | 5 |
| Surface size and concept count | 3 | 2 | 4 |
| Runtime dependencies | 2 | 5 | 5 |
| Evolution rules | 3 | 5 | 5 |

**How mature is FM compared with pi?** On contracts FM is ahead of pi, and nothing in pi argues for
weakening them. On the consumer model FM is behind: it treats "one endpoint, one client" as the unit
of work, idealizes provider data (seven quirks, metadata from list endpoints), and spends its
complexity budget on caching layers, configuration scopes and auth types that pi shows are
unnecessary. The revision is worth doing; it makes the SDK smaller *and* better fitted to its users.

### 2.2 Findings against FM

Severity scale of the `java-sdk-design` skill: *breaks callers*, *misuse-prone*, *friction*, *cosmetic*;
plus *overengineering* and *gap*.

| ID | Severity | FM element | Finding | v2 resolution |
|---|---|---|---|---|
| F1 | friction (both named consumers) | `LlmClient` bound to one endpoint (§0, §5.4) | Agents and workflow builders hold N providers and switch models per call; each host keeps `Map<endpoint, LlmClient>`, re-implements "which models can I use", and passes endpoints alongside model ids | `Llm` over many providers; `complete(Model, …)` dispatches to `model.providerId()`; never falls back |
| F2 | friction | `ChatResponse` + `message()` + `continueWith(…)`; immutable `ChatRequest` as both conversation and call spec (§7.3, §7.5) | Two types for one fact; policy and history in one value | `AssistantMessage` is the reply; `Conversation` is history (data); `ChatOptions` is the call spec |
| F3 | misuse-prone | Reasoning parts carry `origin()`; foreign ones are stripped (§7.4) | Only reasoning is handled; tool-call id formats, provider signatures on tool calls, images for non-vision models and aborted turns are not | Message-level origin and a full hand-off table (§3.9) |
| F4 | misuse-prone | `ModelInfo` from `models().list()` with a TTL (§7.8, §11.4) | OpenAI and Anthropic list endpoints return ids, not limits or prices; most fields would be `UNKNOWN`; pickers and cost need bundled data | Generated catalog per provider module (`BUNDLED`), overlaid by live data; synchronous reads; explicit `refresh()` |
| F5 | friction | Anthropic's mandatory `max_tokens` as "a documented dialect default" (§9.1) | One fixed value is wrong for most models | Catalog default: the model's maximum output, clamped to the context window minus the estimated input |
| F6 | overengineering | Five merge scopes — endpoint, client builder, `defaults(GenerationSettings)`, `withOptions(CallOptions)` views, request — plus `omit(Param)` | Precedence becomes the hardest thing to learn and test | Three scopes: `Llm` defaults → provider defaults → call `ChatOptions`, then catalog-derived values; no `omit` |
| F7 | overengineering | `ChatCall` handle with exclusive `send`/`stream`/`sendAsync` | A state machine per call; a workflow "Stop" needs one handle per node | `CancelToken` in `ChatOptions` (tree-cancellable, like `AbortSignal`); `stream.close()` and thread interrupts still cancel |
| F8 | overengineering | Four cache layers with seven public types: `PromptCache`, `CacheBreakpoint`, `CacheMode`, `ResponseCache`, `CacheKey`, `CachedExchange`, `CachingOptions` (§11) | The everyday need (prompt caching) is buried under configuration for rarer ones | Everyday: `cacheRetention(NONE/SHORT/LONG)` + `sessionId`, markers placed by the codec. Opt-in, kept: explicit `cacheBreakpoint()` overrides placement; `ResponseCache` + `CacheMode` for re-runs and cassettes; key and exchange types become internal |
| F9 | overengineering | About nineteen auth types — six `Credentials` variants, `ApiKey`, `BearerToken`, `Secret` references, `SecretResolver`, `CredentialProvider`, `AuthMethod`, `TokenStore`, `OAuthCredentials`, `OAuthConfig`, `OAuthTokens`, `AuthOptions`, `BrowserLauncher`, `LoginSession`, `LoginPrompt`, `LoginOptions`, `AuthStatus`, `AuthApi` — plus six auth events | The closed `LoginPrompt` cannot express "select a profile", "paste the code" or "enter the key" | pi's split: stored `Credential` (two variants) · `CredentialStore` · provider strategy `ApiKeyAuth`/`OAuthAuth` · UI `AuthInteraction` · `AuthStatus` |
| F10 | friction | Endpoint JSON with `Secret` references resolved by `SecretResolver` (§9.4) | Secret and non-secret configuration share one document; hosts write resolvers | Split by construction: provider configuration JSON has no credential fields; credentials live in the `CredentialStore` under the provider id (a vault is a store implementation) |
| F11 | friction | `OptionKey<T>` and `QuirkKey<T>` constants (§7.2, §8.7) | Generic keys are less discoverable than builders; seven quirks underestimate reality | Typed `ProviderOptions` classes per API family and typed `ApiCompat` classes |
| F12 | overengineering | Protocol SPI: `Dialect`, `ChatCodec`, `StreamDecoder`, `ModelListCodec`, `AccountCodec`, `EncodeContext`, `DecodeContext`, `WireFrame`, `StreamFormat`, `ProviderApi`, `ProviderApiContext` | A protocol author meets eleven types; `Dialect` only bundles codecs | `WireApi` + `StreamDecoder` (+ two contexts) for protocols; model listing is a provider `ModelSource`; `AccountCodec` folds into provider APIs; `ProviderApi` kept as the Layer-3 mechanism for provider-only operations |
| F13 | misuse-prone (default) | `UnsupportedPolicy.FAIL` by default (§9.2) | Model switching is the common case; `FAIL` breaks a workflow run when a node's model changes | Soft settings adapt with warnings by default, hard ones fail; `strict()` opt-in (§3.9) |
| F14 | gap | `Usage` without money; `Pricing.estimate(…)` optional | Every budget UI recomputes | `Usage.cost()` from catalog prices (tiers included), absent when unknown |
| F15 | gap | No universal body escape hatch; per-provider `EXTRA_BODY` keys | One key per provider module | `ChatOptions.payload(UnaryOperator<JsonObject>)`, the last edit before sending, visible in `preview()` |
| F16 | gap | No partial tool arguments while streaming (FM §20 item 6 defers it) | Agent UIs cannot show a write in progress | `ToolCallDelta.partialArguments()`: best-effort, repaired JSON |
| F17 | gap | Mapping of `CONTEXT_LENGTH_EXCEEDED` left to each codec | Providers report overflow in dozens of formats; some truncate silently | Shared detector: codec patterns + preset patterns + a silent-overflow check against `contextWindow` |
| F18 | friction | Thirteen exported packages | Import churn for a small concept set | Five packages (§3.14) |

### 2.3 Where FM is better than pi — kept unchanged

FM §10 (timeouts connect/idle/total, retries only for documented not-processed responses,
`outcomeUnknown`, no retry after visible output, framing, threading, ownership), §12.3 (OAuth security
contract), the internal JSON with stable key order and zero runtime dependencies, record-derived tool
and output schemas (§4.5, §4.6, §7.7), `preview()`/`toCurl()`/wire log/JFR (§14.2), the error taxonomy
(§7.14), contract kits and `ScriptedTransport` (§14.1), JPMS and Gradle conventions (§6.4–§6.6), and the
evolution rules (§17.1). §4 lists everything kept.

---

## 3. Architecture v2

### 3.1 Principles — changes to FM §2

FM principles 1, 2, 4 and 6–12 are unchanged. Changed or added:

- **3′ Provider is data, protocol is a pure codec, model is data.** Presets, compat flags and model
  catalogs ship as data with provider modules; only a new wire protocol is code.
- **5′ Never send what nobody chose — catalog-derived defaults excepted, and shown.** Unset sampling
  values are not sent. Values derived from the catalog (max tokens, reasoning mapping, cache markers)
  appear in `preview()` and as `Warning`s where they adapt a request.
- **13 One conversation, any model.** A `Conversation` is portable data; the SDK adapts history to the
  target model by documented rules and reports every adaptation.
- **14 Configuration and secrets never share a document.** Provider configuration is serializable;
  credentials live only in a `CredentialStore`.

### 3.2 Glossary — eight nouns

| Concept | Public types | One line |
|---|---|---|
| **Llm** | `Llm` | Thread-safe runtime over configured providers: transport, credentials, catalog, listeners |
| **Provider** | `Provider` | A configured backend: id, base URL, wire APIs, compat flags, auth strategy, models |
| **Model** | `Model`, `ModelRef` | A catalog entry naming its provider and API, with limits, reasoning levels and prices |
| **Conversation** | `Conversation`, `Message`, `Content` | System prompt, tools and messages; portable and serializable |
| **Reply** | `AssistantMessage`, `ChatStream`, `ChatEvent` | The model's turn, whole or streamed; appendable to the conversation |
| **Tool** | `Tool`, `ToolCall`, `ToolResult` | A function the model may call; the SDK transports, the host executes |
| **Options** | `ChatOptions` | Per-call generation, caching and operational settings |
| **Credential** | `Credential`, `CredentialStore`, `AuthInteraction` | What authenticates a provider, where it is kept, how a user supplies it |

The Simple path needs three types: `Llm`, `Model`, `AssistantMessage`.

### 3.3 Usage

**Configured runtime.** Everything is optional; `Llm.create()` is this with discovered providers.

```java
try (Llm llm = Llm.builder()
        .provider(Providers.anthropic())
        .provider(Providers.openai())
        .provider(OpenAiCompatible.ollama())                                  // keyless; local-model timeouts as provider defaults
        .credentials(CredentialStore.file(home.resolve(".my-agent/credentials.json")))
        .defaults(o -> o.timeouts(t -> t.total(Duration.ofMinutes(20))))
        .responseCache(ResponseCache.inMemory(1_000))                         // opt-in: re-run a workflow without re-billing
        .interceptor(tracing::addHeaders)                                     // opt-in: per-attempt wire hook (FM §8.2)
        .listener(metrics::record)
        .build()) {
    …
}
```

**Per-run policy without views.** What FM expressed with `withOptions(…)` is a `ChatOptions` value
shared by the calls of a run:

```java
ChatOptions runOptions = ChatOptions.builder()
        .tag("workflowRun", runId)                    // copied onto every event, log line and JFR record
        .listener(runPanel::onEvent)                  // added to the Llm's listeners for these calls only
        .responseCache(CacheMode.REFRESH)             // "force re-run"
        .cancel(runToken)
        .build();
```

**Switch models inside one conversation.**

```java
Conversation chat = Conversation.builder()
        .system("You are a terse senior Java reviewer.")
        .user("Review this diff:\n" + diff)
        .build();

AssistantMessage review = llm.complete(llm.model("anthropic", "claude-sonnet-5"), chat,
        ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).build());
chat = chat.append(review).appendUser("Is that review complete?");

AssistantMessage check = llm.complete(llm.model("openai", "gpt-5.1"), chat);     // history adapted per §3.9
check.warnings().forEach(log::info);                                             // e.g. reasoning_converted
```

**Tools — the host owns the loop.**

```java
record ReadFile(@Description("Workspace-relative path") String path) {}
Tool readFile = Tool.of("read_file", "Read a UTF-8 text file from the workspace", ReadFile.class);

Conversation chat = Conversation.builder()
        .system("You are a coding assistant. Use tools to inspect the workspace.")
        .tool(readFile)
        .user("What does build.gradle.kts configure?")
        .build();

AssistantMessage reply = llm.complete(model, chat);
for (int turn = 0; reply.hasToolCalls() && turn < 8; turn++) {
    List<ToolResult> results = reply.toolCalls().stream()
            .map(call -> ToolResult.of(call, workspace.read(call.arguments(ReadFile.class).path())))
            .toList();
    chat = chat.append(reply, results);
    reply = llm.complete(model, chat);
}
```

**Streaming with a Stop button shared by a whole run.**

```java
CancelToken run = CancelToken.create();                        // one token for every node of a workflow run
ui.onStop(run::cancel);

ChatOptions options = ChatOptions.builder().cancel(run.child()).build();
try (ChatStream stream = llm.stream(model, chat, options)) {
    for (ChatEvent event : stream) {
        switch (event) {
            case ChatEvent.TextDelta d                -> ui.appendText(d.text());
            case ChatEvent.ReasoningDelta d           -> ui.appendThinking(d.text());
            case ChatEvent.ToolCallDelta d            -> d.partialArguments().get("path").ifPresent(ui::showWriting);
            case ChatEvent.PartEnd(int i, ToolCall c) -> ui.showToolCall(c);
            default                                   -> { }
        }
    }
    chat = chat.append(stream.result());                       // equals what complete() returns
} catch (RequestCancelledException e) {
    if (e.partial().isPresent()) chat = chat.append(e.partial().get());   // an aborted turn can be kept and continued
}
```

**Structured output** (unchanged from FM §4.6):

```java
record LineItem(String description, int quantity, BigDecimal unitPrice) {}
record Invoice(String number, LocalDate issued, List<LineItem> items, BigDecimal total) {}

Invoice invoice = llm.complete(model, Conversation.of("Extract the invoice:\n" + text), Invoice.class);
```

**Portable reasoning, provider-specific tuning, last-resort payload edit.**

```java
ChatOptions tuned = ChatOptions.builder()
        .reasoning(ReasoningLevel.HIGH)                                            // mapped via model.reasoningLevels()
        .provider(AnthropicOptions.builder().thinkingBudget(12_000).build())       // only anthropic-messages reads it
        .provider(OpenAiResponsesOptions.builder().serviceTier("flex").build())    // only openai-responses reads it
        .payload(body -> body.with("metadata", Json.object("user_id", "u-17")))    // shown in preview()
        .build();
// On other APIs the foreign provider options are inert and reported as Warning("option_not_applicable").
```

**Connect a provider from a UI.**

```java
AuthStatus status = llm.auth().status("openrouter");     // no network: NOT_CONFIGURED, or CONFIGURED via "OPENROUTER_API_KEY"
if (status.state() == AuthStatus.State.NOT_CONFIGURED) {
    llm.auth().login("openrouter", AuthType.OAUTH, dialog);   // runs the provider's flow and stores the credential
}
List<Model> usable = llm.models().available();            // models of configured providers, for the picker

AuthInteraction dialog = new AuthInteraction() {          // one adapter for every provider and flow
    @Override public String prompt(AuthPrompt p) {        // blocks the login thread until the user answers
        return switch (p) {
            case AuthPrompt.SecretText s -> ui.askSecret(s.message());
            case AuthPrompt.Text t       -> ui.ask(t.message());
            case AuthPrompt.Select s     -> ui.choose(s.message(), s.options());
            case AuthPrompt.Code c       -> ui.askCode(c.message());       // pre-empted when the loopback callback arrives first
        };
    }
    @Override public void notify(AuthNotice n) {
        switch (n) {
            case AuthNotice.OpenUrl u    -> ui.openBrowser(u.url());
            case AuthNotice.DeviceCode d -> ui.showCode(d.verificationUri(), d.userCode());
            case AuthNotice.Info i       -> ui.info(i.message());
            case AuthNotice.Progress p   -> ui.status(p.message());
        }
    }
};
```

Terminals use `AuthInteraction.console()`. A static "API key" form stores its values with
`llm.auth().save("deepseek", new ApiKeyCredential(Secret.of(key), Map.of()))` — no dialog needed.

**Web backend with many users.** Each user gets a view over their own credentials; the login runs on a
virtual thread, `notify(OpenUrl)` makes the host redirect the browser, and `prompt(Code)` waits until
the host's callback route supplies the redirect URI; the flow validates `state` and PKCE (FM §12.3).

```java
Llm alice = llm.withCredentials(userStore.scoped("user-8412"));      // shares providers, transport and catalog
RedirectInteraction web = AuthInteraction.redirect(url -> session.redirectTo(url));
Thread.startVirtualThread(() -> alice.auth().login("openrouter", AuthType.OAUTH, web));
// GET /oauth/callback (after the host checks that the session belongs to user-8412):
web.complete(request.fullUri());
```

**Model picker and dynamic catalogs.**

```java
for (Model m : llm.models().available()) {
    ui.addModel(m.ref(), m.name(), m.contextWindow(), m.reasoningLevels(),
            m.input().contains(Modality.IMAGE), m.prices().flatMap(Prices::inputPerMillion));
}
RefreshReport report = llm.models().refresh("openrouter", "ollama");  // explicit network; per-provider errors, old lists kept
```

**Custom provider, from code or configuration.**

```java
Provider gateway = OpenAiCompatible.custom("corp-gw", URI.create("https://llm-gw.corp.example/v1")).toBuilder()
        .auth(ApiKeyAuth.bearer("Gateway key", "CORP_GW_KEY"))
        .compat(OpenAiCompletionsCompat.builder().maxTokensField("max_tokens").developerRole(false).build())
        .model(Model.builder("corp-gw", "gpt-5.1").contextWindow(400_000).maxOutputTokens(128_000)
                .reasoningLevels(LOW, MEDIUM, HIGH).build())
        .build();

List<Provider> fromConfig = ProvidersConfig.read(Files.readString(configFile), Providers.presets());
```

```json
{
  "schema": "llm-transport.providers/1",
  "providers": [
    { "id": "corp-gw", "preset": "openai-compatible", "baseUrl": "https://llm-gw.corp.example/v1",
      "headers": { "X-Tenant": "team-42" },
      "compat": { "maxTokensField": "max_tokens", "developerRole": false },
      "models": [ { "id": "gpt-5.1", "contextWindow": 400000, "maxOutputTokens": 128000,
                    "reasoning": ["low", "medium", "high"] } ] },
    { "id": "work-anthropic", "preset": "anthropic" }
  ]
}
```

No credential field exists in this format; keys and tokens for `corp-gw` and `work-anthropic` live in
the credential store under those ids.

**Testing without network, keys or mocks.**

```java
FakeProvider fake = FakeProvider.create()                         // provider "fake", models "fake" and "fake-thinker"
        .reply(r -> r.reasoning("Need the file first.").toolCall("read_file", Json.object("path", "a.txt")))
        .reply("Here is the summary.");
try (Llm llm = Llm.of(fake.provider())) {
    assertEquals("Here is the summary.", new Agent(llm, fake.model()).run("Summarize a.txt"));
}
assertEquals(2, fake.requests().size());                          // adapted conversations, as the codec received them
```

**Debugging** (FM §4.17, new arguments):

```java
PreparedRequest p = llm.preview(model, chat, options);   // no network, no credentials
p.warnings().forEach(System.err::println);               // reasoning_clamped XHIGH→HIGH; max_tokens=64000 from catalog
System.out.println(p.body().toPrettyJson());
System.out.println(p.toCurl());                          // the key appears as $ANTHROPIC_API_KEY
```

### 3.4 Layers and one call end to end

```
┌──────────────── PUBLIC API  dev.llmtransport · .auth · .event · .json ────────────────────────────────┐
│ Llm ─ complete  completeAsync  stream  preview  test  models()  auth()  withCredentials  addListener │
│ Model · Provider · Conversation/Message/Content · Tool · AssistantMessage · ChatStream/ChatEvent     │
│ ChatOptions · CancelToken · Usage/Cost · LlmException · Credential/CredentialStore/AuthInteraction   │
├──────────────── EXECUTION CORE  (internal) ──────────────────────────────────────────────────────────┤
│ Resolver: option merge · catalog defaults · reasoning clamp · hand-off transform · soft/hard checks  │
│ Engine → AttemptRunner (deadline, retry, outcome) → StreamPump → Accumulator (+ cost)                │
│ AuthResolver: chain + source label; refresh inside CredentialStore.update · OAuthFlows (PKCE,        │
│ loopback, external redirect, device) · Catalog · EventDispatcher · JFR · Redactor · SSE/NDJSON · JSON│
├──────── SPI for protocol and provider authors ──────┬──────── SPI fed by hosts ───────────────────────┤
│ WireApi · StreamDecoder · ApiCompat · ProviderOptions│ HttpTransport · WireInterceptor · ResponseCache ·│
│ ModelSource · ApiKeyAuth · OAuthAuth · ProviderBundle│ CredentialStore · AuthInteraction · LlmListener ·│
│ ProviderApi (Layer 3, experimental)                  │ JsonMapper                                       │
├──────────────────────────────────────────────────────┴───────────────────────────────────────────────────┤
│ Provider modules: openai (Responses, Chat Completions, compatible presets) · anthropic · google          │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

```
complete(model, conversation, options)
 ─► provider = providers[model.providerId()]     unknown id → IllegalArgumentException naming the known ids; never a fallback
 ─► Resolver        options: call ▷ provider defaults ▷ Llm defaults ▷ catalog-derived (max tokens, reasoning map, cache)
                    hand-off transform of the history for (provider, api, model) · soft/hard checks → warnings or failure
 ─► WireApi.encode  pure ─► payload hook ─► PreparedRequest                    (preview() stops here)
 ─► ResponseCache   opt-in; hit → replay through the codec (FM §11.3)
 ─► AuthResolver    stored credential ▷ environment/ambient → headers, query, base URL, source; OAuth refresh in store.update
 ─► WireInterceptor* per attempt ─► HttpTransport   destination bound to the provider origin; connect/idle/total; CancelToken
 ─► AttemptRunner   FM §10.3 retry and outcome rules
 ─► decode | StreamDecoder → ChatEvent* → Accumulator → AssistantMessage (usage + cost, warnings, origin)
 ─► LlmListener events · JFR
```

Everything below the resolver is FM's pipeline (FM §5.2).

### 3.5 Public API sketch (core)

Conventions are FM's (§7): record-style accessors, static factories, private constructors, builders with
consumer-builder overloads, `@NullMarked`, `Optional` only for normally-absent values, `/// Markdown` Javadoc
stating immutability or thread safety.

```java
package dev.llmtransport;

/// Thread-safe, closeable runtime over a fixed set of providers. Dispatches each call to the provider named
/// by the model — never to another provider, model or credential. Owns what it creates, borrows what it is given.
@ApiStatus.NonExtendable
public interface Llm extends AutoCloseable {
    static Llm create()                                  { … }  // discovered providers, Environment.system(), in-memory store
    static Llm of(Provider... providers)                 { … }
    static Builder builder()                             { … }

    ModelCatalog models();
    Auth auth();
    default Model model(String providerId, String modelId) { return models().require(providerId, modelId); }

    AssistantMessage complete(Model model, Conversation conversation, ChatOptions options);
    default AssistantMessage complete(Model model, Conversation conversation) { … }
    default AssistantMessage complete(Model model, String userText)          { … }
    /// Sets OutputFormat.of(type) unless one is set, completes, binds. @throws InvalidResponseException output_*
    <T> T complete(Model model, Conversation conversation, Class<T> outputType);
    /// Runs on a virtual thread; cancelling the future cancels the call.
    CompletableFuture<AssistantMessage> completeAsync(Model model, Conversation conversation, ChatOptions options);

    ChatStream stream(Model model, Conversation conversation, ChatOptions options);
    default ChatStream stream(Model model, Conversation conversation)        { … }

    PreparedRequest preview(Model model, Conversation conversation, ChatOptions options);  // no I/O, no credentials
    ConnectionReport test(Model model);           // configuration → authentication → reachability → model access; non-billable

    /// A view sharing providers, transport, catalog and listeners, with another credential store (users, tenants).
    Llm withCredentials(CredentialStore store);
    Registration addListener(LlmListener listener);
    /// Layer 3 (FM §8.5): typed provider-only operations — files, batches, Gemini cached contents, account info.
    @ApiStatus.Experimental <T> T providerApi(String providerId, ProviderApi<T> api);
    @Override void close();                       // idempotent; cancels in-flight calls; views: no-op

    /// Not thread-safe. build() validates, reports every violation at once and performs no I/O.
    final class Builder {
        public Builder provider(Provider provider)                  { … }  // adds; an equal id replaces
        public Builder discoverProviders()                          { … }  // ServiceLoader<ProviderBundle>
        public Builder credentials(CredentialStore store)           { … }  // default in memory; borrowed
        public Builder environment(Environment environment)         { … }  // default system(); servers: none()
        public Builder defaults(ChatOptions defaults)               { … }
        public Builder defaults(Consumer<ChatOptions.Builder> edit) { … }
        public Builder http(Consumer<HttpOptions.Builder> edit)     { … }  // FM §7.12; or an injected transport, borrowed
        public Builder responseCache(ResponseCache cache)           { … }  // opt-in; default none (FM §11.3 semantics)
        public Builder interceptor(WireInterceptor interceptor)     { … }  // adds, ordered (FM §8.2)
        public Builder listener(LlmListener listener)               { … }
        public Builder jsonMapper(JsonMapper mapper)                { … }
        public Builder clock(Clock clock)                           { … }
        public Llm build()                                          { … }
    }
}
```

```java
/// Immutable, JSON-serializable catalog entry. Absent means "not known" — never zero and never "no".
public final class Model {
    public static Builder builder(String providerId, String modelId) { … }   // hosts describe gateway or local models
    public ModelRef ref()                          { … }
    public String providerId()                     { … }
    public String id()                             { … }   // opaque; never split on ':' or '/'
    public String name()                           { … }
    public String api()                            { … }   // WireApi id; the provider's default when not set
    public Set<Modality> input()                   { … }   // TEXT, IMAGE, DOCUMENT, AUDIO
    public OptionalLong contextWindow()            { … }
    public OptionalLong maxOutputTokens()          { … }
    public List<ReasoningLevel> reasoningLevels()  { … }   // supported levels; empty = no reasoning control
    public Capabilities capabilities()             { … }   // tools, structured output, streaming: SUPPORTED/UNSUPPORTED/UNKNOWN
    public Optional<Prices> prices()               { … }   // per million tokens, input-size tiers, currency
    public Optional<ApiCompat> compat()            { … }   // overrides the provider's compat field by field
    public List<FieldDescriptor> parameters()      { … }   // parameter form derived from the fields above (reasoning
                                                           //   levels, output limit, supported sampling) — no extra data
    public Source source()                         { … }
    public Optional<Instant> deprecatedAt()        { … }
    public Builder toBuilder()                     { … }
    public enum Source { BUNDLED, LIVE, CUSTOM, UNLISTED }  // UNLISTED: a new id for a known provider, provider defaults
}

public record ModelRef(String providerId, String modelId) { }   // what conversations and configurations persist

@ApiStatus.NonExtendable
public interface ModelCatalog {
    List<Model> all();                                      // never I/O: bundled + custom + last refresh
    List<Model> all(String providerId);
    Optional<Model> find(ModelRef ref);
    /// A catalogued model, or an UNLISTED one for a known provider (warned on use);
    /// an unknown provider throws IllegalArgumentException naming the known ids and the module to add.
    Model require(String providerId, String modelId);
    List<Model> available();                                // providers whose auth resolves locally; no network
    RefreshReport refresh(String... providerIds);           // network; all dynamic providers when empty; lists kept on failure
}

/// Immutable. A configured backend; presets are copied and adjusted, never mutated.
public final class Provider {
    public static Builder builder(String id, WireApi defaultApi) { … }
    public String id()                           { … }   // "anthropic", "openai-work", "corp-gw"; also the credential key
    public String name()                         { … }
    public URI baseUrl()                         { … }
    public Map<String, String> headers()         { … }
    public List<WireApi> apis()                  { … }   // default first; a model names the one it uses
    public Optional<ApiKeyAuth> apiKeyAuth()     { … }
    public Optional<OAuthAuth> oauthAuth()       { … }   // at least one; keyless servers use ApiKeyAuth.none()
    public List<Model> models()                  { … }   // bundled or configured
    public Optional<ModelSource> modelSource()   { … }   // dynamic listing
    public Optional<ApiCompat> compat()          { … }   // default flags for its models
    public ChatOptions defaults()                { … }   // e.g. local servers: long timeouts
    public Optional<URI> apiKeyUrl()             { … }   // "get a key" link
    public Builder toBuilder()                   { … }
}
```

```java
/// Immutable, JSON-serializable (versioned canonical form). The source of truth of a chat; portable across models.
public final class Conversation {
    public static Conversation of(String userText)                               { … }
    public static Builder builder()                                              { … }
    public Optional<String> system()                                             { … }
    public List<Tool> tools()                                                    { … }
    public List<Message> messages()                                              { … }
    public Conversation append(Message... messages)                              { … }
    public Conversation append(AssistantMessage reply, List<ToolResult> results) { … }
    public Conversation appendUser(String text)                                  { … }
    public Conversation withSystem(String system)                                { … }
    public Conversation withTools(List<Tool> tools)                              { … }
    public Builder toBuilder()                                                   { … }
    /// Not thread-safe. Singular methods add, plural ones replace.
    public static final class Builder { /* system, tool, tools, user(String), user(Content...), assistant(String),
                                           message, messages, build */ }
}

public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    Instant timestamp();                                   // set by the SDK when not given
}
public final class UserMessage implements Message       { /* of(String), of(Content...), content(), text() */ }
public final class ToolResultMessage implements Message { /* results(): List<ToolResult> */ }
// Content, ToolCall and ToolResult as in FM §7.4, with two changes: origin() moves from parts to AssistantMessage,
// and Content.Reasoning gains redacted() for encrypted reasoning that is only replayable to its origin.

/// Immutable, JSON-serializable. The reply and the history entry at once.
public final class AssistantMessage implements Message {
    public List<Content> content()             { … }
    public String text()                       { … }   // text parts only
    public List<ToolCall> toolCalls()          { … }
    public boolean hasToolCalls()              { … }
    public Optional<String> reasoningText()    { … }
    public StopReason stopReason()             { … }   // open value type: STOP, LENGTH, TOOL_USE, CONTENT_FILTER, REFUSAL,
                                                       //   ABORTED, ERROR, OTHER; the raw provider value is kept
    public Optional<String> errorMessage()     { … }   // partial replies (ABORTED, ERROR) only
    public Usage usage()                       { … }
    public ModelRef model()                    { … }   // origin, with api(): drives the hand-off rules
    public String api()                        { … }
    public Optional<String> responseModel()    { … }   // concrete model reported by the provider (gateways)
    public Optional<String> responseId()       { … }
    public List<Warning> warnings()            { … }
    public ResponseInfo info()                 { … }   // transient, not serialized: request id, attempts, latency,
                                                       //   time to first output, provider request id, rate limits
    public JsonValue json()                    { … }
    public <T> T as(Class<T> type)             { … }   // FM's parse(): truncation and refusal throw before binding
}

/// Immutable. Disjoint buckets; absent = not reported.
public final class Usage {
    public OptionalLong input()       { … }   // uncached input
    public OptionalLong cacheRead()   { … }
    public OptionalLong cacheWrite()  { … }
    public OptionalLong output()      { … }   // includes reasoning
    public OptionalLong reasoning()   { … }
    public OptionalLong totalInput()  { … }   // input + cacheRead + cacheWrite when all are known
    public Optional<Cost> cost()      { … }   // from Model.prices(); absent when a needed price or counter is unknown
    public JsonValue raw()            { … }
}
public record Cost(Currency currency, BigDecimal input, BigDecimal cacheRead, BigDecimal cacheWrite,
                   BigDecimal output, BigDecimal total) { }
```

```java
/// Single consumer, single iteration, bounded, closeable (FM §10.4 unchanged).
@ApiStatus.NonExtendable
public interface ChatStream extends Iterable<ChatEvent>, AutoCloseable {
    Iterable<String> textDeltas();
    Stream<ChatEvent> events();
    AssistantMessage partial();              // immutable snapshot so far; never blocks
    AssistantMessage result();               // drains; equals complete() for the same exchange
    @Override void close();                  // thread-safe; cancels an unfinished call
}

/// Deltas and part ends are frozen records; lifecycle events are final classes. Keep a default branch.
public sealed interface ChatEvent {
    record TextDelta(int index, String text)                                      implements ChatEvent { }
    record ReasoningDelta(int index, String text)                                 implements ChatEvent { }
    record ToolCallStart(int index, String callId, String name)                   implements ChatEvent { }
    record ToolCallDelta(int index, String fragment, JsonObject partialArguments) implements ChatEvent { }  // best-effort repaired parse
    record PartEnd(int index, Content content)                                    implements ChatEvent { }  // authoritative part: text,
                                                                                                             //   reasoning + signature, ToolCall, image
    record Unknown(String type, JsonValue raw)                                    implements ChatEvent { }
    final class Started implements ChatEvent { /* responseId(), responseModel() */ }
    final class Done implements ChatEvent    { /* message(): AssistantMessage */ }
}
```

```java
/// Immutable. Unset inherits: call ▷ provider defaults ▷ Llm defaults ▷ catalog-derived ▷ not sent.
/// Portable fields have a JSON form; the cancel token and the payload hook are never serialized.
public final class ChatOptions {
    public static ChatOptions none()                                        { … }
    public static Builder builder()                                         { … }
    // generation — portable
    public OptionalDouble temperature()                                     { … }
    public OptionalInt maxTokens()                                          { … }   // default from the catalog (§3.9)
    public Optional<ReasoningLevel> reasoning()                             { … }
    public Optional<ToolChoice> toolChoice()                                { … }
    public Optional<OutputFormat> output()                                  { … }   // FM §7.7
    public List<String> stop()                                              { … }
    // prompt caching
    public Optional<CacheRetention> cacheRetention()                        { … }   // default SHORT (§8, decision 1)
    public Optional<String> sessionId()                                     { … }   // cache routing and affinity where supported
    public Optional<CacheMode> responseCache()                              { … }   // READ_WRITE, REFRESH, BYPASS, OFFLINE (FM §11.3)
    // operational
    public Optional<TimeoutPolicy> timeouts()                               { … }   // FM §7.12 and §10.2
    public Optional<RetryPolicy> retry()                                    { … }   // FM §7.12 and §10.3
    public Optional<CancelToken> cancel()                                   { … }
    public Map<String, String> headers()                                    { … }   // protected headers rejected
    public Map<String, String> tags()                                       { … }   // copied onto events, logs and JFR
    public List<LlmListener> listeners()                                    { … }   // added to the Llm's listeners for this call
    public boolean strict()                                                 { … }   // soft adaptations fail instead of warning
    // provider-specific and escape hatch
    public <T extends ProviderOptions> Optional<T> provider(Class<T> type)  { … }   // read only by its API family
    public Optional<UnaryOperator<JsonObject>> payload()                    { … }   // last edit of the wire body
    public ChatOptions overriddenBy(ChatOptions higher)                     { … }
    public Builder toBuilder()                                              { … }
    public static final class Builder { /* one setter per accessor; provider(…) adds or replaces by type;
                                           timeouts(Consumer<…>), retry(Consumer<…>) consumer-builders */ }
}

public enum ReasoningLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }
public enum CacheRetention { NONE, SHORT, LONG }

/// Thread-safe. Cancels every call carrying it or one of its children.
public final class CancelToken {
    public static CancelToken create()             { … }
    public CancelToken child()                     { … }   // cancelled with its parent; cancelling it leaves the parent alone
    public void cancel()                           { … }   // idempotent; before start the call never starts
    public boolean isCancelled()                   { … }
    public Registration onCancel(Runnable action)  { … }
}
```

Errors keep FM §7.14 with three changes: `LlmException.partial()` returns the reply received before the
failure (stop reason `ERROR` or `ABORTED`, usage included, appendable); `CONTEXT_LENGTH_EXCEEDED` becomes
`CONTEXT_OVERFLOW`, raised by the shared detector (§3.10); `CACHE_MISS` moves to the testing artifact.

### 3.6 SPI

```java
package dev.llmtransport.spi;

/// Stateless, thread-safe, pure: no I/O, credentials, retries or clocks. One wire protocol at one revision;
/// an incompatible revision is a new WireApi (FM §16.4 unchanged).
public interface WireApi {
    String id();                                                     // "anthropic-messages", "openai-responses",
                                                                     // "openai-completions", "google-generate-content"
    HttpCall encode(ApiRequest request, EncodeContext ctx);          // relative URI + JSON body; ctx.warn(…) for adaptations
    AssistantMessage decode(HttpReply reply, DecodeContext ctx);
    StreamDecoder streamDecoder(DecodeContext ctx);                  // one per stream
    default StreamFormat streamFormat()                              { return StreamFormat.SSE; }
    /// Error facts for a non-2xx reply, including this API's context-overflow patterns; the core picks the exception.
    default LlmException.Details decodeError(HttpReply reply, DecodeContext ctx) { … }
    /// Tool-call ids from other APIs, made acceptable to this one (hand-off, §3.9).
    default String normalizeToolCallId(String foreignId)             { return foreignId; }
}

/// What a codec receives: the target model, the history already adapted to it, the effective options.
public record ApiRequest(Model model, Conversation conversation, ChatOptions options, boolean streaming) { }

public interface EncodeContext { <C extends ApiCompat> C compat(Class<C> type); JsonMapper json(); void warn(Warning w); }
public interface DecodeContext { Model model(); <C extends ApiCompat> C compat(Class<C> type); JsonMapper json(); void warn(Warning w); }

/// Confined to the consuming thread (FM §8.1 contract rows unchanged).
public interface StreamDecoder {
    List<ChatEvent> onFrame(Frame frame);                            // none for keep-alives, several for composite frames
    List<ChatEvent> onEnd();                                         // Done exactly once, or throw unexpected_stream_end
}

/// Typed compat flags of one API family (OpenAiCompletionsCompat, AnthropicCompat, …): provider default, model override.
public interface ApiCompat { String api(); ApiCompat overriddenBy(ApiCompat higher); }

/// Typed provider-specific request options (AnthropicOptions, OpenAiResponsesOptions, GeminiOptions, …).
public interface ProviderOptions { String api(); }

/// Dynamic model listing (OpenRouter, Ollama, gateways); I/O through the core's authenticated, deadline-bound client.
@FunctionalInterface
public interface ModelSource { List<Model> fetch(ProviderHttp http) throws IOException; }
public interface ProviderHttp { JsonValue get(String relativePath) throws IOException; }

/// ServiceLoader: lets a provider module contribute presets to Llm.create() and ProvidersConfig.
public interface ProviderBundle { List<Provider> providers(); }
```

Kept from FM unchanged: `HttpTransport` and `WireInterceptor` (§8.2; the interceptor now serves
tracing headers and request signing such as Bedrock SigV4, while everyday headers come from
`ChatOptions.headers()` and body edits from the payload hook), `ProviderApi` + `ProviderApiContext`
(§8.5) for provider-only operations, and `ResponseCache` (§7.13, with `CacheKey` and `CachedExchange`
moved to internal types).

**Extension ladder** (FM §8.6, re-rung): 1 — options (`ChatOptions`, provider defaults); 2 — data
(a `Provider` preset with compat flags and models; `ProvidersConfig`); 3 — host-fed dependencies
(`CredentialStore`, `HttpTransport`, `ResponseCache`, `JsonMapper`, `AuthInteraction`); 4 — observation
(`LlmListener`, JFR); 5 — interception and hooks (`WireInterceptor`, payload hook); 6 — escape hatches
(`ProviderOptions`, `ProviderApi`, `Content.Unknown`); SPI — a new `WireApi`, or a provider auth strategy.

Contract rows (thread, ordering, error isolation, blocking, lifecycle) are FM §8.4 for the kept SPIs,
plus:

| Extension | Thread | Ordering | Error isolation | Blocking | Lifecycle |
|---|---|---|---|---|---|
| `CredentialStore` | any; thread-safe | `update` serialized per key | failures become `AuthenticationException(credential_store)` | short I/O | host-owned |
| `AuthInteraction` | the `login()` caller | prompts in flow order | a throw aborts the login | `prompt` blocks; `notify` must not | per login |
| `ApiKeyAuth` / `OAuthAuth` | any | — | propagate as `AuthenticationException` | `resolve` local; `login`/`refresh` network | provider-owned |
| `ModelSource` | refresh caller | — | reported per provider in `RefreshReport` | network within the deadline | provider-owned |

### 3.7 Provider data: presets, compat, catalogs

**Presets.** FM's preset table (§8.7) stays, with three additions per preset: its auth chain (for example
Anthropic: stored credential → `ANTHROPIC_API_KEY`), its compat flags, and its bundled catalog. The
`google` module replaces `gemini` so Gemini API and, later, Vertex share one codec.

**Compat flags** are typed per API family and merged field by field: preset → provider configuration →
model. `OpenAiCompletionsCompat` starts with pi's evidence as the checklist: `maxTokensField`,
`developerRole`, `store`, `streamUsage`, `strictTools`, `reasoningFormat` (openai, openrouter, deepseek,
qwen, zai, chat-template…), `reasoningContentReplay`, `thinkingAsText`, `toolResultName`,
`assistantAfterToolResult`, `cacheControl`, `sessionHeader`. `OpenAiCompatible.custom(id, url)` detects
flags for well-known hosts and reports what it detected in `describe()`; presets never rely on
detection.

**Catalogs.** Each provider module ships `models.json` as a resource, generated at build time by a
Gradle task from public model metadata (models.dev, OpenRouter) plus curated overrides, pinned by tests
and versioned with the module. Every field may be absent. `refresh()` overlays `LIVE` data from dynamic
sources; host-described models are `CUSTOM`; a model id missing from the catalog is still callable as
`UNLISTED` with provider defaults, because models ship faster than modules. Reasoning levels map to
provider values from catalog data (pi's `thinkingLevelMap`), not from code.

### 3.8 Auth

**Types** (package `dev.llmtransport.auth`):

```java
@ApiStatus.NonExtendable
public interface Auth {
    AuthStatus status(String providerId);                                    // local: store and environment only
    List<AuthType> methods(String providerId);                               // API_KEY, OAUTH in preference order
    Credential login(String providerId, AuthType type, AuthInteraction ui);  // blocks; stores the result
    Credential login(String providerId, AuthType type, AuthInteraction ui, CancelToken cancel);
    void save(String providerId, Credential credential);                     // static forms
    void logout(String providerId);                                          // local: deletes the stored credential
}

public final class AuthStatus {
    public enum State { NOT_CONFIGURED, CONFIGURED, EXPIRED, REFRESH_FAILED }
    /* state(), type(), source() — "ANTHROPIC_API_KEY", "stored credential", "OAuth", "gcloud ADC" — expiresAt() */
}
public enum AuthType { API_KEY, OAUTH }

public sealed interface Credential permits ApiKeyCredential, OAuthCredential { }
/// Immutable; toString() redacts. settings: provider-scoped non-secret values collected at login (account id, region).
public record ApiKeyCredential(Secret key, Map<String, String> settings) implements Credential { }
public final class OAuthCredential implements Credential {
    /* access(), refresh(), expiresAt(), issuer(), clientId(), account() — the binding of FM finding B6 —
       extra(): JsonObject, provider data such as a per-account base URL */
}

/// SPI (host). One credential per key; update() is the only write path — atomic per key, and across processes
/// where the store supports it. Refresh runs inside update(), so a rotated refresh token is persisted in the same step.
public interface CredentialStore {
    Optional<Credential> read(String key);
    List<Entry> list();                                                     // keys and types; never secrets
    Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change);
    void delete(String key);
    default CredentialStore scoped(String prefix) { … }                     // users and tenants
    static CredentialStore inMemory()      { … }
    static CredentialStore file(Path path) { … }   // JSON, owner-only permissions, lock + atomic rename; not encrypted
    record Entry(String key, AuthType type) { }
}

/// SPI (host UI). Prompts and notices reach only the caller of login() — never listeners, logs or events.
public interface AuthInteraction {
    String prompt(AuthPrompt prompt);          // blocks until answered; throw to abort
    void notify(AuthNotice notice);            // returns quickly
    static AuthInteraction console()                                 { … }  // terminal; opens a desktop browser when possible
    static RedirectInteraction redirect(Consumer<URI> sendBrowserTo) { … }  // web hosts; complete(callbackUri) finishes
}
public sealed interface AuthPrompt {
    record Text(String message, Optional<String> placeholder) implements AuthPrompt { }
    record SecretText(String message)                         implements AuthPrompt { }
    record Select(String message, List<Option> options)       implements AuthPrompt { }
    record Code(String message)                               implements AuthPrompt { }  // pasted code or redirect URI
    record Option(String id, String label, Optional<String> description) { }
}
public sealed interface AuthNotice {
    record OpenUrl(URI url, Optional<String> instructions)                  implements AuthNotice { }
    record DeviceCode(URI verificationUri, String userCode, Instant expiresAt) implements AuthNotice { }
    record Info(String message, List<URI> links)                            implements AuthNotice { }
    record Progress(String message)                                         implements AuthNotice { }
}

/// SPI (provider). How a provider accepts keys; presets use the factories.
public interface ApiKeyAuth {
    String name();                                                    // "Anthropic API key"
    Optional<ResolvedAuth> resolve(AuthInput input);                  // stored credential first, then environment/ambient
    default List<FieldDescriptor> fields() { … }                      // key + settings, for static forms (FM §7.2 type)
    default Optional<ApiKeyCredential> login(AuthInteraction ui) { … }   // prompts for fields()
    static ApiKeyAuth bearer(String name, String... envVars)                 { … }
    static ApiKeyAuth header(String name, String header, String... envVars) { … }
    static ApiKeyAuth none()                                                 { … }  // keyless local servers
    static ApiKeyAuth dynamic(String name, TokenSupplier supplier)           { … }  // Entra ID, ADC: cached to expiry, single-flight
}
public interface OAuthAuth {
    String name();
    OAuthCredential login(AuthInteraction ui, CancelToken cancel);
    OAuthCredential refresh(OAuthCredential credential);              // network; runs inside CredentialStore.update
    ResolvedAuth toAuth(OAuthCredential credential);                  // no I/O
    static OAuthAuth standard(OAuthConfig config) { … }               // PKCE S256 loopback, external redirect,
                                                                      //   device code, client credentials (FM §12.2)
}
/// Applied to one request. Anything not expressible here is provider configuration, not auth.
public record ResolvedAuth(Map<String, String> headers, Map<String, String> query, Optional<URI> baseUrl, String source) { }
public record AuthInput(Optional<ApiKeyCredential> stored, Environment environment) { }
public interface Environment {
    Optional<String> get(String name);
    static Environment system() { … }  static Environment none() { … }  static Environment of(Map<String, String> values) { … }
}
```

`OAuthConfig` is FM's (§7.10) plus `redirectUri(URI)` for web hosts; `Secret` remains a redacting
wrapper without references.

**Resolution order.** Stored credential → provider environment variables and ambient sources through
`Environment` → not configured (`AuthenticationException(login_required)` naming the variables). A
stored credential owns its provider: a failed OAuth refresh reports `REFRESH_FAILED` and never falls back
to an environment key. `Llm.create()` uses `Environment.system()`; multi-tenant servers use
`Environment.none()` so the operator's variables cannot authenticate a tenant.

**Security.** FM §12.3 holds inside `OAuthFlows` and `OAuthAuth.standard`. Restated for the new types:
prompts and notices go only to the `AuthInteraction` passed to `login()`; tokens never appear in events,
logs, exceptions or `toString()`; credentials are keyed by provider id within a store scope, and an
`OAuthCredential` whose issuer or client id no longer matches the provider is rejected as
`login_required`; single-flight refresh is `update()` atomicity; after `logout()` a late login or refresh
cannot store tokens.

### 3.9 Portability rules

**Defaults resolved when the call leaves a setting unset.**

| Setting | Resolution |
|---|---|
| `maxTokens` | provider defaults → `Llm` defaults → model maximum output clamped to `contextWindow − estimate(conversation) − margin` → sent only if the API requires it; when limits are unknown and the API requires a value, the API's documented minimum default, with a warning |
| `reasoning` | provider or `Llm` defaults → not sent (the model's own default) |
| Reasoning level not in `model.reasoningLevels()` | nearest supported level, `Warning(reasoning_clamped)`; `OFF` on models without reasoning control |
| Budget-based APIs (Anthropic, Gemini thinking budgets) | level → budget from catalog data or the API's published table (`AnthropicOptions.thinkingBudget` overrides); max tokens raised to fit budget + answer room, capped at the model maximum |
| `cacheRetention` | `SHORT`; markers placed by the codec (§3.11) |
| Sampling (`temperature`, `stop`) | not sent |

**Soft and hard adaptations.**

| Situation | Default | `strict()` |
|---|---|---|
| Unsupported reasoning level | clamp + warning | fail |
| Parameter the model rejects (compat flag, for example temperature on reasoning models) | drop + warning | fail |
| `maxTokens` above the model maximum | clamp + warning | fail |
| Images for a non-vision model | placeholder text + warning | fail |
| Foreign `ProviderOptions` | inert + warning | inert + warning (declared provider-specific) |
| Cache retention the API cannot express | nearest value + warning | nearest value + warning (advisory) |
| Tools on a model marked `UNSUPPORTED` | fail | fail |
| Output schema with neither native nor documented adapted support | fail | fail |
| Document input the API cannot carry | fail | fail |

**Hand-off.** Applied by the resolver to history before encoding, for target provider P, API A and
model M; each adaptation beyond an id rename is a warning.

| History part | Origin = (P, A, M) | Any other origin |
|---|---|---|
| Text | as is | as is; provider text signatures dropped |
| Reasoning with text and signature | replayed | converted to plain assistant text (§8, decision 2); empty → dropped |
| Redacted or encrypted reasoning | replayed | dropped |
| Tool call | as is | id normalized by `A.normalizeToolCallId`; provider signatures dropped; matching tool results remapped |
| Images in user or tool messages, M without `IMAGE` input | placeholder text | placeholder text |
| Aborted or failed assistant turns | content kept; empty turns dropped | same |

Unknown parts from another origin are dropped with a warning; unknown parts from the same origin are
replayed (FM §16.4 principle kept).

### 3.10 Streaming, errors and cancellation

- **Streams** follow FM §10.4 unchanged (framing, normalized deltas, terminal evidence, bounded
  accumulation, no reconnection). Event shapes change as in §3.5: `PartEnd` carries the authoritative
  part; `Done` carries the final message; partial tool arguments are best-effort and never validated.
- **Failures** are exceptions (FM §7.14). The iterator throws once; `e.partial()` holds everything
  received, so an agent can append the aborted turn and continue ("Continue").
- **Context overflow** is detected in one place: the API's `decodeError` patterns, preset patterns for
  compatible providers, and a silent-overflow check (reported input above `contextWindow`, or a length
  stop with zero output on a filled context). Result: `InvalidRequestException(CONTEXT_OVERFLOW)`.
- **Cancellation.**

| Operation | Cancel with | Effect |
|---|---|---|
| any call | `CancelToken.cancel()` (or a parent's) | before start: never sent; during: transport aborted, `RequestCancelledException` with `partial()` |
| `stream()` | `stream.close()` from any thread | same as above |
| `completeAsync()` | `future.cancel(true)` | cancels the call's internal child token |
| `complete()` | interrupting the calling thread | FM §10.1 |
| `login()` | its `CancelToken`, or throwing from `prompt()` | loopback stopped; `AuthenticationException(login_cancelled)` |
| everything | `llm.close()` | cancels in-flight calls it owns |

FM §10.2, §10.3, §10.5 (remaining rows) and §10.6 stay as written, with `ChatCall` read as "a call".

### 3.11 Caching — reduced to what a transport needs

| Concern | v2 | Replaces (FM §11) |
|---|---|---|
| Provider prompt caching — everyday | `ChatOptions.cacheRetention` + `sessionId`; codecs place markers automatically | `PromptCache` (`auto`, `key`, `retention`) |
| Provider prompt caching — advanced | `Conversation.Builder.cacheBreakpoint()` marks a prefix explicitly and overrides automatic placement | `CacheBreakpoint` sealed type (now internal) |
| Response cache — opt-in | `Llm.Builder.responseCache(ResponseCache)` + per-call `ChatOptions.responseCache(CacheMode)`; FM §11.3 semantics (key includes credential scope; only complete successes; replay through the codec; `fromCache` in `info()` and events); cassettes = `ResponseCache.directory(…)` + `OFFLINE` | `CachingOptions`, public `CacheKey`, `CachedExchange` |
| Model catalog | in memory; bundled + last refresh; `refresh()` is the only I/O; `models().refreshIfOlderThan(Duration)` for pickers that want FM's TTL behaviour | TTL cache with implicit fetch on miss |
| Credentials | the `CredentialStore`; `ApiKeyAuth.dynamic` caches until expiry | `TokenStore`, `SecretResolver` caching rules |

| API | `SHORT` | `LONG` | `sessionId` |
|---|---|---|---|
| Anthropic Messages | `cache_control` on the system prompt, last tool and last message | 1-hour TTL where supported, else `SHORT` + warning | — |
| OpenAI Responses and Chat Completions | automatic prefix caching | extended retention where the model supports it | `prompt_cache_key` |
| Gemini | implicit caching | implicit caching | — |
| Compatible providers | per compat flag (`cacheControl`, automatic) | per compat flag | per compat flag (`sessionHeader`) |

FM §11.2's determinism rules (stable key order, no volatile values in prompts, identical requests →
identical bytes) stay: they are what makes `SHORT` effective. Provider cache resources with their own
lifetime (Gemini cached contents) stay a provider API (`llm.providerApi("google", Gemini.CACHES)`).

### 3.12 Observability

- **Listener events** shrink from fourteen to seven: `RequestStarted`, `RequestFirstOutput` (UI
  progress), `RequestRetrying`, `RequestFinished` (outcome completed/failed/cancelled, usage, cost,
  latency, time to first output, attempts, warnings, from cache), `CredentialRefreshed`,
  `CredentialRefreshFailed`, `ModelsRefreshed`. Login
  progress is `AuthNotice`, delivered to the interaction only. Events stay sealed, content-free and
  secret-free, isolated and ordered per call (FM §7.11), and carry `ChatOptions.tags()`.
- **One vocabulary** (pi-telemetry's "the domain owns the schema"): attribute names such as
  `llm.provider`, `llm.api`, `llm.model`, `llm.request_id`, `llm.usage.input`, `llm.usage.cache_read`,
  `llm.cost.total`, `llm.outcome` are defined once and used by events, JFR, logs and the optional
  OpenTelemetry module, which maps them to the GenAI semantic conventions and propagates
  `io.opentelemetry.context.Context`.
- **No-op by default, reference adapter in tests**: no listener costs nothing; `RecordingListener` in
  `-testing` is the reference implementation and the basis of a small listener contract kit.
- JFR and `System.Logger` as FM §15.

### 3.13 Testing

| Tool | Purpose | Change from FM §14.1 |
|---|---|---|
| `FakeProvider` | A provider with models (`fake`, `fake-thinker`) and scripted replies (text, reasoning, tool calls, usage, stop reason, failures), streamed in chunks, optional pacing, simulated cache usage per `sessionId`; `requests()` records adapted conversations | replaces `FakeLlm`; works in multi-provider tests |
| Cassettes | `ResponseCache.directory(…)` + `CacheMode.OFFLINE`; `Cassettes.recordOnce(path)` helper | as FM |
| `ScriptedTransport`, `LlmErrors`, `TestClock`, `FakeAuthorizationServer` | as FM | — |
| Contract kits | `WireApiContract` (golden fixtures, hand-off cases, split chunks), `HttpTransportContract`, `CredentialStoreContract` (atomic `update`, concurrent refresh), `ListenerContract` | renamed and extended |

### 3.14 Project structure

| Artifact | JPMS module | Contents |
|---|---|---|
| `llm-transport-bom` | — | version alignment |
| `llm-transport-core` | `dev.llmtransport` | API, SPI, engine, JSON, OAuth flows, SSE/NDJSON, JFR; zero runtime dependencies |
| `llm-transport-openai` | `dev.llmtransport.openai` | Responses and Chat Completions `WireApi`s; `Providers.openai()`; `OpenAiCompatible` presets (OpenRouter, DeepSeek, xAI, Qwen, Mistral, Groq, Ollama, LM Studio, vLLM, LiteLLM, Azure OpenAI, `custom`); compat and options classes; catalogs |
| `llm-transport-anthropic` | `dev.llmtransport.anthropic` | Messages `WireApi`, preset, `AnthropicOptions`, hosted tools, catalog |
| `llm-transport-google` | `dev.llmtransport.google` | generateContent `WireApi`, Gemini preset (Vertex later), options, catalog |
| `llm-transport` | `dev.llmtransport.providers` | `Providers` index, `Providers.presets()`, `ProvidersConfig` |
| `llm-transport-testing` | `dev.llmtransport.testing` | `FakeProvider`, cassette helpers, `ScriptedTransport`, contract kits |
| later | — | `-otel`, `-jackson`, `-kotlin`, `-bedrock`, `-vertex`, keychain `CredentialStore` |

Core packages — five exported instead of thirteen:

```
dev.llmtransport        API  Llm, Model, ModelRef, ModelCatalog, Modality, Capabilities, Prices, Provider, Conversation,
                             Message, UserMessage, AssistantMessage, ToolResultMessage, Content, Tool, ToolCall, ToolResult,
                             ToolChoice, OutputFormat, ChatOptions, ReasoningLevel, CacheRetention, CacheMode,
                             CancelToken, ChatStream, ChatEvent, Usage, Cost, StopReason, Warning, PreparedRequest,
                             ConnectionReport, RefreshReport, ResponseInfo, TimeoutPolicy, RetryPolicy, HttpOptions,
                             FieldDescriptor, LlmException (+ subclasses), ErrorCode, Registration
                        SPI  ResponseCache, WireInterceptor
dev.llmtransport.auth   API  Auth, AuthStatus, AuthType, Credential, ApiKeyCredential, OAuthCredential, AuthPrompt,
                             AuthNotice, OAuthConfig, Secret, Environment        SPI  CredentialStore, AuthInteraction,
                             ApiKeyAuth, OAuthAuth, ResolvedAuth, AuthInput, TokenSupplier
dev.llmtransport.event  API  LlmListener, LlmEvent (+ 6)
dev.llmtransport.json   API  JsonValue family, Json, JsonSchema, Description     SPI  JsonMapper
dev.llmtransport.spi    SPI  WireApi, StreamDecoder, ApiRequest, EncodeContext, DecodeContext, HttpCall, HttpReply, Frame,
                             StreamFormat, ApiCompat, ProviderOptions, ModelSource, ProviderHttp, ProviderBundle,
                             ProviderApi, ProviderApiContext, HttpTransport
dev.llmtransport.internal.*  resolve, engine, auth, oauth, catalog, stream, json, http, jfr, redact
```

The root package is the hub, as in FM; FM's ArchUnit rules (no `util`/`impl`/`manager`, internal not
exported, provider modules never import internals) stay.

### 3.15 Complexity budget

| Measure | FM | v2 |
|---|---|---|
| Glossary nouns | 10 | 8 |
| Types in the Simple example | 5 | 3 |
| Facade method names | 10 on `LlmClient` + 5 on `ChatApi` | 11 on `Llm` |
| Configuration scopes merged per call | 5 + `omit` | 3 + catalog-derived |
| Exported packages | 13 | 5 |
| Types a protocol author implements | 3 (+ 2 optional codecs) | 2 |
| Auth types (public) | ≈ 19 + 6 events | ≈ 18, of which a UI touches 6 (`Auth`, `AuthStatus`, `AuthType`, `AuthInteraction`, `AuthPrompt`, `AuthNotice`) |
| Public cache types | 7 | 3 (`CacheRetention`, `CacheMode`, `ResponseCache`) |
| Listener events | 14 | 7 |
| Features removed | — | none (§5) |
| Runtime dependencies of the core | 0 | 0 |

The auth type count barely moves; what changes is coverage — one protocol expresses every flow pi
supports, where FM's closed prompt set could not — and the removal of a separate token pipeline.

### 3.16 Java 26 and object-oriented design in v2

pi's solutions are TypeScript-shaped (structural object literals, string unions, `AbortSignal`,
`hasApi()` narrowing, async iterables). v2 translates each into the Java idiom instead of copying it.

| pi (TypeScript) | v2 (Java 26, OOD) | Benefit |
|---|---|---|
| String-union event `type` + `switch` on strings | sealed `ChatEvent` with records; `case PartEnd(int i, ToolCall c)` record patterns; exhaustiveness checked by javac | Type-safe handling; adding a variant is visible at compile time in codecs |
| `stream` vs `streamSimple`, typed options via `hasApi()` narrowing | one `complete`/`stream`; `ChatOptions.provider(AnthropicOptions)` — typed classes looked up by class token, read only by their API family | One entry point; IDE completion on provider options; no casts |
| `compat` object literals with ~20 optional flags | immutable `ApiCompat` classes with builders, merged field by field (preset → provider → model) | Documented, validated, overridable per model |
| Provider as an object literal of functions (`createProvider({ … })`) | `Provider` value + strategy objects (`WireApi`, `ApiKeyAuth`, `OAuthAuth`, `ModelSource`) | Strategy pattern: flows swap without flags; presets copied with `toBuilder()` |
| `AbortSignal` in options | `CancelToken` with `child()` (composite), `onCancel` (observer), usable by several calls | Workflow-wide Stop without per-call handles |
| Async `prompt()` returning a promise | blocking `AuthInteraction.prompt()` on a virtual thread | Same protocol for desktop, CLI and web callbacks; no callback pyramids |
| Async iterable + `result()` promise | `ChatStream implements Iterable<ChatEvent>, AutoCloseable` + `result()`; `events()` for `java.util.stream` and gatherers | Plain `for` loops and try-with-resources |
| Mutable `partial` shared by events | immutable `AssistantMessage` snapshots | No aliasing |
| Error as data (`stopReason: "error"`) | unchecked exception hierarchy with `code()`, `retryable()`, `outcomeUnknown()`, `partial()` | Failures cannot be ignored silently; partial output survives |
| TypeBox schemas | records + `@Description` → JSON Schema; `call.arguments(ReadFile.class)`; `complete(…, Invoice.class)` | Schema and binding from one declaration |
| Explicit `telemetryContext` argument | `ScopedValue` call context (request id, tags, deadline) visible to listeners, interceptors and JFR | No parameter threading, no `ThreadLocal` leaks |
| `Models` mutable collection (`setProvider`) | immutable `Llm` built once; `withCredentials(…)` views | Thread-safe sharing; reconfiguration builds a new runtime |

Design patterns in v2 (FM §5.5 updated): facade (`Llm`, `models()`, `auth()`), static factories and
builders with `toBuilder()`, strategy (`WireApi`, `ApiKeyAuth`, `OAuthAuth`, `ModelSource`),
data-driven variants (presets, compat, catalogs), chain of responsibility (`WireInterceptor`), observer
(`LlmListener`, `CancelToken.onCancel`), iterator (`ChatStream`), composite (`CancelToken.child()`),
decorator/view (`withCredentials`), template-free value objects and algebraic data types (sealed
`Message`, `Content`, `ChatEvent`, `Credential`, `AuthPrompt`, `AuthNotice`), typed keys by class
(`ProviderOptions`, `ApiCompat`, `ProviderApi<T>`).

---

## 4. Kept from FM, by reference

| FM section | Status in v2 |
|---|---|
| §2 principles 1, 2, 4, 6–12 | unchanged |
| §3.4 anti-goals | unchanged; "no routing" now reads "no routing *between* providers" — dispatch to the provider named by the model is not routing |
| §4.5–§4.7 tools, output formats, multimodal input | unchanged apart from the new call shape |
| §4.17 debugging, §14.2 | unchanged (`preview` takes model, conversation and options) |
| §6.4–§6.6 JPMS, Gradle conventions, Java 26 | unchanged (module list per §3.14) |
| §7.4 content parts, §7.6 tools, §7.7 output formats and JSON | unchanged except `origin()` → message, `Reasoning.redacted()` |
| §7.9 connection report | unchanged; `test(Model)` |
| §7.12 `TimeoutPolicy`, `RetryPolicy`, `HttpOptions` | unchanged; carried by `ChatOptions` and the builder |
| §7.14 errors | unchanged apart from §3.5 |
| §8.2 `HttpTransport`, `WireInterceptor` | unchanged |
| §8.5 provider APIs | unchanged mechanism; `llm.providerApi(providerId, api)`; experimental until two consumers |
| §11.3 response cache | unchanged semantics; opt-in via `responseCache(…)`; key and exchange types internal |
| §10 execution contracts | unchanged |
| §11.2 determinism rules | unchanged |
| §12.1–§12.3 credential application, flows, security contract | unchanged in substance; applied through `ResolvedAuth`, `OAuthFlows`, `CredentialStore.update` |
| §16.1, §16.4 portable mappings, API revisions | unchanged; `Dialect` read as `WireApi` |
| §17 evolution rules and anti-overengineering list | unchanged; budget per §3.15 |
| §19 verification approach | unchanged; applies to v2 before adoption (§9) |

---

## 5. What moved, and where each feature is now

No FM feature is dropped; elements are replaced by simpler equivalents or kept as opt-in layers.

| FM element | v2 | Feature still available as | Why |
|---|---|---|---|
| Endpoint-bound `LlmClient`, `Endpoint`, `ProviderRegistry` | replaced | `Llm` + `Provider` instances; `Providers.presets()` | F1 |
| `ChatRequest`, `ChatResponse`, `continueWith` | replaced | `Conversation` + `ChatOptions`, `AssistantMessage`, `append(…)` | F2 |
| `withOptions` views, `CallOptions`, `GenerationSettings`, `Param`, `omit` | replaced | `ChatOptions` (tags, listeners, cache mode, timeouts per call or run) | F6 |
| `ChatCall`, `sendAsync` | replaced | `CancelToken`, `completeAsync` | F7 |
| `PromptCache`, `CacheBreakpoint` | simplified | `cacheRetention`, `sessionId`, `cacheBreakpoint()` | F8 |
| `ResponseCache`, `CacheMode`, `CachingOptions`, `CacheKey`, `CachedExchange` | simplified | `ResponseCache` + `CacheMode` (key and exchange internal) | F8 |
| `Credentials` hierarchy, `ApiKey`, `BearerToken`, `SecretResolver`, `Secret` references, `CredentialProvider`, `TokenStore`, `OAuthCredentials`, `OAuthTokens`, `AuthOptions`, `BrowserLauncher`, `LoginSession`, `LoginPrompt`, `LoginOptions`, `AuthApi` | replaced | `Credential`, `CredentialStore` (vaults are stores), `ApiKeyAuth.dynamic`, `withCredentials(…)`, `AuthInteraction`, `Auth` | F9, F10 |
| `OptionKey<T>`, `QuirkKey<T>` | replaced | `ProviderOptions`, `ApiCompat` | F11 |
| `Dialect`, `ChatCodec`, `ModelListCodec`, `AccountCodec` | replaced | `WireApi`, `ModelSource`, provider APIs | F12 |
| `ProviderApi`, `ProviderApiContext`, `WireInterceptor`, `HttpTransport` | kept | unchanged | — |
| `UnsupportedPolicy` | replaced | soft/hard rules + `strict()` | F13 |
| `AccountInfo`, `connection().account()` | moved | a provider API per provider that reports balances | only some providers have it |
| `raw()` | kept, experimental | `llm.providerApi(id, RawApi.KEY)` | wait for a second consumer |
| Per-model parameter `FieldDescriptor`s | derived | `Model.parameters()`, computed from catalog fields | no second source of truth |
| `FakeLlm` | replaced | `FakeProvider` | pi13 |

From pi, deliberately not adopted now: image generation, classifiers, deferred (background) responses,
WebSocket transport, mid-conversation system messages with sections and tool diffs, the stream-frame
encoder (w12). Each would return as its own verb or option when a consumer needs it.

---

## 6. Name mapping (FM → v2)

| FM | v2 |
|---|---|
| `LlmClient` (one endpoint) | `Llm` (many providers) |
| `Endpoint` | `Provider` instance (+ `ModelRef` in calls) |
| `LlmProvider` preset | `Provider` preset (`Providers.anthropic()`) |
| `Dialect`, `DialectId`, `ChatCodec` | `WireApi` |
| `ModelId`, `ModelInfo`, `ModelsApi` | `ModelRef`, `Model`, `ModelCatalog` |
| `chat().send(…)`, `chat().stream(…)`, `chat().preview(…)` | `complete(…)`, `stream(…)`, `preview(…)` |
| `ChatCall`, `sendAsync()` | `CancelToken`, `completeAsync(…)` |
| `ChatRequest` | `Conversation` + `ChatOptions` |
| `ChatResponse`, `ResponseMetadata` | `AssistantMessage`, `AssistantMessage.info()` |
| `GenerationSettings`, `CallOptions`, `withOptions` | `ChatOptions` |
| `Reasoning`, `Effort` | `ReasoningLevel` (+ `AnthropicOptions.thinkingBudget`) |
| `ChatEvent.ToolCallCompleted`, `PartCompleted`, `Finished` | `ChatEvent.PartEnd`, `ChatEvent.Done` |
| `FinishReason` | `StopReason` |
| `Pricing`, `Pricing.estimate` | `Prices`, `Usage.cost()` |
| `OptionKey<T>`, `QuirkKey<T>` | `ProviderOptions`, `ApiCompat` |
| `UnsupportedPolicy` | `ChatOptions.strict()` |
| `TokenStore` | `CredentialStore` |
| `OAuthCredentials` (host-owned, per account) | `llm.withCredentials(store.scoped(account))` |
| `OAuthTokens` | `OAuthCredential` |
| `AuthApi` | `Auth` |
| `LoginSession`, `LoginPrompt`, `LoginOptions`, `BrowserLauncher` | `AuthInteraction`, `AuthPrompt`, `AuthNotice` |
| `Credentials.dynamic(CredentialProvider)` | `ApiKeyAuth.dynamic(name, TokenSupplier)` |
| `Endpoint.toJson()` / `fromJson(…)` | `ProvidersConfig` (no credential fields) |
| `FakeLlm` | `FakeProvider` |
| `caching(c -> c.responses(…).mode(…))`, `withOptions(o -> o.cacheMode(…))` | `responseCache(…)`, `ChatOptions.responseCache(CacheMode)` |
| `llm.providerApi(Gemini.CACHES)` | `llm.providerApi("google", Gemini.CACHES)` |

---

## 7. Roadmap delta (replaces FM §18 slices)

| Slice | Deliverable | Exit evidence |
|---|---|---|
| **0 — API proof** | `Llm`, `Model`, `Provider`, `Conversation`, `AssistantMessage`, `ChatOptions`, `CancelToken`, `FakeProvider`; §3.3 examples in Java and Kotlin | examples compile and run offline; ArchUnit and japicmp baselines |
| **1 — Engine and two dissimilar APIs** | FM §10 engine, SSE, JDK transport; Chat Completions with `OpenAiCompatible` presets and compat flags; Anthropic Messages; catalog generator and cost; hand-off rules; tools; structured output; streaming with partial tool arguments; `preview`/`toCurl`; overflow detector; `test()`; response cache (memory, directory) | `WireApiContract` green for both, including hand-off fixtures (Anthropic ↔ Chat Completions), split chunks, late errors, premature EOF; one live run per API |
| **2 — Auth** | `CredentialStore` (memory, file), environment chain with `source`, `AuthInteraction.console()` and `redirect(…)`, `OAuthAuth.standard` (loopback, external redirect, device, client credentials), OpenRouter OAuth preset, `FakeAuthorizationServer` | concurrent refresh is single-flight across two processes sharing a file store; login cancel and expiry paths |
| **3 — Breadth** | OpenAI Responses, Google generateContent, reasoning continuity per API, prompt-cache mappings, multimodal input, dynamic catalogs (OpenRouter, Ollama), rate limits | four APIs green; conversation round-trips across all pairs |
| **4+ — On demand** | embeddings and images as new verbs over model kinds, provider APIs (files, batches, Gemini caches), Bedrock, Vertex, OpenTelemetry, Jackson, Kotlin coroutines | per operation: side effects, billing, cancellation, ownership documented |

---

## 8. Open decisions and risks

| # | Topic | Recommendation | Why it is open |
|---|---|---|---|
| 1 | Default `cacheRetention` | `SHORT` (pi's choice): both consumers are multi-turn, and reads cost a fraction of input | Single-shot callers pay the cache-write premium on Anthropic for nothing; FM's principle "never send unset" argued for `NONE` |
| 2 | Foreign reasoning in hand-off | Convert non-empty reasoning to plain text (pi's production behaviour), drop signatures and redacted parts | Dropping is cheaper and changes the transcript less; decide with an evaluation on real agent sessions |
| 3 | Lenient default with `strict()` opt-in | Keep lenient: model switching is the norm | Silent-looking adaptations; mitigated by warnings in the reply, events and `preview()` |
| 4 | `Environment.system()` in `Llm.create()` | Keep for desktop and CLI; document `Environment.none()` for servers | Implicit input on servers |
| 5 | Catalog data source and freshness | Generate from public metadata with curated overrides at module release; `UNLISTED` and `refresh()` cover new models | Licence of the source data; weekly model launches vs module releases |
| 6 | `llm.model(String, String)` has adjacent strings (FM finding B1) | Keep: "provider, model" is a universal order and an unknown provider fails at once, naming the known ids; `find(ModelRef)` for persisted references | A swap still compiles |
| 7 | `Usage` buckets | Disjoint (pi) + `totalInput()`, so cost is a dot product | FM specified inclusive `inputTokens()`; pick one before slice 1 |
| 8 | Root package size (~45 types) | Accept, as FM did for its hub; split only along a real seam | Browsing a large package |
| 9 | `completeAsync` on the facade | Keep one method; virtual threads make it a thin wrapper | Could be left to hosts |
| 10 | Consumer-subscription OAuth | No presets (FM §8.7); the `OAuthAuth` SPI allows authorized hosts to add them | Terms of service |

---

## 9. Verification status

This revision is a design argument. Read: pi-ai's README, `types.ts`, `models.ts`, `auth/types.ts`,
provider factories, `api/lazy.ts`, `api/transform-messages.ts`, `api/simple-options.ts`,
`utils/event-stream.ts`, `utils/retry.ts`, `utils/overflow.ts`; pi-telemetry's README; FM in full.
Not done: compiling the v2 sketch or examples (FM's compile evidence, FM §19.3, covers FM's sketch, not
this one), measuring anything, or checking provider facts beyond what pi documents. Before adoption,
repeat FM §19.3 for §3.5–§3.8 and the §3.3 examples, and pin the hand-off table with golden fixtures.
