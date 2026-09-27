# LLM Transport SDK — Final Architecture

*Proposals A and B, critically compared and merged into one design for Java 26 + Gradle*

| | |
|---|---|
| **Status** | Final architecture proposal: the backbone to implement against, not an implementation |
| **Date** | 27 September 2026 |
| **Stack** | Java 26 (toolchain and `--release 26`), Gradle 9 (Kotlin DSL, version catalog, convention plugins) |
| **Inputs** | [`goals.md`](../../goals.md) · **A** = [`architecture-proposal-transportsdk.md`](architecture-proposal-transportsdk.md) · **B** = [`llm-transport-sdk-architecture.md`](llm-transport-sdk-architecture.md) · the four requirement documents both were derived from · the `java-sdk-design` skill and its references |
| **Placeholders** | Root package `dev.llmtransport`, artifact prefix `llm-transport-`, model ids in examples |

Code blocks are compile-shaped API sketches; `…` marks an omitted body. §19.2–§19.3 list what was
compiled and executed while preparing this document and what is still unverified. Tags such as **[A]**, **[B]**
and **[new]** show where a decision came from.

| You are… | Read |
|---|---|
| deciding whether to adopt the design | §0, §1 (comparison), §2, §4 (usage) |
| an application developer | §3, §4, §9 (configuration), §10 (execution contracts), §7.14 (errors) |
| a UI / workflow-builder integrator | §4.10–§4.13, §12 (OAuth), §13 (UI guide) |
| a provider or protocol author | §8 (SPI, provider modules, recipes), §16 (portability rules) |
| planning the implementation | §5–§7, §17–§19 |

---

## 0. The design on one page

An `LlmClient` is a thread-safe, closeable runtime bound to **one endpoint**: a provider, a base URL,
a wire protocol (a *dialect*) and credentials. Callers work with one portable, immutable model —
`ChatRequest`, `ChatResponse`, `ChatEvent`, `Message`, `Content`, `Tool`, `Usage` — and never with
provider wire formats. A provider is **data** (base URL, auth method, quirks, form fields); a wire
protocol is a **pure codec**; an API version is a **dialect revision**. The core owns everything that
must behave identically for every provider: credential application and refresh, deadlines, retries,
SSE/NDJSON framing, stream aggregation, caching, events, JFR and redaction.

```java
try (LlmClient llm = LlmClient.create(Providers.ANTHROPIC, ApiKey.fromEnv())) {   // reads ANTHROPIC_API_KEY
    ChatResponse reply = llm.chat().send(ModelId.of("claude-sonnet-5"), "Explain Java records in two sentences.");
    System.out.println(reply.text());
}
```

Switching to OpenAI, Gemini, DeepSeek, OpenRouter, a corporate gateway or a local Ollama changes the
endpoint, not the calling code (§4.3). Everything else is disclosed progressively from the same entry
point.

**Twelve decisions that shape the SDK**

1. **Sync-first facade bound to one endpoint, one sub-API per noun**: `chat()`, `models()`,
   `connection()`, `auth()` **[B navigation, A endpoint binding]**.
2. **Provider = data, protocol = pure codec, API version = dialect revision**. Provider-only
   operations that need several HTTP exchanges run as typed *provider APIs* through the same core
   execution **[B codecs, A native services]**.
3. **One request survives a provider switch.** Portable fields are mapped by every dialect;
   provider-specific options are typed, namespaced and *scoped* — inert, with a warning, on other
   providers **[new]**.
4. **An explicit, cancellable call handle** — `chat().call(request)` → `send()`, `stream()` or
   `sendAsync()` on a virtual thread, `cancel()` from any thread **[A handle, new async]**.
5. **Per-call operational policy through cheap derived views** — `llm.withOptions(o -> o.timeout(…))` —
   so `ChatRequest` stays pure domain data **[new; replaces B's timeout-in-request]**.
6. **Streaming is a single-use, closeable iterable of sealed events** whose aggregate `response()`
   equals the `send()` result, with bounded accumulation **[B aggregate, A memory bound]**.
7. **Cache management in four explicit layers**: provider prompt caching (`cacheBreakpoint()`,
   `PromptCache`), an opt-in response cache that doubles as test cassettes, a model-catalog cache with
   TTL, and expiry-aware credential caching **[new, built on B's cache hints and A's separation of
   concepts]**.
8. **Output formats from plain text to typed Java records**: `send(request, Invoice.class)`, JSON
   Schema derived from records, typed tool arguments — without third-party dependencies **[new]**.
9. **Safe resilience defaults**: retries only for responses that providers document as *not
   processed*; never after an ambiguous failure or visible stream output; three timeouts that fit
   long reasoning calls — connect, stream idle, total **[B defaults, A outcome rules, new timeout model]**.
10. **OAuth in the core, JDK-only**: `llm.auth().login()` for desktop and CLI hosts, host-owned
    `OAuthCredentials` for multi-user servers, and A's security contract **[B ergonomics, A security]**.
11. **Zero third-party runtime dependencies in core**: JDK `HttpClient` (HTTP/2; HTTP/3 opt-in on
    Java 26), an internal JSON tree with stable key order, `System.Logger`, JFR **[B, new]**.
12. **Testing and debugging are features**: `FakeLlm`, cassette replay, contract kits, `preview()`
    with `toCurl()`, redacted wire logging, JFR events, an effective-configuration view **[A, B, new]**.

**Deliberately absent** (anti-goals, §3.4): agent loops and tool execution, routing and fallback
between providers, semantic caching, conversation stores, prompt templating, a proxy server, global
state, and any third-party type on a public signature.

---

## 1. Critical comparison of the two proposals

### 1.1 Method

Both documents were read in full and assessed against the request in `goals.md`, the `java-sdk-design`
review checklist, and the Java 26 platform (checked on a local Temurin 26.0.2.1 JDK, §19.2). Neither
proposal was used as a base to patch: each element of the final design was chosen by argument, and
§1.5 records its origin. Findings use the skill's severity scale: **breaks callers**,
**misuse-prone**, **friction**, **cosmetic**.

### 1.2 Overall assessment

**Proposal A** is the stronger *analysis*. It qualifies every requirement source, resolves their
disagreements with reasons, and specifies the contracts that are hardest to retrofit: submission
certainty after failures, credential/destination binding, OAuth security for native and web hosts,
honest metadata, one-shot call handles, and ownership on every resource. Its weaknesses are
ergonomic. Its main paths carry many concepts (`ProviderSession`, `InferenceAdapter`,
`ExecutionContext`, `ProviderContext`, `ResolvedRequest`, `RequestAssessment`, `ServiceKey`,
`Fact<T>`, `ModelQuery`, `ModelPage`), it gives few end-to-end examples, and it leaves concrete
surfaces — events, provider options, testing fakes — to be designed later. Some of its defaults hurt
out-of-the-box robustness.

**Proposal B** is the stronger *surface*. It is usage-first and concrete: sub-APIs per noun, provider
as data, pure codecs, a complete API sketch, defaults and footgun tables, a testing artifact with
contract kits, a zero-dependency core and a Gradle build. Its weaknesses are a handful of contract
defects that surface in production: a model/prompt parameter swap that compiles, timeout defaults that
break long non-streaming calls, no cancellation handle for `send()`, login prompts broadcast to every
listener, and OAuth tokens keyed only by endpoint id.

**Verdict.** B supplies the better skeleton and A the better contracts. The final design uses B's
surface wherever the two agree, A's semantics wherever B's would cause a defect, and new work where
both leave a requirement of `goals.md` or of this request uncovered — cache management, typed output
formats, portability of provider options, debugging, and Java 26.

### 1.3 Scorecard

| # | Criterion | A | B | Final design |
|---|---|---|---|---|
| 1 | Requirements analysis | Per-source qualification; reasoned decisions on every disagreement | Consensus and divergence tables; explicit cut list | A's rigor for contracts, B's cut list for scope |
| 2 | Layer-1 ergonomics | Builder with provider, key and model as chain steps, then `generate(String)` | `OpenAi.client(key)` + `chat().send(model, text)` | B's brevity with a typed `ModelId` (fixes B1) |
| 3 | Navigation | Facade verbs (`generate`, `stream`, `newCall`, `inspect`) plus `connection()`, `models()`, `service()` | Sub-API per noun: `chat()`, `models()`, `auth()`, `raw()` | B's shape; A's `newCall` becomes `chat().call()` |
| 4 | Provider abstraction | Adapter performs exchanges through an `ExecutionContext` | Provider = data; dialect = pure codec; core executes | B for request/response operations; A's context model only for multi-step provider APIs |
| 5 | API versions | New or revised adapter; never repurpose a factory | New `Dialect` constant per revision | Same intent; B's mechanism |
| 6 | Configuration | Precise precedence, omission, header rules, enforced limits, request defaults | Scope tree, defaults and footgun tables, consumer-builders | B's shape with A's semantics (defaults, omission, non-escalation) |
| 7 | Streaming | Events plus non-blocking `summary()`; no hidden accumulation | Sealed events; `response()` equals `send()`; `partial()` | B's aggregate, bounded per A |
| 8 | Cancellation & async | One-shot `GenerationCall`, cancellable before and during | `stream.close()`; `send()` only via thread interrupt; async deferred | A's handle, plus `sendAsync()` on it |
| 9 | Auth & OAuth | Credential sources outlive clients; deep security for native, web and device flows | `client.auth().login()`; sealed prompts; token stores; browser hook | B's ergonomics, A's security model and multi-user path |
| 10 | UI & workflow | Parameter descriptors, staged check report, workflow mapping | Config-field forms, string routing, `prepare()`, UI guide | One `FieldDescriptor` for both forms; staged report; `preview()` |
| 11 | Model metadata | `Fact<T>` with knowledge state and provenance per field; pagination | Tri-state support; `source()` and `fetchedAt()`; bundled catalog | B's model plus A's freshness, as a catalog cache with TTL |
| 12 | Errors | Categories plus `FailureDetails` with submission certainty; refusal is a result | Stable codes, `retryable`, `outcomeUnknown`, rich fields | B's model; A's refusal-as-result |
| 13 | Retry defaults | None for generation | Three attempts on documented-retryable responses; never ambiguous ones | B's defaults minus `504`; A's ambiguity rule |
| 14 | Timeouts | Connect 10 s; total 5 min including streaming; idle off | Connect 10 s; first byte 120 s; idle 60 s; total 10 min | Neither: connect / stream idle / total (§10.2) |
| 15 | Caching | Concepts separated; response cache off; cache resources later | Cache hints; stable key order; cache usage fields | New four-layer design built on both (§11) |
| 16 | Tools & output formats | Tools transported; schema helpers "above the transport" | Tool types; JSON Schema format; Jackson module | B's types plus record-typed outputs and tool arguments in core |
| 17 | Testing & debugging | Scripted provider in test sources; strong evidence plan | Testing artifact, fakes, contract kits, named tests, `prepare()` | B's artifact, A's evidence plan, new debugging tools |
| 18 | Dependencies | JDK HTTP plus one internal JSON library | JDK only; internal JSON with stable key order | B |
| 19 | Structure & build | Three artifacts; Maven; Java 21 | Per-family artifacts, testing, BOM; Gradle; Java 17 | B's layout plus an aggregate artifact; Gradle; Java 26 |
| 20 | Concept count | High on main paths | Moderate (glossary of nine) | B's budget; A's rules where they prevent defects |
| 21 | Document usability | Contract-first, few end-to-end examples | Usage-first, many examples | B's structure |

### 1.4 Concrete findings

**Proposal A**

| ID | Severity | Finding | Consequence | Resolution |
|---|---|---|---|---|
| A1 | misuse-prone | Required inputs (provider, credentials, model) are optional chain steps on `LlmClient.builder()` | Forgetting one compiles and fails later | `LlmClient.create(provider, credentials)` / `Endpoint.of(…)`; model in request or endpoint |
| A2 | friction | No automatic retry for generation, even for `429`, `503` and `529`, which providers document as rejected before processing | Ordinary load spikes become failures in every application | Retry documented not-processed responses by default (§10.3) |
| A3 | breaks callers | Default total deadline of 5 minutes includes stream consumption | Long reasoning or large outputs fail by default | Total 10 minutes plus a separate stream-idle limit |
| A4 | friction | Streams expose only `summary()`; the aggregate needs an opt-in collector | Every UI writes an accumulator to append the reply to history | Bounded aggregate `response()` |
| A5 | overengineering risk | About twenty concepts on the main caller and provider paths | High learning cost; many one-implementation seams | Pure codecs plus two small contexts; glossary of ten |
| A6 | misuse-prone (extenders) | Adapters drive HTTP exchanges themselves | Each adapter can get deadlines, cancellation or errors subtly wrong | Core owns I/O for request/response operations |
| A7 | friction | No concrete event types, option keys or consumer fakes | Implementers must design them later, inconsistently | Specified in §7, §8 and §14 |
| A8 | friction | "One established JSON library" used internally | Version conflicts in IDE-plugin and application classpaths unless shaded | Internal JSON tree, no dependency |
| A9 | stack | Maven build; Java 21 baseline | Superseded by the chosen stack | Gradle; Java 26 |
| A10 | cosmetic | `inspect()` returns an assessment separate from any wire preview | Two concepts for "what would happen" | One `preview()` returning problems and the exact wire request |

**Proposal B**

| ID | Severity | Finding | Consequence | Resolution |
|---|---|---|---|---|
| B1 | misuse-prone | `send(String model, String userText)` has adjacent `String` parameters | Swapping them compiles and sends the prompt as a model id | `send(ModelId, String)` |
| B2 | breaks callers | The 120 s first-byte timeout also applies to `send()`; non-streaming responses deliver headers only after generation. The 60 s idle limit can abort streams during silent reasoning | Long reasoning calls time out by default | Connect / stream-idle (streams only, 5 min) / total (10 min) |
| B3 | misuse-prone | `ChatRequest.timeout(Duration)` puts operational policy in a domain value hosts persist and reuse | Stored requests carry stale timeouts | Per-call policy via `withOptions(…)` views |
| B4 | friction | `send()` is cancellable only by interrupting the calling thread | Awkward with executors and UI "Stop" buttons | `ChatCall.cancel()` |
| B5 | security | `AuthEvent.PromptIssued` delivers login URLs and device codes to every listener, telemetry included | Interaction secrets can reach logs | The event carries no URL or code; only the `LoginSession` exposes the prompt |
| B6 | misuse-prone | OAuth tokens are keyed by `Endpoint.id()` only | Multi-user servers need an endpoint per user; a changed issuer or client reuses stale tokens | Key = issuer + client id + account; host-owned `OAuthCredentials` |
| B7 | friction | Endpoint persistence and secret references are left to each host | Every host writes and tests its own mapping | Canonical endpoint JSON with secret references |
| B8 | semantics | `ContentFilteredException` models an output refusal as a failure | Partial output and usage are hidden in an exception | Refusal is a structured result (`FinishReason.CONTENT_FILTER`, `refusal()`) |
| B9 | friction | `models().list()` has no freshness or caching control | Every UI caches the catalog itself | Catalog cache with TTL and explicit `refresh()` |
| B10 | evolution | Every event is a record | Adding a component breaks constructors and record patterns | Deltas are frozen records; lifecycle events are final classes |
| B11 | misuse-prone | `504` is retried by default | The upstream may already have generated and billed | Excluded by default; reported as `outcomeUnknown` |
| B12 | friction | `Credentials.supplied` results are never cached | Cloud token suppliers run on every attempt | `Credentials.dynamic` with expiry-aware, single-flight caching |
| B13 | stack | Java 17 baseline; "no `synchronized` across I/O" rule | The rule is obsolete since JEP 491 (JDK 24) | Java 26; lock rules restated in §10.6 |

**Both proposals**

| ID | Gap | Resolution |
|---|---|---|
| N1 | No cache management beyond hints: no response cache, catalog TTL or explicit cache resources | Four-layer design (§11) |
| N2 | No typed structured output or typed tool arguments in the core | Record-based binding and schema derivation (§7.7) |
| N3 | No rule for provider-specific options when the endpoint changes | Option scoping (§16.2) |
| N4 | Debugging limited to events | `preview()`, `toCurl()`, wire log, JFR, `describe()` (§14.2) |
| N5 | Nothing compiled; examples unverified | API skeleton and examples compiled on JDK 26 (§19.3) |

### 1.5 Origin of the final design

| Final element | From | Why |
|---|---|---|
| Endpoint-bound `LlmClient`, one client per endpoint, no router facade | A + B | Billing and identity stay explicit; hosts compose clients |
| Sub-API per noun (`chat()`, `models()`, `connection()`, `auth()`) | B | Scales to new operations; IDE-discoverable |
| `ChatCall` handle (`send`, `stream`, `sendAsync`, `cancel`) | A + new | UI cancellation without thread interrupts; async without a second facade |
| `withOptions(…)` derived views | new | Keeps requests pure; per-run tags, listeners and cache mode |
| Provider = data, `Dialect` = pure codec, revisions as constants | B | Adding a compatible provider is zero code; codecs are testable pure functions |
| Typed provider APIs through core execution (`providerApi(…)`) | A | Multi-step provider-only operations without an untyped escape hatch |
| `OptionKey<T>` / `QuirkKey<T>` | B | Typed, namespaced provider extras and traits |
| Option scoping (foreign options inert, with a warning) | new | One request works across providers |
| `GenerationSettings` with client defaults and `omit(Param)` | A | Configure once; suppress an inherited value explicitly |
| Sealed `ChatEvent`, aggregate `response()`, `partial()` | B | Most common UI need in one call |
| Bounded stream accumulation | A | No unbounded hidden copies |
| Refusal as a structured result | A | Output-side policy outcomes are data, not failures |
| Error model with stable codes, `retryable`, `outcomeUnknown` | B | Callers branch on codes, not messages |
| Retry defaults for not-processed responses; no retry after ambiguous failure | B + A | Robust out of the box without double billing |
| Connect / idle / total timeout model | new | Fixes A3 and B2 |
| Four-layer caching | new (B hints, A concepts) | Explicit cost control and test determinism |
| Record-typed outputs and tool arguments, JSON Schema derivation | new | Least code for structured output; no Jackson required |
| OAuth engine in core; `auth().login()`; sealed prompts; `BrowserLauncher` | B | Desktop/CLI login in a few lines |
| Host-owned `OAuthCredentials`; identity-bound token keys; security contract | A | Multi-user servers; RFC 8252 / 9700 / 8628 practices |
| Staged `ConnectionReport` | A + B | "Reachable ≠ authenticated ≠ usable", rendered per step |
| One `FieldDescriptor` for connection and parameter forms | A + B | One form renderer for workflow builders |
| Canonical endpoint JSON with secret references | new (A's sketch) | Save and load connections without host mapping code |
| Internal JSON with stable key order; zero runtime dependencies | B | No classpath conflicts; byte-stable prompt prefixes |
| `preview()` + `toCurl()`, wire log, JFR, `describe()` | A + B + new | Diagnose without a debugger or a proxy |
| `FakeLlm`, cassettes, contract kits, `TestClock` | B + new | Tests without mocks, keys or network |
| Gradle multi-project with convention plugins; Java 26 | B + new | Chosen stack |

---

## 2. Design principles

1. **Common path first.** Layer 1 is a factory and one call with defaults. Layer 2 configures the same
   entry point. Layer 3 is a documented door one level down, never an internal class.
2. **Hide wiring, expose consequence.** Callers never assemble codecs, framers, retry loops or token
   refreshers. They do see and decide anything that costs money, opens a browser, retries, caches,
   drops an option or owns a resource.
3. **Dialect is code, provider is data, version is a revision.** Adding DeepSeek is a preset; adding a
   wire format is a codec; adopting a new API revision is a new dialect constant.
4. **Honest unknowns.** Unknown capability, price, usage or limit is absent or `UNKNOWN`, never `false`
   or `0`. Unknown content parts, events and enum values are preserved, never fatal.
5. **Never send what the caller did not set; never change meaning silently.** No invented sampling
   defaults. An inexpressible setting fails the request unless the caller chose best-effort, and every
   adaptation is reported.
6. **Sync-first.** Blocking calls return or throw; streams are closeable, single-use and bounded; async
   is a view on the call handle. Clients are shared across threads, including virtual threads.
7. **Immutable values, thread-safe clients, explicit ownership.** Every public type states which of the
   three it is; every resource has one owner.
8. **Secrets are types; references persist, values never.** Keys and tokens redact everywhere;
   configuration stores `env:`, `file:` or host-defined references.
9. **I/O only in verbs.** `build()`, accessors, `preview()`, `describe()` and rendering never touch the
   network: `send`, `stream`, `test`, `list`, `refresh`, `login` do.
10. **Zero-dependency core.** JDK only, plus compile-time annotations. Heavy or opinionated integrations
    live in optional modules.
11. **Testability and debuggability are product features**, shipped with the library.
12. **Additive evolution under a complexity budget.** Every public type traces to a use case (§17.3).

---

## 3. Consumer model

### 3.1 Definition

> **A Java library that connects to any LLM provider or gateway — authenticating (OAuth included),
> discovering models, and sending chat requests with tools, structured output and streaming —
> through one portable, typed API.**

### 3.2 Consumers

| Consumer | Needs | Primary surface |
|---|---|---|
| AI coding agent (CLI, IDE, desktop) | Several endpoints, streaming with tool calls, reasoning continuity, cancel, usage, prompt caching, OAuth from a terminal | `chat()`, `ChatStream`, `continueWith`, `auth()` |
| Visual node-based workflow builder | Provider list, generated connection forms, test connection, model picker, parameter forms, run with progress, secret-free save/load | `ProviderRegistry`, `FieldDescriptor`, `connection()`, `models()`, events, `Endpoint` JSON |
| Library author building on top | Stable model types, fakes, events | model types, `llm-transport-testing` |
| Provider or protocol author | Small SPI, contract kit, fixtures | `Dialect`, `ChatCodec`, `StreamDecoder`, `DialectContract` |

Runtime: JVM 26+, desktop and server, module path or classpath. Browser frontends call a backend that
uses the SDK; the SDK does not run in a browser.

### 3.3 Glossary — ten nouns

| Concept | One line | Public type |
|---|---|---|
| **Client** | The configured, shareable runtime for one endpoint | `LlmClient` |
| **Endpoint** | Where and how to reach a provider: provider, base URL, dialect, credentials, headers, default model | `Endpoint` |
| **Provider** | A backend described as data: id, default URL, dialects, auth methods, form fields, quirks | `LlmProvider` |
| **Dialect** | A wire protocol and revision, implemented as a pure codec | `Dialect` (SPI; constants such as `OpenAi.RESPONSES`) |
| **Request / Response** | An immutable chat call and its typed result; a stream of events | `ChatRequest`, `ChatResponse`, `ChatStream`, `ChatEvent` |
| **Message / Content** | One turn and its ordered parts | `Message`, `Content` |
| **Tool** | A function the model may call; the call; the result | `Tool`, `ToolCall`, `ToolResult` |
| **Model** | An opaque model id and its discovered metadata | `ModelId`, `ModelInfo` |
| **Credentials** | What is presented to the endpoint: key, token, OAuth, dynamic | `Credentials` |
| **Event** | Something the client observed, for UIs and metrics | `LlmEvent`, `LlmListener` |

Everything else (`Usage`, `GenerationSettings`, `TimeoutPolicy`, `PromptCache`, `JsonValue`, …) is
reached from these nouns by IDE completion. An application developer's Simple path needs five of them.

### 3.4 Anti-goals (README section)

Not an agent framework: no tool execution, loops, planning or memory. Not a prompt-template engine. Not
a conversation store. Not a retrieval or vector library. Not a UI or CLI. Not a proxy or gateway server.
No routing, fallback, key pools or client-side rate limiting between providers. No semantic caching. No
cost accounting beyond `Usage`, `Pricing` and estimates. No hidden provider or model switching. No global
mutable state. No reading of environment variables except through explicitly named methods.

### 3.5 Progressive disclosure

| Layer | Who | Surface |
|---|---|---|
| 1 — defaults | most callers | `LlmClient.create(Providers.OPENAI, ApiKey.fromEnv())` → `llm.chat().send(model, text)` |
| 2 — configuration | configured callers | `Endpoint.builder(…)`, `LlmClient.builder(endpoint).timeouts(…).retry(…).defaults(…).caching(…)`, `ChatRequest.builder()…`, `withOptions(…)` |
| 3 — escape hatches | advanced callers | typed provider options (`AnthropicOptions.THINKING_BUDGET`), `providerApi(Gemini.CACHES)`, `response.raw()`, `llm.raw()` (experimental), custom `Dialect` |

---

## 4. Usage first

These examples are the README and are compiled as tests in the `examples` module (§19). Java 26 syntax
is used throughout; model ids are illustrative.

### 4.1 Simple — the 80 % case

```java
try (LlmClient llm = LlmClient.create(Providers.ANTHROPIC, ApiKey.fromEnv())) {  // reads ANTHROPIC_API_KEY now; fails fast, naming it
    ChatResponse reply = llm.chat().send(ModelId.of("claude-sonnet-5"), "Explain Java records in two sentences.");
    System.out.println(reply.text());
    System.out.println(reply.usage());          // Usage[input=21, output=64, cacheRead=absent, cacheWrite=absent, reasoning=absent]
}
```

Nothing to wire: the provider's default base URL and dialect, the JDK HTTP transport, default timeouts
and retries, no listeners, no caching. Forgetting the key is impossible (`fromEnv()` fails with the
variable name); swapping model and prompt does not compile (`ModelId`).

### 4.2 Configurable — a superset of Simple, same entry point

```java
Endpoint endpoint = Endpoint.builder(Providers.ANTHROPIC)
        .id("work-anthropic")                                     // used in events, logs and token-store keys
        .baseUrl(URI.create("https://llm-gw.corp.example/anthropic"))
        .credentials(ApiKey.from(Secret.ref("vault:team-42/anthropic")))
        .header("X-Tenant", "team-42")
        .defaultModel("claude-sonnet-5")
        .build();

try (LlmClient llm = LlmClient.builder(endpoint)
        .timeouts(t -> t.connect(Duration.ofSeconds(5)).total(Duration.ofMinutes(20)))
        .retry(r -> r.maxAttempts(4))
        .defaults(d -> d.maxOutputTokens(2_048).temperature(0.2))  // applied to every request unless overridden
        .auth(a -> a.secretResolver(vault::find))                   // resolves "vault:" references
        .listener(metrics::record)
        .build()) {

    ChatRequest request = ChatRequest.builder()                     // no model: the endpoint default applies
            .system("You are a terse senior Java reviewer.")
            .user("Review this diff:\n" + diff)
            .reasoning(Reasoning.effort(Effort.MEDIUM))
            .build();

    ChatResponse review = llm.chat().send(request);
}
```

### 4.3 Switch provider, protocol or API version without rewriting code

```java
ChatRequest request = ChatRequest.of("Summarise these release notes:\n" + notes);   // no model inside

List<Endpoint> endpoints = List.of(
        Endpoint.of(Providers.OPENAI, ApiKey.fromEnv()).withDefaultModel("gpt-5.1"),
        Endpoint.of(Providers.ANTHROPIC, ApiKey.fromEnv()).withDefaultModel("claude-sonnet-5"),
        Endpoint.of(Providers.GEMINI, ApiKey.fromEnv()).withDefaultModel("gemini-2.5-pro"),
        Endpoint.of(Providers.DEEPSEEK, ApiKey.fromEnv()).withDefaultModel("deepseek-chat"),
        Endpoint.of(Providers.OLLAMA).withDefaultModel("qwen3:8b"));             // local: no credentials

for (Endpoint endpoint : endpoints) {
    try (LlmClient llm = LlmClient.create(endpoint)) {
        System.out.println(endpoint.id() + ": " + llm.chat().send(request).text());
    }
}
```

Same provider, different protocol; pinned protocol revision; endpoints from configuration:

```java
Endpoint viaChatCompletions = Endpoint.builder(Providers.OPENAI)
        .dialect(OpenAi.CHAT_COMPLETIONS)        // OpenAI's default dialect is OpenAi.RESPONSES
        .credentials(ApiKey.fromEnv())
        .build();

// A dialect constant names one tested protocol revision. An incompatible revision becomes a new constant
// (for example OpenAi.RESPONSES_V2); a preset changes its default dialect only in a minor release, with a changelog entry.

Endpoint fromConfig = Endpoint.fromJson(Files.readString(configFile), Providers.registry());
```

Provider-specific tuning that survives a switch:

```java
ChatRequest tuned = ChatRequest.builder()
        .user(question)
        .reasoning(Reasoning.effort(Effort.HIGH))              // portable: every dialect maps it (§16.1)
        .option(AnthropicOptions.THINKING_BUDGET, 12_000)     // takes effect on Anthropic endpoints only
        .option(OpenAiOptions.SERVICE_TIER, "flex")           // takes effect on OpenAI endpoints only
        .build();
// On any other endpoint the foreign options are inert and reported as Warning("option_not_applicable").
```

### 4.4 Streaming

```java
try (ChatStream stream = llm.chat().stream(request)) {             // AutoCloseable: close() cancels
    for (ChatEvent event : stream) {                                // blocks per event on the calling thread
        switch (event) {
            case ChatEvent.TextDelta d         -> ui.appendText(d.text());
            case ChatEvent.ReasoningDelta d    -> ui.appendThinking(d.text());
            case ChatEvent.ToolCallCompleted c -> ui.showToolCall(c.call());
            default                            -> { }               // Started, UsageReported, Finished, Unknown, future variants
        }
    }
    ChatResponse full = stream.response();                          // equals what send() would have returned
}
```

The one-line form for command-line tools:

```java
try (ChatStream stream = llm.chat().stream("Write a haiku about Gradle.")) {
    stream.textDeltas().forEach(System.out::print);
    System.out.println();
    System.out.println(stream.response().usage());
}
```

A "Stop" button calls `stream.close()` from any thread; the iterating thread receives
`RequestCancelledException`, and text already delivered stays valid (`stream.partial()`).

### 4.5 Tools — the SDK transports, the host executes

```java
record ReadFile(@Description("Workspace-relative path") String path) {}

Tool readFile = Tool.function("read_file", "Read a UTF-8 text file from the workspace", ReadFile.class);

ChatRequest request = ChatRequest.builder()
        .system("You are a coding assistant. Use tools to inspect the workspace.")
        .tool(readFile)
        .user("What does build.gradle.kts configure?")
        .build();

ChatResponse response = llm.chat().send(request);
for (int turn = 0; response.hasToolCalls() && turn < 8; turn++) {       // the host owns the loop and its budget
    List<ToolResult> results = response.toolCalls().stream()
            .map(call -> ToolResult.of(call, workspace.read(call.arguments(ReadFile.class).path())))
            .toList();
    request = request.continueWith(response, results);                 // appends the assistant turn (reasoning
    response = llm.chat().send(request);                                //  signatures included) and the results
}
System.out.println(response.text());
```

`Tool.function(…, ReadFile.class)` derives the JSON Schema from the record; `call.arguments(ReadFile.class)`
binds the arguments back. Hosted tools come from provider modules (`OpenAiTools.webSearch()`,
`AnthropicTools.codeExecution()`); tool choice uses `ToolChoice.auto()`, `none()`, `required()` or
`only("read_file")`.

### 4.6 Structured output and output formats

```java
record LineItem(String description, int quantity, BigDecimal unitPrice) {}
record Invoice(String number, LocalDate issued, List<LineItem> items, BigDecimal total) {}

Invoice invoice = llm.chat().send(ChatRequest.of("Extract the invoice:\n" + pdfText), Invoice.class);
```

The typed overload sets `OutputFormat.of(Invoice.class)` (strict JSON Schema where the dialect supports
it), sends, and binds the result. A truncated, refused or non-conforming answer throws
`InvalidResponseException` with code `output_truncated`, `output_refused` or `output_invalid`; the
response stays available through `e.response()`.

| Output format | Request | Read the result with |
|---|---|---|
| Plain text (default) | nothing, or `.output(OutputFormat.text())` | `response.text()` |
| JSON | `.output(OutputFormat.json())` | `response.json()` → `JsonValue` |
| JSON Schema | `.output(OutputFormat.jsonSchema(schema))` | `response.json()` or `response.parse(type)` |
| Java record | `.output(Invoice.class)` or `send(request, Invoice.class)` | `response.parse(Invoice.class)` |
| Streamed text | `stream(…)` | `ChatEvent.TextDelta`, `stream.textDeltas()` |
| Media output (image, audio) | provider options, then portable settings when two dialects agree | `ChatEvent.PartCompleted`, `message().content()` |
| Native payload | — | `response.raw()` (non-streamed), `Content.Unknown`, `ChatEvent.Unknown` |
| Persisted form | — | `Json.valueOf(response)` (canonical, versioned JSON) |

### 4.7 Multimodal input

```java
ChatResponse answer = llm.chat().send(ChatRequest.builder()
        .user(Content.text("Why does this build fail? The spec is attached."),
              Content.image(Path.of("build-error.png")),        // read when the request is encoded, not now
              Content.document(Path.of("spec.pdf")))
        .build());
```

Paths are read at encode time, URLs are passed to providers that accept them (fetched by the SDK only if
the dialect requires inline data and the call opted in), and provider file ids stay scoped to their
endpoint (`Content.fileRef(…)`).

### 4.8 Prompt caching

```java
ChatRequest request = ChatRequest.builder()
        .system(projectInstructions)                      // large and stable
        .tools(workspaceTools)
        .cacheBreakpoint()                                // cache prefix 1: tools + system
        .messages(history)
        .cacheBreakpoint()                                // cache prefix 2: the conversation so far
        .user(nextQuestion)
        .promptCache(PromptCache.key("session-" + sessionId).retention(Duration.ofHours(1)))
        .build();

Usage usage = llm.chat().send(request).usage();           // cacheReadTokens() / cacheWriteTokens() show the effect
```

`PromptCache.auto()` places breakpoints automatically (end of tools and system, end of history before the
last user turn). Dialects with explicit cache control map breakpoints to it; dialects that cache
automatically treat them as satisfied hints (§11.2). Opt in per request or once through
`defaults(d -> d.promptCache(PromptCache.auto()))`.

### 4.9 Cancellation, async and per-call policy

```java
ChatCall call = llm.chat().call(request);                  // no I/O yet
ui.onStop(call::cancel);                                    // thread-safe; before or during the call
call.sendAsync()                                            // runs on a virtual thread
    .thenAccept(ui::showResult);                            // future.cancel(true) also cancels the call
```

```java
LlmClient quick = llm.withOptions(o -> o.timeout(Duration.ofSeconds(20)).retry(RetryPolicy.none()));
quick.chat().send("Classify this ticket: " + ticket);      // shares pool, auth and caches; close() is a no-op

LlmClient run = llm.withOptions(o -> o.tag("workflowRun", runId)   // copied onto every event of this view
        .listener(runPanel::onEvent)
        .cacheMode(CacheMode.REFRESH));
```

### 4.10 Configuration-driven endpoints and generated forms

```java
ProviderRegistry registry = Providers.registry().with(corporateGateway);   // bundled presets + host-defined ones

for (LlmProvider provider : registry.all()) {
    form.addProvider(provider.id(), provider.displayName(), provider.apiKeyUrl());
    provider.connectionFields().forEach(field -> form.addField(provider.id(), field));  // key, label, kind, default, help
}

// Submitted values arrive as strings; build() reports every invalid field at once.
Endpoint.Builder builder = Endpoint.builder(registry.require(form.providerId())).id(form.connectionName());
form.values().forEach(builder::set);                        // "baseUrl", "defaultModel", "openai.organization", …
builder.credentials(ApiKey.from(Secret.ref(form.secretReference())));   // for example "keychain:work-openai"
Endpoint endpoint = builder.build();

String saved = endpoint.toJson();                            // secret-free: references only; literal secrets are refused
Endpoint restored = Endpoint.fromJson(saved, registry);
```

```json
{
  "schema": "llm-transport.endpoint/1",
  "id": "work-anthropic",
  "provider": "anthropic",
  "baseUrl": "https://llm-gw.corp.example/anthropic",
  "credentials": { "type": "apiKey", "secret": "vault:team-42/anthropic" },
  "headers": { "X-Tenant": "team-42" },
  "defaultModel": "claude-sonnet-5",
  "options": { "anthropic.beta": ["interleaved-thinking-2025-05-14"] }
}
```

### 4.11 OAuth

**Desktop or CLI host — loopback redirect:**

```java
Endpoint endpoint = Endpoint.builder(Providers.OPENROUTER).credentials(Credentials.oauth()).build();

try (LlmClient llm = LlmClient.builder(endpoint)
        .auth(a -> a.tokenStore(TokenStore.file(Path.of(home, ".my-agent", "tokens.json")))
                    .browserLauncher(BrowserLauncher.system()))
        .build()) {

    if (llm.auth().status().state() == AuthStatus.State.LOGIN_REQUIRED) {
        try (LoginSession login = llm.auth().login()) {               // PKCE S256 + listener on 127.0.0.1:<ephemeral>
            switch (login.prompt()) {
                case LoginPrompt.OpenBrowser p when p.launched() -> ui.status("Continue in your browser…");
                case LoginPrompt.OpenBrowser p                   -> ui.showLink(p.url());
                case LoginPrompt.EnterCode p                     -> ui.showCode(p.verificationUri(), p.userCode());
                case LoginPrompt.NoInteraction _                 -> { }
            }
            login.await(Duration.ofMinutes(5));                        // tokens saved to the TokenStore
        }
    }
    llm.chat().send(ModelId.of("openrouter/auto"), "hello");           // credentials applied; renewal is automatic
}
```

**Web backend — many users, the host owns the redirect:**

```java
OAuthCredentials alice = OAuthCredentials.builder(Providers.OPENROUTER)       // provider's OAuth defaults
        .account("user-8412")                                                 // part of the token-store key
        .tokenStore(encryptedDatabaseStore)
        .build();

LoginSession login = alice.login(LoginOptions.externalRedirect(URI.create("https://app.example/oauth/callback")));
pending.put(login.state(), new PendingLogin(currentUser(), login));          // bind to the initiating application user
return redirectTo(((LoginPrompt.OpenBrowser) login.prompt()).url());

// GET /oauth/callback
PendingLogin p = pending.remove(request.queryParam("state"));
p.requireUser(currentUser());                                                // the host enforces its own access control
p.login().acceptRedirect(request.fullUri());                                 // validates state and PKCE, exchanges, stores
p.login().close();

LlmClient aliceClient = LlmClient.create(Endpoint.of(Providers.OPENROUTER, alice));   // borrows alice's credentials
```

**Headless device flow:**

```java
try (LoginSession login = llm.auth().login(LoginOptions.deviceCode())) {
    if (login.prompt() instanceof LoginPrompt.EnterCode code) {
        terminal.println("Open " + code.verificationUri() + " and enter " + code.userCode());
    }
    login.await();                                                             // polls per the server's interval
}
```

### 4.12 Discovery for a workflow-builder UI

```java
ConnectionReport report = llm.connection().test();          // non-billable: configuration → network → auth → model access
report.steps().forEach(step -> ui.showStep(step.kind(), step.status(), step.latency(), step.message()));

for (ModelInfo m : llm.models().list()) {                    // one network call, then cached (TTL, default 1 h)
    ui.addModel(m.id(), m.displayName().orElse(m.id().value()),
            m.capabilities().support(Capability.TOOLS),      // SUPPORTED / UNSUPPORTED / UNKNOWN
            m.contextWindow(),                               // OptionalLong: absent means "not reported"
            m.pricing().flatMap(Pricing::inputPerMillion));
}

List<FieldDescriptor> parameterForm = llm.models().get(ModelId.of("claude-sonnet-5")).parameters();
```

### 4.13 Events for progress and status

```java
Registration registration = llm.addListener(event -> {
    switch (event) {
        case RequestEvent.Started s     -> ui.progress(s.requestId(), "sending to " + s.model());
        case RequestEvent.FirstOutput f -> ui.progress(f.requestId(), "streaming after " + f.latency().toMillis() + " ms");
        case RequestEvent.Retrying r    -> ui.progress(r.requestId(), "retry " + r.attempt() + " in " + r.delay());
        case RequestEvent.Completed c   -> ui.done(c.requestId(), c.usage(), c.fromCache());
        case RequestEvent.Failed f      -> ui.failed(f.requestId(), f.error().code());
        case AuthEvent.RefreshFailed a  -> ui.status("Sign-in expired for " + a.endpointId());
        default -> { }
    }
});
registration.close();                                          // or released automatically by llm.close()
```

Listeners observe; they cannot alter a call, and an exception thrown by one is logged and isolated.
Events carry ids, timings, usage and errors — never prompts, outputs or secrets.

### 4.14 Failure

```java
try {
    ChatResponse response = llm.chat().send(request);
} catch (RateLimitedException e) {                              // retries already exhausted
    scheduleLater(e.retryAfter().orElse(Duration.ofSeconds(10)));
} catch (AuthenticationException e) {
    if (e.is(ErrorCode.LOGIN_REQUIRED)) ui.askToSignIn();
} catch (InvalidRequestException e) {
    if (e.is(ErrorCode.CONTEXT_LENGTH_EXCEEDED)) compactHistory();
} catch (LlmException e) {                                      // root: code, retryable, outcomeUnknown, httpStatus, ids
    log.warn("LLM call {} failed: {} (outcome unknown: {})", e.requestId().orElse("n/a"), e.code(), e.outcomeUnknown());
}
```

### 4.15 Lifecycle

```java
try (LlmClient llm = LlmClient.create(endpoint)) {            // owns the JDK HttpClient it created
    …
}                                                              // idempotent; cancels in-flight calls; later calls throw IllegalStateException

HttpTransport shared = HttpTransport.jdk();                    // one connection pool for many endpoints
LlmClient a = LlmClient.builder(endpointA).http(h -> h.transport(shared)).build();   // borrowed: never closed by a client
```

### 4.16 Testing without network, keys or mocks

```java
@Test
void summarizerSendsOneRequestAndReturnsTheText() {
    FakeLlm fake = FakeLlm.create().reply("Short summary.");
    try (LlmClient llm = fake.client()) {                      // a real client over a scripted dialect
        assertEquals("Short summary.", new Summarizer(llm).summarize(longText));
    }
    assertEquals(1, fake.requests().size());                   // recorded, fully resolved requests
    assertTrue(fake.requests().getFirst().messages().getLast().text().contains(longText));
}
```

Replay recorded provider traffic in CI:

```java
LlmClient llm = LlmClient.builder(endpoint)
        .caching(c -> c.responses(ResponseCache.directory(Path.of("src/test/resources/cassettes")))
                       .mode(CacheMode.OFFLINE))             // never touches the network; a miss fails and names the request
        .build();
// Record locally once with CacheMode.REFRESH and a real key; commit the cassette files.
```

### 4.17 Debugging

```java
PreparedRequest preview = llm.chat().preview(request);          // no network, no credentials
preview.problems().forEach(System.err::println);                // what send() would reject, with field paths
preview.body().ifPresent(body -> System.out.println(body.toPrettyJson()));   // exact wire JSON
System.out.println(preview.toCurl());                           // runnable; the key appears as $ANTHROPIC_API_KEY
System.out.println(llm.connection().describe().toPrettyJson()); // effective configuration, redacted
```

Wire logging is one switch (`http(h -> h.wireLog(WireLog.HEADERS))`, redacted), and every call emits a
JFR event visible in JDK Mission Control (§15).

### 4.18 Escape hatches (Layer 3)

```java
ChatRequest withNativeOptions = ChatRequest.builder()
        .user(prompt)
        .option(AnthropicOptions.THINKING_BUDGET, 8_192)                     // typed provider option
        .option(AnthropicOptions.EXTRA_BODY, Json.object("metadata", Json.object("user_id", "u-17")))
        .build();

GeminiCaches caches = llm.providerApi(Gemini.CACHES);                        // typed provider-only API
CachedContent corpus = caches.create(model, List.of(Message.user(bigCorpus)), Duration.ofHours(2));

RawResponse credits = llm.raw().get("credits");                              // @Experimental; relative to the base URL only
```

### 4.19 Kotlin — no wrapper needed

```kotlin
LlmClient.create(Providers.ANTHROPIC, ApiKey.fromEnv()).use { llm ->
    val reply = llm.chat().send { it.model("claude-sonnet-5").system("Be terse.").user("Why sealed types?") }
    println(reply.text())
    llm.chat().stream("Count to three").use { stream ->
        for (event in stream) if (event is ChatEvent.TextDelta) print(event.text())
    }
}
```

**Wrong-usage check.** Forgetting the key or the model fails at the call site with a named cause; swapping
model and prompt does not compile; forgetting to close a stream is visible (`AutoCloseable` in every
example); nothing performs I/O until a verb (`send`, `stream`, `test`, `list`, `refresh`, `login`) runs;
the core never guesses a provider or a model.

---

## 5. Architecture

### 5.1 Layers and dependency direction

```
 ┌──────────────────────────────── PUBLIC API  (dev.llmtransport.*) ────────────────────────────────┐
 │  LlmClient ─ chat()  models()  connection()  auth()  withOptions()  providerApi()  raw()         │ facade + sub-APIs
 │  ChatRequest · ChatResponse · ChatStream/ChatEvent · Message/Content · Tool · Usage · ModelInfo   │ portable model (immutable)
 │  Endpoint · LlmProvider · Credentials · GenerationSettings · Timeout/RetryPolicy · CachingOptions │ configuration (data)
 │  LlmEvent/LlmListener · LlmException · PromptCache · Json                                        │ observation, errors, JSON
 ├──────────────────────────────── EXECUTION CORE  (internal, not exported) ────────────────────────┤
 │  RequestResolver → ExecutionEngine → AttemptRunner (retry, deadline) → StreamPump → Accumulator  │ one pipeline for all dialects
 │  CredentialApplier · TokenRefresher · OAuthEngine · ResponseCacheStage · ModelCatalog            │
 │  EventDispatcher · JfrRecorder · Redactor · SseFramer/NdjsonFramer/IdleWatchdog · JSON · binder  │
 ├──────────── SPI for protocol authors ───────────────┬──────────── SPI fed by hosts ───────────────┤
 │  Dialect · ChatCodec · StreamDecoder · ModelListCodec│  HttpTransport · WireInterceptor · TokenStore│
 │  pure functions: no I/O, no credentials, no retries │  BrowserLauncher · SecretResolver           │
 │  ProviderApi + ProviderApiContext (multi-step ops)  │  CredentialProvider · ResponseCache · JsonMapper│
 ├──────────────────────────────────────────────────────┴──────────────────────────────────────────────┤
 │  Provider modules: openai (Responses, Chat Completions + compatible presets) · anthropic (Messages) │
 │  · gemini (generateContent) · later: ollama (native), bedrock (Converse), vertex                     │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
   compile-time:  application → llm-transport (aggregate) → provider modules → core → JDK
   core never depends on provider modules, UI toolkits, cloud SDKs, JSON libraries or agents.
```

### 5.2 One request, end to end

```
ChatRequest ─► RequestResolver     client defaults ∘ endpoint ∘ view options ∘ request  (unset inherits; omit() suppresses)
                                   model resolution · option scoping · capability checks · UnsupportedPolicy
            ─► ChatCodec.encode    pure; returns WireRequest (relative URI + JSON body) + warnings
            ─► ResponseCache       optional; key = hash(endpoint, credential scope, dialect, method, path, body)
                 └ hit ──────────► ChatCodec.decode / StreamDecoder replay ─► ChatResponse (fromCache = true)
            ─► CredentialApplier   API key / bearer / OAuth / dynamic; expiry-aware, single-flight refresh
            ─► WireInterceptor*    per attempt, registration order in, reverse order out
            ─► HttpTransport.send  destination bound to the endpoint base URL; deadline and cancellation enforced
            ─► AttemptRunner       RetryPolicy · one total deadline · never after visible output · outcome classification
            ─► decode              ChatCodec.decode │ SseFramer/NdjsonFramer → StreamDecoder → ChatEvent* → Accumulator
            ─► ResponseCache.put   successful, complete responses only
            ─► EventDispatcher     Started · Sent · FirstOutput · Retrying · Warning · Completed/Failed/Cancelled · JFR
            ─► ChatResponse        message · text · toolCalls · usage · finishReason · warnings · metadata · raw
```

Every provider gets the same deadlines, retries, cancellation, redaction, caching and events because
none of that lives in a codec. Provider APIs (§8.5) enter the same pipeline below the codec stage
through `ProviderApiContext.exchange(…)`.

### 5.3 Internal components — no god classes

| Component | Responsibility | Deletion test |
|---|---|---|
| `DefaultLlmClient` | Wires the components below; owns resources it created | Thin by design; a facade, not a behaviour holder |
| `RequestResolver` | Merge scopes, resolve model and aliases, scope options, validate | Deleting it spreads precedence rules into every dialect |
| `ExecutionEngine` | Orders the pipeline stages; creates the call context (`ScopedValue`) | Deleting it recreates the pipeline in each API |
| `AttemptRunner` | Retry decisions, backoff with jitter, `Retry-After`, deadline, outcome classification | Deleting it recreates retry loops per dialect |
| `StreamPump` + `Accumulator` | Frame → event loop, idle watchdog, bounded aggregation | Deleting it breaks "stream result == send result" |
| `CredentialApplier` + `TokenRefresher` | Present credentials; cache expiring tokens; single-flight renewal | Shared by OAuth and dynamic credentials |
| `OAuthEngine` | PKCE, loopback receiver, external redirect, device polling, client credentials, revocation | One security-reviewed implementation |
| `ResponseCacheStage` | Key computation, mode handling, replay through the codec | Keeps caching out of codecs |
| `ModelCatalog` | Catalog TTL cache, refresh, snapshots | Every UI would cache otherwise |
| `EventDispatcher` | Ordered, isolated, synchronous listener delivery; tags | Listener isolation in one place |
| `JfrRecorder`, `Redactor` | JFR events; one redaction policy for logs, events, errors, previews | Consistent secrecy |
| `JdkHttpTransport` | Default transport over `java.net.http.HttpClient` | Replaceable via SPI |
| `SseFramer`, `NdjsonFramer`, `IdleWatchdog` | Framing across network chunks; idle detection | Shared by all dialects |
| `JsonParser`, `JsonWriter`, `RecordBinder` | Bounded parser, stable-order writer, record ↔ JSON, schema derivation | Zero dependencies |

### 5.4 Design it twice — shapes considered

**Consumer surface**

| Candidate | Shape | Verdict |
|---|---|---|
| Facade verbs (A) | `client.generate(…)`, `client.stream(…)`, `client.newCall(…)`, `client.inspect(…)` | Short for chat, but the facade grows with every operation family (embeddings, files, batches) |
| Sub-API per noun (B) | `client.chat().send/stream/…`, `client.models()…` | **Chosen**: IDE-discoverable, scales by adding nouns |
| Typed operation dispatch | `transport.execute(Generate.of(r))` | Rejected: a tiny method count hides a large vocabulary; lifetimes differ per operation |
| Provider-specific clients | `AnthropicClient`, `OpenAiClient` | Rejected: every host branches per provider; contracts diverge |
| Multi-endpoint router | `llm.chat("or:model")` | Rejected for core: hides which endpoint is billed; hosts compose clients |

**Provider SPI**

| Candidate | Shape | Verdict |
|---|---|---|
| Adapter drives I/O (A) | `InferenceAdapter.generate(request, ExecutionContext)` | Flexible, but each adapter re-implements timing, cancellation and error subtleties |
| Pure codec (B) | `encode`/`decode`/`StreamDecoder`; the core executes | **Chosen** for request/response operations: least code per dialect, testable as functions |
| Hybrid for the rest | Typed `ProviderApi` implemented against `ProviderApiContext.exchange(…)` | **Chosen** for provider-only, multi-step operations (files, batches, cached contents) |

### 5.5 Object-oriented structure at a glance

| Pattern | Where | What it buys the caller |
|---|---|---|
| Facade + sub-APIs | `LlmClient` → `chat()`, `models()`, `connection()`, `auth()` | One entry point; discovery by completion |
| Static factories | `LlmClient.create`, `Endpoint.of`, `ChatRequest.of`, `Tool.function`, `Content.image`, `ApiKey.fromEnv` | Named construction; required values cannot be forgotten |
| Builders with consumer-builders | every configuration and request type | Readable configuration; immutable products |
| Strategy | `Dialect` / `ChatCodec` / `StreamDecoder` per wire protocol | Protocols swap without touching calling code |
| Data-driven variants | `LlmProvider` presets with `QuirkKey` traits | New providers without new classes |
| Adapter | `HttpTransport`, `JsonMapper`, `TokenStore`, `SecretResolver`, `CredentialProvider`, `ResponseCache` | Host infrastructure plugs in behind small interfaces |
| Chain of responsibility | `WireInterceptor` | Signing, headers and tracing without subclassing |
| Observer | `LlmListener` over sealed `LlmEvent` | UI progress and metrics without coupling |
| Iterator | `ChatStream` (single-use, closeable) | Plain `for` loops over live output |
| Command / one-shot handle | `ChatCall` | Cancellation and async for one call |
| View (decorator) | `withOptions(…)` | Scoped policy without new clients or resources |
| State machine | `LoginSession` | Explicit, cancellable login lifecycle |
| Value objects, algebraic data types | records and sealed hierarchies (`Content`, `ChatEvent`, `Credentials`, `OutputFormat`) | Exhaustive, type-safe handling |
| Registry (instance-scoped) | `ProviderRegistry` | Configuration-driven provider lookup without global state |
| Typed keys | `OptionKey<T>`, `QuirkKey<T>`, `ProviderApi<T>` | Type-safe extensibility instead of string maps |

---

## 6. Project structure

### 6.1 Artifacts

| Artifact | JPMS module | Contents | Depends on |
|---|---|---|---|
| `llm-transport-bom` | — | Version alignment (`java-platform`) | — |
| `llm-transport-core` | `dev.llmtransport` | API, SPI, execution, JDK transport, JSON, OAuth engine, token stores, caches, JFR | JDK; compile-only annotations |
| `llm-transport-openai` | `dev.llmtransport.openai` | Responses and Chat Completions dialects, options, quirks, hosted tools, compatible presets | core |
| `llm-transport-anthropic` | `dev.llmtransport.anthropic` | Messages dialect, options, hosted tools | core |
| `llm-transport-gemini` | `dev.llmtransport.gemini` | generateContent dialect, options, tools, explicit cache API | core |
| `llm-transport` | `dev.llmtransport.providers` | `Providers` index; `requires transitive` the three families | the above |
| `llm-transport-testing` | `dev.llmtransport.testing` | `FakeLlm`, `ScriptedTransport`, `RecordingListener`, `LlmErrors`, `TestClock`, `FakeAuthorizationServer`, contract kits | core, JUnit Jupiter API |
| `examples` (unpublished) | — | README examples as tests, Java and Kotlin | all |
| later, on demand | — | `-kotlin` (coroutines), `-jackson`, `-otel`, `-keychain`, `-config` (YAML/properties), `-spring-boot`, `-ollama`, `-bedrock`, `-vertex` | core |

Most applications depend on `llm-transport` (one coordinate); size-sensitive hosts depend on core plus
one family. Adding an artifact requires a significant optional dependency, a distinct lifecycle, or an
independently useful integration — never a layer of the design.

### 6.2 Repository tree

```
llm-transport-sdk/
├── settings.gradle.kts                  includeBuild("build-logic"), project list, repositories
├── gradle/
│   ├── libs.versions.toml               single source of dependency and plugin versions
│   └── wrapper/                         Gradle 9.x wrapper, pinned
├── build-logic/                         included build with precompiled convention plugins
│   ├── settings.gradle.kts
│   ├── build.gradle.kts                 `kotlin-dsl`
│   └── src/main/kotlin/
│       ├── sdk.java-library.gradle.kts  toolchain 26, --release 26, lint as errors, Markdown Javadoc, reproducible jars
│       ├── sdk.published.gradle.kts     maven-publish, signing, sources and Javadoc jars, POM metadata
│       ├── sdk.api-compat.gradle.kts    binary-compatibility check against the last release (japicmp)
│       └── sdk.live-tests.gradle.kts    `liveTest` suite: credentialed, opt-in, cost-bounded
├── llm-transport-bom/
├── llm-transport-core/
│   └── src/
│       ├── main/java/module-info.java
│       ├── main/java/dev/llmtransport/…           (§6.3)
│       ├── test/java/…                            unit, contract and architecture tests
│       └── test/resources/fixtures/…              framing, JSON and OAuth fixtures
├── llm-transport-openai/
│   └── src/
│       ├── main/java/dev/llmtransport/openai/
│       │   ├── OpenAi.java · OpenAiOptions.java · OpenAiQuirks.java · OpenAiTools.java · OpenAiCompatible.java
│       │   └── internal/responses/… · internal/chat/…      codecs, stream decoders, error mapping
│       └── test/resources/fixtures/responses/… · chat-completions/…   golden wire fixtures
├── llm-transport-anthropic/   dev/llmtransport/anthropic/{Anthropic, AnthropicOptions, AnthropicTools}.java + internal/
├── llm-transport-gemini/      dev/llmtransport/gemini/{Gemini, GeminiOptions, GeminiTools, GeminiCaches, CachedContent}.java + internal/
├── llm-transport/             dev/llmtransport/providers/Providers.java
├── llm-transport-testing/     dev/llmtransport/testing/…
├── examples/                  src/test/java · src/test/kotlin
└── docs/                      requirements/ · proposals/ · extending.md · CHANGELOG.md
```

### 6.3 Core packages

```
dev.llmtransport             API   LlmClient, CallOptions, Endpoint, LlmProvider, ModelId, DialectId, Usage, Warning,
                                   ErrorCode, Registration, LlmException + 8 subclasses (§7.14)
dev.llmtransport.chat        API   ChatApi, ChatCall, ChatRequest, ChatResponse, ChatStream, ChatEvent, Message, Role,
                                   Content, ToolCall, ToolResult, FinishReason, GenerationSettings, Param, Reasoning,
                                   Effort, OutputFormat, PreparedRequest, ResponseMetadata, RateLimits
dev.llmtransport.tool        API   Tool, FunctionTool, ProviderTool, ToolChoice
dev.llmtransport.model       API   ModelsApi, ModelInfo, Capability, Capabilities, SupportLevel, Modality, Pricing
dev.llmtransport.connection  API   ConnectionApi, ConnectionReport, ConnectionTest, AccountInfo
dev.llmtransport.auth        API   AuthApi, AuthOptions, AuthStatus, AuthMethod, Credentials, ApiKey, BearerToken, Secret,
                                   OAuthCredentials, OAuthConfig, OAuthTokens, LoginSession, LoginPrompt, LoginOptions
                             SPI   TokenStore, BrowserLauncher, SecretResolver, CredentialProvider
dev.llmtransport.provider    API   ProviderRegistry, FieldDescriptor, OptionKey, QuirkKey      SPI  ProviderBundle
dev.llmtransport.config      API   TimeoutPolicy, RetryPolicy, UnsupportedPolicy
dev.llmtransport.cache       API   PromptCache, CacheBreakpoint, CacheMode, CachingOptions, CacheKey, CachedExchange
                             SPI   ResponseCache
dev.llmtransport.event       API   LlmEvent, RequestEvent, AuthEvent, LlmListener
dev.llmtransport.json        API   JsonValue (+ JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull),
                                   Json, JsonSchema, Description                           SPI  JsonMapper
dev.llmtransport.http        API   HttpOptions, WireLog, Headers, WireRequest, WireResponse, TransportOptions, RawApi, RawResponse
                             SPI   HttpTransport, WireInterceptor
dev.llmtransport.spi         SPI   Dialect, ChatCodec, StreamDecoder, ModelListCodec, AccountCodec, EncodeContext, DecodeContext,
                                   WireFrame, StreamFormat, ProviderApi, ProviderApiContext
dev.llmtransport.internal.*  —     client, execution, resolve, auth, oauth, cache, catalog, http, stream, json, jfr, redact
```

Rules, enforced by ArchUnit tests in core:

- Packages by noun, never by layer; at most three levels below the root; no `util`, `impl`, `manager`.
- The root package is the hub: it holds the facade and the value types every feature uses. Feature
  packages depend on the root and on each other only along this DAG: `chat → {tool, cache, provider,
  http, json}`, `cache → {http, json}`, `tool → json`, `http → json`, `model → {provider, json}`,
  `event → chat`, `spi → {chat, http, model, provider, json}`. No other cycles.
- Host-fed SPIs live beside the feature they serve (`auth.TokenStore`, `cache.ResponseCache`,
  `http.HttpTransport`, `json.JsonMapper`); provider-authoring SPIs live in `spi`. Both are marked
  `/// **SPI**` and grow only through `default` methods.
- `internal` packages are not exported and carry `@ApiStatus.Internal`; provider modules never import
  them.
- Sealed hierarchies whose variants span packages (`Content` permits `ToolCall` and `ToolResult`) rely
  on the core being compiled as a named module; this was verified to load both on the module path and
  on the classpath (§19.2).

### 6.4 `module-info.java` (core)

```java
/// LLM Transport SDK core: the portable API, the SPIs, and the JDK-based default implementation.
module dev.llmtransport {
    requires transitive java.net.http;                    // the API exposes HttpClient.Version (HttpOptions)
    requires jdk.httpserver;                              // OAuth loopback receiver, bound to 127.0.0.1 only
    requires static java.desktop;                         // BrowserLauncher.system(); when absent the prompt is reported, not opened
    requires static jdk.jfr;                              // JFR events, when the module is present
    requires static transitive org.jspecify;              // nullness annotations on the API: visible to consumers' compilers
    requires static transitive org.jetbrains.annotations; // @ApiStatus.* on the API: visible to consumers' compilers

    exports dev.llmtransport;
    exports dev.llmtransport.auth;
    exports dev.llmtransport.cache;
    exports dev.llmtransport.chat;
    exports dev.llmtransport.config;
    exports dev.llmtransport.connection;
    exports dev.llmtransport.event;
    exports dev.llmtransport.http;
    exports dev.llmtransport.json;
    exports dev.llmtransport.model;
    exports dev.llmtransport.provider;
    exports dev.llmtransport.spi;
    exports dev.llmtransport.tool;

    uses dev.llmtransport.provider.ProviderBundle;
}
```

`transitive` is required wherever exported signatures mention another module's types; javac's
`exports` lint (part of `-Xlint:all -Werror`) enforces it (§19.3). Modular applications that bind records
for structured output or tool arguments export (or open) the records' packages to `dev.llmtransport`;
classpath applications need nothing.

### 6.5 Gradle build conventions

`settings.gradle.kts`:

```kotlin
pluginManagement { includeBuild("build-logic") }

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "llm-transport-sdk"
include(
    "llm-transport-bom", "llm-transport-core", "llm-transport-openai", "llm-transport-anthropic",
    "llm-transport-gemini", "llm-transport", "llm-transport-testing", "examples",
)
```

`gradle/libs.versions.toml` (versions pinned when the build is created; the core's runtime needs none):

```toml
[versions]
jspecify = "1.0.0"
jetbrains-annotations = "26.0.2"
junit = "5.13.4"
archunit = "1.4.1"

[libraries]
jspecify = { module = "org.jspecify:jspecify", version.ref = "jspecify" }
jetbrains-annotations = { module = "org.jetbrains:annotations", version.ref = "jetbrains-annotations" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-jupiter-api = { module = "org.junit.jupiter:junit-jupiter-api" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
archunit = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
```

`build-logic/src/main/kotlin/sdk.java-library.gradle.kts`:

```kotlin
plugins { `java-library` }

val libs = versionCatalogs.named("libs")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(26) }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 26                                   // one-line switch if an LTS target is required (§20)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {                 // /// Markdown comments (JEP 467) are rendered natively
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.withType<AbstractArchiveTask>().configureEach {     // reproducible artifacts
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    "compileOnlyApi"(libs.findLibrary("jspecify").get())                // visible to consumers' compilers, not at runtime
    "compileOnlyApi"(libs.findLibrary("jetbrains-annotations").get())
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

With `-Xlint:all -Werror`, javac's `missing-explicit-ctor` lint rejects any public class in an exported
package without an explicit constructor; it enforces the convention of §7 that public classes expose
static factories and builders behind private constructors (exceptions keep public constructors).

A provider module's build file is four lines:

```kotlin
plugins { id("sdk.java-library"); id("sdk.published"); id("sdk.api-compat") }
description = "LLM Transport SDK — Anthropic Messages dialect"
dependencies {
    api(project(":llm-transport-core"))
    testImplementation(project(":llm-transport-testing"))
}
```

The testing artifact exposes JUnit types to its users, so it aligns JUnit on its `api` configuration:

```kotlin
dependencies {
    api(project(":llm-transport-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
}
```

Test suites: `test` (unit, contract and fixture tests, always), `liveTest` (credentialed calls to real
endpoints, enabled with `-Plive` and environment keys, bounded in tokens), and `:examples:test` (every
README example compiles and runs against `FakeLlm`). CI runs `check` on every change and `liveTest`
nightly; releases additionally run the japicmp report against the previous version.

### 6.6 Java 26 on the surface and inside

Only final (non-preview) language and library features are used, so consumers never need
`--enable-preview`.

| Feature (JDK) | Where | Benefit |
|---|---|---|
| Records (16) | Small, stable values: `ModelId`, `DialectId`, `Warning`, `CacheKey`, delta events, login prompts | Value semantics without boilerplate |
| Sealed types (17) + pattern `switch` (21) + record patterns (21) | `Content`, `ChatEvent`, `LlmEvent`, `Credentials`, `LoginPrompt`, `Tool`, `ToolChoice`, `OutputFormat`, `JsonValue` | Exhaustive handling inside codecs: adding a content variant fails compilation in every dialect until handled |
| Unnamed variables and patterns `_` (22) | Examples, internal switches | Readable "ignore" branches |
| Virtual threads (21), no pinning in `synchronized` (JEP 491, 24) | Blocking API scales per call; `sendAsync()` uses a virtual-thread-per-task executor | No SDK thread pools; simple blocking code |
| `ScopedValue` (final in 25) | Per-call context (request id, tags, deadline) visible to listeners, interceptors, codecs and JFR | Immutable, bounded context; no `ThreadLocal` leaks |
| `HttpClient` HTTP/2 (11), `AutoCloseable` (21), HTTP/3 (JEP 517, 26) | `JdkHttpTransport`; `HttpOptions.httpVersion(HttpClient.Version.HTTP_3)` opt-in | Zero-dependency transport; HTTP/3 when endpoints support it |
| Sequenced collections (21) | `messages().getLast()`, `stages().getFirst()` in APIs and examples | Readable access to ordered data |
| Markdown documentation comments (JEP 467, 23) | All public Javadoc as `///` | Readable source docs, better rendering |
| Stream gatherers (24) | `ChatStream.events()` works with `gather(…)`; used internally for delta coalescing | Composable stream processing without custom collectors |
| Module import declarations (25) and compact source files (25) | Script-style quickstarts for modular users: `import module dev.llmtransport;` | Minimal-ceremony trials |
| JFR custom events | `dev.llmtransport.Request`, `…Retry`, `…TokenRefresh` | Zero-dependency production diagnostics |
| Final-field integrity (JEP 500, 26 warns on deep reflection) | Record binding uses canonical constructors and accessors only | No reflective final-field writes; future-proof |

Not used: structured concurrency, lazy constants and primitive patterns remain previews in JDK 26
(verified for `StructuredTaskScope`, §19.2); `Unsafe` and deep reflection are never used.

---

## 7. Public API sketch (core)

Conventions: record-style accessors (`timeout()`, never `getTimeout()`); static factories instead of
constructors, so every public class declares a private constructor (exceptions excepted: they take
`LlmException.Details`); builders for four or more parameters
or two or more optional ones, with required values in the factory; singular builder methods add, plural ones replace; consumer-builder overloads *edit* the
current value; `final` classes unless designed for extension; API interfaces that consumers must not
implement are `@ApiStatus.NonExtendable`; `@NullMarked` packages, `Optional`/`OptionalLong` only for
normally-absent results, empty collections instead of null. Every type's Javadoc states *immutable*,
*thread-safe* or *not thread-safe*.

### 7.1 Facade, builder and per-call options

```java
package dev.llmtransport;

/// Thread-safe, shareable runtime for one endpoint. Owns what it creates; borrows what it is given.
/// `close()` is idempotent, cancels in-flight calls and releases owned resources; later calls throw
/// `IllegalStateException`. Not for implementation by consumers: methods may be added in minor releases.
@ApiStatus.NonExtendable
public interface LlmClient extends AutoCloseable {
    static LlmClient create(Endpoint endpoint)                              { return builder(endpoint).build(); }
    static LlmClient create(LlmProvider provider, Credentials credentials) { return create(Endpoint.of(provider, credentials)); }
    static LlmClient create(LlmProvider provider)                          { return create(Endpoint.of(provider)); }
    static Builder builder(Endpoint endpoint)                              { … }

    Endpoint endpoint();
    ChatApi chat();
    ModelsApi models();
    ConnectionApi connection();
    AuthApi auth();                                       // NOT_APPLICABLE status for non-OAuth credentials

    /// Lightweight view sharing this client's transport, credentials, caches and catalog; `close()` on a
    /// view is a no-op. Overrides only operational policy — never endpoint, credentials or destination.
    LlmClient withOptions(Consumer<CallOptions.Builder> overrides);

    Registration addListener(LlmListener listener);       // released on Registration.close() or client close
    <T> T providerApi(ProviderApi<T> api);                // typed provider-only operations; no I/O to obtain
    @ApiStatus.Experimental RawApi raw();                 // relative paths under the endpoint base URL only

    @Override void close();

    /// Not thread-safe. `build()` validates everything, reports all violations at once, and performs no I/O.
    final class Builder {
        public Builder timeouts(TimeoutPolicy policy)                           { … }
        public Builder timeouts(Consumer<TimeoutPolicy.Builder> edit)           { … }
        public Builder retry(RetryPolicy policy)                                { … }
        public Builder retry(Consumer<RetryPolicy.Builder> edit)                { … }
        public Builder http(HttpOptions options)                                { … }  // or an injected transport
        public Builder http(Consumer<HttpOptions.Builder> edit)                 { … }
        public Builder auth(AuthOptions options)                                { … }  // token store, launcher, resolver
        public Builder auth(Consumer<AuthOptions.Builder> edit)                 { … }
        public Builder caching(CachingOptions options)                          { … }
        public Builder caching(Consumer<CachingOptions.Builder> edit)           { … }
        public Builder defaults(GenerationSettings defaults)                    { … }  // request defaults
        public Builder defaults(Consumer<GenerationSettings.Builder> edit)      { … }
        public Builder unsupported(UnsupportedPolicy policy)                    { … }  // default FAIL
        public Builder listener(LlmListener listener)                           { … }  // singular: adds
        public Builder interceptor(WireInterceptor interceptor)                 { … }  // singular: adds, ordered
        public Builder executor(Executor executor)                              { … }  // for sendAsync(); borrowed
        public Builder jsonMapper(JsonMapper mapper)                            { … }  // default: record binder
        public Builder clock(Clock clock)                                       { … }  // tests: TTLs, expiry, backoff
        public LlmClient build()                                                { … }
    }
}

/// Immutable. Operational overrides for a view; unset values inherit from the client.
public final class CallOptions {
    public static Builder builder()                  { … }
    public Optional<Duration> timeout()              { … }   // total deadline for each call made through the view
    public Optional<RetryPolicy> retry()             { … }
    public Optional<CacheMode> cacheMode()           { … }
    public Optional<UnsupportedPolicy> unsupported() { … }
    public List<LlmListener> listeners()             { … }   // added to, never replacing, client listeners
    public Map<String, String> tags()                { … }   // copied onto events, logs and JFR records
    public Map<String, String> headers()             { … }   // ordinary headers; protected ones are rejected

    public static final class Builder {
        public Builder timeout(Duration total)                 { … }
        public Builder retry(RetryPolicy policy)               { … }
        public Builder cacheMode(CacheMode mode)               { … }
        public Builder unsupported(UnsupportedPolicy policy)   { … }
        public Builder listener(LlmListener listener)          { … }
        public Builder tag(String key, String value)           { … }
        public Builder header(String name, String value)       { … }
        public CallOptions build()                             { … }
    }
}

/// A view may shorten a deadline or narrow behaviour; it can never grant a new destination, account or credential.
public interface Registration extends AutoCloseable { @Override void close(); }
```

### 7.2 Endpoint, provider, registry and descriptors

```java
package dev.llmtransport;

/// Immutable, thread-safe. Where and how to reach one provider. Persistable when its credentials are
/// references (`toJson()` refuses literal secrets); `toString()` redacts.
public final class Endpoint {
    public static Endpoint of(LlmProvider provider)                           { … }  // for providers without auth
    public static Endpoint of(LlmProvider provider, Credentials credentials)  { … }
    public static Builder builder(LlmProvider provider)                       { … }
    public static Endpoint fromJson(String json, ProviderRegistry registry)   { … }

    public String id()                         { … }  // default: provider id; [a-z0-9][a-z0-9._-]*
    public LlmProvider provider()              { … }
    public Dialect dialect()                   { … }  // provider default unless overridden
    public URI baseUrl()                       { … }  // includes any deployment prefix and version path
    public Credentials credentials()           { … }
    public Map<String, String> headers()       { … }
    public Optional<ModelId> defaultModel()    { … }
    public <T> Optional<T> option(OptionKey<T> key) { … }   // endpoint-level provider options (organization, api-version…)

    public Endpoint withDefaultModel(String model)          { … }
    public Endpoint withCredentials(Credentials credentials) { … }
    public Builder toBuilder()                               { … }
    public String toJson()                                   { … }

    public static final class Builder {
        public Builder id(String id)                          { … }
        public Builder baseUrl(URI baseUrl)                   { … }
        public Builder dialect(Dialect dialect)               { … }  // must be one the provider declares
        public Builder credentials(Credentials credentials)   { … }
        public Builder header(String name, String value)      { … }
        public Builder headers(Map<String, String> headers)   { … }
        public Builder defaultModel(String model)             { … }
        public Builder defaultModel(ModelId model)            { … }
        public <T> Builder option(OptionKey<T> key, T value)  { … }
        public Builder allowInsecureCredentials()             { … }  // credentials over http:// to a non-loopback host
        /// Form and configuration path: parses `raw` per the field's descriptor and routes it to the typed
        /// setter. Keys: "id", "baseUrl", "dialect", "defaultModel", "header.<Name>", provider option keys.
        public Builder set(String key, String raw)            { … }
        public Endpoint build()                               { … }  // lists every invalid field
    }
}

/// Immutable, thread-safe. A provider is data: everything about a backend that is not a wire format.
public final class LlmProvider {
    public static Builder builder(String id, Dialect defaultDialect) { … }

    public String id()                                { … }  // "openai", "anthropic", "deepseek", "corp-gw"
    public String displayName()                       { … }
    public List<Dialect> dialects()                   { … }  // supported; the first is the default
    public URI defaultBaseUrl()                       { … }
    public List<AuthMethod> authMethods()             { … }  // how credentials are presented; first = preferred
    public Optional<String> apiKeyEnvVar()            { … }  // used by ApiKey.fromEnv()
    public Optional<OAuthConfig> oauthDefaults()      { … }
    public List<FieldDescriptor> connectionFields()   { … }  // drives connection forms; always includes base URL and auth
    public <T> Optional<T> quirk(QuirkKey<T> key)     { … }  // typed provider traits read by codecs
    public Optional<URI> apiKeyUrl()                  { … }  // "get a key" link for UIs
    public Builder toBuilder()                        { … }  // presets are copied and adjusted, never mutated

    public static final class Builder { /* displayName, dialect(Dialect) adds, defaultBaseUrl, auth(AuthMethod) adds,
        apiKeyEnvVar, apiKeyUrl, oauthDefaults(OAuthConfig), connectionField(FieldDescriptor) adds,
        <T> quirk(QuirkKey<T>, T), build() */ }
}

public record ModelId(String value) {            // opaque; never split on ':' or '/', never normalized
    public ModelId { if (value.isBlank()) throw new IllegalArgumentException("model id must not be blank"); }
    public static ModelId of(String value) { return new ModelId(value); }
}

public record DialectId(String family, String revision) { }   // ("openai-responses", "v1"), ("anthropic-messages", "2023-06-01")
```

```java
package dev.llmtransport.provider;

/// Immutable, thread-safe. Explicit registration always wins; discovery happens only when asked.
public final class ProviderRegistry {
    public static ProviderRegistry discover()               { … }  // ServiceLoader<ProviderBundle>; duplicate ids fail, naming both modules
    public static ProviderRegistry of(LlmProvider... providers) { … }
    public ProviderRegistry with(LlmProvider provider)      { … }  // returns a new registry; replaces an equal id
    public List<LlmProvider> all()                          { … }
    public Optional<LlmProvider> find(String id)            { … }
    public LlmProvider require(String id)                   { … }  // IllegalArgumentException naming known ids and the module to add
}

/// Immutable. One form field, for connection forms and model parameter forms alike.
public final class FieldDescriptor {
    public enum Kind { TEXT, SECRET_REFERENCE, URL, INTEGER, DECIMAL, BOOLEAN, CHOICE, DURATION, JSON }
    public static Builder builder(String key, Kind kind) { … }
    public String key()                       { … }  // "baseUrl", "temperature", "anthropic.thinking_budget"
    public String label()                     { … }
    public Kind kind()                        { … }
    public boolean required()                 { … }
    public Optional<String> defaultValue()    { … }  // the provider's documented default, if known
    public Optional<String> help()            { … }
    public Optional<String> group()           { … }  // "Connection", "Sampling", "Advanced"
    public List<String> choices()             { … }
    public Optional<BigDecimal> min()         { … }
    public Optional<BigDecimal> max()         { … }
    public Optional<String> unit()            { … }  // "tokens", "s"
}

/// Typed, namespaced key for provider-specific values. The namespace scopes where the option applies (§16.2).
/// The value type drives parsing for forms and JSON configuration.
public final class OptionKey<T> {
    public static <T> OptionKey<T> of(String namespace, String name, Class<T> type)                  { … }
    public static <E> OptionKey<List<E>> listOf(String namespace, String name, Class<E> elementType) { … }  // no unchecked casts
    public String namespace() { … }  public String name() { … }
    public Optional<FieldDescriptor> descriptor() { … }        // lets forms render provider options
}

/// A provider *trait* read by a codec (for example which field carries max tokens); set on presets only.
public final class QuirkKey<T> {
    public static <T> QuirkKey<T> of(String namespace, String name, Class<T> type, T defaultValue) { … }
}

/// SPI (ServiceLoader): lets a provider module contribute presets to ProviderRegistry.discover().
public interface ProviderBundle { List<LlmProvider> providers(); }
```

### 7.3 Chat API, call handle, request and settings

```java
package dev.llmtransport.chat;

/// Thread-safe. Every method validates locally before any I/O.
@ApiStatus.NonExtendable
public interface ChatApi {
    /// Blocks until complete or until the total deadline; retries per RetryPolicy. @throws LlmException
    ChatResponse send(ChatRequest request);
    default ChatResponse send(String userText)                          { return send(ChatRequest.of(userText)); }
    default ChatResponse send(ModelId model, String userText)           { return send(ChatRequest.of(model, userText)); }
    default ChatResponse send(Consumer<ChatRequest.Builder> spec)       { … }
    /// Sets OutputFormat.of(type) unless the request has an output format, sends, and binds the result.
    /// @throws InvalidResponseException output_truncated, output_refused or output_invalid
    <T> T send(ChatRequest request, Class<T> type);

    /// Single-use, closeable, bounded; close() cancels; never retried after the first event.
    ChatStream stream(ChatRequest request);
    default ChatStream stream(String userText)                          { return stream(ChatRequest.of(userText)); }
    default ChatStream stream(Consumer<ChatRequest.Builder> spec)       { … }

    /// A one-shot, cancellable handle; performs no I/O until started.
    ChatCall call(ChatRequest request);

    /// Resolves, validates and encodes without network access or credentials.
    PreparedRequest preview(ChatRequest request);
}

/// Thread-safe for cancel()/isCancelled(); exactly one of send(), stream() or sendAsync() may start it.
@ApiStatus.NonExtendable
public interface ChatCall {
    String id();                                     // the SDK request id carried by events, logs and errors
    ChatResponse send();
    ChatStream stream();
    /// Runs on the client executor (default: a virtual thread per call). Cancelling the returned future
    /// cancels the call; it completes exceptionally with the same exceptions send() throws.
    CompletableFuture<ChatResponse> sendAsync();
    void cancel();                                   // idempotent; before start, the call never starts
    boolean isCancelled();
}

/// Immutable, thread-safe. Only explicitly set values are ever sent.
public final class ChatRequest {
    public static ChatRequest of(String userText)                  { … }  // model: endpoint default
    public static ChatRequest of(ModelId model, String userText)   { … }
    public static Builder builder()                                { … }
    public static Builder builder(ModelId model)                   { … }

    public Optional<ModelId> model()                       { … }
    public List<Content> system()                          { … }  // top-level instructions; portable across dialects
    public List<Message> messages()                        { … }
    public List<Tool> tools()                              { … }
    public Optional<ToolChoice> toolChoice()               { … }
    public GenerationSettings settings()                   { … }
    public List<CacheBreakpoint> cacheBreakpoints()        { … }

    /// Returns a request with `response.message()` and the tool results appended — reasoning signatures
    /// and provider continuation data included — ready for the next turn.
    public ChatRequest continueWith(ChatResponse response, List<ToolResult> results) { … }
    public ChatRequest continueWith(ChatResponse response, String nextUserText)      { … }
    public ChatRequest withModel(ModelId model)            { … }
    public Builder toBuilder()                             { … }

    /// Not thread-safe. Singular methods add, plural ones replace, scalars: last write wins.
    public static final class Builder {
        public Builder model(ModelId model)                              { … }
        public Builder model(String model)                               { … }
        public Builder system(String text)                               { … }
        public Builder system(Content... parts)                          { … }
        public Builder user(String text)                                 { … }
        public Builder user(Content... parts)                            { … }
        public Builder assistant(String text)                            { … }
        public Builder message(Message message)                          { … }
        public Builder messages(List<Message> messages)                  { … }
        public Builder tool(Tool tool)                                   { … }
        public Builder tools(List<Tool> tools)                           { … }
        public Builder toolChoice(ToolChoice choice)                     { … }
        /// Marks the end of everything added so far (tools, system, messages) as a prompt-cache prefix.
        public Builder cacheBreakpoint()                                 { … }
        // Shortcuts for the most common settings; the rest via settings(…)
        public Builder temperature(double temperature)                   { … }
        public Builder maxOutputTokens(int tokens)                       { … }
        public Builder reasoning(Reasoning reasoning)                    { … }
        public Builder output(OutputFormat format)                       { … }
        public Builder output(Class<?> recordType)                       { … }
        public Builder promptCache(PromptCache promptCache)              { … }
        public <T> Builder option(OptionKey<T> key, T value)             { … }
        public Builder settings(GenerationSettings settings)             { … }
        public Builder settings(Consumer<GenerationSettings.Builder> edit) { … }
        public ChatRequest build()                                       { … }
    }
}

/// Immutable. Mergeable generation settings, used for request settings and for client defaults.
/// Merge rule, field by field: request > view > client default > nothing sent. Unset inherits; an empty
/// list is unset; `omit(Param…)` sends nothing for a parameter even when a default exists.
public final class GenerationSettings {
    public static GenerationSettings empty()        { … }
    public static Builder builder()                 { … }

    public OptionalDouble temperature()             { … }
    public OptionalDouble topP()                    { … }
    public OptionalInt topK()                       { … }
    public OptionalInt maxOutputTokens()            { … }
    public List<String> stop()                      { … }
    public OptionalLong seed()                      { … }
    public Optional<Reasoning> reasoning()          { … }
    public Optional<OutputFormat> output()          { … }
    public Optional<PromptCache> promptCache()      { … }
    public Optional<Boolean> parallelToolCalls()    { … }
    public <T> Optional<T> option(OptionKey<T> key) { … }
    public Set<Param> omitted()                     { … }
    public GenerationSettings overriddenBy(GenerationSettings higher) { … }
    public Builder toBuilder()                      { … }

    public static final class Builder {
        public Builder temperature(double value)          { … }  // range-checked against [0, 2]; dialects check narrower ranges
        public Builder topP(double value)                 { … }
        public Builder topK(int value)                    { … }
        public Builder maxOutputTokens(int tokens)        { … }  // > 0
        public Builder stop(String sequence)              { … }
        public Builder stop(List<String> sequences)       { … }
        public Builder seed(long seed)                    { … }
        public Builder reasoning(Reasoning reasoning)     { … }
        public Builder output(OutputFormat format)        { … }
        public Builder promptCache(PromptCache cache)     { … }
        public Builder parallelToolCalls(boolean allowed) { … }
        public <T> Builder option(OptionKey<T> key, T value) { … }
        public Builder omit(Param... params)              { … }
        public Builder set(String key, String raw)        { … }  // form path, parsed per the model's FieldDescriptor
        public GenerationSettings build()                 { … }
    }
}

public enum Param { TEMPERATURE, TOP_P, TOP_K, MAX_OUTPUT_TOKENS, STOP, SEED, REASONING, OUTPUT, PROMPT_CACHE, PARALLEL_TOOL_CALLS }

/// Immutable. Unset ≠ off: absent reasoning sends nothing; `off()` explicitly disables it.
public final class Reasoning {
    public static Reasoning effort(Effort effort)   { … }  // portable level; each dialect documents its mapping
    public static Reasoning budget(int tokens)      { … }  // explicit token budget where the dialect has one
    public static Reasoning off()                   { … }
    public Reasoning visible()                      { … }  // ask for reasoning text or summaries where offered
    public Optional<Effort> effort() { … }  public OptionalInt budgetTokens() { … }
    public boolean enabled() { … }          public boolean isVisible() { … }
}
public enum Effort { MINIMAL, LOW, MEDIUM, HIGH, MAX }

public enum Role { USER, ASSISTANT, TOOL }

/// Immutable. One turn with ordered content parts.
public final class Message {
    public static Message user(String text)                      { … }
    public static Message user(Content... parts)                 { … }
    public static Message assistant(String text)                 { … }
    public static Message toolResults(List<ToolResult> results)  { … }
    public static Builder builder(Role role)                     { … }
    public Role role()                  { … }
    public List<Content> content()      { … }
    public String text()                { … }  // concatenated text parts; "" if none
    public List<ToolCall> toolCalls()   { … }

    public static final class Builder {
        public Builder text(String text)           { … }  // adds a text part
        public Builder part(Content part)          { … }  // adds a part
        public Builder parts(List<Content> parts)  { … }  // replaces all parts
        public Message build()                     { … }
    }
}
```

### 7.4 Content parts

```java
package dev.llmtransport.chat;

/// Sealed, immutable content parts. Media constructed from a Path is read when the request is encoded.
/// Reasoning and Unknown parts are bound to the dialect family that produced them (`origin()`): they are
/// replayed to that family and stripped, with Warning("reasoning_stripped"), elsewhere.
public sealed interface Content permits Content.Text, Content.Image, Content.Document, Content.Audio,
        Content.Reasoning, Content.Refusal, Content.Unknown, ToolCall, ToolResult {

    static Text text(String text)                              { … }
    static Image image(Path file)                              { … }
    static Image image(URI url)                                { … }
    static Image image(byte[] data, String mediaType)          { … }
    static Document document(Path file)                        { … }
    static Document document(byte[] data, String mediaType)    { … }
    static Audio audio(byte[] data, String format)             { … }
    static Content fileRef(String providerFileId, String mediaType) { … }   // scoped to its endpoint and account

    final class Text implements Content      { public String text() { … }  public List<Citation> citations() { … } }
    final class Image implements Content     { /* source: path | url | bytes | file ref; mediaType; optional detail */ }
    final class Document implements Content  { /* source; mediaType; optional title */ }
    final class Audio implements Content     { /* bytes; format; optional transcript (outputs) */ }
    final class Reasoning implements Content {
        public Optional<String> text() { … }  public Optional<String> signature() { … }
        public JsonValue providerData() { … } public DialectId origin() { … }
    }
    final class Refusal implements Content   { public String text() { … } }
    final class Unknown implements Content   { public String type() { … }  public JsonValue raw() { … }  public DialectId origin() { … } }
    record Citation(String title, URI source, int startIndex, int endIndex) { }
}

/// Immutable. A tool call requested by the model. `id()` is synthesized when a dialect provides none.
public final class ToolCall implements Content {
    public String id() { … }  public String name() { … }
    public JsonObject arguments() { … }                        // @throws InvalidResponseException invalid_tool_arguments
    public String argumentsJson() { … }                        // raw text, always available
    public <T> T arguments(Class<T> type) { … }                // record binding
}

/// Immutable. The host's answer to one ToolCall (name and id are taken from the call).
public final class ToolResult implements Content {
    public static ToolResult of(ToolCall call, String text)            { … }
    public static ToolResult of(ToolCall call, JsonValue json)         { … }
    public static ToolResult of(ToolCall call, Object value)           { … }  // records, maps, lists, primitives
    public static ToolResult of(ToolCall call, List<Content> parts)    { … }  // text and images
    public static ToolResult error(ToolCall call, String message)      { … }
    public String callId() { … }  public String toolName() { … }
    public List<Content> content() { … }  public boolean isError() { … }
}
```

### 7.5 Response, metadata, usage and streaming

```java
package dev.llmtransport;

/// Immutable. Absent means "not reported" — never zero. Codecs normalize provider counters: inputTokens()
/// includes cache reads and writes; reasoningTokens() is part of outputTokens(); totalTokens() is reported
/// by the provider or derived only when both parts are known.
public final class Usage {
    public static Usage empty()             { … }
    public OptionalLong inputTokens()       { … }
    public OptionalLong outputTokens()      { … }
    public OptionalLong cacheReadTokens()   { … }
    public OptionalLong cacheWriteTokens()  { … }
    public OptionalLong reasoningTokens()   { … }
    public OptionalLong totalTokens()       { … }
    public JsonValue raw()                  { … }   // the provider's usage object
}

/// A non-fatal note about how a request was mapped. Codes: option_not_applicable, option_dropped,
/// option_adapted, reasoning_stripped, untested_api_version, cache_hint_ignored.
public record Warning(String code, String message) { }
```

```java
package dev.llmtransport.chat;

/// Immutable, thread-safe. Evolving model: a final class with accessors, not a record.
public final class ChatResponse {
    public Message message()               { … }  // the assistant turn; append as-is (continueWith does)
    public String text()                   { … }  // text parts only: no reasoning, refusal or tool arguments
    public List<ToolCall> toolCalls()      { … }
    public boolean hasToolCalls()          { … }
    public Optional<String> reasoningText(){ … }  // only what the provider returned
    public Optional<String> refusal()      { … }
    public FinishReason finishReason()     { … }
    public Usage usage()                   { … }
    public JsonValue json()                { … }  // parses text(); @throws InvalidResponseException output_invalid
    public <T> T parse(Class<T> type)      { … }  // binds json(); truncation and refusal throw before binding
    public List<Warning> warnings()        { … }  // dropped, adapted or inapplicable options; stripped parts
    public ResponseMetadata metadata()     { … }
    public Optional<RawResponse> raw()     { … }  // redacted status, headers and body; absent for streamed responses
}

/// Immutable. Operational facts about how the response was obtained.
public final class ResponseMetadata {
    public String requestId()                    { … }  // SDK id (events, logs, JFR)
    public String endpointId()                   { … }
    public DialectId dialect()                   { … }
    public Optional<ModelId> model()             { … }  // as reported by the provider
    public Optional<String> providerResponseId() { … }
    public Optional<String> providerRequestId()  { … }  // for support tickets
    public Optional<String> route()              { … }  // upstream reported by a gateway (for example OpenRouter)
    public int attempts()                        { … }
    public Duration latency()                    { … }
    public Optional<Duration> timeToFirstOutput(){ … }
    public boolean fromCache()                   { … }
    public Optional<RateLimits> rateLimits()     { … }  // parsed from response headers where the dialect knows them
}

public final class RateLimits {
    public OptionalLong requestsRemaining() { … }  public OptionalLong tokensRemaining() { … }
    public Optional<Instant> requestsReset() { … } public Optional<Instant> tokensReset() { … }
}

/// Open value type: dialects add reasons; the raw provider value is always preserved.
public final class FinishReason {
    public static final FinishReason STOP = of("stop"), LENGTH = of("length"), TOOL_CALLS = of("tool_calls"),
            CONTENT_FILTER = of("content_filter"), REFUSAL = of("refusal"), OTHER = of("other");
    public static FinishReason of(String raw) { … }
    public String raw()                       { … }
}

/// Single consumer and single iteration: iterator(), textDeltas() and events() are mutually exclusive
/// views of one sequence, and a second view throws IllegalStateException. Bounded: the producer is
/// back-pressured by the blocking socket read. close() is thread-safe, idempotent, cancels an unfinished
/// call and releases the connection.
@ApiStatus.NonExtendable
public interface ChatStream extends Iterable<ChatEvent>, AutoCloseable {
    @Override Iterator<ChatEvent> iterator();
    Iterable<String> textDeltas();               // text-only view; other events are still aggregated
    Stream<ChatEvent> events();                  // java.util.stream view; closing it closes this stream
    /// After completion: the aggregate, equal to the send() result. Before completion: drains the rest first.
    /// @throws IllegalStateException when accumulation was disabled or exceeded its limit
    ChatResponse response();
    Optional<ChatResponse> partial();            // non-blocking snapshot so far; never drains
    @Override void close();
}

/// Sealed stream events. Delta events are records whose shape is frozen; lifecycle events are final
/// classes that may gain accessors. Consumers keep a `default` branch: variants may be added in minors.
public sealed interface ChatEvent {
    record TextDelta(int part, String text)                         implements ChatEvent { }
    record ReasoningDelta(int part, String text)                    implements ChatEvent { }
    record ToolCallStarted(int part, String callId, String name)    implements ChatEvent { }
    record ToolCallDelta(int part, String argumentsFragment)        implements ChatEvent { }
    record ToolCallCompleted(int part, ToolCall call)               implements ChatEvent { }
    record PartCompleted(int part, Content content)                 implements ChatEvent { }  // images, audio, refusals, citations
    record Unknown(String type, JsonValue raw)                      implements ChatEvent { }
    final class Started implements ChatEvent       { public Optional<String> responseId() { … }  public Optional<ModelId> model() { … } }
    final class UsageReported implements ChatEvent { public Usage usage() { … } }
    final class Finished implements ChatEvent      { public FinishReason reason() { … }  public Usage usage() { … } }
}

/// Immutable. The result of preview(): exactly what send() would transmit, or why it would not.
public final class PreparedRequest {
    public List<String> problems()         { … }  // field paths and reasons; empty when sendable
    public boolean sendable()              { … }
    public Optional<ModelId> model()       { … }
    public DialectId dialect()             { … }
    public String method()                 { … }
    public URI uri()                       { … }
    public Headers headers()               { … }  // credentials redacted
    public Optional<JsonValue> body()      { … }  // stable key order: byte-identical to the wire
    public List<Warning> warnings()        { … }
    public String toCurl()                 { … }  // secrets replaced by environment-variable placeholders
}
```

### 7.6 Tools

```java
package dev.llmtransport.tool;

/// Sealed: portable functions, or provider-hosted tools created by provider modules.
public sealed interface Tool permits FunctionTool, ProviderTool {
    static FunctionTool function(String name, String description, Class<?> argumentsType) { … }  // schema from a record
    static FunctionTool.Builder function(String name)                                     { … }
    String name();
}

public final class FunctionTool implements Tool {
    public String name() { … }  public Optional<String> description() { … }
    public JsonSchema parameters() { … }  public boolean strict() { … }
    public static final class Builder { /* description, parameters(JsonSchema), parameters(Class<?>), strict(boolean), build() */ }
}

/// Created by provider modules, for example OpenAiTools.webSearch(), AnthropicTools.codeExecution().
/// A provider tool sent to another provider's endpoint follows the UnsupportedPolicy.
public final class ProviderTool implements Tool {
    public String namespace() { … }  public String name() { … }  public JsonObject config() { … }
}

public sealed interface ToolChoice {
    static ToolChoice auto()                { return Auto.INSTANCE; }
    static ToolChoice none()                { return None.INSTANCE; }
    static ToolChoice required()            { return Required.INSTANCE; }
    static ToolChoice only(String toolName) { return new Only(toolName); }
    enum Auto implements ToolChoice { INSTANCE }
    enum None implements ToolChoice { INSTANCE }
    enum Required implements ToolChoice { INSTANCE }
    record Only(String toolName) implements ToolChoice { }
}
```

### 7.7 Output formats and JSON

```java
package dev.llmtransport.chat;

public sealed interface OutputFormat {
    static OutputFormat text()                          { return PlainText.INSTANCE; }
    static OutputFormat json()                          { return AnyJson.INSTANCE; }
    static OutputFormat jsonSchema(JsonSchema schema)   { return new Schema("output", schema, true); }
    static OutputFormat of(Class<?> recordType)         { return new Typed(recordType); }  // schema derived at encode time
    enum PlainText implements OutputFormat { INSTANCE }
    enum AnyJson implements OutputFormat { INSTANCE }
    record Schema(String name, JsonSchema schema, boolean strict) implements OutputFormat { }
    record Typed(Class<?> type) implements OutputFormat { }                  // bound with the client's JsonMapper
}
```

```java
package dev.llmtransport.json;

/// Immutable JSON tree; the only JSON type on public signatures. Numbers keep their lexical form; objects
/// keep insertion order, so written JSON is byte-stable (prompt-cache prefixes, cache keys, fixtures).
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
    String toJson();
    String toPrettyJson();
}
public final class JsonObject implements JsonValue {        // members keep insertion order
    public Optional<JsonValue> get(String name) { … }
    public String string(String name)           { … }     // throws if absent or not a string
    public Map<String, JsonValue> members()     { … }
    /* toJson(), toPrettyJson() */
}
public final class JsonArray implements JsonValue   { public List<JsonValue> values() { … }  /* … */ }
public final class JsonString implements JsonValue  { public String value() { … }  /* … */ }
public final class JsonNumber implements JsonValue  { public BigDecimal value() { … }  public long longValue() { … }  /* … */ }
public final class JsonBoolean implements JsonValue { public boolean value() { … }  /* … */ }
public enum JsonNull implements JsonValue           { INSTANCE;  /* … */ }

/// Static entry point for JSON values and record binding with the default mapper.
public final class Json {
    public static JsonValue parse(String json)                         { … }  // bounded depth and size
    public static JsonObject object(Object... keysAndValues)            { … }  // "key", value, "key", value…
    public static JsonArray array(Object... values)                    { … }
    public static JsonValue valueOf(Object value)                      { … }  // records, enums, strings, numbers, booleans,
                                                                              // lists, maps, Optional, java.time, SDK value types
    public static <T> T convert(JsonValue json, Class<T> type)         { … }  // canonical constructors only
    public static JsonSchema schemaOf(Class<?> recordType)             { … }  // strict-compatible: all fields required,
                                                                              // Optional<T> → nullable, enums → enum
}

public final class JsonSchema {
    public static JsonSchema parse(String json)  { … }
    public static JsonSchema of(JsonObject json) { … }
    public JsonObject asJson()                   { … }
}

/// Describes a record, component or enum constant in derived schemas (sent to the model).
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD}) @Retention(RetentionPolicy.RUNTIME)
public @interface Description { String value(); }

/// SPI: replaces record binding (for example with the llm-transport-jackson module for POJOs).
public interface JsonMapper {
    JsonValue toJson(Object value);
    <T> T fromJson(JsonValue json, Class<T> type);
    JsonSchema schemaFor(Class<?> type);
}
```

SDK value types (`Message`, `ChatRequest`, `ChatResponse`, `GenerationSettings`, `Usage`, `ModelInfo`)
have a documented, versioned canonical JSON form through `Json.valueOf(…)` and `Json.convert(…)`: agents
persist conversations and workflow builders persist node outputs without writing mappers.

### 7.8 Models

```java
package dev.llmtransport.model;

@ApiStatus.NonExtendable
public interface ModelsApi {
    List<ModelInfo> list();              // cached per client for CachingOptions.modelCatalogTtl (default 1 h); network on a miss
    List<ModelInfo> refresh();           // always network; replaces the cached snapshot
    ModelInfo get(ModelId id);           // per-model endpoint where available, else the list; @throws InvalidRequestException model_not_found
    List<ModelInfo> cached();            // never I/O; may be empty or stale (see ModelInfo.fetchedAt())
}

/// Immutable. Absent or UNKNOWN means "not reported" — never "no" and never zero.
public final class ModelInfo {
    public enum Source { LIVE, BUNDLED, CONFIGURED }
    public static Builder builder(ModelId id)           { … }   // hosts describe models for gateways without listing
    public ModelId id()                                 { … }
    public Optional<String> displayName()               { … }
    public Optional<String> family()                    { … }
    public OptionalLong contextWindow()                 { … }
    public OptionalLong maxOutputTokens()               { … }
    public Set<Modality> inputModalities()              { … }
    public Set<Modality> outputModalities()             { … }
    public Capabilities capabilities()                  { … }
    public List<FieldDescriptor> parameters()           { … }   // parameter form for this model; may be empty
    public Optional<Pricing> pricing()                  { … }
    public Optional<Instant> deprecatedAt()             { … }
    public Source source()                              { … }
    public Optional<Instant> fetchedAt()                { … }
    public JsonValue raw()                              { … }
}

public enum Modality { TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT }
public enum Capability { STREAMING, TOOLS, PARALLEL_TOOLS, STRUCTURED_OUTPUT, JSON_MODE, VISION, DOCUMENTS,
                         AUDIO_INPUT, AUDIO_OUTPUT, IMAGE_OUTPUT, REASONING, PROMPT_CACHING, EMBEDDINGS }
public enum SupportLevel { SUPPORTED, UNSUPPORTED, UNKNOWN }

public final class Capabilities {
    public static Capabilities unknown()               { … }
    public SupportLevel support(Capability capability) { … }
    public Set<Capability> supported()                 { … }
}

/// Decimal prices per million tokens in one currency; a missing component is absent, never zero.
public final class Pricing {
    public Currency currency()                          { … }
    public Optional<BigDecimal> inputPerMillion()       { … }
    public Optional<BigDecimal> outputPerMillion()      { … }
    public Optional<BigDecimal> cacheReadPerMillion()   { … }
    public Optional<BigDecimal> cacheWritePerMillion()  { … }
    public Optional<BigDecimal> estimate(Usage usage)   { … }  // absent when a needed price or counter is unknown
}
```

### 7.9 Connection

```java
package dev.llmtransport.connection;

@ApiStatus.NonExtendable
public interface ConnectionApi {
    ConnectionReport test();                                          // non-billable staged check
    ConnectionReport test(Consumer<ConnectionTest.Builder> options);  // for example a specific model, or an
                                                                      // explicitly billable inference probe
    AccountInfo account();                                            // provider-reported; unsupported → unsupported_feature
    JsonObject describe();                                            // local: effective configuration, redacted
}

/// Immutable. Steps run in order and stop at the first failure; later steps are SKIPPED.
public final class ConnectionReport {
    public enum Kind { CONFIGURATION, NETWORK, AUTHENTICATION, MODEL_ACCESS, INFERENCE }
    public enum Status { PASSED, FAILED, SKIPPED, NOT_SUPPORTED }
    public boolean ok()                        { … }
    public List<Step> steps()                  { … }
    public Optional<Step> firstFailure()       { … }
    public static final class Step {
        public Kind kind() { … }  public Status status() { … }  public Optional<Duration> latency() { … }
        public String message() { … }  public Optional<LlmException> error() { … }
    }
}

public final class ConnectionTest {
    public static final class Builder {
        public Builder model(ModelId model)                   { … }   // check access to this model
        public Builder inferenceProbe(int maxOutputTokens)    { … }   // explicitly billable; off by default
        public Builder timeout(Duration timeout)              { … }   // default 15 s
        public ConnectionTest build()                         { … }
    }
}

public final class AccountInfo {
    public Optional<String> label()            { … }
    public Optional<BigDecimal> balance()      { … }
    public Optional<Currency> currency()       { … }
    public Optional<RateLimits> rateLimits()   { … }
    public JsonValue raw()                     { … }
}
```

### 7.10 Authentication

```java
package dev.llmtransport.auth;

/// What is presented to the endpoint. Never serialized with values; toString() redacts.
public sealed interface Credentials permits ApiKey, BearerToken, OAuthCredentials,
        Credentials.ClientManagedOAuth, Credentials.Dynamic, Credentials.None {
    static Credentials none()                                   { return None.INSTANCE; }
    /// OAuth managed by the client with the provider's defaults and the client's AuthOptions.
    static Credentials oauth()                                  { … }
    static Credentials oauth(OAuthConfig overrides)             { … }
    /// Host-supplied tokens (cloud identity, rotation); cached until expiry, refreshed single-flight.
    static Credentials dynamic(CredentialProvider provider)     { return new Dynamic(provider); }

    enum None implements Credentials { INSTANCE }
    final class ClientManagedOAuth implements Credentials { public Optional<OAuthConfig> overrides() { … } }
    record Dynamic(CredentialProvider provider) implements Credentials { }
}

public final class ApiKey implements Credentials {
    public static ApiKey of(String value)          { … }  // rejects blank
    public static ApiKey fromEnv()                 { … }  // the provider's apiKeyEnvVar(), resolved at client build
    public static ApiKey fromEnv(String variable)  { … }
    public static ApiKey from(Secret secret)       { … }
    public String fingerprint()                    { … }  // "sk-…a1b2"; used in logs instead of the value
}
public final class BearerToken implements Credentials { public static BearerToken of(String t) { … }  public static BearerToken from(Secret s) { … } }

/// A secret value or a reference to one. Only references are persisted.
public final class Secret {
    public static Secret of(String value)        { … }  // literal; never exported
    public static Secret env(String variable)    { … }  // "env:NAME"
    public static Secret file(Path path)         { … }  // "file:/path"
    public static Secret ref(String reference)   { … }  // "scheme:rest"; env: and file: built in, others via SecretResolver
    public Optional<String> reference()          { … }
    @Override public String toString()           { … }  // the reference, or "Secret[***]"
}

/// SPI. Resolves host-defined secret references ("vault:…", "keychain:…"). Called once per client build.
@FunctionalInterface
public interface SecretResolver { Optional<String> resolve(String reference); }

/// SPI. Supplies expiring tokens (cloud identity, host rotation). May block; the SDK caches and coalesces.
@FunctionalInterface
public interface CredentialProvider {
    AccessToken fetch() throws IOException;
    final class AccessToken {
        public static AccessToken of(Secret token)                    { … }  // no known expiry: fetched once per client
        public static AccessToken of(Secret token, Instant expiresAt) { … }  // renewed before expiry
        public Secret token() { … }  public Optional<Instant> expiresAt() { … }
    }
}

/// How a provider accepts credentials; declared by presets and validated at Endpoint.build().
public final class AuthMethod {
    public static AuthMethod bearer()                 { … }  // Authorization: Bearer …
    public static AuthMethod header(String name)      { … }  // x-api-key: …
    public static AuthMethod queryParameter(String n) { … }  // opt-in on presets; never logged
    public static AuthMethod none()                   { … }
    public static AuthMethod oauth()                  { … }
}

/// Status and interactive login for the endpoint's credentials.
@ApiStatus.NonExtendable
public interface AuthApi {
    AuthStatus status();                     // local; no I/O
    LoginSession login();                    // explicit start: may bind a loopback listener and open a browser
    LoginSession login(LoginOptions options);
    AuthStatus refresh();                    // network; single-flight
    void logout();                           // local: clears stored tokens and pending sessions; no I/O
    void revoke();                           // network revocation where supported, then logout()
}

public final class AuthStatus {
    public enum State { NOT_APPLICABLE, AUTHENTICATED, EXPIRING, LOGIN_REQUIRED }
    public State state() { … }  public Optional<Instant> expiresAt() { … }
    public Optional<String> account() { … }  public Set<String> scopes() { … }
}

/// Host-owned OAuth credentials: shareable by several clients, one per account in multi-user servers.
/// Thread-safe; close() cancels pending logins and releases listeners (stored tokens remain).
public final class OAuthCredentials implements Credentials, AuthApi, AutoCloseable {
    public static Builder builder(OAuthConfig config)     { … }
    public static Builder builder(LlmProvider provider)   { … }  // provider.oauthDefaults(), else IllegalArgumentException
    // AuthApi methods …
    @Override public void close() { … }
    public static final class Builder {
        public Builder account(String accountKey)              { … }  // part of the token-store key
        public Builder tokenStore(TokenStore store)            { … }  // default in-memory
        public Builder browserLauncher(BrowserLauncher l)      { … }  // default BrowserLauncher.system()
        public Builder refreshSkew(Duration skew)              { … }  // default 60 s
        public OAuthCredentials build()                        { … }
    }
}

/// Immutable. Provider presets supply defaults; hosts may override per endpoint. PKCE S256 is always used.
public final class OAuthConfig {
    public static Builder builder(String clientId) { … }
    public String clientId() { … }  public Optional<Secret> clientSecret() { … }  // confidential clients only
    public URI authorizationEndpoint() { … }  public URI tokenEndpoint() { … }
    public Optional<URI> deviceAuthorizationEndpoint() { … }  public Optional<URI> revocationEndpoint() { … }
    public Set<String> scopes() { … }  public Set<Grant> grants() { … }
    public enum Grant { AUTHORIZATION_CODE, DEVICE_CODE, CLIENT_CREDENTIALS }
    /// Maps non-standard token responses (OpenRouter's PKCE exchange returns {"key": …}).
    public Optional<Function<JsonObject, OAuthTokens>> tokenResponseMapper() { … }
}

/// One interactive login. Confined to one thread except cancel(), close() and acceptRedirect().
public interface LoginSession extends AutoCloseable {
    String state();                               // the OAuth state parameter: correlates external callbacks
    LoginPrompt prompt();                         // delivered only here, never through listeners
    AuthStatus await();                           // blocks until tokens are stored, timeout (LoginOptions) or cancel
    AuthStatus await(Duration timeout);
    void acceptRedirect(URI callbackUri);         // external-redirect mode: validates state and PKCE, then exchanges
    void cancel();
    @Override void close();                       // cancels if pending; stops the loopback listener
}

/// Closed set (changing it is a major version): switch over it exhaustively.
public sealed interface LoginPrompt {
    record OpenBrowser(URI url, boolean launched) implements LoginPrompt { }
    final class EnterCode implements LoginPrompt {
        public URI verificationUri() { … }  public String userCode() { … }
        public Optional<URI> verificationUriComplete() { … }  public Instant expiresAt() { … }
    }
    enum NoInteraction implements LoginPrompt { INSTANCE }      // client credentials
}

public final class LoginOptions {
    public static LoginOptions browser()                         { … }  // authorization code + PKCE + loopback (default)
    public static LoginOptions externalRedirect(URI redirectUri) { … }  // the host receives the callback; no listener
    public static LoginOptions deviceCode()                      { … }
    public LoginOptions scopes(Set<String> scopes)               { … }
    public LoginOptions timeout(Duration timeout)                { … }  // default 5 min
    public LoginOptions loopbackPort(int port)                   { … }  // for providers with fixed redirect URIs
}

/// SPI. Keys bind tokens to issuer, client and account. Implementations persist a rotated refresh token
/// atomically before it is used; some providers invalidate the previous token immediately.
public interface TokenStore {
    Optional<OAuthTokens> load(Key key);
    void save(Key key, OAuthTokens tokens);
    void delete(Key key);
    record Key(String issuer, String clientId, String account) { }
    static TokenStore inMemory()      { … }
    static TokenStore file(Path path) { … }  // JSON, owner-only permissions where supported, lock + atomic rename; not encrypted
}

/// SPI. Called only from login(); must not throw; returns whether a browser was opened.
@FunctionalInterface
public interface BrowserLauncher {
    boolean open(URI uri);
    static BrowserLauncher system() { … }  // java.awt.Desktop when available and not headless
    static BrowserLauncher none()   { return uri -> false; }
}

public final class AuthOptions {  // client-level: tokenStore, browserLauncher, secretResolver, refreshSkew
    public static final class Builder {
        public Builder tokenStore(TokenStore store) { … }  public Builder browserLauncher(BrowserLauncher l) { … }
        public Builder secretResolver(SecretResolver r) { … }  public Builder refreshSkew(Duration skew) { … }
        public AuthOptions build() { … }
    }
}

/// Immutable; toString() redacts. The access "token" may be an API key issued by OAuth (OpenRouter).
public final class OAuthTokens { /* accessToken (Secret), refreshToken, expiresAt, scopes, account, raw */ }
```

### 7.11 Events

```java
package dev.llmtransport.event;

/// Observation only. Called synchronously on the thread that produced the event, outside SDK locks;
/// must be brief; a thrown exception is logged and never affects the call. Events of one call arrive in order.
@FunctionalInterface
public interface LlmListener { void onEvent(LlmEvent event); }

/// Sealed, immutable, content-free and secret-free. Keep a `default` branch: variants may be added.
public sealed interface LlmEvent permits RequestEvent, AuthEvent {
    String endpointId();
    Instant at();
    Map<String, String> tags();
}

public sealed interface RequestEvent extends LlmEvent {
    String requestId();
    final class Started implements RequestEvent     { /* model(), operation(), streaming() */ }
    final class Sent implements RequestEvent        { /* attempt() */ }
    final class FirstOutput implements RequestEvent { /* latency() */ }
    final class Retrying implements RequestEvent    { /* attempt(), delay(), error() */ }
    final class Warned implements RequestEvent      { /* warning() */ }
    final class Completed implements RequestEvent   { /* latency(), usage(), finishReason(), fromCache(), providerRequestId() */ }
    final class Failed implements RequestEvent      { /* latency(), error(), outcomeUnknown() */ }
    final class Cancelled implements RequestEvent   { /* partialOutput() */ }
}

public sealed interface AuthEvent extends LlmEvent {
    final class LoginStarted implements AuthEvent   { /* flow() — never the URL or the user code */ }
    final class LoginSucceeded implements AuthEvent { /* account() */ }
    final class LoginFailed implements AuthEvent    { /* error() */ }
    final class TokenRefreshed implements AuthEvent { /* expiresAt() */ }
    final class RefreshFailed implements AuthEvent  { /* error(), loginRequired() */ }
    final class LoggedOut implements AuthEvent      { }
}
```

### 7.12 Configuration objects

```java
package dev.llmtransport.config;

/// Immutable. Defaults: connect 10 s · streamIdle 5 min (streams only) · total 10 min (all attempts and backoff).
public final class TimeoutPolicy {
    public static TimeoutPolicy defaults()        { … }
    public static TimeoutPolicy forLocalModels()  { … }  // connect 5 s · idle 10 min · total 30 min (model loading)
    public static Builder builder()               { … }
    public Builder toBuilder()                    { … }
    public Duration connect()  { … }  public Duration streamIdle() { … }  public Optional<Duration> total() { … }
    public static final class Builder {
        public Builder connect(Duration d)    { … }   // > 0
        public Builder streamIdle(Duration d) { … }   // > 0; reset by any received bytes, keep-alives included
        public Builder total(Duration d)      { … }   // > 0
        public Builder noTotalTimeout()       { … }   // the only way to disable; warns at build with retries enabled
        public TimeoutPolicy build()          { … }
    }
}

/// Immutable. Defaults: 3 attempts; retry on pre-send connection failures and 408, 409, 429, 500, 502, 503, 529;
/// honour Retry-After up to 60 s; exponential backoff 500 ms × 2, capped at 8 s, full jitter; never after
/// visible stream output; never after an ambiguous post-send failure (504, timeouts, resets).
public final class RetryPolicy {
    public static RetryPolicy defaults() { … }
    public static RetryPolicy none()     { … }   // exactly one attempt
    public static Builder builder()      { … }
    public Builder toBuilder()           { … }
    public int maxAttempts() { … }  public Set<Integer> retryOnStatus() { … }  public Duration maxRetryAfter() { … }
    public static final class Builder {
        public Builder maxAttempts(int attempts)                 { … }   // ≥ 1
        public Builder retryOnStatus(Set<Integer> statuses)      { … }
        public Builder backoff(Duration initial, double multiplier, Duration max) { … }
        public Builder maxRetryAfter(Duration max)               { … }
        public RetryPolicy build()                               { … }
    }
}

public enum UnsupportedPolicy { FAIL, DROP_WITH_WARNING }
```

```java
package dev.llmtransport.http;

/// Options for the default JDK transport; rejected at build when a transport is injected.
public final class HttpOptions {
    public static final class Builder {
        public Builder transport(HttpTransport transport)            { … }  // injected: borrowed, never closed
        public Builder httpVersion(HttpClient.Version version)       { … }  // default HTTP_2; HTTP_3 opt-in (JDK 26)
        public Builder proxy(ProxySelector proxy)                    { … }
        public Builder trustStore(Path file, char[] password)        { … }
        public Builder clientCertificate(Path pkcs12, char[] password) { … }
        public Builder insecureSkipTlsVerification()                 { … }  // explicit name; logs a warning at build
        public Builder userAgentSuffix(String suffix)                { … }
        public Builder wireLog(WireLog level)                        { … }  // default OFF; always redacted
        public HttpOptions build()                                   { … }
    }
}
public enum WireLog { OFF, HEADERS, BODIES }
```

### 7.13 Cache types

```java
package dev.llmtransport.cache;

/// Immutable. Provider-side prompt caching for a request (§11.2).
public final class PromptCache {
    public static PromptCache auto()               { … }  // SDK places breakpoints where the dialect needs markers
    public static PromptCache key(String cacheKey) { … }  // routing hint where supported (OpenAI prompt_cache_key)
    public static PromptCache disabled()           { … }  // strip markers; ask providers not to cache where possible
    public PromptCache retention(Duration ttl)      { … }  // mapped to the nearest supported value, never silently longer
    public PromptCache automaticBreakpoints()      { … }
}

public sealed interface CacheBreakpoint {
    enum AfterTools implements CacheBreakpoint { INSTANCE }
    enum AfterSystem implements CacheBreakpoint { INSTANCE }
    record AfterMessage(int index) implements CacheBreakpoint { }
}

public enum CacheMode { READ_WRITE, REFRESH, BYPASS, OFFLINE }

/// Client-level cache settings.
public final class CachingOptions {
    public static final class Builder {
        public Builder responses(ResponseCache cache)      { … }  // default: none (response caching off)
        public Builder mode(CacheMode mode)                { … }  // default READ_WRITE when a cache is set
        public Builder modelCatalogTtl(Duration ttl)       { … }  // default 1 h
        public Builder maxStreamAccumulation(long bytes)   { … }  // default 32 MiB; 0 disables accumulation
        public CachingOptions build()                      { … }
    }
}

/// SPI. Exact-match response cache. Thread-safe. Values are complete, successful exchanges only.
public interface ResponseCache {
    Optional<CachedExchange> get(CacheKey key);
    void put(CacheKey key, CachedExchange exchange);
    default void remove(CacheKey key) { }
    default void clear() { }
    static ResponseCache inMemory(int maxEntries)                 { … }  // LRU
    static ResponseCache inMemory(int maxEntries, Duration ttl)   { … }
    static ResponseCache directory(Path directory)                { … }  // one readable JSON file per entry; test cassettes
}

public record CacheKey(String hash, String description) { }  // description: method, path and a body digest, no secrets

/// Immutable. A recorded exchange: status, redacted headers, and either the body or the stream frames.
public final class CachedExchange { /* status(), headers(), body(): Optional<byte[]>, frames(): List<WireFrame>, storedAt() */ }
```

### 7.14 Errors

```java
package dev.llmtransport;

/// Unchecked root. Messages are for people; callers branch on type and code(). Causes are kept;
/// no third-party exception leaks. Failure atomicity: a failed call leaves the client usable.
public class LlmException extends RuntimeException {
    public LlmException(Details details)                  { … }
    public LlmException(Details details, Throwable cause) { … }
    public Details details()                     { … }
    public ErrorCode code()                      { … }  // shortcuts delegating to details()
    public boolean is(ErrorCode code)            { … }
    public boolean retryable()                   { … }  // the SDK's assessment after its own retries
    public boolean outcomeUnknown()              { … }  // the request may have executed (and billed) remotely
    public OptionalInt httpStatus()              { … }
    public Optional<String> providerCode()       { … }
    public Optional<String> providerRequestId()  { … }
    public Optional<String> requestId()          { … }  // SDK id; absent for local validation errors
    public Optional<String> endpointId()         { … }
    public int attempts()                        { … }
    public Optional<JsonValue> errorBody()       { … }  // bounded and redacted

    /// Immutable error facts. Codecs return them from ChatCodec.decodeError; the core selects the exception
    /// type from the code, adds call facts (request id, endpoint, attempts, outcome) and throws.
    public static final class Details {
        public static Builder builder(ErrorCode code, String message) { … }
        public Builder toBuilder() { … }
        /* code(), message(), httpStatus(), providerCode(), providerRequestId(), retryAfter(), retryable(),
           outcomeUnknown(), requestId(), endpointId(), attempts(), errorBody(); Builder: one setter per field */
    }
}

public final class RateLimitedException extends LlmException {
    public RateLimitedException(Details details) { … }
    public Optional<Duration> retryAfter()       { … }
}
// AuthenticationException, InvalidRequestException, ProviderException, TransportException,
// RequestTimeoutException and RequestCancelledException have the same constructor shape;
// InvalidResponseException also accepts the ChatResponse it could not bind (response()).

/// Open value type with well-known constants; dialects add codes.
public final class ErrorCode {
    public static final ErrorCode INVALID_REQUEST, UNSUPPORTED_FEATURE, CONTEXT_LENGTH_EXCEEDED, MODEL_NOT_FOUND,
            REQUEST_TOO_LARGE, INVALID_CREDENTIALS, LOGIN_REQUIRED, REFRESH_FAILED, PERMISSION_DENIED, RATE_LIMITED,
            QUOTA_EXHAUSTED, OVERLOADED, SERVER_ERROR, CONNECT_FAILED, STREAM_INTERRUPTED, OUTCOME_UNKNOWN,
            MALFORMED_RESPONSE, OUTPUT_INVALID, OUTPUT_TRUNCATED, OUTPUT_REFUSED, DEADLINE_EXCEEDED,
            STREAM_IDLE_TIMEOUT, CANCELLED, CACHE_MISS;
    public static ErrorCode of(String code) { … }
    public String value() { … }
}
```

| Exception | Typical codes | Typical HTTP | Retried by default |
|---|---|---|---|
| `InvalidRequestException` | `invalid_request`, `unsupported_feature`, `context_length_exceeded`, `model_not_found`, `request_too_large` | 400, 404, 413, 422 | no |
| `AuthenticationException` | `invalid_credentials`, `login_required`, `refresh_failed`, `permission_denied` | 401, 403 | once after a forced OAuth refresh on 401 |
| `RateLimitedException` (+ `retryAfter()`) | `rate_limited`, `quota_exhausted` | 429, 402 | `rate_limited` yes |
| `ProviderException` | `overloaded`, `server_error` | 500, 502, 503, 529 | yes |
| `TransportException` | `connect_failed`, `stream_interrupted`, `outcome_unknown` | — | before send only |
| `InvalidResponseException` (+ `response()`) | `malformed_response`, `output_invalid`, `output_truncated`, `output_refused` | 200 | no |
| `RequestTimeoutException` | `deadline_exceeded`, `stream_idle_timeout` | 408, 504 | 408 yes; after send no |
| `RequestCancelledException` | `cancelled` | — | no |

Local programmer misuse (a closed client, a second start of a call, a second stream iterator) throws
`IllegalStateException`; invalid builder input throws `IllegalArgumentException` naming every invalid
field. The testing artifact ships `LlmErrors` factories so host tests can construct every exception.

---

## 8. SPI, provider modules and extension

Every SPI type has at most three abstract methods, uses library-owned or JDK types only, grows only
through `default` methods, ships a default implementation and a test double, and documents five
contract rows: thread, ordering, error isolation, blocking, lifecycle (§8.4).

### 8.1 Dialects and codecs — the only way to add a wire format

```java
package dev.llmtransport.spi;

/// Stateless, thread-safe. One wire protocol at one revision. Constants live in provider modules
/// (OpenAi.RESPONSES, Anthropic.MESSAGES); a new incompatible revision is a new constant.
public interface Dialect {
    DialectId id();
    ChatCodec chat();
    default Optional<ModelListCodec> models()  { return Optional.empty(); }
    default Optional<AccountCodec> account()   { return Optional.empty(); }
}

/// Pure functions: no I/O, credentials, retries or clocks. Thread-safe.
public interface ChatCodec {
    /// Inexpressible settings: throw InvalidRequestException(unsupported_feature), or, under
    /// DROP_WITH_WARNING, omit them and call ctx.warn(…). Returns a request with a relative URI.
    WireRequest encode(ChatRequest request, EncodeContext ctx);
    ChatResponse decode(WireResponse response, DecodeContext ctx);
    StreamDecoder newStreamDecoder(DecodeContext ctx);                        // one per stream
    default StreamFormat streamFormat() { return StreamFormat.SSE; }
    /// Maps a non-2xx response to error facts (code, provider code, retry hint, Retry-After); the core picks
    /// the exception type and adds call facts, so every dialect's errors look alike. The default covers HTTP semantics.
    default LlmException.Details decodeError(WireResponse response, DecodeContext ctx) { … }
}

/// Confined to the consuming thread; frames in arrival order, events in emission order.
public interface StreamDecoder {
    List<ChatEvent> onFrame(WireFrame frame);   // none for keep-alives, several for composite frames
    List<ChatEvent> onEnd();                    // server closed: emit Finished exactly once, or throw unexpected_stream_end
}

public interface ModelListCodec {
    WireRequest encode(Optional<String> pageToken, EncodeContext ctx);
    Page decode(WireResponse response, DecodeContext ctx);
    record Page(List<ModelInfo> models, Optional<String> nextPageToken) { }
}

public interface AccountCodec {
    WireRequest encode(EncodeContext ctx);
    AccountInfo decode(WireResponse response, DecodeContext ctx);
}

/// What a codec may read. Options are pre-scoped: only keys applicable to this dialect or provider appear.
public interface EncodeContext {
    Endpoint endpoint();
    ModelId model();
    boolean streaming();
    UnsupportedPolicy policy();
    <T> T quirk(QuirkKey<T> key);                      // preset value or the key's default
    <T> Optional<T> option(OptionKey<T> key);
    JsonMapper jsonMapper();
    void warn(Warning warning);
}

public interface DecodeContext {
    Endpoint endpoint();
    ModelId requestedModel();
    JsonMapper jsonMapper();
    void warn(Warning warning);
}

public final class WireFrame { /* event(): Optional<String>, data(): String, id(): Optional<String> — one SSE event or NDJSON line */ }
public enum StreamFormat { SSE, NDJSON }             // binary event streams arrive with the module that needs them
```

| Item | `Dialect`, `ChatCodec`, metadata codecs | `StreamDecoder` |
|---|---|---|
| Thread | any; stateless | the consuming thread only |
| Ordering | — | frames in arrival order; events in emission order |
| Error isolation | encode exceptions propagate as `InvalidRequestException`; decode exceptions become `InvalidResponseException(malformed_response)` with the cause | a throw ends the stream with `InvalidResponseException`; delivered events stay valid |
| Blocking | never | never |
| Lifecycle | singleton per constant | one per stream; `onEnd()` at most once |

The codec contract kit (`DialectContract`, §14.1) pins these rules plus the portable behaviours of §16:
unknown fields and parts preserved, tool-call ids stable, usage normalized, reasoning replayed only to
its origin, `Finished` exactly once, chunk splits at arbitrary byte and UTF-8 boundaries.

### 8.2 HTTP transport and interceptors

```java
package dev.llmtransport.http;

/// SPI. Thread-safe. Default: HttpTransport.jdk(). Injected transports are borrowed, never closed by clients.
public interface HttpTransport extends AutoCloseable {
    /// Blocks until response headers arrive and returns a response whose body the caller consumes and
    /// closes. Must abort promptly when the calling thread is interrupted and when the response is closed early.
    WireResponse send(WireRequest request, TransportOptions options) throws IOException;
    @Override default void close() { }
    static HttpTransport jdk()                    { … }
    static HttpTransport jdk(HttpOptions options) { … }
}

/// SPI. Runs once per attempt, inside retries, after credentials are applied; registration order on the
/// way in, reverse order on the way out. May add headers or sign; may short-circuit deliberately.
/// Exceptions propagate and are translated. Streaming bodies pass through unread.
public interface WireInterceptor {
    WireResponse intercept(Chain chain) throws IOException;
    interface Chain {
        WireRequest request();
        Endpoint endpoint();
        String requestId();
        WireResponse proceed(WireRequest request) throws IOException;
    }
}

public final class WireRequest  { /* method(), uri() (relative from codecs, absolute after resolution), headers(),
                                     body(): Optional<JsonValue> or bytes, toBuilder() — immutable */ }
public final class WireResponse implements AutoCloseable { /* status(), headers(), body(): InputStream (single use),
                                     bytes(), json(), close() releases or aborts */ }
public final class Headers      { /* of(Map), first(name): Optional<String>, all(name): List<String>, names();
                                     immutable, case-insensitive; toString() redacts credential headers */ }
public final class TransportOptions { /* connectTimeout(), responseTimeout(), httpVersion() */ }

/// An experimental escape hatch: the endpoint's credentials, deadlines, retries, interceptors and events
/// apply; paths are relative to the endpoint base URL and cannot leave its origin.
@ApiStatus.Experimental
public interface RawApi {
    RawResponse get(String path);
    RawResponse post(String path, JsonValue body);
}
public final class RawResponse { /* status(), headers() (redacted), json(), text() */ }
```

`IOException` is allowed on transport SPIs because it is natural for implementers; the core translates
it to `TransportException` and classifies it as *before send* (retryable) or *after send* (outcome
unknown). After the interceptor chain, the core re-validates that the destination is still the
endpoint's origin; interceptors cannot redirect credentials elsewhere. `JdkHttpTransport` negotiates
HTTP/2, forces HTTP/1.1 for cleartext loopback servers (no `h2c` upgrade surprises with local runtimes),
enforces the idle limit through the stream watchdog (the JDK client has no read timeout), and offers
HTTP/3 when configured.

### 8.3 Host-fed dependencies

| SPI | Abstract methods | Shipped defaults | Second implementation path | Registered via |
|---|---|---|---|---|
| `HttpTransport` | 1 | `jdk()` | OkHttp module, `ScriptedTransport` | `http(h -> h.transport(…))` |
| `WireInterceptor` | 1 | — | SigV4 signing module, tracing headers | `interceptor(…)` |
| `TokenStore` | 3 | `inMemory()`, `file(Path)` | keychain module, encrypted database | `auth(a -> a.tokenStore(…))`, `OAuthCredentials.Builder` |
| `BrowserLauncher` | 1 | `system()`, `none()` | IDE or desktop-toolkit launchers | `auth(a -> a.browserLauncher(…))` |
| `SecretResolver` | 1 | `env:` and `file:` built in | vault, OS keychain | `auth(a -> a.secretResolver(…))` |
| `CredentialProvider` | 1 | — | Azure Entra, Google ADC, host rotation | `Credentials.dynamic(…)` |
| `ResponseCache` | 2 | `inMemory(…)`, `directory(Path)` | Redis or database caches | `caching(c -> c.responses(…))` |
| `JsonMapper` | 3 | record binder | Jackson module | `jsonMapper(…)` |
| `ProviderBundle` | 1 | one per provider module | host bundles for private gateways | `META-INF/services` |

### 8.4 Contract rows every extension documents

| Extension | Thread | Ordering | Error isolation | Blocking | Lifecycle |
|---|---|---|---|---|---|
| `LlmListener` | producing thread, outside SDK locks | per call, in order | caught, logged, ignored | must be brief; slows the caller | builder, `addListener`, view |
| `WireInterceptor` | calling thread, per attempt | registration order in, reverse out | propagates, translated | allowed within the deadline | client lifetime |
| `HttpTransport` | any; thread-safe | — | `IOException` translated | yes; interruptible | owned if SDK-created, else borrowed |
| `TokenStore` | any; thread-safe | — | propagates as `AuthenticationException` | short I/O allowed | host-owned |
| `CredentialProvider` | refresh thread, single-flight | — | becomes `credentials_unavailable`, not retried | allowed within the deadline | host-owned |
| `ResponseCache` | any; thread-safe | — | failures logged; the call proceeds uncached | short I/O allowed | host-owned |
| `BrowserLauncher` | the `login()` caller | — | must not throw; `false` means "not opened" | must return quickly | host-owned |

### 8.5 Provider APIs — typed provider-only operations

```java
package dev.llmtransport.spi;

/// A typed, provider-only API (files, batches, cached contents), declared as a constant by a provider module.
public final class ProviderApi<T> {
    public static <T> ProviderApi<T> of(String namespace, String name, Class<T> type,
                                        Function<ProviderApiContext, T> factory) { … }
    public String namespace() { … }  public String name() { … }  public Class<T> type() { … }
}

/// Core execution for provider APIs: every exchange gets the client's credentials, deadline, cancellation,
/// retry classification, redaction and events. Only relative URIs under the endpoint are accepted.
public interface ProviderApiContext {
    Endpoint endpoint();
    JsonMapper jsonMapper();
    WireResponse exchange(WireRequest request, Replay replay);
    enum Replay { SAFE, UNSAFE }                     // SAFE permits retries (reads, idempotent writes)
}
```

`llm.providerApi(Gemini.CACHES)` validates that the key's namespace matches the endpoint's dialect
family or provider (otherwise `IllegalStateException` naming both), creates the API once per client,
and returns it without I/O. A provider API follows the facade's own rules: blocking methods with
explicit I/O verbs, closeable handles for anything with a lifetime, and documented side effects and
retention. Closing a client never deletes remote resources.

### 8.6 Extension ladder applied

| Need | Rung | Mechanism |
|---|---|---|
| Timeouts, retries, proxy, TLS, HTTP version, caching, unsupported handling | 1 — built-in option | `TimeoutPolicy`, `RetryPolicy`, `HttpOptions`, `CachingOptions`, `UnsupportedPolicy` |
| A provider speaking an existing dialect | 1 — data | `LlmProvider.builder(…)` with quirks; optional `ProviderBundle` |
| Another HTTP stack, token store, secret vault, browser, cloud identity, JSON binder, cache store | 2 — host-fed dependency | §8.3 |
| Progress, metrics, tracing, audit | 3 — observation | `LlmListener`, JFR |
| Headers, request signing, wire logging to a custom sink | 4 — interception | `WireInterceptor` |
| Provider-only fields, hosted tools, native payloads, provider-only operations | 5 — escape hatch | `OptionKey<T>`, `ProviderTool`, `response.raw()`, `providerApi(…)`, `raw()` |
| A new wire format | SPI | `Dialect` + `ChatCodec` + `StreamDecoder`, passing `DialectContract` |

Not offered: a generic plugin registry, callback-style streaming, raw SSE lines on the main API, or
subclassing of `LlmClient`.

### 8.7 Provider modules

```java
package dev.llmtransport.openai;

public final class OpenAi {
    public static final Dialect RESPONSES;              // default for OpenAi.PROVIDER
    public static final Dialect CHAT_COMPLETIONS;       // also the compatibility dialect for most gateways
    public static final LlmProvider PROVIDER;
    private OpenAi() { }
}
public final class OpenAiOptions {                      // namespace "openai"
    public static final OptionKey<String>     ORGANIZATION, PROJECT;                         // endpoint level
    public static final OptionKey<Boolean>    STORE;
    public static final OptionKey<String>     SERVICE_TIER, REASONING_SUMMARY, PREVIOUS_RESPONSE_ID, SAFETY_IDENTIFIER;
    public static final OptionKey<JsonObject> EXTRA_BODY;                                    // merged last; protected fields rejected
}
public final class OpenAiQuirks {                       // set on presets, read by the Chat Completions codec
    public static final QuirkKey<String>  MAX_TOKENS_FIELD;          // "max_completion_tokens" | "max_tokens"
    public static final QuirkKey<Boolean> STREAM_USAGE, DEVELOPER_ROLE, STRICT_SCHEMA, TOOL_CALL_STREAMING;
    public static final QuirkKey<String>  REASONING_FORMAT;          // "reasoning_effort" | "deepseek" | "qwen_enable_thinking" | "openrouter" | "none"
}
public final class OpenAiTools {
    public static ProviderTool webSearch()                              { … }
    public static ProviderTool fileSearch(List<String> vectorStoreIds)  { … }
    public static ProviderTool codeInterpreter()                        { … }
    public static ProviderTool remoteMcp(String label, URI serverUrl)   { … }
}
public final class OpenAiCompatible {                   // presets are data; CHAT_COMPLETIONS unless noted
    public static final LlmProvider OPENROUTER, DEEPSEEK, XAI, QWEN, MISTRAL, GROQ, OLLAMA, LM_STUDIO, VLLM, LITELLM, AZURE_OPENAI;
    public static LlmProvider custom(String id, URI baseUrl) { … }   // bearer auth, conservative quirks
}
```

The Anthropic and Gemini modules follow the same shape:

```java
package dev.llmtransport.anthropic;

public final class Anthropic { public static final Dialect MESSAGES; public static final LlmProvider PROVIDER; }
public final class AnthropicOptions {                   // namespace "anthropic"
    public static final OptionKey<Integer>      THINKING_BUDGET;
    public static final OptionKey<List<String>> BETA;               // OptionKey.listOf("anthropic", "beta", String.class)
    public static final OptionKey<String>       API_VERSION;        // pinned by the dialect revision; overriding warns
    public static final OptionKey<JsonObject>   EXTRA_BODY;
}
public final class AnthropicTools {                     // hosted tools as ProviderTool values
    public static ProviderTool webSearch(int maxUses) { … }  public static ProviderTool codeExecution() { … }
    public static ProviderTool bash() { … }  public static ProviderTool textEditor() { … }
    public static ProviderTool computerUse(int displayWidth, int displayHeight) { … }
}
```

```java
package dev.llmtransport.gemini;

public final class Gemini {
    public static final Dialect GENERATE_CONTENT;
    public static final LlmProvider PROVIDER;
    public static final ProviderApi<GeminiCaches> CACHES;             // explicit cached contents
}
public final class GeminiOptions {                      // namespace "gemini"
    public static final OptionKey<Integer>    THINKING_BUDGET;
    public static final OptionKey<Boolean>    INCLUDE_THOUGHTS;
    public static final OptionKey<JsonArray>  SAFETY_SETTINGS;
    public static final OptionKey<String>     CACHED_CONTENT;       // a CachedContent.name()
    public static final OptionKey<JsonObject> EXTRA_BODY;
}
public final class GeminiTools { public static ProviderTool googleSearch() { … }  public static ProviderTool codeExecution() { … }
                                 public static ProviderTool urlContext() { … } }

/// Remote resources with their own lifetime; closing a client never deletes them.
public interface GeminiCaches {
    CachedContent create(ModelId model, List<Message> contents, Duration ttl);
    CachedContent get(String name);
    List<CachedContent> list();
    CachedContent extend(String name, Duration ttl);
    void delete(String name);
}
public final class CachedContent { /* name(), model(), expiresAt(), usage() */ }
```

The aggregate artifact adds one index:

```java
package dev.llmtransport.providers;

public final class Providers {
    public static final LlmProvider OPENAI, ANTHROPIC, GEMINI, OPENROUTER, DEEPSEEK, XAI, QWEN, MISTRAL, GROQ,
            OLLAMA, LM_STUDIO, VLLM, LITELLM, AZURE_OPENAI;
    public static LlmProvider custom(String id, URI baseUrl, Dialect dialect) { … }
    public static ProviderRegistry registry() { … }      // every bundled preset, as an immutable registry
    private Providers() { }
}
```

| Preset | Default base URL | Dialects (default first) | Auth | Key variable | Notes |
|---|---|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | Responses, Chat Completions | bearer | `OPENAI_API_KEY` | |
| `anthropic` | `https://api.anthropic.com/v1` | Messages | `x-api-key` + version header | `ANTHROPIC_API_KEY` | `max_tokens` required: documented dialect default, visible in `preview()` |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` | generateContent | `x-goog-api-key` | `GEMINI_API_KEY` | tool-call ids synthesized; explicit caches via `Gemini.CACHES` |
| `openrouter` | `https://openrouter.ai/api/v1` | Chat Completions | bearer; OAuth PKCE issuing a key | `OPENROUTER_API_KEY` | attribution headers as options; `route()` reported |
| `deepseek` | `https://api.deepseek.com/v1` | Chat Completions, Messages (Anthropic-compatible path) | bearer | `DEEPSEEK_API_KEY` | reasoning content quirk |
| `xai` | `https://api.x.ai/v1` | Chat Completions, Responses | bearer | `XAI_API_KEY` | |
| `qwen` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | Chat Completions | bearer | `DASHSCOPE_API_KEY` | `enable_thinking` quirk |
| `mistral`, `groq` | provider `/v1` roots | Chat Completions | bearer | `MISTRAL_API_KEY`, `GROQ_API_KEY` | strict-schema quirk where unsupported |
| `ollama`, `lm-studio`, `vllm` | `http://127.0.0.1:11434/v1`, `:1234/v1`, `:8000/v1` | Chat Completions (Ollama native later) | none (vLLM optional bearer) | — | use `TimeoutPolicy.forLocalModels()` |
| `litellm` | host-supplied | Chat Completions | bearer | — | |
| `azure-openai` | `https://<resource>.openai.azure.com/openai/v1` | Responses, Chat Completions | `api-key` header or Entra bearer via `Credentials.dynamic` | `AZURE_OPENAI_API_KEY` | |

Preset data is volatile: URLs, headers, variables and quirks are re-verified against provider
documentation when a module is released, pinned by fixtures, and versioned with the module. No preset
is shipped for consumer-subscription logins (ChatGPT, Claude Pro/Max, Copilot, Gemini CLI): their tokens
are not documented for third parties; the generic `OAuthConfig` lets an authorized host configure
them.

### 8.8 Recipes

| Task | Steps | Code |
|---|---|---|
| Provider on an existing dialect | `LlmProvider.builder(id, dialect)` with base URL, auth method, key variable, quirks, form fields; optionally a `ProviderBundle` | none beyond the preset |
| New wire format | implement `Dialect`, `ChatCodec`, `StreamDecoder` in `…/internal`; expose `public static final Dialect` and a preset; extend `DialectContract` with golden fixtures | one module |
| New API revision (for example Responses v2) | add a new dialect constant sharing code where possible; declare it on presets; switch a preset's default in a minor release; deprecate the old constant, remove it in a major | codec delta only |
| Provider-only request field or hosted tool | `OptionKey` constants or `ProviderTool` factories in the provider module; the codec reads them | provider module only |
| Provider-only operation | a `ProviderApi<T>` constant implemented against `ProviderApiContext` | provider module only |

The portable model (`ChatRequest`, `Content`, `ChatEvent`) never changes for a provider addition;
provider-only concepts travel through `OptionKey`, `ProviderTool`, `providerData()` and `Unknown(raw)`.
Promotion to the portable model requires two dialects with the same meaning.

---

## 9. Configuration model

### 9.1 Scopes and precedence

```
Endpoint                      LlmClient.Builder             defaults(…)                 withOptions(…)           ChatRequest
(persistable, per backend)    (policy, per client)          (GenerationSettings)         (CallOptions, per view)  (per call)
├─ provider                   ├─ timeouts                   ├─ temperature, topP, topK   ├─ timeout               ├─ model
├─ id                         ├─ retry                      ├─ maxOutputTokens, stop     ├─ retry                 ├─ system, messages
├─ baseUrl                    ├─ http (options | transport) ├─ seed, reasoning, output   ├─ cacheMode             ├─ tools, toolChoice
├─ dialect                    ├─ auth                       ├─ promptCache               ├─ unsupported           ├─ cacheBreakpoints
├─ credentials                ├─ caching                    ├─ parallelToolCalls         ├─ listener*             └─ settings (same fields
├─ headers                    ├─ unsupported                └─ option(key, value)        ├─ tag*                     as defaults, + omit)
├─ defaultModel               ├─ listener*, interceptor*                                 └─ header*
└─ option(key, value)         ├─ executor, jsonMapper, clock
                              └─ build()  validates, no I/O
```

- **Generation settings**, field by field: request → client defaults → a dialect-required value (for
  example Anthropic's mandatory `max_tokens`, documented and visible in `preview()`) → nothing sent.
  `omit(Param…)` at a higher scope suppresses a lower one.
- **Operational policy**: view → client → SDK default. A view may shorten a deadline or narrow
  behaviour; it cannot change endpoint, credentials, destination or account.
- **Model**: request → endpoint default → `InvalidRequestException` naming both places.
- **Headers**: endpoint → view, case-insensitive. Credential, content-type and protocol-version headers
  are SDK-controlled; setting them is rejected at build with a pointer to the right setting.
- **Provider options**: request → client defaults → endpoint; then scoped to the dialect (§16.2).
- **Collections**: singular methods add, plural methods replace, an empty list means "inherit".
- **Environment**: read only through `ApiKey.fromEnv(…)` and `Secret.env(…)`, resolved once at client
  build; a missing variable fails with its name. Nothing else reads environment variables or system
  properties.
- **Snapshots**: built clients never observe later builder edits. A configuration reload builds new
  clients; calls in flight keep their configuration.

### 9.2 Defaults (documented; changing one is a behavioural change with a changelog entry)

| Setting | Default | Rationale |
|---|---|---|
| Transport | JDK `HttpClient`, HTTP/2 (HTTP/1.1 for cleartext loopback), one per client | Zero dependencies; connection reuse |
| Connect timeout | 10 s | Fail fast on unreachable hosts |
| Stream idle | 5 min, streams only, reset by any bytes | Detect dead connections without killing silent reasoning phases |
| Total deadline | 10 min per call: credentials, attempts, backoff, and stream consumption | Matches common provider SDK practice; long jobs raise it explicitly |
| Retries | 3 attempts on pre-send failures and 408, 409, 429, 500, 502, 503, 529; `Retry-After` ≤ 60 s; backoff 500 ms × 2, cap 8 s, full jitter | Recovers from documented not-processed responses only |
| Unsupported settings | `FAIL` | No silent semantic change; UIs switching models opt into `DROP_WITH_WARNING` |
| Sampling parameters | none sent | Provider defaults apply; unset ≠ default |
| Response cache | off | Caching model output is a product decision |
| Model catalog TTL | 1 h | Pickers stay responsive; `refresh()` is explicit |
| Stream accumulation limit | 32 MiB | Bounded memory for `response()` |
| Token store | in memory | Nothing touches disk unless asked |
| Browser launcher | `system()` when a desktop is available, otherwise none | Opens only as part of an explicit `login()` |
| TLS | verification on; redirects off | Credentials never follow redirects |
| Logging | `System.Logger`; no bodies, prompts, outputs or secrets; wire log `OFF` | Privacy by default |
| JFR | events emitted; recorded only when a recording is active | Near-zero cost |
| Metadata checks | `connection().test()` 15 s, no inference | Non-billable by default |

### 9.3 Footgun probes

| Probe | Decision |
|---|---|
| `Duration.ZERO` or negative timeouts | Rejected at the setter; `noTotalTimeout()` is the named opt-out |
| `maxAttempts(0)` | Rejected; `RetryPolicy.none()` means one attempt |
| Many retries with `noTotalTimeout()` | Warned at build |
| `ApiKey.of("")`, blank ids, blank model ids | Rejected at the call site |
| No credentials on a provider that requires them | Rejected at build, listing the accepted methods and the key variable |
| OAuth credentials without provider defaults or an `OAuthConfig` | Rejected at build |
| `http://` to a non-loopback host | Allowed with a build warning; credentials never sent over it unless `allowInsecureCredentials()` is set on the endpoint |
| Query-parameter keys | Only when a preset declares them; never in logs, events, previews or errors |
| Literal secrets in `Endpoint.toJson()` | Refused, naming the field and suggesting `Secret.env`/`Secret.ref` |
| Protected headers in `headers()` or `EXTRA_BODY` touching auth, model, or stream fields | Rejected |
| `CacheMode.OFFLINE` without a response cache | Rejected at build |
| `insecureSkipTlsVerification()` | Explicit name; warning at build and in `describe()` |
| Confusable parameters | `ModelId` vs text, `ApiKey` vs `OptionKey<String>`, `Duration` everywhere, no positional booleans on public methods |

### 9.4 Portable configuration and secrets

- `Endpoint.toJson()` / `Endpoint.fromJson(json, registry)` use a versioned schema
  (`llm-transport.endpoint/1`, example in §4.10). Unknown provider ids fail with the known ids; unknown
  fields fail unless prefixed `x-` (host extensions); no classes are loaded by name.
- Credentials persist only as references: `env:NAME`, `file:/path`, or host schemes such as `vault:…`
  and `keychain:…` resolved by a `SecretResolver`. OAuth endpoints persist the OAuth configuration
  and account key, never tokens.
- The core reads only this JSON. YAML, `.properties`, Spring Boot and similar formats live in optional
  modules that map onto the same builders (`llm-transport-config`, `llm-transport-spring-boot`).

### 9.5 Forms for UIs

`LlmProvider.connectionFields()` and `ModelInfo.parameters()` return the same `FieldDescriptor` type,
so one renderer draws connection forms and parameter forms. `Endpoint.Builder.set(key, raw)` and
`GenerationSettings.Builder.set(key, raw)` accept canonical strings (`.` decimals, ISO-8601
durations, JSON for `JSON` fields), route them to the typed setters, and `build()` reports every
invalid field with its key. Hosts parse locale-specific input before calling `set`. Rendering a form,
reading descriptors or validating performs no I/O.

---

## 10. Execution contracts

### 10.1 Sync (primary)

`send()` blocks until a response, the total deadline or cancellation. Retries happen inside; the caller
sees one outcome. Interrupting the calling thread aborts the transport, throws
`RequestCancelledException` with the `InterruptedException` as cause, and re-asserts the interrupt flag.
Clients and sub-APIs are thread-safe; many virtual threads may call one client concurrently.

### 10.2 Timeouts

| Timeout | Applies to | Enforcement |
|---|---|---|
| `connect` | Each connection establishment | `HttpClient` connect timeout |
| `streamIdle` | Streams only: time without any received bytes, keep-alives included | Watchdog closes the body; `RequestTimeoutException(stream_idle_timeout)`, `outcomeUnknown = true` |
| `total` | Whole call: credential acquisition, every attempt and backoff, the non-streamed response, and stream consumption until the terminal event | Deadline carried in the call context; `RequestTimeoutException(deadline_exceeded)` |

There is deliberately no "first byte" timeout: a non-streamed response delivers its headers only after
generation finishes, so such a timeout would abort long reasoning calls (finding B2). For very long
generations, prefer `stream(…)` with a raised `total`: the idle watchdog then detects dead connections
early, and intermediaries see traffic.

### 10.3 Retries, idempotency and outcome certainty

- Retried: connection failures before the request was sent, and responses the provider documents as
  not processed (default set in §9.2, refined per dialect by `decodeError`). `Retry-After` is honoured
  up to `maxRetryAfter`; backoff uses full jitter; every retry emits `RequestEvent.Retrying`.
- Not retried: any failure after the request may have been processed — timeouts after send, resets,
  `504` — which surfaces with `outcomeUnknown() == true` so the host can decide (double billing).
- Never retried after the first stream event; the stream fails instead and `partial()` remains usable.
- One OAuth exception: a `401` on OAuth credentials forces one refresh and one retry, because the
  request was rejected before execution.
- One total deadline covers everything; attempts stop when the next backoff would exceed it.
- Only replayable bodies are retried (JSON bodies are; streamed uploads are not). Where a dialect
  supports idempotency keys, one key is used for all attempts of a logical call.
- No automatic fallback to another provider, model or credential; hosts do that explicitly.

### 10.4 Streaming

- Framing across network chunks, multi-line SSE fields and NDJSON lines is the library's job; consumers
  only see whole events.
- Deltas, cumulative snapshots and replacements are normalized to deltas per part; interleaved parts and
  tool calls keep their `part` index; no global text buffer.
- Completion requires the protocol's terminal evidence: trailing usage is processed, and a premature
  EOF or a late provider error is a failure, not a success.
- On success iteration ends after `Finished`; on failure the iterator throws the typed exception once
  (no duplicate error event). Refusals and length limits are successful terminal outcomes with their
  finish reasons.
- Memory is bounded by framing buffers, the current part, and the accumulation limit; a tool call or
  native payload exceeding its bound fails explicitly.
- `response()` drains and returns the aggregate — equal to `send()` for the same exchange; `partial()`
  never drains; the aggregate can be disabled (`maxStreamAccumulation(0)`), after which `response()`
  throws `IllegalStateException`.
- There is no transparent reconnection. A protocol-level resume capability, where one exists, would be
  an explicit operation with its own cursor semantics.

### 10.5 Cancellation and async

| Operation | Cancel with | Effect |
|---|---|---|
| `stream()` | `stream.close()` from any thread | socket closed; iterator throws `RequestCancelledException`; `Cancelled(partialOutput)` event |
| `call.send()`, `call.stream()` | `call.cancel()` from any thread, before or during | before start: never starts; during: transport aborted |
| `call.sendAsync()` | `call.cancel()` or `future.cancel(true)` | same; the future completes exceptionally |
| `send()` | interrupt the calling thread | transport aborted; interrupt flag restored |
| any | `client.close()` | cancels in-flight calls it owns; later calls throw `IllegalStateException` |
| `login()` | `session.cancel()` or `close()` | listener stopped; `await()` throws `AuthenticationException(login_cancelled)` |

Closing a socket is not proof that the provider stopped work or billing; the `Cancelled` event reports
only what the client observed. `sendAsync()` completes on the client executor (a virtual thread per call
by default, owned by the client; or the injected executor, borrowed), never on an I/O thread, and fails
with the same exception types as `send()`; `join()` wraps them in `CompletionException` as usual.
`Flow.Publisher` and Kotlin coroutine adapters come later over the same `ChatCall` contract.

### 10.6 Threading and ownership

| Resource | Created by | Closed by |
|---|---|---|
| Default transport (`HttpClient`) | `LlmClient.build()` | `client.close()` |
| Injected transport, executor, token store, response cache, JSON mapper | host | host, never the client |
| Client-managed OAuth state and loopback listeners | client | `client.close()`; sessions by `LoginSession.close()` |
| Host-owned `OAuthCredentials` | host | host (`close()`), independently of clients |
| `ChatStream` response body | `stream()` | caller (`close()`); `client.close()` aborts it |
| Listener registrations | `addListener` | `Registration.close()` or `client.close()` |
| Views from `withOptions` | parent | nothing to close; they fail like the parent once it is closed |
| Remote resources (files, cached contents, batches) | explicit provider-API calls | explicit calls; never by `close()` |

Clients, sub-APIs, codecs, transports and registries are thread-safe; models, configuration, events and
exceptions are immutable; builders, iterators and login sessions are confined to one thread (except the
documented thread-safe `cancel`/`close`). No lock is held across network I/O: token refresh is
single-flight through a shared in-flight future that waiters join. On Java 26 `synchronized` no longer
pins virtual threads (JEP 491), so either lock form is acceptable for short critical sections. The call
context — request id, tags, deadline — travels in a `ScopedValue`, never a `ThreadLocal`. Client
`close()` is bounded (five seconds by default) and reports unfinished cleanup through the logger.

---

## 11. Cache management — four layers, one vocabulary

### 11.1 Overview

| Layer | What is cached | Configured by | Default | Invalidation | Observed through |
|---|---|---|---|---|---|
| 1. Provider prompt cache | Prompt prefixes, on the provider's side | `cacheBreakpoint()`, `promptCache(…)`, `defaults(d -> d.promptCache(…))` | Nothing sent; providers' implicit caching still applies | Provider TTL; `PromptCache.disabled()` | `Usage.cacheReadTokens()`, `cacheWriteTokens()`, `Pricing` cache prices |
| 2. Response cache | Complete successful responses, client-side | `caching(c -> c.responses(…).mode(…))`, `withOptions(o -> o.cacheMode(…))` | Off | TTL, `remove`, `clear`, `REFRESH` mode, changed request bytes | `metadata().fromCache()`, `Completed.fromCache()`, JFR |
| 3. Model catalog cache | `models().list()` results per client | `caching(c -> c.modelCatalogTtl(…))` | 1 h | `models().refresh()` | `ModelInfo.fetchedAt()`, `source()` |
| 4. Credential cache | Resolved secrets, OAuth tokens, dynamic tokens | `auth(…)`, `TokenStore`, `Credentials.dynamic(…)` | In memory | `auth().logout()`, `revoke()`, expiry, new client for rotated static secrets | `AuthStatus`, `AuthEvent.TokenRefreshed` |

Provider-side cache *resources* with their own lifecycle — Gemini cached contents — are managed
explicitly through `llm.providerApi(Gemini.CACHES)` (create, list, extend, delete) and referenced from
requests with `GeminiOptions.CACHED_CONTENT`. Closing a client never deletes them.

### 11.2 Provider prompt caching

Request surface: `cacheBreakpoint()` marks the end of everything added so far as a cacheable prefix;
`PromptCache.key(…)` adds a routing key where supported; `retention(…)` requests a lifetime;
`PromptCache.auto()` places breakpoints after tools and system and after the conversation before the
newest user turn.

| Dialect | Breakpoints | `key(…)` | `retention(…)` | Usage normalization |
|---|---|---|---|---|
| Anthropic Messages | `cache_control` on the last block of each marked prefix; the protocol's breakpoint limit is validated (excess follows the `UnsupportedPolicy`) | not applicable | supported TTL values; mapped to the nearest value not longer than requested, reported as `option_adapted` | cache read and creation counters mapped; `inputTokens()` includes them |
| OpenAI Responses and Chat Completions | automatic prefix caching: breakpoints are satisfied hints, no warning | `prompt_cache_key` | extended retention where the model supports it | cached-token details mapped to `cacheReadTokens()` |
| Gemini generateContent | implicit caching: satisfied hints; explicit caches via `Gemini.CACHES` | not applicable | explicit cache TTL | cached-content counter mapped |
| Compatible providers | per preset quirk: automatic (for example DeepSeek), `cache_control` passthrough where accepted, otherwise satisfied hints | quirk | quirk | provider counters normalized by the codec |

Determinism rules make caching effective: JSON keys keep insertion order, the SDK injects no volatile
values (timestamps, random ids) into prompts, tools keep declaration order, and identical requests
produce identical bytes (pinned by `DialectContract`; visible in `preview().body()`). Cache writes can
cost more than plain input on some providers, so automatic placement is opt-in.

### 11.3 Response cache (client-side, opt-in)

- **Key**: SHA-256 over endpoint id, base URL, dialect id, a one-way fingerprint of the credential
  scope (API key hash, OAuth account key, or dynamic provider identity), method, resolved path,
  cache-relevant headers (protocol version and beta flags, never credentials) and the canonical body.
  A shared cache therefore never serves one tenant's response to another, and a codec change that alters
  the wire bytes naturally misses.
- **Stored**: status, redacted headers, and the body (non-streamed) or frames (streamed) of 2xx
  exchanges that finished with terminal evidence. Never errors, cancellations or partial streams.
- **Hit**: replayed through the same codec or stream decoder, so types, warnings and events are those of
  a live call; streams replay without artificial delays; `fromCache()` is `true`; usage is the recorded
  usage, flagged as not spent.
- **Modes**: `READ_WRITE` (default when a cache is set), `REFRESH` (skip reads, write results), `BYPASS`
  (neither), `OFFLINE` (read only; a miss throws `LlmException(cache_miss)` naming the key description
  and never touches the network).
- **Stores**: `ResponseCache.inMemory(maxEntries[, ttl])` (LRU) and `ResponseCache.directory(path)` (one
  readable JSON file per entry, with the request body for diagnosing misses — intended for tests and
  development). Persistent production stores are host SPI implementations that own encryption and
  retention: a response cache holds prompts and outputs.
- **Scope**: exact-match memoization only. Semantic caching, which trades correctness for hit rate, is
  an anti-goal. Whether memoizing sampled output is acceptable is the caller's decision, made explicitly.

### 11.4 Model catalog cache

Per client, keyed by endpoint and credential scope. `list()` serves the snapshot while it is younger than
the TTL and fetches otherwise; `refresh()` always fetches; `cached()` never performs I/O. A failed
fetch keeps the previous snapshot, leaves `fetchedAt()` unchanged and throws, so a UI can show stale data
with an error instead of an empty picker. Paging is followed internally up to a bound of 10 000 models.
Sources are never merged silently: live listings win; bundled data (for providers without a listing
endpoint) and host-configured `ModelInfo` values are fallbacks marked by `source()`.

### 11.5 Credential caching

Static secrets (`env:`, `file:`, host references) are resolved once at client build; rotating them means
a new client or `Credentials.dynamic(…)`. OAuth tokens live in the `TokenStore` and are refreshed before
expiry minus the skew, single-flight per token key; a rotated refresh token is persisted before it is
used. Dynamic tokens are cached until expiry minus the skew, also single-flight. A caller that is
cancelled while waiting for a shared refresh stops waiting without cancelling the refresh others need.

---

## 12. Authentication and OAuth

### 12.1 Credential application — every attempt

```
Credentials ─┬─ ApiKey / BearerToken ─► provider AuthMethod: Authorization: Bearer … | <header>: … | ?<param>=…
             ├─ None ─────────────────► nothing (allowed only for providers declaring AuthMethod.none())
             ├─ Dynamic ──────────────► cached AccessToken, else CredentialProvider.fetch() (single-flight) ─► bearer
             └─ OAuth ────────────────► TokenStore.load(key) ─┬─ absent ─────────► AuthenticationException(login_required)
                                                              ├─ expiring ───────► refresh (single-flight) ─► save
                                                              └─ valid ──────────► Authorization: Bearer <access>
```

### 12.2 Interactive login

```
login() ─► CREATED ─► PROMPTED ──────────────────────────► CALLBACK ─► EXCHANGING ─► DONE (tokens stored)
                        │ OpenBrowser (launcher tried)     ▲ loopback request,     │ code + PKCE verifier,
                        │ EnterCode (device polling)  ─────┘ or acceptRedirect()   │ or device poll success
                        └───── cancel() / close() / timeout / denial ────────────► CANCELLED or FAILED
```

| Flow | Selected by | Behaviour |
|---|---|---|
| Authorization code + PKCE, loopback | `LoginOptions.browser()` (default when the config has an authorization endpoint) | Listener on `127.0.0.1`, ephemeral or fixed port, one request, `state` validated, minimal success page, then stopped |
| Authorization code + PKCE, external redirect | `LoginOptions.externalRedirect(uri)` | No listener; the host's callback route calls `acceptRedirect(uri)` |
| Device authorization | `LoginOptions.deviceCode()` | Polls at the server's interval; handles pending, slow-down, denial and expiry |
| Client credentials | config grant `CLIENT_CREDENTIALS` | Non-interactive; `prompt()` is `NoInteraction`; also used transparently on first call |
| Refresh | automatic, or `auth().refresh()` | Proactive before expiry; one forced refresh and retry on `401` |
| Logout / revoke | `auth().logout()` / `auth().revoke()` | Local clear, no I/O / remote revocation where supported, then local clear |

### 12.3 Security contract

- PKCE S256 and a fresh `state` per transaction; no implicit or password grants. HTTPS for authorization
  and token endpoints, except loopback.
- The loopback listener binds `127.0.0.1` only, accepts one callback, and closes on success, failure,
  timeout or cancellation. Nothing listens on external interfaces.
- Authorization and token endpoints come from trusted configuration (presets or host `OAuthConfig`); an
  arbitrary issuer URL is not permission to contact a host. Inference, OAuth and proxy destinations are
  separate scopes; redirects are not followed with credentials.
- Tokens are bound to issuer, client id and account (`TokenStore.Key`). A refresh or re-login cannot
  silently change principal or account; switching accounts means new credentials.
- Refresh is single-flight; rotated refresh tokens are persisted atomically before use; a refresh
  rejected as invalid deletes stored tokens and yields `login_required`.
- `logout()` is local and immediate; `revoke()` contacts the server. After either, a late login or
  refresh completion cannot store tokens.
- Login prompts (authorization URLs, device codes) are delivered only by `LoginSession.prompt()` to the
  initiating caller; `AuthEvent.LoginStarted` carries the flow type only. Tokens never appear in events,
  logs, exceptions or `toString()`.
- Successful authorization means credentials were obtained — not that a model, quota or billing account
  is usable; `connection().test()` answers that.
- Web hosts bind pending sessions to the initiating application user and enforce their own access control
  on start, callback, status and inference routes; the SDK validates the OAuth transaction, not the
  application's users.
- These rules follow OAuth for native apps (RFC 8252), the OAuth security best current practice
  (RFC 9700) and device authorization (RFC 8628).

### 12.4 Provider-specific shapes without special code paths

| Case | Mechanism |
|---|---|
| OpenRouter PKCE returns an API key, not tokens | `OAuthConfig.tokenResponseMapper` maps `{"key": …}` to `OAuthTokens` without expiry |
| Azure OpenAI with Entra ID, Vertex AI with Google credentials | `Credentials.dynamic(() -> AccessToken.of(Secret.of(cloudSdk.token()), cloudSdk.expiry()))` |
| Corporate gateway with client credentials | `OAuthConfig` grant `CLIENT_CREDENTIALS` with a client secret reference |
| AWS Bedrock SigV4 | a signing `WireInterceptor` in the optional `llm-transport-bedrock` module |
| mTLS gateways | `HttpOptions.clientCertificate(…)`; transport identity, separate from token presentation |

---

## 13. UI and workflow-builder integration guide

| Need | SDK answer | I/O |
|---|---|---|
| List providers and render a connection form | `Providers.registry().all()`, `LlmProvider.connectionFields()` | no |
| Validate a form | `Endpoint.Builder.set(key, raw)` + `build()` → every invalid field | no |
| "Get an API key" link | `LlmProvider.apiKeyUrl()` | no |
| Save / load a connection | `endpoint.toJson()` / `Endpoint.fromJson(…)`; secrets as references | no |
| "Test connection" button | `connection().test()` → staged `ConnectionReport` | yes, non-billable |
| "Sign in" button, device-code dialog, web callback | `auth().login(…)`, `LoginPrompt`, `acceptRedirect`, `AuthEvent` | yes |
| Auth status chip | `auth().status()` | no |
| Model picker with capabilities, context size, price | `models().list()` (cached), `ModelInfo` | first time, then cached |
| Parameter form | `ModelInfo.parameters()` → `FieldDescriptor`; `GenerationSettings.Builder.set(key, raw)` | no |
| Warn about options the selected model cannot use | `preview()` problems and warnings; `Capabilities.support(…)` | no |
| Preview / dry-run a node | `chat().preview(request)`: exact body, redacted headers, warnings | no |
| Run a node with progress and a Stop button | `chat().call(request)` + `stream()`/`sendAsync()`; `RequestEvent`s; `call.cancel()` | yes |
| Show partial output after a failure or stop | `stream.partial()` | no |
| Cost of a run | `Usage` + `Pricing.estimate(usage)`; `fromCache()` excludes replayed calls | no |
| Re-run a graph without re-billing unchanged nodes | response cache with `READ_WRITE`; `REFRESH` for "force re-run" | depends |
| Correlate events with a workflow run | `withOptions(o -> o.tag("run", id).listener(panel))` | no |
| Fan-out ("one request per file" vs "all files in one") | host decision; the SDK reports per-request usage | — |
| No execution on rendering | nothing in `Endpoint`, `LlmProvider`, `ModelInfo`, `preview()`, `status()`, `describe()` performs I/O | — |

---

## 14. Testing and debugging toolkit

### 14.1 Testing (`llm-transport-testing`)

```java
package dev.llmtransport.testing;

/// A scripted dialect and transport behind a real LlmClient: validation, events, streaming aggregation,
/// retries and caching behave exactly as in production.
public final class FakeLlm {
    public static FakeLlm create()                                          { … }
    public FakeLlm reply(String text)                                       { … }  // next call answers with text
    public FakeLlm reply(Consumer<ScriptedReply.Builder> reply)             { … }  // tool calls, reasoning, usage, finish reason
    public FakeLlm stream(String... textDeltas)                             { … }  // next call streams these deltas
    public FakeLlm fail(LlmException error)                                 { … }  // for example LlmErrors.rateLimited(…)
    public FakeLlm respond(Function<ChatRequest, ChatResponse> handler)     { … }  // dynamic answers
    public LlmClient client()                                               { … }
    public LlmClient.Builder clientBuilder()                                { … }  // add listeners, caching, timeouts
    public Endpoint endpoint()                                              { … }
    public List<ChatRequest> requests()                                     { … }  // resolved requests, in order
    public void assertAllRepliesConsumed()                                  { … }
}

/// One scripted answer: text, tool calls, reasoning, usage and finish reason.
public final class ScriptedReply {
    public static final class Builder {
        public Builder text(String text)                                    { … }
        public Builder toolCall(String name, JsonObject arguments)          { … }
        public Builder reasoning(String text)                               { … }
        public Builder usage(long inputTokens, long outputTokens)           { … }
        public Builder finishReason(FinishReason reason)                    { … }
        public ScriptedReply build()                                        { … }
    }
}
```

| Tool | Purpose |
|---|---|
| `FakeLlm` | Host tests without network, keys or mocks of the facade |
| `ScriptedTransport` | Wire-level scripts (status, headers, SSE chunks split anywhere, delays, resets) for dialect and transport tests |
| `RecordingListener` | Captures events for assertions on progress, retries and warnings |
| `LlmErrors` | Factories for every exception with realistic fields |
| `TestClock` | Advances time for TTLs, token expiry and backoff without sleeping |
| `FakeAuthorizationServer` | Local OAuth server: PKCE, device flow, refresh rotation, denial, slow-down, expiry |
| Contract kits | `DialectContract`, `HttpTransportContract`, `TokenStoreContract`, `ResponseCacheContract`, `JsonMapperContract`: abstract JUnit classes every implementation extends |
| Cassettes | `ResponseCache.directory(…)` + `CacheMode.OFFLINE` in CI; `REFRESH` to re-record |

| Level | Tooling | Proves |
|---|---|---|
| Host unit tests | `FakeLlm`, `RecordingListener`, `LlmErrors` | Host logic and error handling, offline |
| Dialect conformance | `DialectContract`, golden fixtures, `ScriptedTransport` | Wire mapping, streaming, error mapping |
| Replay | cassettes | Behaviour against real recorded traffic, deterministic |
| Live | `liveTest` suite (`-Plive`) | Real endpoints, bounded cost, nightly |

### 14.2 Debugging

| Question | Tool |
|---|---|
| What exactly will be sent? | `chat().preview(request)`: body, redacted headers, warnings, problems |
| Reproduce outside Java? | `preview.toCurl()` with credential placeholders |
| Which configuration is in effect? | `connection().describe()`: endpoint, dialect, policies, caches, redacted |
| What went over the wire? | `http(h -> h.wireLog(WireLog.HEADERS or BODIES))` → logger `dev.llmtransport.wire`, redacted |
| Why slow, how many retries, cached or not? | `ResponseMetadata` (attempts, latency, time to first output, `fromCache`), events, JFR |
| Key, network, or model problem? | `connection().test()` staged report |
| Which provider request failed? | `LlmException.providerRequestId()`, `requestId()`, `errorBody()`, `code()` |
| Production incident | JFR (`jcmd <pid> JFR.start`) with `dev.llmtransport.*` events; no restart, no secrets |

Every value type has a readable, redacted `toString()`, for example
`ChatRequest[model=claude-sonnet-5, messages=3, tools=[read_file], settings={temperature=0.2}]`.

---

## 15. Observability

- **Events** (§7.11): sealed, ordered per call, synchronous and isolated; tags from `withOptions` are
  attached to every event of that view; events never carry prompts, outputs or secrets.
- **JFR**: `dev.llmtransport.Request` (endpoint, dialect, model, streaming, outcome, attempts, input,
  output and cache tokens, latency, time to first output, from cache), `dev.llmtransport.Retry`
  (attempt, delay, reason), `dev.llmtransport.TokenRefresh` (endpoint, outcome, latency). Emitted
  always; recorded only when a recording is active.
- **Logging**: `System.Logger` — `dev.llmtransport` (lifecycle at DEBUG, insecure configuration and
  isolated listener failures at WARNING), `dev.llmtransport.auth`, `dev.llmtransport.wire` (only when
  wire logging is enabled). No payloads or secrets by default; the SDK never configures logging.
- **OpenTelemetry** (optional `llm-transport-otel`): a listener and an interceptor mapping calls to
  GenAI semantic-convention spans and metrics, with the convention version pinned by the module and
  content capture opt-in.

---

## 16. Portability — hiding provider, protocol and version differences

### 16.1 Portable concepts and their dialect mappings

Illustrative; each row is pinned by golden fixtures in the dialect's `DialectContract` suite and
re-verified when a module is released.

| Portable concept | OpenAI Responses | Chat Completions (+ compatible) | Anthropic Messages | Gemini generateContent |
|---|---|---|---|---|
| `system(…)` | `instructions` | system or developer message (quirk) | top-level `system` | `systemInstruction` |
| `Tool.function(…)` | function tool | `tools[].function` | `tools[]` with `input_schema` | `functionDeclarations` |
| `ToolCall` | `function_call` item | `tool_calls[]` | `tool_use` block | `functionCall` part, id synthesized |
| `ToolResult` | `function_call_output` item | `tool` role message | `tool_result` block in a user turn | `functionResponse` part |
| `Reasoning.effort(…)` | `reasoning.effort` | `reasoning_effort` or the preset's reasoning format | native effort or the dialect's published effort-to-budget table | `thinkingConfig` |
| Reasoning continuity | reasoning items with encrypted content | preset quirk (for example `reasoning_content`) | `thinking` blocks with signatures | thought signatures |
| `OutputFormat.jsonSchema(…)` | `text.format` JSON Schema | `response_format` JSON Schema | native structured output, or a documented forced-tool adaptation | response MIME type + schema |
| `maxOutputTokens` | `max_output_tokens` | `max_completion_tokens` or `max_tokens` (quirk) | `max_tokens` (required) | `maxOutputTokens` |
| `seed` | not in the protocol → policy | `seed` | not in the protocol → policy | `seed` |
| Cache breakpoints | automatic | automatic, or `cache_control` (quirk) | `cache_control` | implicit, or explicit caches |
| Streaming | typed SSE events | SSE chunks + `[DONE]` | typed SSE events | SSE |
| Usage | input, output, cached, reasoning details | `usage` (stream usage opt-in per quirk) | input, output, cache read, cache creation | `usageMetadata` |

### 16.2 Option scoping — why one request survives a provider switch

- Every `OptionKey` has a namespace: a dialect family (`openai`, `anthropic`, `gemini`) or a provider id
  (`openrouter`, `deepseek`).
- An option applies when its namespace matches the endpoint's dialect family or provider. Otherwise it
  is **inert**: not sent, reported as `Warning("option_not_applicable")`, a `Warned` event and a
  `preview()` warning, regardless of the `UnsupportedPolicy` — it was declared provider-specific, so
  ignoring it elsewhere changes no portable meaning.
- An applicable option the model or revision cannot express follows the `UnsupportedPolicy`.
- Consequently one request, or one workflow-node configuration, can carry tuning for several providers
  and run unchanged against any of them.

### 16.3 Unsupported settings and documented adaptations

- A portable setting the dialect cannot express fails with `InvalidRequestException(unsupported_feature)`
  naming the field and the dialect (`FAIL`, default), or is omitted with a warning (`DROP_WITH_WARNING`).
- Documented equivalent adaptations are part of a dialect's contract and are not failures: system
  placement, role mapping, tool-id synthesis, usage normalization, schema output through a forced tool
  call where native schema output is missing, and effort-to-budget mapping through the dialect's
  published table. Adaptations beyond a rename are reported as `option_adapted`.
- Never adapted: numeric ranges (a temperature outside the dialect's range is rejected, not rescaled),
  reasoning parts from another origin (stripped, with a warning), provider tools on another provider
  (policy).
- Model metadata sharpens validation: a capability marked `UNSUPPORTED` fails early (or is dropped under
  best-effort); `UNKNOWN` proceeds and lets the provider decide.

### 16.4 API versions and revisions

- A `DialectId` is a protocol family and revision; a constant never changes meaning. An incompatible
  revision becomes a new constant; the old one is deprecated with `since` and `forRemoval` and removed in
  the next major version.
- Version parameters (Anthropic's version header, Azure's `api-version`, Gemini's `v1beta` path) belong to
  the dialect revision or the preset. Overriding them through an option is allowed and reported as
  `untested_api_version`.
- Newer server revisions degrade gracefully in older SDKs: unknown fields, parts, events, finish reasons
  and error codes are preserved (`Unknown`, open value types), never fatal.

---

## 17. Evolution, compatibility and complexity budget

### 17.1 Compatibility rules

- Semantic versioning; `0.x` carries no promise, stated in the README. japicmp runs against the previous
  minor on every release; every intended break is listed in `CHANGELOG.md`.
- API interfaces (`LlmClient`, `ChatApi`, `ChatStream`, …) are `@ApiStatus.NonExtendable`: methods may be
  added in minors. SPI interfaces grow only through `default` methods; a new abstract SPI method needs a
  major version.
- Sealed hierarchies derived from providers (`Content`, `ChatEvent`, `LlmEvent`) may gain variants in
  minors; consumers keep a `default` branch and every wire-derived family has an `Unknown` variant.
  `LoginPrompt` and `JsonValue` are closed: changing them is a major version.
- Wire-derived models are final classes with accessors and builders; records are used only for small,
  frozen shapes (ids, delta events, keys).
- Defaults, ordering, error codes and redaction are observable behaviour: changes are at least minor,
  with a changelog entry and a renamed test.
- Experimental surfaces (`raw()`, early provider APIs) carry `@ApiStatus.Experimental` and are outside the
  promise until two real consumers have shaped them. No `v1`/`v2` packages: extend in place, deprecate
  with a named replacement.

### 17.2 Complexity budget

| Measure | Budget | This design |
|---|---|---|
| Concepts a caller must learn (glossary) | ≤ 10 | 10 |
| Types in the Simple example | ≤ 5 | 5 (`LlmClient`, `Providers`, `ApiKey`, `ModelId`, `ChatResponse`) |
| Methods on the facade | ≤ 10 | 10 |
| Names on the client builder | ≤ 13 | 13 (18 methods counting object/consumer overloads) |
| Types a protocol author must implement | ≤ 3 | 3 (`Dialect`, `ChatCodec`, `StreamDecoder`) |
| Abstract methods per SPI | ≤ 3 | ≤ 3 |
| Runtime dependencies of the core | 0 | 0 |
| Package depth below the root | ≤ 3 | 2 |

Every public type traces to a use case in §4 or an extension in §8. A new public type without a new
glossary concept or a new use case is a review failure.

### 17.3 Not added, on purpose

| Idea (source) | Why not |
|---|---|
| Router, fallback chains, circuit breakers, key pools, client-side rate limiter (requirements, A and B deferred) | Host policy; hides which account is billed; key pools may breach terms. Events expose what hosts need |
| Agent loop, tool execution, MCP client, memory (requirements) | Anti-goals; `continueWith` removes the boilerplate without owning the loop |
| Semantic response caching | Trades correctness for hit rate; exact-match caching covers tests and re-runs |
| Generic operation dispatch `execute(Operation)` (A option B) | Hides a large vocabulary behind one method; lifetimes differ |
| Per-provider client classes (A option C) | Forces hosts to branch per provider |
| `Fact<T>` wrappers on every metadata field (A) | Verbose for every UI; provenance is kept per `ModelInfo` |
| Separate `inspect()` assessment (A) | Merged into `preview()` |
| Timeout inside `ChatRequest` (B) | Operational policy belongs to views |
| Records for every event (B) | Growth breaks consumers; only frozen deltas are records |
| Config files, YAML and interpolation in core (requirements) | Optional modules map onto the same builders |
| Reactive libraries, Kotlin in the core | JDK types only; adapters are optional modules |
| Stream reconnection after output | Unsafe: may duplicate billed generation |

### 17.4 Anticipated review questions

| Question | Answer |
|---|---|
| Why is `LlmClient` an interface rather than a final class? | Hosts decorate it (tenant routing, auditing) and tests may substitute it; `@NonExtendable` keeps method additions non-breaking and the implementation internal. Implementing it outside the SDK is unsupported — `FakeLlm` is the supported substitute |
| Why a hand-written JSON library? | Zero runtime dependencies avoid version conflicts in IDE plugins and applications; stable key order is a functional requirement for prompt caching and cache keys; the scope is small (tree, bounded parser, writer, record binder). POJOs use the optional Jackson module |
| Is a response cache overengineering for a transport? | It is opt-in and one SPI with two abstract methods, and it serves three needs with one mechanism: deterministic test cassettes, "re-run without re-billing" in workflow builders, and offline demos |
| Why is OAuth in the core rather than a separate artifact? | The implementation needs only the JDK; the core already owns credential application and refresh, so splitting would duplicate the token pipeline. The security surface is isolated in `internal.oauth` and tested against `FakeAuthorizationServer` |
| Why one client per endpoint and no router? | Billing, credentials and data residency stay explicit; routing is host policy; a map of clients sharing one transport costs nothing |
| Why sync-first in 2026? | Virtual threads make blocking code the scalable default; `sendAsync()` covers futures; reactive and coroutine adapters can follow without changing the core contract |
| `send(request, Invoice.class)` returns only the record — where is the usage? | The shortcut serves the common extraction case; `send(request)` plus `response.parse(Invoice.class)` keeps all metadata. Both use one binder |
| Why don't foreign provider options fail under `FAIL`? | They are declared provider-specific; ignoring them on another provider changes no portable meaning, and failing would break every multi-provider configuration. They are still reported |
| Why no first-byte timeout? | It aborts long non-streamed generations; idle and total limits cover dead connections and runaway calls |
| Why records for delta events but classes for lifecycle events? | A record freezes its constructor and deconstruction pattern; deltas are stable, lifecycle events grow |
| Why `ServiceLoader` at all? | Only `ProviderRegistry.discover()` uses it, for UI provider lists; constants and explicit registration always work; duplicate ids fail loudly |
| Isn't a hundred-plus public types a lot? | Callers learn ten nouns and the Simple path uses five types; every other type is reached by completion and backs a use case in §4 or an extension in §8 (budget in §17.2) |

---

## 18. Delivery roadmap — minimal skeleton first

| Slice | Deliverable | Exit evidence |
|---|---|---|
| **0 — API proof** | Core types and facade, `FakeLlm`, examples in Java and Kotlin, ArchUnit rules, japicmp baseline | §4 examples compile and run offline; misuse and ownership tests pass |
| **1 — Transport and two dissimilar protocols** | JDK transport, SSE framing, execution engine (deadlines, retries, cancellation, events, JFR), API keys, `ChatCall`/`sendAsync`, Chat Completions with compatible presets **and** Anthropic Messages, tools, record-typed output, streaming, `preview`/`toCurl`, models list and catalog cache, connection test, response cache (memory and directory) | Both dialects pass `DialectContract` with golden fixtures, including split chunks, late errors and premature EOF; cassettes replay offline; one live run per dialect |
| **2 — OAuth vertical slice** | PKCE loopback, external redirect, device flow, client credentials, refresh and rotation, file token store, browser launcher, OpenRouter preset, `FakeAuthorizationServer` | Desktop and web examples log in, cancel, expire and refresh against the fake server; concurrent refresh is single-flight |
| **3 — Breadth** | OpenAI Responses, Gemini generateContent, reasoning continuity, prompt-cache mappings, multimodal input, rate limits, account info, remaining presets, `Providers` index complete | Four dialects green; history round-trips per provider; portability table rows pinned by fixtures |
| **4 — Additional operations** | Embeddings sub-API; provider APIs for files, batches and Gemini caches; token counting — ordered by consumer demand | Each operation documents side effects, billing, cancellation, ownership and retention |
| **5 — Integrations** | Kotlin coroutines, Jackson, OpenTelemetry, keychain token store, config and Spring Boot bindings, Ollama native, Bedrock, Vertex | Optional modules preserve core contracts and dependency isolation |

Slices 0–2 form the first usable release for both named consumers. Slice 1 deliberately pairs the most
widely compatible protocol with the most different one, so the portable model is tested early.

---

## 19. Verification

### 19.1 Tests are the specification

The README examples compile and run in `:examples:test`. Named tests pin every documented rule, for example:

- `simple example sends one request with default timeouts and no sampling parameters`
- `swapping model and prompt does not compile` (compile-fail fixture)
- `unset temperature is absent from the wire body; omit(TEMPERATURE) suppresses a client default`
- `foreign provider option is inert and reported as option_not_applicable`
- `unsupported portable setting fails under FAIL and is dropped with a warning under DROP_WITH_WARNING`
- `429 is retried up to maxAttempts honouring Retry-After, then RateLimitedException`
- `timeout after send and 504 are not retried and report outcomeUnknown`
- `send() is not limited by the stream-idle timeout`
- `stream delivers whole SSE events when chunks split lines and UTF-8 sequences`
- `stream is never retried after the first event`
- `stream response equals send response for the same recorded exchange`
- `premature EOF and late provider errors fail the stream`
- `closing a stream cancels the request, releases the connection and emits Cancelled(partialOutput)`
- `ChatCall.cancel before start prevents any request; sendAsync future cancel aborts the call`
- `continueWith preserves reasoning signatures and strips foreign-origin reasoning with a warning`
- `send(request, Invoice.class) throws output_truncated on a length finish`
- `record schema derivation is strict-compatible and binds through canonical constructors only`
- `unknown content part, event, finish reason and error code are preserved, not rejected`
- `missing usage is absent, not zero; unknown capability is UNKNOWN, not UNSUPPORTED`
- `response cache key includes credential scope; two tenants never share an entry`
- `OFFLINE cache miss fails without network and names the request`
- `model catalog serves stale data with an error after a failed refresh`
- `listener exception is logged and does not fail the call`
- `injected transport, token store and response cache are not closed by the client`
- `API key never appears in toString, logs, events, previews, curl output or exceptions`
- `login prompt is not delivered to listeners`
- `loopback login validates state, uses PKCE S256 and stops the listener in every outcome`
- `concurrent expired-token calls trigger exactly one refresh; rotated refresh token is persisted first`
- `connection test stops at AUTHENTICATION for a wrong key and never performs inference`
- `preview performs no I/O and matches the bytes send() transmits`
- `endpoint JSON round-trips and refuses literal secrets`
- `kotlin caller uses builders and consumer-builders without platform types`
- `japicmp reports no unintended binary change against the previous minor`

Every SPI implementation, shipped or third-party, extends its contract kit. Footgun probes (§9.3) each
have a test. Memory is measured against stream length with a fixed configured buffer; cancellation
latency and dependency footprint are tracked in CI. No "zero-copy" or throughput claims are made without
such evidence.

### 19.2 What was verified while preparing this document

Checked on a local Temurin **26.0.2.1** JDK:

- `java.net.http.HttpClient.Version.HTTP_3` and `HttpOption.H3_DISCOVERY` exist (JEP 517).
- `ScopedValue`, stream gatherers, unnamed patterns and compact source files with `import module`
  compile and run without preview flags; `StructuredTaskScope`, `LazyConstant` and primitive patterns
  are rejected as preview features.
- A sealed interface compiled in a named module with a permitted subclass in another package loads and
  pattern-matches both on the module path and on the classpath.
- Reflective mutation of a final field prints the JEP 500 warning, confirming that binding through
  canonical constructors is the forward-compatible choice.
- Markdown documentation comments (`///`) render with the JDK 26 `javadoc` tool.

Compile evidence for the API sketch and the Gradle conventions is reported in §19.3.

### 19.3 Compile evidence for the API sketch and build conventions

A verification build — Gradle 9.7.1, JDK 26.0.2.1 toolchain, `--release 26`, the §6.5 conventions copied
as written — transcribed every type and signature of §6.4, §7, §8.1–§8.3, §8.5, §8.7 and §14.1 into stub
sources (177 files in eight subprojects, the core compiled as the named module `dev.llmtransport`) and
compiled every Java example of §4.1–§4.18 against them.

| Check | Result |
|---|---|
| API sketch (§7, §8, §14.1) and examples (§4.1–§4.18) | Compile; class files are version 70 (Java 26) |
| `module-info.java`, toolchain 26, `--release 26` | Work; Gradle 9.7.1 runs on JDK 26 as well as on JDK 21 |
| Convention plugin (§6.5), including Markdown Javadoc with doclint and reproducible archives | Works as written |
| `gradle build -x test` with `-Xlint:all,-processing,-serial -Werror`, Javadoc included | Passes with zero warnings after the fixes below |

The check found five issues, all fixed in this document: `transitive` on `java.net.http` and on the
annotation modules, because exported signatures use their types (javac `exports` lint, §6.4);
`OptionKey.listOf(…)` for list-valued options instead of unchecked casts in provider modules (§7.2); the
JUnit BOM on the testing artifact's `api` configuration (§6.5); a Javadoc line that began with an
annotation name and was parsed as a block tag (§8.2); and sketches for types it had to invent — `Usage`,
`Warning`, the JSON members, `Headers`, `RawApi`, `Message.Builder`, the Anthropic and Gemini option
types, `GeminiCaches`, `ScriptedReply` (§7.5, §7.7, §8.2, §8.7, §14.1). It also confirmed that
`missing-explicit-ctor` enforces the private-constructor convention of §7, and led to the final exception
design in which codecs return `LlmException.Details` and the core chooses the exception type (§7.14, §8.1);
that change was re-compiled with `-Werror`.

Not verified: runtime behaviour (every stub throws), the Kotlin example (§4.19), provider wire formats and
live interoperability, and performance and memory bounds — these are the exit criteria of slices 0–3
(§18).

---

## 20. Open decisions and risks

| # | Topic | Recommendation | Why it is open |
|---|---|---|---|
| 1 | Java 26 baseline | Keep the requested toolchain and `--release 26`. Every language and library feature this design relies on is final in Java 25 LTS except HTTP/3, which is opt-in; switching `options.release` to 25 (HTTP/3 selected at runtime when available) is a one-line change if consumers need an LTS runtime | Java 26 is a non-LTS feature release; its class files do not load on Java 25 |
| 2 | Kotlin toolchain | Pin a Kotlin version that reads Java 26 class files before enabling the Kotlin examples; otherwise compile them in a separate source set with the newest supported JVM target | Kotlin support for a new JDK usually trails the JDK release |
| 3 | Internal JSON | Build the bounded parser, stable-order writer and record binder with a JSON conformance suite and fuzzing; revisit only if binding needs outgrow records | Hand-written parser correctness and performance |
| 4 | Root package and coordinates | `dev.llmtransport` placeholder | Organization naming |
| 5 | Model aliases per endpoint (`fast`, `smart`) | Defer to 1.x; `defaultModel` already keeps calling code portable | Adds a resolution rule; wait for a second consumer |
| 6 | Streaming structured output (partial objects) | Defer; stream text, bind at the end | Needs an incremental JSON parser and a partial-object API |
| 7 | `Flow.Publisher` and coroutine adapters | Slice 5, over `ChatCall` | Cancellation propagation must be proven per adapter |
| 8 | Record binding in modular applications | Document `exports`/`opens` for bound record packages; add a `MethodHandles.Lookup` overload only if users struggle | JPMS accessibility of user types |
| 9 | Cassette format | Version it (`llm-transport.cassette/1`) and keep it human-readable | Becomes a compatibility surface once teams commit cassettes |
| 10 | Provider facts | Re-verify base URLs, headers, limits and quirks at every module release | Volatile, as both proposals note |
| 11 | Consumer-subscription OAuth | No presets; the generic `OAuthConfig` only | Terms of service and vendor enforcement |
| 12 | HTTP/3 | Opt-in; HTTP/2 remains the default | New in JDK 26; proxies and middleboxes vary |

---

## Appendix A — Traceability

| Requirement | Where |
|---|---|
| `goals.md`: wide range of LLM calls; hide OpenAI Responses, Anthropic Messages, Gemini, Grok, DeepSeek, Qwen | §7.3–§7.7, §8.7, §16 |
| `goals.md`: provider and gateway connection and authentication, OAuth included | §4.11, §7.10, §12 |
| `goals.md`: UI hooks — browser opening, events, progress, information | §4.11–§4.13, §7.11, §12.2, §13 |
| `goals.md`: coding agent and node-based workflow builder | §3.2, §4, §13 |
| `goals.md`: hierarchical and modular; API and providers separated; start minimal and grow | §5, §6, §18 |
| `goals.md`: factories, abstractions, facades, interfaces; plug in a provider; upgrade an API version | §5.4, §7, §8, §16.4 |
| `goals.md`: project structure, configuration endpoints and examples, factories, stubs | §6, §7, §8.7, §9 |
| `goals.md`: lightweight, no god classes, good defaults | §2, §5.3, §9.2, §17.2 |
| This request: critical, unbiased comparison and merge | §1 |
| Readable, concise API with speaking names and minimal overhead | §3, §4, §7 |
| Well-structured project tree | §6 |
| Cache management | §4.8, §7.13, §11 |
| Easy testing and debugging | §4.16–§4.17, §14 |
| Flexible settings | §7.1–§7.3, §9 |
| Tools | §4.5, §7.6 |
| Streaming API | §4.4, §7.5, §10.4–§10.5 |
| Output formats | §4.6, §7.7 |
| Switch provider, protocol or API version without rewriting code | §4.3, §16 |
| Java 26 + Gradle; modern Java features | §6.4–§6.6, §19.2–§19.3 |
| Balance between richness and overengineering | §17.2–§17.3 |

## Appendix B — Name mapping

| Proposal A | Proposal B | Final |
|---|---|---|
| `LlmClient` (final class) | `LlmClient` (interface) | `LlmClient` (non-extendable interface) |
| `generate(…)` | `chat().send(…)` | `chat().send(…)` |
| `GenerationRequest` / `GenerationResult` | `ChatRequest` / `ChatResponse` | `ChatRequest` / `ChatResponse` |
| `GenerationSettings`, `GenerationDefaults` | flat settings on `ChatRequest` | `GenerationSettings`, client `defaults(…)` |
| `CallOptions` argument | `ChatRequest.timeout(…)` | `CallOptions` through `withOptions(…)` |
| `GenerationCall` | — | `ChatCall` |
| `GenerationStream`, `StreamSummary`, `ResultCollector` | `ChatStream`, `response()`, `partial()` | `ChatStream` with bounded `response()` and `partial()` |
| `GenerationEvent` | `ChatEvent` | `ChatEvent` |
| `OperationObserver` | `LlmListener`, `LlmEvent` | `LlmListener`, `LlmEvent` |
| `Provider`, `ProviderSession`, `InferenceAdapter` | `Dialect`, `ChatCodec`, `StreamDecoder` | `Dialect`, `ChatCodec`, `StreamDecoder` |
| `Providers.openAiResponses()` | `OpenAi.PROVIDER`, `OpenAi.RESPONSES` | `Providers.OPENAI`, `OpenAi.RESPONSES` |
| `service(ServiceKey<S>)` | `raw()` | `providerApi(ProviderApi<T>)`, experimental `raw()` |
| typed provider options | `OptionKey<T>`, `QuirkKey<T>` | scoped `OptionKey<T>`, `QuirkKey<T>` |
| `inspect()` → `RequestAssessment` | `prepare()` → `PreparedRequest` | `preview()` → `PreparedRequest` |
| `connection().check(…)` → `CheckReport` | `testConnection()` → `ConnectionCheck` | `connection().test()` → `ConnectionReport` |
| `models().list(ModelQuery)` → `ModelPage` | `models().list()` | `models().list()`, `refresh()`, `get(…)`, `cached()` |
| `Fact<T>`, capability status | `SupportLevel`, `source()` | `SupportLevel`, `ModelInfo.source()`, `fetchedAt()` |
| `ParameterDescriptor` | `ConfigField` | `FieldDescriptor` |
| `CredentialSource`, `Secret` | `Credentials`, `ApiKey` | sealed `Credentials`, `ApiKey`, `Secret` |
| `OAuthCredentials`, `AuthorizationSession`, `AuthorizationInstruction` | `AuthApi`, `LoginSession`, `AuthPrompt` | `OAuthCredentials` implementing `AuthApi`, `LoginSession`, `LoginPrompt` |
| `TokenStore` | `TokenStore` keyed by endpoint id | `TokenStore` keyed by issuer, client id and account |
| host browser adapter | `BrowserLauncher` | `BrowserLauncher` |
| `JsonDocument` | `JsonValue` | `JsonValue`, `Json` |
| `FailureDetails` | fields on `LlmException` | fields on `LlmException`, open `ErrorCode` |
| — | `ResponseFormat` | `OutputFormat`, including record types |
| — | `CacheHint` | `cacheBreakpoint()`, `PromptCache`, `ResponseCache` |
