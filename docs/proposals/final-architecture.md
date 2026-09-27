# LLM Transport SDK — Final Architecture

*One design for Java 26 + Gradle: proposals A and B, their first merge, and the lessons of pi-ai and pi-telemetry, combined*

| | |
|---|---|
| **Status** | Final architecture proposal: the backbone to implement against, not an implementation |
| **Date** | 27 September 2026 |
| **Stack** | Java 26 (toolchain and `--release 26`), Gradle 9 (Kotlin DSL, version catalog, convention plugins) |
| **Supersedes** | [`final-architecture-mix.md`](final-architecture-mix.md) and [`architecture-v2-pi-informed.md`](architecture-v2-pi-informed.md), kept as history |
| **Inputs** | [`goals.md`](../../goals.md) · **A** = [`architecture-proposal-transportsdk.md`](architecture-proposal-transportsdk.md) · **B** = [`llm-transport-sdk-architecture.md`](llm-transport-sdk-architecture.md) · the four requirement documents · **pi** = `examples/ai` (`@earendil-works/pi-ai` 0.87.1) and `examples/telemetry` (`pi-telemetry`) · the `java-sdk-design` skill · the product owner's decisions of 27 September 2026 (§1.2) |
| **Naming** | Root package `net.ai.gate` (decided, D10); the Maven group id and the artifact prefix `llm-transport-` remain placeholders; model ids in examples are illustrative |

Code blocks are compile-shaped API sketches; `…` marks an omitted body. §20 lists what was compiled or
executed while preparing the design and what is still unverified. Tags such as **[A]**, **[B]**,
**[pi]**, **[FM]** (the first merge) and **[new]** show where a decision came from.

| You are… | Read |
|---|---|
| deciding whether to adopt the design | §0, §1, §2, §4 |
| an application developer | §3, §4, §9 (configuration), §10 (execution contracts), §7.15 (errors) |
| a UI / workflow-builder integrator | §4.11–§4.14, §11 (model catalog), §13 (auth), §15 (UI guide) |
| a provider or protocol author | §8 (SPI, provider modules, recipes), §14 (portability rules) |
| planning the implementation | §5–§8, §18–§20 |

---

## 0. The design on one page

`Llm` is a thread-safe, closeable runtime over a set of configured **providers**. Callers pick a
**model** — a catalog entry that names its provider and wire API and carries its limits, reasoning
levels and prices — and call `llm.complete(model, conversation)` or `llm.stream(…)`. The call goes to
the provider the model names: never to another provider, model or credential. A **conversation** is
portable, serializable data; the reply is an **`AssistantMessage`** that is appended to it, and the SDK
adapts the history when the next call targets a different model. A provider is **data** (base URL,
compat flags, auth chain, bundled models); a wire protocol is a **pure codec**; an API version is a
**codec revision**. The core owns everything that must behave identically for every provider:
credential resolution and refresh, deadlines, retries, SSE/NDJSON framing, stream aggregation,
hand-off rules, cost, caching, events, JFR and redaction.

```java
try (Llm llm = Llm.create()) {                                     // provider modules on the path; keys from the environment
    Model sonnet = llm.model("anthropic", "claude-sonnet-5");     // catalog entry: limits, prices, reasoning levels
    AssistantMessage reply = llm.complete(sonnet, "Explain Java records in two sentences.");
    System.out.println(reply.text());
    System.out.println(reply.usage());                             // Usage[input=21, output=64, cacheRead=0, cacheWrite=0, cost=USD 0.00102]
}
```

Switching to OpenAI, Gemini, DeepSeek, OpenRouter, a corporate gateway or a local Ollama changes the
model argument, not the calling code — even in the middle of a conversation (§4.3). Everything else is
disclosed progressively from the same entry point.

**Fifteen decisions that shape the SDK**

1. **A multi-provider runtime with explicit dispatch.** One `Llm`; `complete(model, …)` goes to
   `model.providerId()`. No routing, no fallback, no hidden billing **[pi, FM]**.
2. **The model is data.** Catalog entries carry context window, output limit, reasoning levels,
   modalities, prices and compat flags. The freshest data wins across bundled data, a separately
   released catalog artifact, metadata feeds and live listings, refreshed in the background (§11)
   **[pi, owner D4, D11]**.
3. **The reply is the history entry.** `conversation.append(reply)` continues any conversation with any
   model; documented hand-off rules adapt history, and reasoning is carried over by default (§14.4)
   **[pi, owner D2]**.
4. **Provider = data, protocol = pure codec, API version = codec revision.** Adding a compatible
   provider is a preset; adding a wire format is a `WireApi`; provider quirks are typed compat flags
   **[B, pi]**.
5. **Adapt visibly, fail on request.** Settings a model cannot honour are adapted with a `Warning`;
   `strict()` turns adaptations into failures; foreign provider options are inert **[new, owner D3]**.
6. **One `ChatOptions` per call.** Three configured scopes (runtime, provider, call) plus catalog-derived
   defaults; requests stay pure data **[new]**.
7. **Streaming is a single-use, closeable iterable of sealed events** whose `result()` equals the
   `complete()` result, with bounded accumulation and live partial tool arguments **[B, A, pi]**.
8. **Cancellation by `CancelToken` trees** — one Stop button for a whole workflow run;
   `completeAsync()` on virtual threads **[A, pi, new]**.
9. **Caching in plain terms.** Provider prompt caching is on by default (`SHORT`; `NONE` and `LONG`
   configurable at every scope), explicit breakpoints for experts, and an opt-in response cache that
   doubles as test cassettes **[owner D1, pi, FM]**.
10. **Output formats from plain text to typed Java records** — `complete(model, conversation,
    Invoice.class)`, JSON Schema derived from records, typed tool arguments, without third-party
    dependencies **[FM]**.
11. **Safe resilience defaults.** Retries only for responses providers document as *not processed*;
    never after an ambiguous failure or visible stream output; connect / stream-idle / total timeouts
    that fit long reasoning calls **[A, B, FM]**.
12. **Auth that UIs can drive.** A resolution chain that reports its `source`; one login protocol
    (`AuthInteraction.prompt/notify`) for API keys, browser OAuth, device codes and web redirects;
    atomic `CredentialStore.update`; OAuth in the core, JDK-only, with A's security contract
    **[pi, A, B]**.
13. **Zero third-party runtime dependencies in core**: JDK `HttpClient`, an internal JSON tree with
    stable key order, `System.Logger`, JFR **[B]**.
14. **Testing and debugging are features**: `FakeProvider`, cassettes, contract kits, `preview()` with
    `toCurl()`, `test()`, redacted wire logging, JFR, `describe()` **[A, B, pi, FM]**.
15. **A small surface**: eight nouns, five exported packages, seven listener events (§18.2)
    **[budget]**.

**Deliberately absent** (anti-goals, §3.4): agent loops and tool execution, routing and fallback between
providers, semantic caching, conversation stores, prompt templating, a proxy server, global mutable
state, and any third-party type on a public signature.

---

## 1. Lineage and decisions

### 1.1 Sources and what each contributed

| Source | Strongest in | Contributed |
|---|---|---|
| **A** | Contracts: submission certainty after failures, credential/destination binding, OAuth security for native and web hosts, one-shot cancellation, ownership | Outcome rules and `outcomeUnknown`, OAuth security contract, cancellation semantics, refusal as a result |
| **B** | Usage-first surface: provider as data, pure codecs, defaults and footgun tables, testing artifact, zero-dependency core, Gradle | Codec SPI, presets, testing kits, internal JSON, build layout |
| **FM** (first merge of A and B) | The contracts of A on the surface of B, plus a timeout model, record-typed outputs, debugging tools and Java 26 | Timeouts, retries, structured output, `preview()`/`toCurl()`, JFR, Gradle conventions, JPMS |
| **pi-ai** | A shape proven by a production coding agent: many providers at once, model as data, appendable replies, cross-provider hand-off, generated catalogs with prices, auth chains with sources, one login protocol, typed compat flags, a fake provider | The runtime shape (§0 decisions 1–3), catalog, auth protocol, compat flags, hand-off rules, `FakeProvider` |
| **pi-telemetry** | A tiny vendor-neutral telemetry contract: no exporter, no-op default, conformance kit, schema owned by the domain | The observability principle (§17) |

Rejected from pi, with reasons in §1.3: vendor SDKs inside adapters (per-provider timeout and retry
semantics), failures only as data, a live mutable `partial` shared across events, silently ignored
settings, `0` for unknown prices and limits, base-URL sniffing as the compat mechanism, consumer-
subscription OAuth presets, and the `stream`/`streamSimple` split.

### 1.2 Decision log

| # | Question | Decision | Alternatives considered | By |
|---|---|---|---|---|
| D1 | Provider prompt caching by default | **On** (`CacheRetention.SHORT`); `NONE` and `LONG` configurable per call, per provider and per runtime | Off unless requested (FM) | owner, 27 Sep 2026 |
| D2 | Reasoning when the next call uses another model | The requested **reasoning level** maps to the equivalent level the new model supports (§14.4). **Reasoning content** (the model's thinking text) is kept and passed to the new model by default (`ReasoningHandoff.KEEP`); `DROP` is configurable | Strip foreign reasoning (FM) | owner |
| D3 | Settings a model cannot honour | Soft adaptation with a `Warning`; `strict()` fails instead | Fail by default (FM) | owner |
| D4 | Model metadata | **Prefer the freshest data**: per-field merge by timestamp across bundled data, a separately released catalog artifact, metadata feeds and live listings; background refresh on by default | Live list first with TTL (FM); bundled catalog only (pi) | owner |
| D5 | Usage token buckets | Disjoint (`input` excludes cache reads and writes) plus `totalInput()`, so cost is a dot product | Inclusive input (FM) | design |
| D6 | Failures during a call | Unchecked exceptions carrying the partial reply | Failures as data (pi) | design |
| D7 | Where I/O lives | The core; codecs are pure functions | Vendor SDK adapters (pi); adapters drive I/O (A) | design |
| D8 | Unit of configuration | A `Provider` instance inside one multi-provider `Llm` | One client per endpoint (FM) | design |
| D9 | Java baseline | Java 26 (toolchain and `--release 26`) | Java 25 LTS | owner, 27 Sep 2026 |
| D10 | Root package | `net.ai.gate` (`net.ai.gate.*`); JPMS module names follow it | placeholder | owner |
| D11 | Runtime metadata feed | Enabled by default: the background refresh also contacts the public metadata feed; no credentials, prompts or identifiers are sent; `noFeeds()` or `offline()` for restricted networks | Opt-in feed | owner |

### 1.3 Findings the design resolves

| ID | Origin | Finding | Resolution |
|---|---|---|---|
| A1 | A | Provider, credentials and model were optional builder steps | Presets carry provider data; credentials resolve through a chain that names missing variables; the model is a call argument |
| A2 | A | No automatic retry even for `429`/`503`/`529` | Retry documented not-processed responses by default (§10.3) |
| A3 | A | 5-minute total deadline including stream consumption | Connect / stream idle / total 10 min (§10.2) |
| A5 | A | About twenty concepts on the main paths | Eight nouns (§3.3) |
| A6 | A | Adapters drive HTTP exchanges | Core owns I/O; codecs are pure (D7) |
| B1 | B | `send(String model, String text)` swaps silently | `Model` values; `llm.model(provider, id)` fails at once on an unknown provider, naming the known ids |
| B2 | B | First-byte timeout aborts long non-streamed calls | No first-byte timeout (§10.2) |
| B3 | B | Timeout inside the persisted request | Operational policy lives in `ChatOptions`, conversations stay data |
| B4 | B | `send()` cancellable only by interrupt | `CancelToken` (§10.5) |
| B5 | B | Login URLs and device codes broadcast to listeners | Delivered only to the `AuthInteraction` of the `login()` caller (§13.5) |
| B6 | B | OAuth tokens keyed by endpoint id | Credentials keyed by provider id within a store scope; issuer and client bound into the credential (§13.5) |
| B8 | B | Output refusal modelled as an exception | Refusal is a stop reason and a content part |
| B11 | B | `504` retried by default | Not retried; `outcomeUnknown` (§10.3) |
| F1 | FM | One client per endpoint: multi-model hosts keep maps of clients | Multi-provider `Llm` (D8) |
| F3 | FM | Only reasoning parts adapted on a model switch | Full hand-off table (§14.5) |
| F4 | FM | Model metadata expected from list endpoints that rarely return limits or prices | Catalog with freshest-wins merge (§11) |
| F6 | FM | Five configuration scopes plus `omit(Param)` | Three scopes plus catalog-derived defaults (§9.1) |
| F8 | FM | Four cache layers with seven public types | Three public cache types (§12) |
| F9 | FM | Closed login prompt set; about nineteen auth types | One login protocol (§13.3) |
| F11 | FM | Generic `OptionKey`/`QuirkKey` constants | Typed `ProviderOptions` and `ApiCompat` classes |
| F13 | FM | `FAIL` by default breaks model switching | D3 |
| pi-w1 | pi | Vendor SDKs: timeouts and retries "for SDKs that support it" | D7 |
| pi-w3 | pi | Failures after a stream starts are only data | D6 |
| pi-w4 | pi | Live mutable `partial` shared across events | Immutable events and snapshots |
| pi-w5, w6 | pi | Silently ignored settings; `0` for unknown prices | Warnings (D3); absent values (principle 4) |

---

## 2. Design principles

1. **Common path first.** Layer 1 is a factory and one call with defaults. Layer 2 configures the same
   entry point. Layer 3 is a documented door one level down, never an internal class.
2. **Hide wiring, expose consequence.** Callers never assemble codecs, framers, retry loops or token
   refreshers. They do see and decide anything that costs money, opens a browser, retries, caches,
   adapts a setting or owns a resource.
3. **Provider is data, protocol is a pure codec, model is data, version is a revision.** Adding DeepSeek
   is a preset; adding a wire format is a codec; adopting a new API revision is a new codec constant;
   a new model is catalog data.
4. **Honest unknowns.** Unknown capability, price, usage or limit is absent or `UNKNOWN`, never `false`
   or `0`. Unknown content parts, events and enum values are preserved, never fatal.
5. **Never send what nobody chose; adapt visibly.** Unset sampling values are not sent. Values derived
   from the catalog (output limit, reasoning mapping) and the prompt-cache default are visible in
   `preview()`. Every adaptation is a `Warning`; `strict()` makes soft adaptations fail.
6. **One conversation, any model.** A `Conversation` is portable data; the SDK adapts history to the
   target model by documented rules and reports every adaptation.
7. **Sync-first.** Blocking calls return or throw; streams are closeable, single-use and bounded; async
   is `completeAsync()` on virtual threads. Runtimes are shared across threads, including virtual threads.
8. **Immutable values, thread-safe runtimes, explicit ownership.** Every public type states which of the
   three it is; every resource has one owner.
9. **Secrets never share a document with configuration.** Keys and tokens are types that redact
   everywhere; provider configuration is serializable and has no credential fields; credentials live
   in a `CredentialStore`.
10. **I/O only in verbs and in configured background work.** `build()`, accessors, `preview()`,
    `describe()`, catalog reads and form rendering never touch the network; `complete`, `stream`,
    `test`, `refresh` and `login` do. The catalog's background refresh (§11.4) is configuration — on by
    default, never on the caller's thread.
11. **Explicit beats fresh, fresh beats old.** Host-configured values always win; among data sources the
    newest non-absent value wins.
12. **Zero-dependency core.** JDK only, plus compile-time annotations. Heavy or opinionated integrations
    live in optional modules.
13. **Testability and debuggability are product features**, shipped with the library.
14. **Additive evolution under a complexity budget.** Every public type traces to a use case (§18.2).

---

## 3. Consumer model

### 3.1 Definition

> **A Java library that connects to any LLM provider or gateway — authenticating (OAuth included),
> discovering models with current limits and prices, and running conversations with tools, structured
> output and streaming that survive switching models — through one portable, typed API.**

### 3.2 Consumers

| Consumer | Needs | Primary surface |
|---|---|---|
| AI coding agent (CLI, IDE, desktop) | Several providers, model switching mid-session, streaming with tool calls, reasoning continuity, Stop, usage and cost, prompt caching, OAuth from a terminal | `complete`/`stream`, `Conversation`, `ChatStream`, `CancelToken`, `auth()`, `AuthInteraction.console()` |
| Visual node-based workflow builder | Provider list, "connect provider" dialogs, auth status, model picker with prices and limits, parameter forms, test connection, run with progress and Stop, re-run without re-billing, secret-free save/load | `Providers.presets()`, `auth()`, `models().available()`, `Model.parameters()`, `test()`, events, `CancelToken`, response cache, `ProvidersConfig` |
| Multi-user web backend | Per-user credentials, web OAuth redirects, no operator keys leaking to tenants | `withCredentials(store.scoped(user))`, `AuthInteraction.redirect(…)`, `Environment.none()` |
| Library author building on top | Stable model types, fakes, events | model types, `llm-transport-testing` |
| Provider or protocol author | Small SPI, contract kit, fixtures | `WireApi`, `StreamDecoder`, `ApiCompat`, `WireApiContract` |

Runtime: JVM 26+, desktop and server, module path or classpath. Browser frontends call a backend that
uses the SDK; the SDK does not run in a browser.

### 3.3 Glossary — eight nouns

| Concept | Public types | One line |
|---|---|---|
| **Llm** | `Llm` | The thread-safe runtime over configured providers: transport, credentials, catalog, listeners |
| **Provider** | `Provider` | A configured backend: id, base URL, wire APIs, compat flags, auth strategy, models |
| **Model** | `Model`, `ModelRef` | A catalog entry naming its provider and API, with limits, reasoning levels and prices |
| **Conversation** | `Conversation`, `Message`, `Content` | System prompt, tools and messages; portable and serializable |
| **Reply** | `AssistantMessage`, `ChatStream`, `ChatEvent` | The model's turn, whole or streamed; appendable to the conversation |
| **Tool** | `Tool`, `ToolCall`, `ToolResult` | A function the model may call; the SDK transports, the host executes |
| **Options** | `ChatOptions` | Per-call generation, caching and operational settings |
| **Credential** | `Credential`, `CredentialStore`, `AuthInteraction` | What authenticates a provider, where it is kept, how a user supplies it |

Everything else (`Usage`, `CancelToken`, `TimeoutPolicy`, `ReasoningLevel`, `JsonValue`, …) is reached
from these nouns by IDE completion. The Simple path needs three types: `Llm`, `Model`, `AssistantMessage`.

### 3.4 Anti-goals (README section)

Not an agent framework: no tool execution, loops, planning or memory. Not a prompt-template engine. Not
a conversation store. Not a retrieval or vector library. Not a UI or CLI. Not a proxy or gateway server.
No routing, fallback, key pools or client-side rate limiting *between* providers — dispatching a call to
the provider its model names is not routing. No semantic caching. No cost accounting beyond `Usage`,
`Cost` and `Prices`. No hidden provider or model switching. No global mutable state. Environment
variables are read only through the documented auth resolution chain, which a host can disable
(`Environment.none()`).

### 3.5 Progressive disclosure

| Layer | Who | Surface |
|---|---|---|
| 1 — defaults | most callers | `Llm.create()` → `llm.model(provider, id)` → `llm.complete(model, text)` |
| 2 — configuration | configured callers | `Llm.builder()…`, `Provider` presets and `toBuilder()`, `ProvidersConfig`, `Conversation.builder()`, `ChatOptions.builder()`, `auth().login(…)` |
| 3 — escape hatches | advanced callers | typed `ProviderOptions` (`AnthropicOptions`), the payload hook, `WireInterceptor`, `providerApi(…)` (`Gemini.CACHES`), `Content.Unknown`, a custom `WireApi` |

---

## 4. Usage first

These examples are the README and are compiled as tests in the `examples` module (§20). Java 26 syntax
is used throughout; model ids are illustrative.

### 4.1 Simple — the 80 % case

```java
try (Llm llm = Llm.create()) {                                  // discovered providers; keys from the environment
    Model model = llm.model("anthropic", "claude-sonnet-5");
    AssistantMessage reply = llm.complete(model, "Explain Java records in two sentences.");
    System.out.println(reply.text());
    System.out.println(reply.usage());   // Usage[input=21, output=64, cacheRead=0, cacheWrite=0, cost=USD 0.00102]
}
```

Nothing to wire: provider presets from the modules on the path, the JDK HTTP transport, default
timeouts and retries, prompt caching on, a catalog that refreshes itself in the background. A missing
key fails at the call with the variable names (`ANTHROPIC_API_KEY`); an unknown provider fails at
`model(…)` with the known ids.

### 4.2 Configured runtime — a superset of Simple, same entry point

```java
try (Llm llm = Llm.builder()
        .provider(Providers.anthropic())
        .provider(Providers.openai().toBuilder().id("openai-work").header("OpenAI-Project", "proj_42").build())
        .provider(OpenAiCompatible.ollama())                                // keyless; local-model timeouts as provider defaults
        .credentials(CredentialStore.file(home.resolve(".my-agent/credentials.json")))
        .catalog(c -> c.snapshotFile(home.resolve(".my-agent/models.json")))  // fresh data survives restarts
        .defaults(o -> o.maxTokens(8_192).timeouts(t -> t.total(Duration.ofMinutes(20))))
        .responseCache(ResponseCache.inMemory(1_000))                      // opt-in: re-run without re-billing
        .interceptor(tracing::addHeaders)                                  // opt-in: per-attempt wire hook
        .listener(metrics::record)
        .build()) {

    Conversation review = Conversation.builder()
            .system("You are a terse senior Java reviewer.")
            .user("Review this diff:\n" + diff)
            .build();

    AssistantMessage reply = llm.complete(llm.model("anthropic", "claude-sonnet-5"), review,
            ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).build());
}
```

### 4.3 Switch provider, model or API version without rewriting code

One conversation, three providers:

```java
Conversation chat = Conversation.of("What is 25 * 18?");

AssistantMessage claude = llm.complete(llm.model("anthropic", "claude-sonnet-5"), chat,
        ChatOptions.builder().reasoning(ReasoningLevel.HIGH).build());
chat = chat.append(claude).appendUser("Is that calculation correct?");

AssistantMessage gpt = llm.complete(llm.model("openai", "gpt-5.1"), chat);     // Claude's reasoning passed on as text (§14.4)
chat = chat.append(gpt).appendUser("What was the original question?");

AssistantMessage gemini = llm.complete(llm.model("google", "gemini-2.5-pro"), chat);
gemini.warnings().forEach(log::info);                                           // e.g. reasoning_converted, tool_call_id_normalized
```

The same request against many providers:

```java
Conversation question = Conversation.of("Summarise these release notes:\n" + notes);
for (ModelRef ref : List.of(new ModelRef("openai", "gpt-5.1"), new ModelRef("anthropic", "claude-sonnet-5"),
                            new ModelRef("deepseek", "deepseek-chat"), new ModelRef("ollama", "qwen3:8b"))) {
    System.out.println(ref + ": " + llm.complete(llm.models().require(ref), question).text());
}
```

Same provider, different protocol; pinned protocol revision:

```java
Provider openAiChat = Providers.openai().toBuilder()
        .id("openai-chat")
        .defaultApi(OpenAi.CHAT_COMPLETIONS)    // OpenAI's default API is OpenAi.RESPONSES
        .build();

// A WireApi constant names one tested protocol revision. An incompatible revision becomes a new constant
// (for example OpenAi.RESPONSES_V2); a preset changes its default only in a minor release, with a changelog entry.
```

### 4.4 Streaming

```java
try (ChatStream stream = llm.stream(model, chat)) {                  // AutoCloseable: close() cancels
    for (ChatEvent event : stream) {                                  // blocks per event on the calling thread
        switch (event) {
            case ChatEvent.TextDelta d                -> ui.appendText(d.text());
            case ChatEvent.ReasoningDelta d           -> ui.appendThinking(d.text());
            case ChatEvent.ToolCallDelta d            -> d.partialArguments().get("path").ifPresent(ui::showWriting);
            case ChatEvent.PartEnd(int i, ToolCall c) -> ui.showToolCall(c);
            default                                   -> { }               // Started, Done, Unknown, future variants
        }
    }
    chat = chat.append(stream.result());                              // equals what complete() would have returned
}
```

The one-line form for command-line tools:

```java
try (ChatStream stream = llm.stream(model, Conversation.of("Write a haiku about Gradle."))) {
    stream.textDeltas().forEach(System.out::print);
    System.out.println();
    System.out.println(stream.result().usage());
}
```

A "Stop" button cancels a `CancelToken` or calls `stream.close()` from any thread; the iterating thread
receives `RequestCancelledException`, whose `partial()` is an appendable reply with what was received.

### 4.5 Tools — the SDK transports, the host executes

```java
record ReadFile(@Description("Workspace-relative path") String path) {}

Tool readFile = Tool.of("read_file", "Read a UTF-8 text file from the workspace", ReadFile.class);

Conversation chat = Conversation.builder()
        .system("You are a coding assistant. Use tools to inspect the workspace.")
        .tool(readFile)
        .user("What does build.gradle.kts configure?")
        .build();

AssistantMessage reply = llm.complete(model, chat);
for (int turn = 0; reply.hasToolCalls() && turn < 8; turn++) {        // the host owns the loop and its budget
    List<ToolResult> results = reply.toolCalls().stream()
            .map(call -> ToolResult.of(call, workspace.read(call.arguments(ReadFile.class).path())))
            .toList();
    chat = chat.append(reply, results);                                  // the assistant turn (signatures included) + results
    reply = llm.complete(model, chat);
}
System.out.println(reply.text());
```

`Tool.of(…, ReadFile.class)` derives the JSON Schema from the record; `call.arguments(ReadFile.class)`
binds the arguments back. Hosted tools come from provider modules (`OpenAiTools.webSearch()`,
`AnthropicTools.codeExecution()`); tool choice uses `ToolChoice.auto()`, `none()`, `required()` or
`only("read_file")`.

### 4.6 Structured output and output formats

```java
record LineItem(String description, int quantity, BigDecimal unitPrice) {}
record Invoice(String number, LocalDate issued, List<LineItem> items, BigDecimal total) {}

Invoice invoice = llm.complete(model, Conversation.of("Extract the invoice:\n" + pdfText), Invoice.class);
```

The typed overload sets `OutputFormat.of(Invoice.class)` (strict JSON Schema where the API supports it,
a documented adaptation otherwise), sends, and binds the result. A truncated, refused or non-conforming
answer throws `InvalidResponseException` with code `output_truncated`, `output_refused` or
`output_invalid`; the reply stays available through `e.partial()`.

| Output format | Request | Read the result with |
|---|---|---|
| Plain text (default) | nothing, or `output(OutputFormat.text())` | `reply.text()` |
| JSON | `output(OutputFormat.json())` | `reply.json()` → `JsonValue` |
| JSON Schema | `output(OutputFormat.jsonSchema(schema))` | `reply.json()` or `reply.as(type)` |
| Java record | `output(Invoice.class)` or `complete(model, conversation, Invoice.class)` | `reply.as(Invoice.class)` |
| Streamed text | `stream(…)` | `ChatEvent.TextDelta`, `stream.textDeltas()` |
| Media output (image, audio) | provider options, then portable settings when two APIs agree | `ChatEvent.PartEnd`, `reply.content()` |
| Native payload | — | `Content.Unknown`, `ChatEvent.Unknown`, `reply.info().rawBody()` (non-streamed) |
| Persisted form | — | `Json.valueOf(conversation)` (canonical, versioned JSON) |

### 4.7 Multimodal input

```java
AssistantMessage answer = llm.complete(model, Conversation.builder()
        .user(Content.text("Why does this build fail? The spec is attached."),
              Content.image(Path.of("build-error.png")),        // read when the request is encoded, not now
              Content.document(Path.of("spec.pdf")))
        .build());
```

Paths are read at encode time; URLs are passed to APIs that accept them (fetched by the SDK only if the
API requires inline data and the call opted in); provider file ids stay scoped to their provider
(`Content.fileRef(…)`). For a model without image input the image becomes a placeholder text with a
warning, or fails under `strict()`.

### 4.8 Reasoning and provider-specific options

```java
ChatOptions tuned = ChatOptions.builder()
        .reasoning(ReasoningLevel.HIGH)                                          // portable; mapped per model (§14.4)
        .provider(AnthropicOptions.builder().thinkingBudget(12_000).build())     // read only by anthropic-messages
        .provider(OpenAiResponsesOptions.builder().serviceTier("flex").reasoningSummary(DETAILED).build())
        .payload(body -> body.with("metadata", Json.object("user_id", "u-17")))  // last edit of the wire body
        .build();
// On any other API the foreign provider options are inert and reported as Warning("option_not_applicable").

List<ReasoningLevel> levels = model.reasoningLevels();   // for UIs: exactly the levels this model supports
```

### 4.9 Prompt caching

On by default (D1): every call uses `CacheRetention.SHORT`, and codecs place cache markers where the API
needs them (end of tools and system prompt, end of the history). Turn it off or extend it at any scope:

```java
Llm llm = Llm.builder().defaults(o -> o.cacheRetention(CacheRetention.NONE)).build();   // runtime-wide off
ChatOptions longLived = ChatOptions.builder()
        .cacheRetention(CacheRetention.LONG)                  // e.g. Anthropic 1 h, OpenAI extended retention
        .sessionId("session-" + sessionId)                    // cache routing and affinity where supported
        .build();

Conversation expert = Conversation.builder()                  // experts may place markers themselves
        .system(projectInstructions).tools(workspaceTools)
        .cacheBreakpoint()                                    // prefix 1: tools + system
        .messages(history)
        .cacheBreakpoint()                                    // prefix 2: the conversation so far
        .user(nextQuestion)
        .build();

Usage usage = llm.complete(model, expert, longLived).usage(); // cacheRead() / cacheWrite() show the effect
```

### 4.10 Cancellation, async and per-run policy

```java
CancelToken run = CancelToken.create();                        // one token for every node of a workflow run
ui.onStop(run::cancel);

ChatOptions runOptions = ChatOptions.builder()
        .cancel(run.child())                                   // cancelled with the run
        .tag("workflowRun", runId)                             // copied onto every event, log line and JFR record
        .listener(runPanel::onEvent)                           // added to the runtime's listeners for these calls
        .responseCache(CacheMode.REFRESH)                      // "force re-run": skip reads, write results
        .timeouts(t -> t.total(Duration.ofSeconds(90)))
        .build();

llm.completeAsync(model, chat, runOptions)                     // a virtual thread; cancelling the future cancels the call
   .thenAccept(ui::showResult);
```

### 4.11 Configuration-driven providers and generated forms

```java
for (Provider preset : Providers.presets()) {                                   // bundled presets
    form.addProvider(preset.id(), preset.name(), preset.apiKeyUrl());
    preset.apiKeyAuth().ifPresent(a -> a.fields().forEach(f -> form.addField(preset.id(), f)));  // key + settings
}

Provider gateway = OpenAiCompatible.custom("corp-gw", URI.create("https://llm-gw.corp.example/v1")).toBuilder()
        .auth(ApiKeyAuth.bearer("Gateway key", "CORP_GW_KEY"))
        .compat(OpenAiCompletionsCompat.builder().maxTokensField("max_tokens").developerRole(false).build())
        .model(Model.builder("corp-gw", "gpt-5.1").contextWindow(400_000).maxOutputTokens(128_000)
                .reasoningLevels(LOW, MEDIUM, HIGH).build())
        .build();

String saved = ProvidersConfig.write(List.of(gateway));                         // no credential fields exist
List<Provider> restored = ProvidersConfig.read(saved, Providers.presets());
```

```json
{
  "schema": "llm-transport.providers/1",
  "providers": [
    { "id": "corp-gw", "preset": "openai-compatible", "baseUrl": "https://llm-gw.corp.example/v1",
      "headers": { "X-Tenant": "team-42" },
      "compat": { "maxTokensField": "max_tokens", "developerRole": false },
      "defaults": { "cacheRetention": "none" },
      "models": [ { "id": "gpt-5.1", "contextWindow": 400000, "maxOutputTokens": 128000,
                    "reasoning": ["low", "medium", "high"] } ] },
    { "id": "work-anthropic", "preset": "anthropic" }
  ]
}
```

Keys and tokens for `corp-gw` and `work-anthropic` live in the credential store under those ids.

### 4.12 Authentication and OAuth

**Status and "connect provider" dialogs — one adapter for every provider and flow:**

```java
AuthInteraction dialog = new AuthInteraction() {
    @Override public String prompt(AuthPrompt p) {             // blocks the login thread until the user answers
        return switch (p) {
            case AuthPrompt.SecretText s -> ui.askSecret(s.message());
            case AuthPrompt.Text t       -> ui.ask(t.message());
            case AuthPrompt.Select s     -> ui.choose(s.message(), s.options());
            case AuthPrompt.Code c       -> ui.askCode(c.message());   // pre-empted when the loopback callback wins
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

AuthStatus status = llm.auth().status("openrouter");   // no network: NOT_CONFIGURED, or CONFIGURED via "OPENROUTER_API_KEY"
if (status.state() == AuthStatus.State.NOT_CONFIGURED) {
    llm.auth().login("openrouter", AuthType.OAUTH, dialog);   // runs the provider's flow; stores the credential
}
```

**Terminal (device code or loopback), and a static form without a dialog:**

```java
llm.auth().login("openrouter", AuthType.OAUTH, AuthInteraction.console());   // prints URL or code, opens a browser if possible
llm.auth().save("deepseek", new ApiKeyCredential(Secret.of(formKey), Map.of()));
```

**Web backend, many users.** Each user gets a view over their own credentials; the login runs on a
virtual thread; `notify(OpenUrl)` makes the host redirect the browser; `prompt(Code)` waits until the
callback route supplies the redirect URI; the flow validates `state` and PKCE (§13.5).

```java
Llm alice = llm.withCredentials(userStore.scoped("user-8412"));      // shares providers, transport and catalog
RedirectInteraction web = AuthInteraction.redirect(URI.create("https://app.example/oauth/callback"), session::redirectTo);
Thread.startVirtualThread(() -> alice.auth().login("openrouter", AuthType.OAUTH, web));

// GET /oauth/callback — after the host has checked that this session belongs to user-8412:
web.complete(request.fullUri());
```

### 4.13 Discovery for a workflow-builder UI

```java
ConnectionReport report = llm.test(model);                  // non-billable: configuration → network → authentication → model access
report.steps().forEach(step -> ui.showStep(step.kind(), step.status(), step.latency(), step.message()));

for (Model m : llm.models().available()) {                  // models of configured providers; never blocks on the network
    ui.addModel(m.ref(), m.name(),
            m.capabilities().support(Capability.TOOLS),     // SUPPORTED / UNSUPPORTED / UNKNOWN
            m.contextWindow(),                              // OptionalLong: absent means "not known"
            m.prices().flatMap(Prices::inputPerMillion),
            m.updatedAt());                                 // how fresh the data is
}

List<FieldDescriptor> parameterForm = model.parameters();   // derived from the catalog: reasoning levels, output limit…
RefreshReport refreshed = llm.models().refresh("openrouter", "ollama");   // explicit refresh now; errors per provider
```

### 4.14 Events for progress and status

```java
Registration registration = llm.addListener(event -> {
    switch (event) {
        case RequestEvent.Started s     -> ui.progress(s.requestId(), "sending to " + s.model());
        case RequestEvent.FirstOutput f -> ui.progress(f.requestId(), "streaming after " + f.latency().toMillis() + " ms");
        case RequestEvent.Retrying r    -> ui.progress(r.requestId(), "retry " + r.attempt() + " in " + r.delay());
        case RequestEvent.Finished f    -> ui.done(f.requestId(), f.outcome(), f.usage(), f.fromCache());
        case CredentialEvent.RefreshFailed c -> ui.status("Sign-in expired for " + c.providerId());
        default -> { }
    }
});
registration.close();                                          // or released automatically by llm.close()
```

Listeners observe; they cannot alter a call, and an exception thrown by one is logged and isolated.
Events carry ids, timings, usage, cost and errors — never prompts, outputs, login URLs or secrets.

### 4.15 Failure

```java
try {
    AssistantMessage reply = llm.complete(model, chat);
} catch (RateLimitedException e) {                              // retries already exhausted
    scheduleLater(e.retryAfter().orElse(Duration.ofSeconds(10)));
} catch (AuthenticationException e) {
    if (e.is(ErrorCode.LOGIN_REQUIRED)) ui.askToSignIn(e.providerId().orElseThrow());
} catch (InvalidRequestException e) {
    if (e.is(ErrorCode.CONTEXT_OVERFLOW)) chat = compact(chat);  // detected across providers, silent truncation included
} catch (LlmException e) {                                      // root: code, retryable, outcomeUnknown, httpStatus, ids, partial
    log.warn("LLM call {} failed: {} (outcome unknown: {})", e.requestId().orElse("n/a"), e.code(), e.outcomeUnknown());
    e.partial().ifPresent(p -> log.info("kept {} chars of partial output", p.text().length()));
}
```

### 4.16 Lifecycle

```java
try (Llm llm = Llm.create()) {                                // owns the JDK HttpClient and background refresh it created
    …
}                                                             // idempotent; cancels in-flight calls; later calls throw IllegalStateException

HttpTransport shared = HttpTransport.jdk();                   // one connection pool for several runtimes
Llm a = Llm.builder().http(h -> h.transport(shared)).build();  // borrowed: never closed by a runtime
```

### 4.17 Testing without network, keys or mocks

```java
@Test
void agentReadsTheFileThenSummarizes() {
    FakeProvider fake = FakeProvider.create()                   // provider "fake", models "fake" and "fake-thinker"
            .reply(r -> r.reasoning("Need the file first.").toolCall("read_file", Json.object("path", "a.txt")))
            .reply("Here is the summary.");
    try (Llm llm = Llm.of(fake.provider())) {                   // a real runtime over a scripted provider
        assertEquals("Here is the summary.", new Agent(llm, fake.model()).run("Summarize a.txt"));
    }
    assertEquals(2, fake.requests().size());                    // adapted conversations, as the codec received them
    fake.assertAllRepliesConsumed();
}
```

Replay recorded provider traffic in CI:

```java
Llm llm = Llm.builder()
        .responseCache(ResponseCache.directory(Path.of("src/test/resources/cassettes")))
        .defaults(o -> o.responseCache(CacheMode.OFFLINE))    // never touches the network; a miss fails and names the request
        .build();
// Record locally once with CacheMode.REFRESH and a real key; commit the cassette files.
```

### 4.18 Debugging

```java
PreparedRequest preview = llm.preview(model, chat, options);   // no network, no credentials
preview.problems().forEach(System.err::println);               // what complete() would reject, with field paths
preview.warnings().forEach(System.err::println);               // adaptations, e.g. reasoning_clamped XHIGH→HIGH
preview.notes().forEach(System.out::println);                  // derived values, e.g. max_tokens=64000 from the catalog
System.out.println(preview.body().toPrettyJson());             // exact wire JSON
System.out.println(preview.toCurl());                          // runnable; the key appears as $ANTHROPIC_API_KEY
System.out.println(llm.describe().toPrettyJson());             // effective configuration, redacted
```

Wire logging is one switch (`http(h -> h.wireLog(WireLog.HEADERS))`, redacted), and every call emits a
JFR event visible in JDK Mission Control (§17).

### 4.19 Escape hatches (Layer 3)

```java
ChatOptions nativeOptions = ChatOptions.builder()
        .provider(AnthropicOptions.builder().thinkingBudget(8_192).beta("interleaved-thinking-2025-05-14").build())
        .payload(body -> body.with("metadata", Json.object("user_id", "u-17")))
        .build();

GeminiCaches caches = llm.providerApi("google", Gemini.CACHES);                 // typed provider-only API
CachedContent corpus = caches.create(model, List.of(UserMessage.of(bigCorpus)), Duration.ofHours(2));
```

### 4.20 Kotlin — no wrapper needed

```kotlin
Llm.create().use { llm ->
    val model = llm.model("anthropic", "claude-sonnet-5")
    val reply = llm.complete(model, Conversation.builder().system("Be terse.").user("Why sealed types?").build())
    println(reply.text())
    llm.stream(model, Conversation.of("Count to three")).use { stream ->
        for (event in stream) if (event is ChatEvent.TextDelta) print(event.text())
    }
}
```

**Wrong-usage check.** A missing key fails at the call with the variable names; an unknown provider
fails at `model(…)` with the known ids; forgetting to close a stream is visible (`AutoCloseable` in every
example); nothing performs I/O until a verb (`complete`, `stream`, `test`, `refresh`, `login`) runs,
apart from the configured background catalog refresh; the core never guesses a provider or switches a
model.

---

## 5. Architecture

### 5.1 Layers and dependency direction

```
┌──────────────── PUBLIC API  net.ai.gate · .auth · .event · .json ──────────────────────────────────┐
│ Llm ─ complete  completeAsync  stream  preview  test  describe  models()  auth()  withCredentials      │ facade
│ Model · ModelRef · Provider · Conversation/Message/Content · Tool · AssistantMessage · ChatStream/Event │ portable model (immutable)
│ ChatOptions · CancelToken · TimeoutPolicy/RetryPolicy · Usage/Cost/Prices · LlmException · Json        │ options, results, errors
│ Credential · CredentialStore · AuthInteraction · AuthStatus · LlmListener/LlmEvent                     │ auth, observation
├──────────────── EXECUTION CORE  (internal, not exported) ─────────────────────────────────────────────┤
│ Resolver → HandoffTransformer → Engine → AttemptRunner (retry, deadline) → StreamPump → Accumulator    │ one pipeline for all APIs
│ AuthResolver · OAuthFlows · CatalogService (merge, refresh, snapshot) · ResponseCacheStage · Cost     │
│ OverflowDetector · EventDispatcher · JfrRecorder · Redactor · SSE/NDJSON framers · IdleWatchdog · JSON │
├──────────── SPI for protocol and provider authors ─────┬──────────── SPI fed by hosts ─────────────────┤
│ WireApi · StreamDecoder · ApiCompat · ProviderOptions   │ HttpTransport · WireInterceptor · ResponseCache│
│ ApiKeyAuth · OAuthAuth · ModelSource · CatalogFeed      │ CredentialStore · AuthInteraction · JsonMapper │
│ ProviderApi + ProviderApiContext · ProviderBundle       │ LlmListener                                    │
├─────────────────────────────────────────────────────────┴────────────────────────────────────────────────┤
│ Provider modules: openai (Responses, Chat Completions + compatible presets) · anthropic (Messages) ·      │
│ google (generateContent) · catalog data (independently released) · later: bedrock, vertex, ollama native  │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
  compile-time:  application → llm-transport (aggregate) → provider modules → core → JDK
  core never depends on provider modules, UI toolkits, cloud SDKs, JSON libraries or agents.
```

### 5.2 One call, end to end

```
complete(model, conversation, options)
 ─► provider = providers[model.providerId()]  unknown id → IllegalArgumentException naming the known ids; never a fallback
 ─► Resolver           options: call ▷ provider defaults ▷ runtime defaults ▷ catalog-derived (output limit, reasoning map)
                       model: catalog entry or UNLISTED; capability checks; soft/hard adaptation → warnings or failure
 ─► HandoffTransformer history adapted to (provider, api, model): reasoning, tool-call ids, signatures, images (§14.5)
 ─► WireApi.encode     pure; relative URI + JSON body + warnings; cache markers per cacheRetention/breakpoints
 ─► payload hook       last edit of the body ─► PreparedRequest                      (preview() stops here)
 ─► ResponseCacheStage opt-in; key = hash(provider, credential scope, api, method, path, body)
      └ hit ─────────► WireApi.decode / StreamDecoder replay ─► AssistantMessage (info().fromCache() = true)
 ─► AuthResolver       stored credential ▷ environment/ambient → headers, query, base URL, source;
                       OAuth refresh inside CredentialStore.update (single-flight by construction)
 ─► WireInterceptor*   per attempt, registration order in, reverse order out
 ─► HttpTransport      destination bound to the provider origin; connect / idle / total deadline; CancelToken
 ─► AttemptRunner      RetryPolicy · one total deadline · never after visible output · outcome classification
 ─► decode             WireApi.decode │ framer → StreamDecoder → ChatEvent* → Accumulator; OverflowDetector on errors
 ─► Cost               Usage × Model.prices() (tiers) → Usage.cost(), absent when a price is unknown
 ─► ResponseCache.put  successful, complete replies only
 ─► EventDispatcher    Started · FirstOutput · Retrying · Finished · JFR
 ─► AssistantMessage   content · usage + cost · stop reason · origin · warnings · info
```

Every provider gets the same deadlines, retries, cancellation, redaction, hand-off rules, caching and
events because none of that lives in a codec. Provider APIs (§8.6) enter the same pipeline below the
codec stage through `ProviderApiContext.exchange(…)`.

### 5.3 Internal components — no god classes

| Component | Responsibility | Deletion test |
|---|---|---|
| `DefaultLlm` | Wires the components below; owns what it created | Thin by design: a facade, not a behaviour holder |
| `Resolver` | Merge option scopes, resolve the model, apply catalog defaults, map reasoning levels, soft/hard checks | Deleting it spreads precedence rules into every codec |
| `HandoffTransformer` | Adapt history to the target model (§14.5) | Every codec would re-implement cross-provider rules |
| `Engine` | Orders the pipeline stages; creates the call context (`ScopedValue`) | Deleting it recreates the pipeline per operation |
| `AttemptRunner` | Retry decisions, backoff with jitter, `Retry-After`, deadline, outcome classification | Deleting it recreates retry loops per API |
| `StreamPump` + `Accumulator` | Frame → event loop, idle watchdog, bounded aggregation, partial tool-argument parsing | Deleting it breaks "stream result == complete result" |
| `AuthResolver` | Resolution chain, `source` labels, refresh inside `CredentialStore.update`, dynamic-token caching | Shared by API keys, OAuth and cloud identity |
| `OAuthFlows` | PKCE, loopback receiver, external redirect, device polling, client credentials, revocation | One security-reviewed implementation |
| `CatalogService` | Merge bundled, artifact, feed and live data; background refresh; snapshot file | Every UI would merge and refresh catalogs itself |
| `ResponseCacheStage` | Key computation, mode handling, replay through the codec | Keeps caching out of codecs |
| `OverflowDetector` | API patterns, preset patterns, silent truncation → `CONTEXT_OVERFLOW` | Every agent would keep regex tables |
| `CostCalculator` | Prices × usage with input-size tiers | Every budget UI would recompute |
| `EventDispatcher` | Ordered, isolated, synchronous listener delivery; tags; per-call listeners | Listener isolation in one place |
| `JfrRecorder`, `Redactor` | JFR events; one redaction policy for logs, events, errors, previews | Consistent secrecy |
| `JdkHttpTransport` | Default transport over `java.net.http.HttpClient` | Replaceable via SPI |
| `SseFramer`, `NdjsonFramer`, `IdleWatchdog` | Framing across network chunks; idle detection | Shared by all APIs |
| `JsonParser`, `JsonWriter`, `JsonRepair`, `RecordBinder` | Bounded parser, stable-order writer, partial-JSON repair, record ↔ JSON, schema derivation | Zero dependencies |

### 5.4 Design it twice — shapes considered

**Consumer surface**

| Candidate | Shape | Verdict |
|---|---|---|
| Endpoint-bound client (FM) | `LlmClient` per endpoint; `client.chat().send(model, …)` | Rejected: multi-model hosts keep maps of clients and pass endpoints with model ids (F1) |
| Provider-specific clients | `AnthropicClient`, `OpenAiClient` | Rejected: every host branches per provider; contracts diverge |
| Multi-endpoint router with fallback | `llm.chat("fast")` choosing a backend | Rejected: hides who is billed; fallback is host policy |
| **Multi-provider runtime, explicit dispatch** (pi) | `llm.complete(model, conversation, options)`; the model names its provider | **Chosen**: model switching is one argument; billing stays explicit |
| Facade verbs vs sub-API per noun | `llm.complete(…)` vs `llm.chat().send(…)` | Verbs for the call operations (few, typed by model kind later); sub-APIs for nouns with several operations (`models()`, `auth()`) |
| Portable vs provider-typed entry points (pi's `stream`/`streamSimple`) | two method families | Rejected: one method; typed `ProviderOptions` ride in `ChatOptions` |

**Provider SPI**

| Candidate | Shape | Verdict |
|---|---|---|
| Vendor SDK adapters (pi) | each adapter wraps `@anthropic-ai/sdk`, `openai`, … | Rejected: per-provider timeout, retry and error semantics; dependencies |
| Adapter drives I/O (A) | `InferenceAdapter.generate(request, ExecutionContext)` | Rejected for chat: each adapter re-implements timing and cancellation subtleties |
| **Pure codec** (B) | `WireApi.encode`/`decode` + `StreamDecoder`; the core executes | **Chosen**: least code per API, testable as functions |
| Hybrid for the rest (A) | typed `ProviderApi` against `ProviderApiContext.exchange(…)` | **Chosen** for provider-only, multi-step operations |

### 5.5 Object-oriented structure at a glance

| Pattern | Where | What it buys the caller |
|---|---|---|
| Facade + sub-APIs | `Llm` → `models()`, `auth()` | One entry point; discovery by completion |
| Static factories | `Llm.create`, `Conversation.of`, `Tool.of`, `Content.image`, `ToolResult.of`, `CancelToken.create`, `ApiKeyAuth.bearer` | Named construction; required values cannot be forgotten |
| Builders with consumer-builders and `toBuilder()` | every configuration and value type; presets | Readable configuration; immutable products; presets adjusted, never mutated |
| Strategy | `WireApi`, `ApiKeyAuth`, `OAuthAuth`, `ModelSource`, `CatalogFeed` | Protocols and auth flows swap without flags |
| Data-driven variants | presets, `ApiCompat` flags, catalogs | New providers and models without new classes |
| Adapter | `HttpTransport`, `CredentialStore`, `JsonMapper`, `ResponseCache`, `AuthInteraction` | Host infrastructure plugs in behind small interfaces |
| Chain of responsibility | `WireInterceptor` | Signing and tracing headers without subclassing |
| Observer | `LlmListener` over sealed `LlmEvent`; `CancelToken.onCancel` | UI progress and metrics without coupling |
| Composite | `CancelToken.child()` | One Stop for a whole run |
| Iterator | `ChatStream` (single-use, closeable) | Plain `for` loops over live output |
| View | `withCredentials(…)` | Per-user runtime without new transports or catalogs |
| Value objects, algebraic data types | records and sealed hierarchies (`Message`, `Content`, `ChatEvent`, `Credential`, `AuthPrompt`, `AuthNotice`, `OutputFormat`, `JsonValue`) | Exhaustive, type-safe handling with record patterns |
| Typed keys by class | `ProviderOptions`, `ApiCompat`, `ProviderApi<T>` | Type-safe extensibility instead of string maps |

---

## 6. Project structure

### 6.1 Artifacts

| Artifact | JPMS module | Contents | Depends on |
|---|---|---|---|
| `llm-transport-bom` | — | Version alignment (`java-platform`) | — |
| `llm-transport-core` | `net.ai.gate` | API, SPI, execution, JDK transport, JSON, OAuth flows, credential stores, catalog service, response cache, JFR | JDK; compile-only annotations |
| `llm-transport-openai` | `net.ai.gate.openai` | Responses and Chat Completions `WireApi`s; `Providers.openai()`; `OpenAiCompatible` presets; compat, options, hosted tools; bundled catalog | core |
| `llm-transport-anthropic` | `net.ai.gate.anthropic` | Messages `WireApi`, preset, options, hosted tools, bundled catalog | core |
| `llm-transport-google` | `net.ai.gate.google` | generateContent `WireApi`, Gemini preset, options, tools, `Gemini.CACHES`, bundled catalog | core |
| `llm-transport-catalog` | `net.ai.gate.catalog` | Model data only (JSON resources + a `CatalogFeed` for the public metadata source); **date-versioned, released weekly** | core |
| `llm-transport` | `net.ai.gate.providers` | `Providers` index, `Providers.presets()`, `ProvidersConfig`; `requires transitive` the families and the catalog | the above |
| `llm-transport-testing` | `net.ai.gate.testing` | `FakeProvider`, `ScriptedTransport`, `RecordingListener`, `LlmErrors`, `TestClock`, `FakeAuthorizationServer`, cassette helpers, contract kits | core, JUnit Jupiter API |
| `examples` (unpublished) | — | README examples as tests, Java and Kotlin | all |
| later, on demand | — | `-kotlin` (coroutines), `-jackson`, `-otel`, `-keychain` (`CredentialStore`), `-spring-boot`, `-bedrock`, `-vertex`, `-ollama` (native API) | core |

Most applications depend on `llm-transport` (one coordinate); size-sensitive hosts depend on core plus
one family. Adding an artifact requires a significant optional dependency, a distinct lifecycle, or an
independently useful integration — never a layer of the design. `llm-transport-catalog` qualifies by
lifecycle: model data changes weekly, code does not (D4).

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
│       ├── sdk.live-tests.gradle.kts    `liveTest` suite: credentialed, opt-in, cost-bounded
│       └── sdk.model-catalog.gradle.kts `updateModelCatalog` (network, scheduled CI only) and `checkModelCatalog` (offline, every build)
├── llm-transport-bom/
├── llm-transport-core/
│   └── src/
│       ├── main/java/module-info.java
│       ├── main/java/net/ai/gate/…           (§6.3)
│       ├── test/java/…                            unit, contract and architecture tests
│       └── test/resources/fixtures/…              framing, JSON, hand-off and OAuth fixtures
├── llm-transport-openai/
│   └── src/
│       ├── main/java/net/ai/gate/openai/
│       │   ├── OpenAi.java · OpenAiCompatible.java · OpenAiResponsesOptions.java · OpenAiCompletionsCompat.java · OpenAiTools.java
│       │   └── internal/responses/… · internal/completions/…   codecs, stream decoders, error and overflow patterns
│       ├── main/resources/net/ai/gate/openai/models.json   bundled catalog (generated, committed)
│       └── test/resources/fixtures/responses/… · completions/…  golden wire fixtures
├── llm-transport-anthropic/   net/ai/gate/anthropic/{Anthropic, AnthropicOptions, AnthropicCompat, AnthropicTools}.java + internal/ + models.json
├── llm-transport-google/      net/ai/gate/google/{Gemini, GeminiOptions, GeminiTools, GeminiCaches, CachedContent}.java + internal/ + models.json
├── llm-transport-catalog/     net/ai/gate/catalog/{ModelsDevFeed}.java + resources/…/catalog/*.json
├── llm-transport/             net/ai/gate/providers/{Providers, ProvidersConfig}.java
├── llm-transport-testing/     net/ai/gate/testing/…
├── examples/                  src/test/java · src/test/kotlin
└── docs/                      requirements/ · proposals/ · extending.md · CHANGELOG.md
```

### 6.3 Core packages

```
net.ai.gate        API  Llm, Model, ModelRef, ModelCatalog, CatalogOptions, RefreshReport, Modality, Capability,
                             Capabilities, SupportLevel, Prices, Provider, Conversation, Message, UserMessage,
                             AssistantMessage, ToolResultMessage, Content, Tool, FunctionTool, ProviderTool, ToolCall,
                             ToolResult, ToolChoice, OutputFormat, ChatOptions, ReasoningLevel, ReasoningHandoff,
                             CacheRetention, CacheMode, CancelToken, ChatStream, ChatEvent, Usage, Cost, StopReason,
                             Warning, ResponseInfo, RateLimits, PreparedRequest, ConnectionReport, ConnectionTest,
                             TimeoutPolicy, RetryPolicy, HttpOptions, WireLog, FieldDescriptor, LlmException
                             (+ 8 subclasses), ErrorCode, Registration
                        SPI  ResponseCache, WireInterceptor
net.ai.gate.auth   API  Auth, AuthStatus, AuthType, Credential, ApiKeyCredential, OAuthCredential, AuthPrompt,
                             AuthNotice, RedirectInteraction, OAuthConfig, Secret, Environment
                        SPI  CredentialStore, AuthInteraction, ApiKeyAuth, OAuthAuth, ResolvedAuth, AuthInput, TokenSupplier
net.ai.gate.event  API  LlmListener, LlmEvent, RequestEvent, CredentialEvent, CatalogEvent
net.ai.gate.json   API  JsonValue (+ JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull), Json,
                             JsonSchema, Description                                  SPI  JsonMapper
net.ai.gate.spi    SPI  WireApi, StreamDecoder, ApiRequest, EncodeContext, DecodeContext, HttpCall, HttpReply,
                             Frame, StreamFormat, ApiCompat, ProviderOptions, ModelSource, ProviderHttp, CatalogFeed,
                             FeedHttp, ProviderBundle, ProviderApi, ProviderApiContext, RawApi, HttpTransport,
                             TransportOptions
net.ai.gate.internal.*  —  client, resolve, handoff, engine, auth, oauth, catalog, cache, stream, http, json, jfr, redact
```

Rules, enforced by ArchUnit tests in core:

- Packages by noun, never by layer; at most three levels below the root; no `util`, `impl`, `manager`.
- The root package is the hub: the facade and the value types every feature uses. The exported packages
  form one API layer split for navigation, so they may reference each other's public types (the facade
  exposes `auth()`, `HttpOptions` accepts an `spi.HttpTransport`), with three restrictions: `json`
  signatures mention no other SDK package, `event` signatures mention only root and `json` types, and no
  exported signature mentions an `internal` type (implementations delegate to `internal`).
- Host-fed SPIs live beside the feature they serve (`auth.CredentialStore`, root `ResponseCache`,
  `json.JsonMapper`); provider- and protocol-authoring SPIs live in `spi`. Both are marked `/// **SPI**`
  and grow only through `default` methods.
- `internal` packages are not exported and carry `@ApiStatus.Internal`; provider modules never import
  them.
- Sealed hierarchies whose variants span packages rely on the core being compiled as a named module;
  this was verified to load both on the module path and on the classpath (§20.2).

### 6.4 `module-info.java` (core)

```java
/// LLM Transport SDK core: the portable API, the SPIs, and the JDK-based default implementation.
module net.ai.gate {
    requires transitive java.net.http;                    // the API exposes HttpClient.Version (HttpOptions)
    requires jdk.httpserver;                              // OAuth loopback receiver, bound to 127.0.0.1 only
    requires static java.desktop;                         // AuthInteraction.console() opens a browser when available
    requires static jdk.jfr;                              // JFR events, when the module is present
    requires static transitive org.jspecify;              // nullness annotations on the API: visible to consumers' compilers
    requires static transitive org.jetbrains.annotations; // @ApiStatus.* on the API: visible to consumers' compilers

    exports net.ai.gate;
    exports net.ai.gate.auth;
    exports net.ai.gate.event;
    exports net.ai.gate.json;
    exports net.ai.gate.spi;

    uses net.ai.gate.spi.ProviderBundle;
}
```

`transitive` is required wherever exported signatures mention another module's types; javac's
`exports` lint (part of `-Xlint:all -Werror`) enforces it (§20.3). Modular applications that bind records
for structured output or tool arguments export (or open) the records' packages to `net.ai.gate`;
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
    "llm-transport-google", "llm-transport-catalog", "llm-transport", "llm-transport-testing", "examples",
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
    options.release = 26                                   // one-line switch if an LTS target is required (§21)
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
plugins { id("sdk.java-library"); id("sdk.published"); id("sdk.api-compat"); id("sdk.model-catalog") }
description = "LLM Transport SDK — Anthropic Messages API"
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

`sdk.model-catalog` keeps builds reproducible: `updateModelCatalog` (network; run by a scheduled CI job
and before releases) regenerates `models.json` from the metadata source plus curated overrides and
commits it; `checkModelCatalog` (offline, part of `check`) validates the committed file against the
catalog schema and the module's presets.

Test suites: `test` (unit, contract and fixture tests, always), `liveTest` (credentialed calls to real
endpoints, enabled with `-Plive` and environment keys, bounded in tokens), and `:examples:test` (every
README example compiles and runs against `FakeProvider`). CI runs `check` on every change, `liveTest`
nightly and `updateModelCatalog` weekly (releasing `llm-transport-catalog`); releases additionally run the
japicmp report against the previous version.

### 6.6 Java 26 on the surface and inside

Only final (non-preview) language and library features are used, so consumers never need
`--enable-preview`.

| Feature (JDK) | Where | Benefit |
|---|---|---|
| Records (16) | Small, stable values: `ModelRef`, `Warning`, `Cost`, delta events, prompts and notices, `ApiKeyCredential` | Value semantics without boilerplate |
| Sealed types (17) + pattern `switch` (21) + record patterns (21) | `Message`, `Content`, `ChatEvent`, `LlmEvent`, `Credential`, `AuthPrompt`, `AuthNotice`, `Tool`, `ToolChoice`, `OutputFormat`, `JsonValue`; `case PartEnd(int i, ToolCall c)` | Exhaustive handling: adding a content variant fails compilation in every codec until handled |
| Unnamed variables and patterns `_` (22) | Examples, internal switches | Readable "ignore" branches |
| Virtual threads (21), no pinning in `synchronized` (JEP 491, 24) | Blocking calls scale; `completeAsync()`, blocking login prompts, background catalog refresh | No SDK thread pools; one blocking login protocol for desktop, CLI and web |
| `ScopedValue` (final in 25) | Per-call context (request id, tags, deadline) visible to listeners, interceptors, codecs and JFR | Immutable, bounded context; no `ThreadLocal` leaks |
| `HttpClient` HTTP/2 (11), `AutoCloseable` (21), HTTP/3 (JEP 517, 26) | `JdkHttpTransport`; `HttpOptions.httpVersion(HttpClient.Version.HTTP_3)` opt-in | Zero-dependency transport; HTTP/3 when endpoints support it |
| Sequenced collections (21) | `messages().getLast()`, `steps().getFirst()` | Readable access to ordered data |
| Markdown documentation comments (JEP 467, 23) | All public Javadoc as `///` | Readable source docs, better rendering |
| Stream gatherers (24) | `ChatStream.events()` works with `gather(…)`; used internally for delta coalescing | Composable stream processing without custom collectors |
| Module import declarations and compact source files (25) | Script-style quickstarts: `import module net.ai.gate;` | Minimal-ceremony trials |
| JFR custom events | `net.ai.gate.Request`, `…Retry`, `…CredentialRefresh`, `…CatalogRefresh` | Zero-dependency production diagnostics |
| Final-field integrity (JEP 500, 26 warns on deep reflection) | Record binding uses canonical constructors and accessors only | No reflective final-field writes; future-proof |

Not used: structured concurrency, lazy constants and primitive patterns remain previews in JDK 26
(verified for `StructuredTaskScope`, §20.2); `Unsafe` and deep reflection are never used.

pi's TypeScript solutions are translated into Java idioms, not copied:

| pi (TypeScript) | This design (Java 26) | Benefit |
|---|---|---|
| String-union event `type` switched as strings | sealed `ChatEvent` with records and record patterns | Compile-time exhaustiveness |
| `stream` vs `streamSimple`; typed options via `hasApi()` narrowing | one `complete`/`stream`; `ChatOptions.provider(AnthropicOptions)` looked up by class | One entry point; completion on provider options; no casts |
| Object literals with ~20 optional compat flags | immutable `ApiCompat` classes with builders, merged field by field | Documented, validated, overridable per model |
| `createProvider({ … })` object of functions | `Provider` value + strategy objects | Flows swap without flags; presets copied with `toBuilder()` |
| `AbortSignal` | `CancelToken` with `child()` and `onCancel` | Workflow-wide Stop |
| Async `prompt()` promise | blocking `AuthInteraction.prompt()` on a virtual thread | One protocol for desktop, CLI and web |
| Async iterable + `result()` promise | `Iterable<ChatEvent>` + `AutoCloseable` + `result()`; `events()` for streams | `for` loops and try-with-resources |
| Mutable `partial` shared by events | immutable snapshots | No aliasing |
| Failures as data | exceptions with `code()`, `outcomeUnknown()`, `partial()` | Failures cannot be ignored silently |
| TypeBox schemas | records + `@Description` → JSON Schema and binding | Schema and binding from one declaration |
| Explicit `telemetryContext` argument | `ScopedValue` call context | No parameter threading |

---

## 7. Public API sketch (core)

Conventions: record-style accessors (`timeout()`, never `getTimeout()`); static factories instead of
constructors, so every public class declares a private constructor (exceptions excepted: they take
`LlmException.Details`); builders for four or more parameters or two or more optional ones, with
required values in the factory; singular builder methods add, plural ones replace; consumer-builder
overloads *edit* the current value; `toBuilder()` on every configured value; `final` classes unless
designed for extension; API interfaces that consumers must not implement are
`@ApiStatus.NonExtendable`; `@NullMarked` packages; `Optional`/`OptionalLong` only for normally-absent
results; empty collections instead of null. Every type's Javadoc states *immutable*, *thread-safe* or
*not thread-safe*.

### 7.1 Facade and builder

```java
package net.ai.gate;

/// Thread-safe, closeable runtime over a fixed set of providers. Dispatches each call to the provider named
/// by the model — never to another provider, model or credential. Owns what it creates; borrows what it is
/// given. close() is idempotent, cancels in-flight calls and stops background work; later calls throw
/// IllegalStateException. Not for implementation by consumers: methods may be added in minor releases.
@ApiStatus.NonExtendable
public interface Llm extends AutoCloseable {
    static Llm create()                                   { … }  // discovered providers and feeds, Environment.system(),
                                                                 // in-memory credentials, default catalog refresh
    static Llm of(Provider... providers)                  { … }  // those providers only; otherwise as create()
    static Builder builder()                              { … }

    ModelCatalog models();
    Auth auth();
    /// A catalogued model, or an UNLISTED one for a known provider; an unknown provider throws
    /// IllegalArgumentException naming the known ids and the module to add.
    default Model model(String providerId, String modelId) { return models().require(providerId, modelId); }

    AssistantMessage complete(Model model, Conversation conversation, ChatOptions options);
    default AssistantMessage complete(Model model, Conversation conversation) { … }
    default AssistantMessage complete(Model model, String userText)          { … }
    /// Sets OutputFormat.of(type) unless one is set, completes and binds.
    /// @throws InvalidResponseException output_truncated, output_refused or output_invalid (partial() keeps the reply)
    <T> T complete(Model model, Conversation conversation, Class<T> outputType);
    /// Runs on the runtime executor (default: a virtual thread per call); cancelling the future cancels the call;
    /// completes exceptionally with the exceptions complete() throws.
    CompletableFuture<AssistantMessage> completeAsync(Model model, Conversation conversation, ChatOptions options);

    /// Single-use, closeable, bounded; close() cancels; never retried after the first event.
    ChatStream stream(Model model, Conversation conversation, ChatOptions options);
    default ChatStream stream(Model model, Conversation conversation)        { … }

    PreparedRequest preview(Model model, Conversation conversation, ChatOptions options);   // no network, no credentials
    ConnectionReport test(Model model);                                               // non-billable staged check
    ConnectionReport test(Model model, Consumer<ConnectionTest.Builder> options);     // e.g. an explicitly billable probe
    JsonObject describe();                                                            // effective configuration, redacted; no I/O

    /// A view sharing providers, transport, catalog, caches and listeners, with another credential store
    /// (users, tenants); its auth() and models().available() reflect that store. close() on a view is a no-op.
    Llm withCredentials(CredentialStore store);
    Registration addListener(LlmListener listener);        // released on Registration.close() or runtime close
    /// Layer 3: typed provider-only operations (files, batches, cached contents, account balances). No I/O to obtain.
    @ApiStatus.Experimental <T> T providerApi(String providerId, ProviderApi<T> api);
    @Override void close();

    /// Not thread-safe. build() validates everything, reports all violations at once, and performs no I/O.
    final class Builder {
        public Builder provider(Provider provider)                    { … }  // adds; an equal id replaces
        public Builder discoverProviders()                            { … }  // ServiceLoader<ProviderBundle>
        public Builder credentials(CredentialStore store)             { … }  // default in memory; borrowed
        public Builder environment(Environment environment)           { … }  // default system(); servers: none()
        public Builder defaults(ChatOptions defaults)                 { … }  // runtime-wide call defaults
        public Builder defaults(Consumer<ChatOptions.Builder> edit)   { … }
        public Builder catalog(Consumer<CatalogOptions.Builder> edit) { … }  // freshness policy (§11)
        public Builder http(HttpOptions options)                      { … }  // or an injected transport
        public Builder http(Consumer<HttpOptions.Builder> edit)       { … }
        public Builder responseCache(ResponseCache cache)             { … }  // opt-in; borrowed
        public Builder interceptor(WireInterceptor interceptor)       { … }  // singular: adds, ordered
        public Builder listener(LlmListener listener)                 { … }  // singular: adds
        public Builder executor(Executor executor)                    { … }  // for completeAsync(); borrowed
        public Builder jsonMapper(JsonMapper mapper)                  { … }  // default: record binder
        public Builder clock(Clock clock)                             { … }  // tests: expiry, backoff, catalog age
        public Llm build()                                            { … }
    }
}

/// Registration handle; close() is idempotent.
public interface Registration extends AutoCloseable { @Override void close(); }
```

### 7.2 Provider and provider configuration

```java
package net.ai.gate;

/// Immutable, thread-safe. A configured backend: everything about it that is not a wire format.
/// Presets are copied and adjusted with toBuilder(), never mutated.
public final class Provider {
    public static Builder builder(String id, WireApi defaultApi) { … }

    public String id()                           { … }  // "anthropic", "openai-work", "corp-gw"; [a-z0-9][a-z0-9._-]*;
                                                        // also the credential key and the event/log label
    public String name()                         { … }
    public URI baseUrl()                         { … }  // includes any deployment prefix and version path
    public Map<String, String> headers()         { … }
    public List<WireApi> apis()                  { … }  // supported; the first is the default; a model names the one it uses
    public Optional<ApiKeyAuth> apiKeyAuth()     { … }
    public Optional<OAuthAuth> oauthAuth()       { … }  // at least one of the two; keyless servers use ApiKeyAuth.none()
    public List<Model> models()                  { … }  // bundled with the preset or configured
    public Optional<ModelSource> modelSource()   { … }  // live listing (OpenRouter, Ollama, gateways, vendors' /models)
    public Optional<ApiCompat> compat()          { … }  // default flags for its models
    public ChatOptions defaults()                { … }  // provider-scoped call defaults, e.g. long timeouts for local servers
    public Optional<URI> apiKeyUrl()             { … }  // "get a key" link for UIs
    public Builder toBuilder()                   { … }

    public static final class Builder {
        public Builder id(String id)                          { … }
        public Builder name(String name)                      { … }
        public Builder baseUrl(URI baseUrl)                   { … }
        public Builder header(String name, String value)      { … }  // protected headers rejected
        public Builder api(WireApi api)                       { … }  // adds a supported API; the current default stays
        public Builder defaultApi(WireApi api)                { … }  // adds if needed and makes it the default
        public Builder auth(ApiKeyAuth auth)                  { … }
        public Builder auth(OAuthAuth auth)                   { … }
        public Builder model(Model model)                     { … }  // adds or replaces by id
        public Builder models(List<Model> models)             { … }  // replaces
        public Builder modelSource(ModelSource source)        { … }
        public Builder compat(ApiCompat compat)               { … }  // merged field by field with the preset's
        public Builder defaults(Consumer<ChatOptions.Builder> edit) { … }
        public Builder apiKeyUrl(URI url)                     { … }
        public Builder allowInsecureCredentials()             { … }  // credentials over http:// to a non-loopback host
        public Provider build()                               { … }  // lists every invalid field
    }
}
```

```java
package net.ai.gate.providers;   // aggregate artifact

/// Versioned, secret-free provider configuration ("llm-transport.providers/1"). Each entry names a preset or a
/// template ("openai-compatible", "anthropic-compatible", which require a base URL) and states only differences.
/// There is no credential field: credentials live in a CredentialStore under the provider id. Unknown presets
/// fail naming the known ones; unknown fields fail unless prefixed "x-"; no classes are loaded by name.
public final class ProvidersConfig {
    public static List<Provider> read(String json, List<Provider> presets) { … }
    public static String write(List<Provider> providers)                   { … }  // only differences from the preset
}
```

### 7.3 Models and the catalog

```java
package net.ai.gate;

/// Immutable, JSON-serializable catalog entry. Absent or UNKNOWN means "not known" — never zero and never "no".
public final class Model {
    public static Builder builder(String providerId, String modelId) { … }   // hosts describe gateway or local models

    public ModelRef ref()                          { … }
    public String providerId()                     { … }
    public String id()                             { … }   // opaque; never split on ':' or '/', never normalized
    public String name()                           { … }
    public String api()                            { … }   // WireApi id; the provider's default when not set
    public Set<Modality> input()                   { … }
    public Set<Modality> output()                  { … }
    public OptionalLong contextWindow()            { … }
    public OptionalLong maxOutputTokens()          { … }
    public List<ReasoningLevel> reasoningLevels()  { … }   // supported levels in order; empty = no reasoning control
    public Capabilities capabilities()             { … }
    public Optional<Prices> prices()               { … }
    public Optional<ApiCompat> compat()            { … }   // overrides the provider's compat field by field
    /// Parameter form derived from the fields above (reasoning levels, output limit, supported sampling); no I/O.
    public List<FieldDescriptor> parameters()      { … }
    public Optional<Instant> deprecatedAt()        { … }
    public Source source()                         { … }   // the freshest source that contributed (§11.2)
    public Optional<Instant> updatedAt()           { … }   // timestamp of the newest contributing data
    public Builder toBuilder()                     { … }

    public enum Source { BUNDLED, CATALOG, FEED, LIVE, CUSTOM, UNLISTED }
}

/// What conversations, replies and configurations persist.
public record ModelRef(String providerId, String modelId) { }

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
/// Tiers apply to the whole request when its total input exceeds the threshold.
public final class Prices {
    public Currency currency()                          { … }
    public Optional<BigDecimal> inputPerMillion()       { … }
    public Optional<BigDecimal> outputPerMillion()      { … }
    public Optional<BigDecimal> cacheReadPerMillion()   { … }
    public Optional<BigDecimal> cacheWritePerMillion()  { … }
    public List<Tier> tiers()                           { … }
    public Optional<Cost> cost(Usage usage)             { … }   // absent when a needed price or counter is unknown
    public record Tier(long inputTokensAbove, Prices prices) { }
}

@ApiStatus.NonExtendable
public interface ModelCatalog {
    List<Model> all();                                  // never I/O: the merged snapshot
    List<Model> all(String providerId);
    Optional<Model> find(ModelRef ref);
    Model require(String providerId, String modelId);   // catalogued or UNLISTED (warned on use); unknown provider throws
    default Model require(ModelRef ref)                 { return require(ref.providerId(), ref.modelId()); }
    List<Model> available();                            // providers whose auth is configured (local check, no network);
                                                        // after a successful live listing, only listed models (§11.2)
    RefreshReport refresh(String... providerIds);       // network now: feeds and live listings; all when empty
    Optional<Instant> refreshedAt();                    // last successful refresh of any source
}

/// Immutable. Per-provider outcome of a refresh; previous data is kept for every failed source.
public final class RefreshReport {
    public Map<String, Optional<LlmException>> byProvider() { … }
    public List<LlmException> feedErrors()                  { … }
    public boolean ok()                                     { … }
}

/// Client-level freshness policy (§11.4).
public final class CatalogOptions {
    public static final class Builder {
        public Builder refreshInterval(Duration interval) { … }  // default 24 h; background, never on the caller's thread
        public Builder manualRefresh()                    { … }  // no background refresh; refresh() only
        public Builder offline()                          { … }  // bundled, catalog artifact and snapshot only; never network
        public Builder feed(CatalogFeed feed)             { … }  // adds; discovered feeds are included by default
        public Builder noFeeds()                          { … }  // live listings only
        public Builder noLiveListings()                   { … }  // feeds only
        public Builder snapshotFile(Path file)            { … }  // persist the merged snapshot; default in memory
        public CatalogOptions build()                     { … }
    }
}

/// Immutable. One form field, for "connect provider" forms and model parameter forms alike.
public final class FieldDescriptor {
    public enum Kind { TEXT, SECRET, URL, INTEGER, DECIMAL, BOOLEAN, CHOICE, DURATION, JSON }
    public static Builder builder(String key, Kind kind) { … }
    public String key()                       { … }  // "apiKey", "CLOUDFLARE_ACCOUNT_ID", "temperature", "reasoning"
    public String label()                     { … }
    public Kind kind()                        { … }
    public boolean required()                 { … }
    public Optional<String> defaultValue()    { … }
    public Optional<String> help()            { … }
    public Optional<String> group()           { … }  // "Connection", "Sampling", "Advanced"
    public List<String> choices()             { … }
    public Optional<BigDecimal> min()         { … }
    public Optional<BigDecimal> max()         { … }
    public Optional<String> unit()            { … }  // "tokens", "s"
}
```

### 7.4 Conversation and messages

```java
package net.ai.gate;

/// Immutable, thread-safe, JSON-serializable (versioned canonical form). The source of truth of a chat,
/// portable across models (§14).
public final class Conversation {
    public static Conversation of(String userText)                               { … }
    public static Builder builder()                                              { … }

    public Optional<String> system()                                             { … }  // top-level instructions
    public List<Tool> tools()                                                    { … }
    public List<Message> messages()                                              { … }
    public List<Integer> cacheBreakpoints()                                      { … }  // explicit prefix ends as the number of
                                                                                        //   messages before each marker (0 = after
                                                                                        //   system and tools); empty = automatic

    public Conversation append(Message... messages)                              { … }
    public Conversation append(AssistantMessage reply, List<ToolResult> results) { … }  // reply + tool results in one step
    public Conversation appendUser(String text)                                  { … }
    public Conversation withSystem(String system)                                { … }
    public Conversation withTools(List<Tool> tools)                              { … }
    public Builder toBuilder()                                                   { … }

    /// Not thread-safe. Singular methods add, plural ones replace.
    public static final class Builder {
        public Builder system(String text)               { … }
        public Builder tool(Tool tool)                   { … }
        public Builder tools(List<Tool> tools)           { … }
        public Builder user(String text)                 { … }
        public Builder user(Content... parts)            { … }
        public Builder assistant(String text)            { … }
        public Builder message(Message message)          { … }
        public Builder messages(List<Message> messages)  { … }
        /// Marks the end of everything added so far as a prompt-cache prefix; overrides automatic placement.
        public Builder cacheBreakpoint()                 { … }
        public Conversation build()                      { … }
    }
}

public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    Instant timestamp();                                  // set by the SDK when not given
}

public final class UserMessage implements Message {
    public static UserMessage of(String text)             { … }
    public static UserMessage of(Content... parts)        { … }
    public List<Content> content()                        { … }
    public String text()                                  { … }
}

public final class ToolResultMessage implements Message {
    public static ToolResultMessage of(List<ToolResult> results) { … }
    public List<ToolResult> results()                            { … }
}
```

### 7.5 Content parts and tools

```java
package net.ai.gate;

/// Sealed, immutable content parts. Media constructed from a Path is read when the request is encoded.
public sealed interface Content permits Content.Text, Content.Image, Content.Document, Content.Audio,
        Content.Reasoning, Content.Refusal, Content.Unknown, ToolCall, ToolResult {

    static Text text(String text)                                   { … }
    static Image image(Path file)                                   { … }
    static Image image(URI url)                                     { … }
    static Image image(byte[] data, String mediaType)               { … }
    static Document document(Path file)                             { … }
    static Document document(byte[] data, String mediaType)         { … }
    static Audio audio(byte[] data, String format)                  { … }
    static Content fileRef(String providerFileId, String mediaType)  { … }   // scoped to its provider and account

    final class Text implements Content      { public String text() { … }  public List<Citation> citations() { … } }
    final class Image implements Content     { /* source: path | url | bytes | file ref; mediaType; optional detail */ }
    final class Document implements Content  { /* source; mediaType; optional title */ }
    final class Audio implements Content     { /* bytes; format; optional transcript (outputs) */ }
    /// Model reasoning. signature(): opaque replay data, valid only for the model that produced it.
    /// redacted(): encrypted reasoning without readable text — replayable to its origin only.
    final class Reasoning implements Content {
        public Optional<String> text() { … }  public Optional<String> signature() { … }
        public boolean redacted() { … }       public JsonValue providerData() { … }
    }
    final class Refusal implements Content   { public String text() { … } }
    final class Unknown implements Content   { public String type() { … }  public JsonValue raw() { … } }
    record Citation(String title, URI source, int startIndex, int endIndex) { }
}

/// Immutable. A tool call requested by the model. id() is synthesized when an API provides none.
public final class ToolCall implements Content {
    public String id() { … }  public String name() { … }
    public JsonObject arguments() { … }              // @throws InvalidResponseException invalid_tool_arguments
    public String argumentsJson() { … }              // raw text, always available
    public <T> T arguments(Class<T> type) { … }      // record binding
}

/// Immutable. The host's answer to one ToolCall (name and id are taken from the call).
public final class ToolResult implements Content {
    public static ToolResult of(ToolCall call, String text)          { … }
    public static ToolResult of(ToolCall call, JsonValue json)       { … }
    public static ToolResult of(ToolCall call, Object value)         { … }  // records, maps, lists, primitives
    public static ToolResult of(ToolCall call, List<Content> parts)  { … }  // text and images
    public static ToolResult error(ToolCall call, String message)    { … }
    public String callId() { … }  public String toolName() { … }
    public List<Content> content() { … }  public boolean isError() { … }
}

/// Sealed: portable functions, or provider-hosted tools created by provider modules.
public sealed interface Tool permits FunctionTool, ProviderTool {
    static FunctionTool of(String name, String description, Class<?> argumentsType) { … }  // schema from a record
    static FunctionTool.Builder function(String name)                              { … }
    String name();
}

public final class FunctionTool implements Tool {
    public String name() { … }  public Optional<String> description() { … }
    public JsonSchema parameters() { … }  public boolean strict() { … }
    public static final class Builder { /* description, parameters(JsonSchema), parameters(Class<?>), strict(), build() */ }
}

/// Created by provider modules, for example OpenAiTools.webSearch(), AnthropicTools.codeExecution().
/// Sent to another provider's API it is a soft adaptation: dropped with a warning, or a failure under strict().
public final class ProviderTool implements Tool {
    public String api() { … }  public String name() { … }  public JsonObject config() { … }
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

### 7.6 Replies, usage and metadata

```java
package net.ai.gate;

/// Immutable, thread-safe, JSON-serializable. The reply and the history entry at once.
/// Evolving model: a final class with accessors, not a record.
public final class AssistantMessage implements Message {
    public List<Content> content()               { … }
    public String text()                         { … }   // text parts only: no reasoning, refusal or tool arguments
    public List<ToolCall> toolCalls()            { … }
    public boolean hasToolCalls()                { … }
    public Optional<String> reasoningText()      { … }   // only what the provider returned
    public Optional<String> refusal()            { … }
    public StopReason stopReason()               { … }
    public Optional<String> errorMessage()       { … }   // partial replies (ABORTED, ERROR) only
    public Usage usage()                         { … }
    public ModelRef model()                      { … }   // origin, with api(): drives the hand-off rules
    public String api()                          { … }
    public Optional<String> responseModel()      { … }   // concrete model reported by the provider (gateways)
    public Optional<String> responseId()         { … }
    public List<Warning> warnings()              { … }   // adaptations made for this call
    public ResponseInfo info()                   { … }   // transient: not part of the JSON form
    public JsonValue json()                      { … }   // parses text(); @throws InvalidResponseException output_invalid
    public <T> T as(Class<T> type)               { … }   // binds json(); truncation and refusal throw before binding
}

/// Immutable. Disjoint buckets (D5); absent means "not reported" — never zero.
public final class Usage {
    public static Usage empty()        { … }
    public OptionalLong input()        { … }   // uncached input
    public OptionalLong cacheRead()    { … }
    public OptionalLong cacheWrite()   { … }
    public OptionalLong output()       { … }   // includes reasoning
    public OptionalLong reasoning()    { … }
    public OptionalLong totalInput()   { … }   // input + cacheRead + cacheWrite, when all three are known
    public OptionalLong total()        { … }   // reported by the provider, or derived when every part is known
    public Optional<Cost> cost()       { … }   // Model.prices() applied; absent when a needed price is unknown
    public boolean spent()             { … }   // false for replies replayed from the response cache
    public JsonValue raw()             { … }   // the provider's usage object
}

public record Cost(Currency currency, BigDecimal input, BigDecimal cacheRead, BigDecimal cacheWrite,
                   BigDecimal output, BigDecimal total) { }

/// Open value type: APIs add reasons; the raw provider value is always preserved.
public final class StopReason {
    public static final StopReason STOP = of("stop"), LENGTH = of("length"), TOOL_USE = of("tool_use"),
            CONTENT_FILTER = of("content_filter"), REFUSAL = of("refusal"), ABORTED = of("aborted"),
            ERROR = of("error"), OTHER = of("other");
    public static StopReason of(String raw) { … }
    public String raw()                     { … }
}

/// A non-fatal note. As a warning (replies, events, previews) it reports an adaptation: option_not_applicable,
/// option_dropped, option_adapted, reasoning_clamped, reasoning_converted, reasoning_dropped, tool_call_id_normalized,
/// image_omitted, history_adapted, max_tokens_clamped, max_tokens_defaulted, unlisted_model, untested_api_version,
/// cache_hint_ignored. As a preview note it reports a derived value: max_tokens_from_catalog, reasoning_mapped,
/// cache_markers, prompt_cache_default.
public record Warning(String code, String message) { }

/// Immutable. Operational facts about how a reply was obtained; not serialized with the conversation.
public final class ResponseInfo {
    public String requestId()                    { … }  // SDK id (events, logs, JFR)
    public String providerId()                   { … }
    public Optional<String> providerRequestId()  { … }  // for support tickets
    public Optional<String> route()              { … }  // upstream reported by a gateway (for example OpenRouter)
    public int attempts()                        { … }
    public Duration latency()                    { … }
    public Optional<Duration> timeToFirstOutput(){ … }
    public boolean fromCache()                   { … }
    public Optional<RateLimits> rateLimits()     { … }  // parsed from response headers where the API defines them
    public Optional<JsonValue> rawBody()         { … }  // redacted provider body; absent for streamed replies
}

public final class RateLimits {
    public OptionalLong requestsRemaining() { … }  public OptionalLong tokensRemaining() { … }
    public Optional<Instant> requestsReset() { … } public Optional<Instant> tokensReset() { … }
}

/// Immutable. The result of preview(): exactly what complete() would transmit, or why it would not.
public final class PreparedRequest {
    public List<String> problems()         { … }  // field paths and reasons; empty when sendable
    public boolean sendable()              { … }
    public ModelRef model()                { … }
    public String api()                    { … }
    public String method()                 { … }
    public URI uri()                       { … }
    public Map<String, String> headers()   { … }  // credentials redacted
    public JsonValue body()                { … }  // stable key order: byte-identical to the wire
    public List<Warning> warnings()        { … }  // adaptations, as the reply would carry them
    public List<Warning> notes()           { … }  // derived values (output limit from the catalog, reasoning mapping,
                                                  //   cache markers); informational, never attached to replies
    public String toCurl()                 { … }  // secrets replaced by environment-variable placeholders
}
```

### 7.7 Streaming

```java
package net.ai.gate;

/// Single consumer and single iteration: iterator(), textDeltas() and events() are mutually exclusive views of
/// one sequence; a second view throws IllegalStateException. Bounded: the producer is back-pressured by the
/// blocking socket read. close() is thread-safe, idempotent, cancels an unfinished call and releases the connection.
@ApiStatus.NonExtendable
public interface ChatStream extends Iterable<ChatEvent>, AutoCloseable {
    @Override Iterator<ChatEvent> iterator();
    Iterable<String> textDeltas();           // text-only view; other events are still aggregated
    Stream<ChatEvent> events();              // java.util.stream view; closing it closes this stream
    AssistantMessage partial();              // immutable snapshot so far; never blocks
    /// After completion: the aggregate, equal to the complete() result. Before completion: drains the rest first.
    /// @throws IllegalStateException when accumulation was disabled or exceeded its limit
    AssistantMessage result();
    @Override void close();
}

/// Sealed stream events. Delta and part-end records are frozen shapes; lifecycle events are final classes that
/// may gain accessors. Consumers keep a default branch: variants may be added in minor releases.
public sealed interface ChatEvent {
    record TextDelta(int index, String text)                                      implements ChatEvent { }
    record ReasoningDelta(int index, String text)                                 implements ChatEvent { }
    record ToolCallStart(int index, String callId, String name)                   implements ChatEvent { }
    /// partialArguments(): best-effort parse of the arguments so far (repaired JSON); never validated.
    record ToolCallDelta(int index, String fragment, JsonObject partialArguments) implements ChatEvent { }
    /// The authoritative completed part: text, reasoning with its signature, ToolCall, image, refusal, citation.
    record PartEnd(int index, Content content)                                    implements ChatEvent { }
    record Unknown(String type, JsonValue raw)                                    implements ChatEvent { }
    final class Started implements ChatEvent { public Optional<String> responseId() { … }  public Optional<String> responseModel() { … } }
    final class Done implements ChatEvent    { public AssistantMessage message() { … } }
}
```

### 7.8 Call options and cancellation

```java
package net.ai.gate;

/// Immutable, thread-safe. Unset inherits, field by field: call ▷ provider defaults ▷ runtime defaults
/// ▷ catalog-derived ▷ not sent (§9.1); tags, headers and listeners accumulate across scopes.
/// Portable fields have a JSON form (workflow nodes persist them);
/// the cancel token, listeners and the payload hook are never serialized.
public final class ChatOptions {
    public static ChatOptions none()                                        { … }
    public static Builder builder()                                         { … }

    // generation — portable (§14.1 maps each field per API)
    public OptionalDouble temperature()                                     { … }
    public OptionalDouble topP()                                            { … }
    public OptionalInt topK()                                               { … }
    public OptionalInt maxTokens()                                          { … }   // default from the catalog (§14.2)
    public List<String> stop()                                              { … }
    public OptionalLong seed()                                              { … }
    public Optional<ReasoningLevel> reasoning()                             { … }   // mapped per model (§14.4)
    public Optional<ReasoningHandoff> reasoningHandoff()                    { … }   // default KEEP (D2)
    public Optional<ToolChoice> toolChoice()                                { … }
    public Optional<Boolean> parallelToolCalls()                            { … }
    public Optional<OutputFormat> output()                                  { … }
    // caching
    public Optional<CacheRetention> cacheRetention()                        { … }   // default SHORT (D1)
    public Optional<String> sessionId()                                     { … }   // cache routing and affinity
    public Optional<CacheMode> responseCache()                              { … }   // when a ResponseCache is configured
    // operational
    public Optional<TimeoutPolicy> timeouts()                               { … }
    public Optional<RetryPolicy> retry()                                    { … }
    public Optional<CancelToken> cancel()                                   { … }
    public Map<String, String> headers()                                    { … }   // ordinary headers; protected ones rejected
    public Map<String, String> tags()                                       { … }   // copied onto events, logs and JFR
    public List<LlmListener> listeners()                                    { … }   // added to the runtime's listeners
    public boolean strict()                                                 { … }   // soft adaptations fail (§14.3)
    // provider-specific and escape hatch
    public <T extends ProviderOptions> Optional<T> provider(Class<T> type)  { … }   // read only by its API family
    public Optional<UnaryOperator<JsonObject>> payload()                    { … }   // last edit of the wire body
    public ChatOptions overriddenBy(ChatOptions higher)                     { … }
    public Builder toBuilder()                                              { … }

    /// Not thread-safe. One setter per accessor; singular methods add (tag, header, listener, stop, provider
    /// replaces an equal type); consumer-builders edit timeouts and retry.
    public static final class Builder {
        public Builder temperature(double value)               { … }  // range-checked against [0, 2]; APIs check narrower ranges
        public Builder maxTokens(int tokens)                    { … }  // > 0
        public Builder reasoning(ReasoningLevel level)          { … }
        public Builder reasoningHandoff(ReasoningHandoff mode)  { … }
        public Builder cacheRetention(CacheRetention retention) { … }
        public Builder responseCache(CacheMode mode)            { … }
        public Builder timeouts(Consumer<TimeoutPolicy.Builder> edit) { … }
        public Builder cancel(CancelToken token)                { … }
        public Builder tag(String key, String value)            { … }
        public Builder listener(LlmListener listener)           { … }
        public Builder strict()                                 { … }
        public Builder provider(ProviderOptions options)        { … }
        public Builder payload(UnaryOperator<JsonObject> edit)  { … }
        /* topP, topK, stop, seed, toolChoice, parallelToolCalls, output(OutputFormat), output(Class<?>), sessionId,
           retry, header, set(String key, String raw) for form values parsed per FieldDescriptor */
        public ChatOptions build()                              { … }
    }
}

public enum ReasoningLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }
/// What happens to reasoning content from another model when the conversation continues (§14.4).
public enum ReasoningHandoff { KEEP, DROP }
public enum CacheRetention { NONE, SHORT, LONG }
public enum CacheMode { READ_WRITE, REFRESH, BYPASS, OFFLINE }

/// Thread-safe. Cancels every call that carries it or one of its children.
public final class CancelToken {
    public static CancelToken create()             { … }
    public CancelToken child()                     { … }   // cancelled with its parent; cancelling it leaves the parent alone
    public void cancel()                           { … }   // idempotent; before start the call never starts
    public boolean isCancelled()                   { … }
    public Registration onCancel(Runnable action)  { … }
}
```

### 7.9 Output formats and JSON

```java
package net.ai.gate;

public sealed interface OutputFormat {
    static OutputFormat text()                          { return PlainText.INSTANCE; }
    static OutputFormat json()                          { return AnyJson.INSTANCE; }
    static OutputFormat jsonSchema(JsonSchema schema)   { return new Schema("output", schema, true); }
    static OutputFormat of(Class<?> recordType)         { return new Typed(recordType); }  // schema derived at encode time
    enum PlainText implements OutputFormat { INSTANCE }
    enum AnyJson implements OutputFormat { INSTANCE }
    record Schema(String name, JsonSchema schema, boolean strict) implements OutputFormat { }
    record Typed(Class<?> type) implements OutputFormat { }                  // bound with the runtime's JsonMapper
}
```

```java
package net.ai.gate.json;

/// Immutable JSON tree; the only JSON type on public signatures. Numbers keep their lexical form; objects keep
/// insertion order, so written JSON is byte-stable (prompt-cache prefixes, cache keys, fixtures).
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
    String toJson();
    String toPrettyJson();
}
public final class JsonObject implements JsonValue {        // members keep insertion order
    public Optional<JsonValue> get(String name) { … }
    public String string(String name)           { … }     // throws if absent or not a string
    public Map<String, JsonValue> members()     { … }
    public JsonObject with(String name, Object value) { … } // a copy with one member added or replaced
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

SDK value types (`Conversation`, `Message`, `AssistantMessage`, `ChatOptions` portable fields, `Usage`,
`Model`, `ModelRef`) have a documented, versioned canonical JSON form through `Json.valueOf(…)` and
`Json.convert(…)`: agents persist conversations and workflow builders persist node settings and outputs
without writing mappers.

### 7.10 Connection report

```java
package net.ai.gate;

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
        public Builder inferenceProbe(int maxOutputTokens)    { … }   // explicitly billable; off by default
        public Builder timeout(Duration timeout)              { … }   // default 15 s
        public ConnectionTest build()                         { … }
    }
}
```

### 7.11 Authentication

```java
package net.ai.gate.auth;

/// Status and login for the runtime's providers, against the runtime's (or view's) credential store.
@ApiStatus.NonExtendable
public interface Auth {
    AuthStatus status(String providerId);                                    // local: store and environment only; no network
    List<AuthType> methods(String providerId);                               // API_KEY, OAUTH in preference order
    Credential login(String providerId, AuthType type, AuthInteraction ui);  // blocks; stores the result
    Credential login(String providerId, AuthType type, AuthInteraction ui, CancelToken cancel);
    void save(String providerId, Credential credential);                     // static forms: no dialog
    void logout(String providerId);                                          // local: deletes the stored credential
    void revoke(String providerId);                                          // remote revocation where supported, then logout
}

public final class AuthStatus {
    public enum State { NOT_CONFIGURED, CONFIGURED, EXPIRING, EXPIRED, REFRESH_FAILED }
    public State state()                   { … }
    public Optional<AuthType> type()       { … }
    public Optional<String> source()       { … }   // "ANTHROPIC_API_KEY", "stored credential", "OAuth", "gcloud ADC"
    public Optional<Instant> expiresAt()   { … }
    public Optional<String> account()      { … }
}
public enum AuthType { API_KEY, OAUTH }

public sealed interface Credential permits ApiKeyCredential, OAuthCredential { }

/// Immutable; toString() redacts. settings: provider-scoped non-secret values collected at login
/// (account id, gateway id, region) — "connect provider" is one flow.
public record ApiKeyCredential(Secret key, Map<String, String> settings) implements Credential { }

/// Immutable; toString() redacts. Bound to issuer, client and account: a refresh or re-login cannot silently
/// change principal. The access token may be an API key issued by OAuth (OpenRouter).
public final class OAuthCredential implements Credential {
    public Secret access()                  { … }
    public Optional<Secret> refresh()       { … }
    public Optional<Instant> expiresAt()    { … }
    public String issuer()                  { … }
    public String clientId()                { … }
    public Optional<String> account()       { … }
    public Set<String> scopes()             { … }
    public JsonObject extra()               { … }   // provider data, e.g. a per-account base URL (GitHub Copilot)
}

/// A secret value; toString() redacts. Only its fingerprint appears in logs.
public final class Secret {
    public static Secret of(String value) { … }   // rejects blank
    public String reveal()                { … }   // for auth strategies; never logged
    public String fingerprint()           { … }   // "sk-…a1b2"
}

/// SPI (host). One credential per key. update() is the only write path: atomic per key, and across processes
/// where the store supports it. OAuth refresh runs inside update(), so concurrent calls and processes cannot
/// double-refresh, and a rotated refresh token is persisted in the same step.
public interface CredentialStore {
    Optional<Credential> read(String key);
    List<Entry> list();                                                     // keys and types; never secrets
    Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change);
    default void delete(String key) { update(key, current -> Optional.empty()); }
    default CredentialStore scoped(String prefix) { … }                     // users and tenants
    static CredentialStore inMemory()      { … }
    static CredentialStore file(Path path) { … }   // JSON, owner-only permissions where supported, lock + atomic rename;
                                                    // not encrypted
    record Entry(String key, AuthType type) { }
}

/// SPI (host UI). Prompts and notices reach only the caller of login() — never listeners, logs or events.
public interface AuthInteraction {
    String prompt(AuthPrompt prompt);                                   // blocks until answered; throw to abort
    void notify(AuthNotice notice);                                     // returns quickly
    static AuthInteraction console()                                    { … }  // terminal; opens a desktop browser when possible
    /// Web hosts: OAuth flows redirect to redirectUri instead of a loopback listener; OpenUrl goes to sendBrowserTo.
    static RedirectInteraction redirect(URI redirectUri, Consumer<URI> sendBrowserTo) { … }
}

/// An AuthInteraction for web hosts: the Code prompt waits until complete() or fail() is called.
public interface RedirectInteraction extends AuthInteraction {
    URI redirectUri();
    void complete(URI callbackUri);                                     // from the host's callback route
    void fail(String reason);
}

/// Closed set (changing it is a major version): switch over it exhaustively.
public sealed interface AuthPrompt {
    record Text(String message, Optional<String> placeholder)  implements AuthPrompt { }
    record SecretText(String message)                          implements AuthPrompt { }
    record Select(String message, List<Option> options)        implements AuthPrompt { }
    record Code(String message)                                implements AuthPrompt { }  // pasted code or redirect URI;
                                                                                         // pre-empted by the loopback callback
    record Option(String id, String label, Optional<String> description) { }
}

/// Closed set (changing it is a major version).
public sealed interface AuthNotice {
    record OpenUrl(URI url, Optional<String> instructions)                     implements AuthNotice { }
    record DeviceCode(URI verificationUri, String userCode, Instant expiresAt) implements AuthNotice { }
    record Info(String message, List<URI> links)                               implements AuthNotice { }
    record Progress(String message)                                            implements AuthNotice { }
}

/// SPI (provider). How a provider accepts keys; presets use the factories.
public interface ApiKeyAuth {
    String name();                                                  // "Anthropic API key"
    /// Stored credential first, then environment and ambient sources; empty = not configured. No network,
    /// except a dynamic token supplier fetching a token.
    Optional<ResolvedAuth> resolve(AuthInput input);
    /// Side-effect-free check used by status() and available(): never fetches tokens or touches the network.
    default boolean configured(AuthInput input)                { … }
    default List<FieldDescriptor> fields()                     { … }  // key + provider settings, for static forms
    default Optional<ApiKeyCredential> login(AuthInteraction ui) { … }  // prompts for fields()
    static ApiKeyAuth bearer(String name, String... envVars)                   { … }  // Authorization: Bearer
    static ApiKeyAuth header(String name, String header, String... envVars)   { … }  // e.g. x-api-key
    static ApiKeyAuth none()                                                   { … }  // keyless local servers
    static ApiKeyAuth dynamic(String name, TokenSupplier supplier)             { … }  // Entra ID, Google ADC: cached to
                                                                                       // expiry minus skew, single-flight
}

/// SPI (provider or host). OAuth for a provider; refresh and toAuth split so the core owns the locked refresh.
public interface OAuthAuth {
    String name();                                                    // "OpenRouter (OAuth)"
    OAuthCredential login(AuthInteraction ui, CancelToken cancel);
    OAuthCredential refresh(OAuthCredential credential);              // network; runs inside CredentialStore.update
    ResolvedAuth toAuth(OAuthCredential credential);                  // no I/O
    static OAuthAuth standard(OAuthConfig config) { … }               // PKCE S256 loopback, external redirect,
                                                                      // device code, client credentials (§13.3)
}

/// Applied to one request. Anything not expressible here is provider configuration, not auth.
public record ResolvedAuth(Map<String, String> headers, Map<String, String> query, Optional<URI> baseUrl, String source) { }
public record AuthInput(Optional<ApiKeyCredential> stored, Environment environment) { }

@FunctionalInterface
public interface TokenSupplier {
    AccessToken fetch() throws IOException;
    record AccessToken(Secret token, Optional<Instant> expiresAt) { }
}

/// Where the auth chain reads ambient values. Injectable for tests and servers.
public interface Environment {
    Optional<String> get(String name);
    static Environment system()                          { … }
    static Environment none()                            { … }
    static Environment of(Map<String, String> values)    { … }
}

/// Immutable. Presets supply defaults; hosts may override per provider. PKCE S256 is always used.
public final class OAuthConfig {
    public static Builder builder(String clientId) { … }
    public String clientId() { … }
    public Optional<String> clientSecretKey() { … }           // confidential clients: the credential-store key of an
                                                              //   ApiKeyCredential holding the secret; never the secret
    public URI authorizationEndpoint() { … }  public URI tokenEndpoint() { … }
    public Optional<URI> deviceAuthorizationEndpoint() { … }  public Optional<URI> revocationEndpoint() { … }
    public Optional<URI> redirectUri() { … }                  // fixed external redirect; a RedirectInteraction's URI wins
    public OptionalInt loopbackPort() { … }                   // for providers with fixed redirect URIs
    public Set<String> scopes() { … }  public Set<Grant> grants() { … }
    public enum Grant { AUTHORIZATION_CODE, DEVICE_CODE, CLIENT_CREDENTIALS }
    /// Maps non-standard token responses (OpenRouter's PKCE exchange returns {"key": …}).
    public Optional<Function<JsonObject, OAuthCredential>> tokenResponseMapper() { … }
}
```

### 7.12 Events

```java
package net.ai.gate.event;

/// Observation only. Called synchronously on the thread that produced the event, outside SDK locks; must be
/// brief; a thrown exception is logged and never affects the call. Events of one call arrive in order.
@FunctionalInterface
public interface LlmListener { void onEvent(LlmEvent event); }

/// Sealed, immutable, content-free and secret-free. Keep a default branch: variants may be added.
public sealed interface LlmEvent permits RequestEvent, CredentialEvent, CatalogEvent {
    Instant at();
    Map<String, String> tags();
}

public sealed interface RequestEvent extends LlmEvent {
    String requestId();  String providerId();  ModelRef model();
    final class Started implements RequestEvent     { /* api(), streaming() */ }
    final class FirstOutput implements RequestEvent { /* latency() */ }
    final class Retrying implements RequestEvent    { /* attempt(), delay(), errorCode() */ }
    final class Finished implements RequestEvent    { /* outcome(): COMPLETED | FAILED | CANCELLED; usage(), cost(), latency(),
                                                         timeToFirstOutput(), attempts(), warnings(), fromCache(),
                                                         errorCode(), outcomeUnknown(), providerRequestId() */ }
}

public sealed interface CredentialEvent extends LlmEvent {
    String providerId();
    final class Refreshed implements CredentialEvent     { /* expiresAt() */ }
    final class RefreshFailed implements CredentialEvent { /* errorCode(), loginRequired() */ }
}

public sealed interface CatalogEvent extends LlmEvent {
    final class Refreshed implements CatalogEvent { /* providers(), modelsChanged(), failures() */ }
}
```

### 7.13 Policies

```java
package net.ai.gate;

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
/// honour Retry-After up to 60 s; exponential backoff 500 ms × 2, capped at 8 s, full jitter; never after visible
/// stream output; never after an ambiguous post-send failure (504, timeouts, resets).
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

### 7.14 Response cache

```java
package net.ai.gate;

/// SPI (host). Exact-match cache of complete, successful exchanges; opt-in (§12.3). Thread-safe. Keys are
/// SHA-256 hashes; entries are the SDK's versioned exchange format ("llm-transport.exchange/1": status, redacted
/// headers, body or stream frames, the request body for diagnosing misses) — also the cassette format.
public interface ResponseCache {
    Optional<byte[]> get(String key);
    void put(String key, byte[] entry);
    default void remove(String key) { }
    default void clear() { }
    static ResponseCache inMemory(int maxEntries)                 { … }  // LRU
    static ResponseCache inMemory(int maxEntries, Duration ttl)   { … }
    static ResponseCache directory(Path directory)                { … }  // one readable JSON file per entry; test cassettes
}
```

### 7.15 Errors

```java
package net.ai.gate;

/// Unchecked root. Messages are for people; callers branch on type and code(). Causes are kept; no third-party
/// exception leaks. Failure atomicity: a failed call leaves the runtime usable.
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
    public Optional<String> providerId()         { … }
    public int attempts()                        { … }
    public Optional<JsonValue> errorBody()       { … }  // bounded and redacted
    /// What was received before the failure: stop reason ERROR or ABORTED, usage included; appendable.
    public Optional<AssistantMessage> partial()  { … }

    /// Immutable error facts. Codecs return them from WireApi.decodeError; the core selects the exception type
    /// from the code, adds call facts (request id, provider, attempts, outcome, partial) and throws.
    public static final class Details {
        public static Builder builder(ErrorCode code, String message) { … }
        public Builder toBuilder() { … }
        /* code(), message(), httpStatus(), providerCode(), providerRequestId(), retryAfter(), retryable(),
           outcomeUnknown(), requestId(), providerId(), attempts(), errorBody(), partial(); one setter per field */
    }
}

public final class RateLimitedException extends LlmException {
    public RateLimitedException(Details details) { … }
    public Optional<Duration> retryAfter()       { … }
}
// AuthenticationException, InvalidRequestException, ProviderException, TransportException,
// RequestTimeoutException and RequestCancelledException have the same constructor shape;
// InvalidResponseException carries the reply it could not bind through partial().

/// Open value type with well-known constants; APIs add codes.
public final class ErrorCode {
    public static final ErrorCode INVALID_REQUEST, UNSUPPORTED_FEATURE, CONTEXT_OVERFLOW, MODEL_NOT_FOUND,
            REQUEST_TOO_LARGE, INVALID_CREDENTIALS, LOGIN_REQUIRED, REFRESH_FAILED, LOGIN_CANCELLED, PERMISSION_DENIED,
            CREDENTIAL_STORE, RATE_LIMITED, QUOTA_EXHAUSTED, OVERLOADED, SERVER_ERROR, CONNECT_FAILED,
            STREAM_INTERRUPTED, OUTCOME_UNKNOWN, MALFORMED_RESPONSE, INVALID_TOOL_ARGUMENTS, OUTPUT_INVALID,
            OUTPUT_TRUNCATED, OUTPUT_REFUSED, DEADLINE_EXCEEDED, STREAM_IDLE_TIMEOUT, CANCELLED, CACHE_MISS;
    public static ErrorCode of(String code) { … }
    public String value() { … }
}
```

| Exception | Typical codes | Typical HTTP | Retried by default |
|---|---|---|---|
| `InvalidRequestException` | `invalid_request`, `unsupported_feature`, `context_overflow`, `model_not_found`, `request_too_large`, `cache_miss` | 400, 404, 413, 422 | no |
| `AuthenticationException` | `invalid_credentials`, `login_required`, `refresh_failed`, `login_cancelled`, `permission_denied`, `credential_store` | 401, 403 | once after a forced OAuth refresh on 401 |
| `RateLimitedException` (+ `retryAfter()`) | `rate_limited`, `quota_exhausted` | 429, 402 | `rate_limited` yes |
| `ProviderException` | `overloaded`, `server_error` | 500, 502, 503, 529 | yes |
| `TransportException` | `connect_failed`, `stream_interrupted`, `outcome_unknown` | — | before send only |
| `InvalidResponseException` | `malformed_response`, `invalid_tool_arguments`, `output_invalid`, `output_truncated`, `output_refused` | 200 | no |
| `RequestTimeoutException` | `deadline_exceeded`, `stream_idle_timeout` | 408, 504 | 408 yes; after send no |
| `RequestCancelledException` | `cancelled` | — | no |

Context overflow is `InvalidRequestException(CONTEXT_OVERFLOW)` both when a provider rejects the request
(API and preset patterns) and when it truncates silently — reported input above the model's context
window, or a length stop with no output on a full context; in the silent case the reply is kept in
`partial()`.

Local programmer misuse (a closed runtime, a second stream iterator) throws `IllegalStateException`;
invalid builder input throws `IllegalArgumentException` naming every invalid field. The testing artifact
ships `LlmErrors` factories so host tests can construct every exception.

---

## 8. SPI, provider modules and extension

Every host-fed SPI type has at most three abstract methods and every provider or protocol SPI at most
four; each uses library-owned or JDK types only, grows only through `default` methods, ships a default
implementation and a test double, and documents five contract rows: thread, ordering, error isolation,
blocking, lifecycle (§8.5).

### 8.1 Wire APIs and stream decoders — the only way to add a wire format

```java
package net.ai.gate.spi;

/// Stateless, thread-safe, pure: no I/O, credentials, retries or clocks. One wire protocol at one revision;
/// constants live in provider modules (OpenAi.RESPONSES, Anthropic.MESSAGES); an incompatible revision is a new constant.
public interface WireApi {
    String id();                                                     // "openai-responses", "openai-completions",
                                                                     // "anthropic-messages", "google-generate-content"
    default String revision()                                        { return "1"; }   // constants override: "2023-06-01"
    /// Inexpressible settings: soft ones are adapted with ctx.warn(…) (or fail when ctx.strict()), hard ones throw
    /// InvalidRequestException(unsupported_feature). Returns a request with a relative URI.
    HttpCall encode(ApiRequest request, EncodeContext ctx);
    AssistantMessage decode(HttpReply reply, DecodeContext ctx);
    StreamDecoder streamDecoder(DecodeContext ctx);                  // one per stream
    default StreamFormat streamFormat()                              { return StreamFormat.SSE; }
    /// Maps a non-2xx reply to error facts (code, provider code, retry hint, Retry-After, context-overflow patterns);
    /// the core picks the exception type and adds call facts, so every API's errors look alike.
    default LlmException.Details decodeError(HttpReply reply, DecodeContext ctx) { … }
    /// Tool-call ids produced by other APIs, made acceptable to this one (hand-off, §14.5).
    default String normalizeToolCallId(String foreignId)             { return foreignId; }
}

/// What a codec receives: the target model, the history already adapted to it, the effective options.
public record ApiRequest(Model model, Conversation conversation, ChatOptions options, boolean streaming) { }

/// What a codec may read. Provider options are pre-scoped: only those of this API family appear.
public interface EncodeContext {
    Provider provider();
    <C extends ApiCompat> C compat(Class<C> type);   // preset ▷ provider ▷ model, merged; the type's defaults otherwise
    boolean strict();
    JsonMapper json();
    void warn(Warning warning);
}

public interface DecodeContext {
    Provider provider();
    Model model();
    <C extends ApiCompat> C compat(Class<C> type);
    JsonMapper json();
    void warn(Warning warning);
}

/// Confined to the consuming thread; frames in arrival order, events in emission order.
public interface StreamDecoder {
    List<ChatEvent> onFrame(Frame frame);    // none for keep-alives, several for composite frames
    List<ChatEvent> onEnd();                 // server closed: emit Done exactly once, or throw unexpected_stream_end
}

public final class Frame { /* event(): Optional<String>, data(): String, id(): Optional<String> — one SSE event or NDJSON line */ }
public enum StreamFormat { SSE, NDJSON }     // binary event streams arrive with the module that needs them
```

| Item | `WireApi` | `StreamDecoder` |
|---|---|---|
| Thread | any; stateless | the consuming thread only |
| Ordering | — | frames in arrival order; events in emission order |
| Error isolation | encode exceptions propagate as `InvalidRequestException`; decode exceptions become `InvalidResponseException(malformed_response)` with the cause | a throw ends the stream with `InvalidResponseException`; delivered events stay valid |
| Blocking | never | never |
| Lifecycle | singleton per constant | one per stream; `onEnd()` at most once |

The codec contract kit (`WireApiContract`, §16.1) pins these rules plus the portable behaviours of §14:
unknown fields and parts preserved, tool-call ids stable, usage normalized into disjoint buckets,
reasoning replayed only to its origin, hand-off fixtures across APIs, `Done` exactly once, chunk splits
at arbitrary byte and UTF-8 boundaries.

### 8.2 HTTP transport and interceptors

```java
package net.ai.gate.spi;

/// SPI. Thread-safe. Default: HttpTransport.jdk(). Injected transports are borrowed, never closed by runtimes.
public interface HttpTransport extends AutoCloseable {
    /// Blocks until response headers arrive and returns a reply whose body the caller consumes and closes.
    /// Must abort promptly when the calling thread is interrupted and when the reply is closed early.
    HttpReply send(HttpCall call, TransportOptions options) throws IOException;
    @Override default void close() { }
    static HttpTransport jdk()                    { … }
    static HttpTransport jdk(HttpOptions options) { … }
}

public final class HttpCall  { /* method(), uri() (relative from codecs, absolute after resolution), headers(),
                                  body(): Optional<JsonValue> or bytes, toBuilder() — immutable */ }
public final class HttpReply implements AutoCloseable { /* status(), headers(), body(): InputStream (single use),
                                  bytes(), json(), close() releases or aborts */ }
public final class TransportOptions { /* connectTimeout(), responseTimeout(), httpVersion() */ }
```

```java
package net.ai.gate;

/// SPI. Runs once per attempt, inside retries, after credentials are applied; registration order on the way in,
/// reverse order on the way out. May add headers or sign; may short-circuit deliberately. Exceptions propagate and
/// are translated. Streaming bodies pass through unread.
public interface WireInterceptor {
    HttpReply intercept(Chain chain) throws IOException;
    interface Chain {
        HttpCall call();
        String providerId();
        String requestId();
        HttpReply proceed(HttpCall call) throws IOException;
    }
}
```

`IOException` is allowed on transport SPIs because it is natural for implementers; the core translates
it to `TransportException` and classifies it as *before send* (retryable) or *after send* (outcome
unknown). After the interceptor chain, the core re-validates that the destination is still the
provider's origin; interceptors cannot redirect credentials elsewhere. `JdkHttpTransport` negotiates
HTTP/2, forces HTTP/1.1 for cleartext loopback servers (no `h2c` upgrade surprises with local runtimes),
enforces the idle limit through the stream watchdog (the JDK client has no read timeout), and offers
HTTP/3 when configured.

### 8.3 Provider-side SPI

```java
package net.ai.gate.spi;

/// Typed compat flags of one API family (OpenAiCompletionsCompat, AnthropicCompat, …). Immutable; merged field
/// by field: preset ▷ provider configuration ▷ model. Unset fields take the type's documented defaults.
public interface ApiCompat {
    String api();
    ApiCompat overriddenBy(ApiCompat higher);
}

/// Typed provider-specific request options (AnthropicOptions, OpenAiResponsesOptions, GeminiOptions, …).
/// Read only by codecs of api(); inert, with Warning("option_not_applicable"), elsewhere.
public interface ProviderOptions { String api(); }

/// Live model listing (vendor /models endpoints, OpenRouter, Ollama, gateways). I/O through the core's
/// authenticated, deadline-bound client.
@FunctionalInterface
public interface ModelSource { List<Model> fetch(ProviderHttp http) throws IOException; }

public interface ProviderHttp {
    JsonValue get(String relativePath) throws IOException;   // provider credentials, deadlines, redaction, events applied
}

/// A source of model metadata across providers (limits, prices, reasoning levels), for example the public metadata
/// database used to generate the bundled catalog. Entries carry their own timestamps for the freshness merge (§11.2).
public interface CatalogFeed {
    String id();
    List<Model> fetch(FeedHttp http) throws IOException;   // FeedHttp: GET of the feed's own absolute URL, no credentials
}

/// ServiceLoader: lets a module contribute presets, templates, model data and feeds to Llm.create() and ProvidersConfig.
public interface ProviderBundle {
    List<Provider> providers();                                     // presets and templates; empty for data-only modules
    default List<Model> catalogModels()      { return List.of(); }  // shipped model data (source CATALOG)
    default List<CatalogFeed> catalogFeeds() { return List.of(); }  // runtime feeds (source FEED)
}
```

Auth strategies (`ApiKeyAuth`, `OAuthAuth`) are specified with the auth API in §7.11.

### 8.4 Host-fed dependencies

| SPI | Abstract methods | Shipped defaults | Second implementation path | Registered via |
|---|---|---|---|---|
| `HttpTransport` | 1 | `jdk()` | OkHttp module, `ScriptedTransport` | `http(h -> h.transport(…))` |
| `WireInterceptor` | 1 | — | SigV4 signing module, tracing headers | `interceptor(…)` |
| `CredentialStore` | 3 | `inMemory()`, `file(Path)` | keychain module, encrypted database, vault | `credentials(…)`, `withCredentials(…)` |
| `AuthInteraction` | 2 | `console()`, `redirect(…)` | desktop dialogs, IDE prompts | `auth().login(…, ui)` |
| `ResponseCache` | 2 | `inMemory(…)`, `directory(Path)` | Redis or database caches | `responseCache(…)` |
| `JsonMapper` | 3 | record binder | Jackson module | `jsonMapper(…)` |
| `LlmListener` | 1 | — | metrics, OpenTelemetry module | `listener(…)`, `addListener`, `ChatOptions.listener` |
| `ProviderBundle` | 1 | one per provider module | host bundles for private gateways | `META-INF/services` |
| `TokenSupplier` | 1 | — | Azure Entra ID, Google ADC, host rotation | `ApiKeyAuth.dynamic(…)` |

### 8.5 Contract rows every extension documents

| Extension | Thread | Ordering | Error isolation | Blocking | Lifecycle |
|---|---|---|---|---|---|
| `LlmListener` | producing thread, outside SDK locks | per call, in order | caught, logged, ignored | must be brief; slows the caller | builder, `addListener`, per call |
| `WireInterceptor` | calling thread, per attempt | registration order in, reverse out | propagates, translated | allowed within the deadline | runtime lifetime |
| `HttpTransport` | any; thread-safe | — | `IOException` translated | yes; interruptible | owned if SDK-created, else borrowed |
| `CredentialStore` | any; thread-safe | `update` serialized per key | failures become `AuthenticationException(credential_store)` | short I/O allowed | host-owned |
| `AuthInteraction` | the `login()` caller | prompts in flow order | a throw aborts the login | `prompt` blocks; `notify` must not | per login |
| `ApiKeyAuth`, `OAuthAuth` | any | — | propagate as `AuthenticationException` | `resolve` and `toAuth` local; `login`, `refresh` network | provider-owned |
| `ModelSource`, `CatalogFeed` | background refresh or `refresh()` caller | — | reported per source in `RefreshReport`; previous data kept | network within the deadline | provider- or bundle-owned |
| `ResponseCache` | any; thread-safe | — | failures logged; the call proceeds uncached | short I/O allowed | host-owned |
| `TokenSupplier` | refresh thread, single-flight | — | becomes `AuthenticationException(refresh_failed)`, not retried | allowed within the deadline | host-owned |

### 8.6 Provider APIs — typed provider-only operations

```java
package net.ai.gate.spi;

/// A typed, provider-only API (files, batches, cached contents, account balances), declared as a constant by a
/// provider module.
public final class ProviderApi<T> {
    public static <T> ProviderApi<T> of(String api, String name, Class<T> type,
                                        Function<ProviderApiContext, T> factory) { … }
    public String api() { … }  public String name() { … }  public Class<T> type() { … }
}

/// Core execution for provider APIs: every exchange gets the provider's credentials, deadline, cancellation, retry
/// classification, redaction and events. Only relative URIs under the provider base URL are accepted.
public interface ProviderApiContext {
    Provider provider();
    JsonMapper jsonMapper();
    HttpReply exchange(HttpCall call, Replay replay);
    enum Replay { SAFE, UNSAFE }                     // SAFE permits retries (reads, idempotent writes)
}

/// Built-in experimental escape hatch: relative GET/POST under the provider base URL with its credentials.
public interface RawApi {
    ProviderApi<RawApi> KEY = …;                                   // valid for every provider, whatever its APIs
    JsonValue get(String path);
    JsonValue post(String path, JsonValue body);
}
```

`llm.providerApi("google", Gemini.CACHES)` validates that the API family matches one of the provider's
`apis()` (otherwise `IllegalStateException` naming both), creates the API once per runtime and provider,
and returns it without I/O. A provider API follows the facade's rules: blocking methods with explicit
I/O verbs, closeable handles for anything with a lifetime, documented side effects and retention.
Closing a runtime never deletes remote resources.

### 8.7 Extension ladder

| Need | Rung | Mechanism |
|---|---|---|
| Timeouts, retries, caching, reasoning, strictness, proxy, TLS, HTTP version | 1 — options | `ChatOptions`, provider defaults, `HttpOptions`, `CatalogOptions` |
| A provider speaking an existing API; a model the catalog lacks | 2 — data | `Provider` preset + `toBuilder()`, compat flags, `Model.builder(…)`, `ProvidersConfig`, `ProviderBundle` |
| Another HTTP stack, credential vault, login UI, JSON binder, cache store, cloud identity | 3 — host-fed dependency | §8.4 |
| Progress, metrics, tracing, audit | 4 — observation | `LlmListener`, JFR |
| Headers, request signing, body edits | 5 — interception | `WireInterceptor`, payload hook |
| Provider-only fields, hosted tools, native payloads, provider-only operations | 6 — escape hatch | `ProviderOptions`, `ProviderTool`, `Content.Unknown`, `providerApi(…)`, `RawApi` |
| A new wire format; a non-standard auth flow | SPI | `WireApi` + `StreamDecoder` passing `WireApiContract`; `ApiKeyAuth` / `OAuthAuth` |

Not offered: a generic plugin registry, callback-style streaming, raw SSE lines on the main API, or
subclassing of `Llm`.

### 8.8 Provider modules

```java
package net.ai.gate.openai;

public final class OpenAi {
    public static final WireApi RESPONSES;              // default for Providers.openai()
    public static final WireApi CHAT_COMPLETIONS;       // also the compatibility API for most gateways
    public static Provider provider() { … }             // preset: base URL, ApiKeyAuth.bearer("OpenAI API key", "OPENAI_API_KEY"),
                                                        // bundled models, compat, ModelSource for /models
    private OpenAi() { }
}

public final class OpenAiResponsesOptions implements ProviderOptions {   // api "openai-responses"
    public static Builder builder() { … }
    /* serviceTier, reasoningSummary(AUTO | CONCISE | DETAILED), store, previousResponseId, safetyIdentifier,
       include(List<String>), promptCacheKey — each optional */
}

/// Typed flags for the Chat Completions dialect family; set on presets, overridable per provider and model.
public final class OpenAiCompletionsCompat implements ApiCompat {
    public static Builder builder() { … }
    /* maxTokensField ("max_completion_tokens" | "max_tokens"), developerRole, store, streamUsage, strictTools,
       reasoningFormat (OPENAI | OPENROUTER | DEEPSEEK | QWEN | ZAI | TOGETHER | CHAT_TEMPLATE | NONE),
       reasoningContentReplay, thinkingAsText, toolResultName, assistantAfterToolResult, cacheControl
       (NONE | ANTHROPIC_STYLE), sessionHeader (NONE | OPENAI | OPENROUTER), overflowPatterns */
}

public final class OpenAiTools {
    public static ProviderTool webSearch()                              { … }
    public static ProviderTool fileSearch(List<String> vectorStoreIds)  { … }
    public static ProviderTool codeInterpreter()                        { … }
    public static ProviderTool remoteMcp(String label, URI serverUrl)   { … }
}

/// Presets are data; CHAT_COMPLETIONS unless noted. custom(…) detects flags for well-known hosts and reports them
/// in describe(); presets never rely on detection.
public final class OpenAiCompatible {
    public static Provider openRouter() { … }  public static Provider deepSeek() { … }  public static Provider xai() { … }
    public static Provider qwen() { … }        public static Provider mistral() { … }   public static Provider groq() { … }
    public static Provider ollama() { … }      public static Provider lmStudio() { … }  public static Provider vllm() { … }
    public static Provider liteLlm(URI baseUrl) { … }  public static Provider azureOpenAi(URI resourceUrl) { … }
    public static Provider custom(String id, URI baseUrl) { … }   // bearer auth from <ID>_API_KEY, conservative flags
}
```

The Anthropic and Google modules follow the same shape:

```java
package net.ai.gate.anthropic;

public final class Anthropic {
    public static final WireApi MESSAGES;
    public static Provider provider() { … }
    public static Provider compatible(String id, URI baseUrl) { … }   // template for Anthropic-compatible endpoints
}
public final class AnthropicOptions implements ProviderOptions {          // api "anthropic-messages"
    public static Builder builder() { … }
    /* thinkingBudget(int), beta(String) adds, apiVersion(String) — pinned by the revision; overriding warns
       untested_api_version — metadataUserId(String) */
}
public final class AnthropicCompat implements ApiCompat { /* for Anthropic-compatible endpoints: betaHeader,
       toolChoiceFormat, cacheTtlSupport */ }
public final class AnthropicTools {                                       // hosted tools as ProviderTool values
    public static ProviderTool webSearch(int maxUses) { … }  public static ProviderTool codeExecution() { … }
    public static ProviderTool bash() { … }  public static ProviderTool textEditor() { … }
    public static ProviderTool computerUse(int displayWidth, int displayHeight) { … }
}
```

```java
package net.ai.gate.google;

public final class Gemini {
    public static final WireApi GENERATE_CONTENT;
    public static Provider provider() { … }
    public static final ProviderApi<GeminiCaches> CACHES;             // explicit cached contents
}
public final class GeminiOptions implements ProviderOptions {          // api "google-generate-content"
    public static Builder builder() { … }
    /* thinkingBudget(int), includeThoughts(), safetySettings(JsonArray), cachedContent(String) */
}
public final class GeminiTools { public static ProviderTool googleSearch() { … }  public static ProviderTool codeExecution() { … }
                                 public static ProviderTool urlContext() { … } }

/// Remote resources with their own lifetime; closing a runtime never deletes them.
public interface GeminiCaches {
    CachedContent create(Model model, List<Message> contents, Duration ttl);
    CachedContent get(String name);
    List<CachedContent> list();
    CachedContent extend(String name, Duration ttl);
    void delete(String name);
}
public final class CachedContent { /* name(), model(), expiresAt(), usage() */ }
```

The aggregate artifact adds one index:

```java
package net.ai.gate.providers;

public final class Providers {
    public static Provider openai()      { … }  public static Provider anthropic()  { … }
    public static Provider google()      { … }  public static Provider openRouter() { … }
    public static Provider deepSeek()    { … }  public static Provider xai()        { … }
    public static Provider qwen()        { … }  public static Provider mistral()    { … }
    public static Provider groq()        { … }  public static Provider ollama()     { … }
    public static Provider lmStudio()    { … }  public static Provider vllm()       { … }
    public static List<Provider> presets() { … }   // every bundled preset and the two templates, immutable
    private Providers() { }
}
```

| Preset | Default base URL | APIs (default first) | Auth chain | Notes |
|---|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | Responses, Chat Completions | stored → `OPENAI_API_KEY` (bearer) | live `/models` |
| `anthropic` | `https://api.anthropic.com/v1` | Messages | stored → `ANTHROPIC_API_KEY` (`x-api-key`) + version header | `max_tokens` required: catalog default, visible in `preview()` |
| `google` | `https://generativelanguage.googleapis.com/v1beta` | generateContent | stored → `GEMINI_API_KEY` (`x-goog-api-key`) | tool-call ids synthesized; explicit caches via `Gemini.CACHES` |
| `openrouter` | `https://openrouter.ai/api/v1` | Chat Completions | stored (API key or OAuth PKCE issuing a key) → `OPENROUTER_API_KEY` | live catalog with prices; `route()` reported; session header |
| `deepseek` | `https://api.deepseek.com/v1` | Chat Completions, Messages (Anthropic-compatible path) | stored → `DEEPSEEK_API_KEY` | reasoning-content replay flag |
| `xai` | `https://api.x.ai/v1` | Chat Completions, Responses | stored → `XAI_API_KEY` | |
| `qwen` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | Chat Completions | stored → `DASHSCOPE_API_KEY` | `reasoningFormat = QWEN` |
| `mistral`, `groq` | provider `/v1` roots | Chat Completions | stored → `MISTRAL_API_KEY`, `GROQ_API_KEY` | strict-schema flag where unsupported |
| `ollama`, `lm-studio`, `vllm` | `http://127.0.0.1:11434/v1`, `:1234/v1`, `:8000/v1` | Chat Completions (Ollama native later) | `ApiKeyAuth.none()` (vLLM optional bearer) | provider defaults: `TimeoutPolicy.forLocalModels()`, `cacheRetention(NONE)`; live model listing |
| `litellm` | host-supplied | Chat Completions | stored → `LITELLM_API_KEY` | |
| `azure-openai` | `https://<resource>.openai.azure.com/openai/v1` | Responses, Chat Completions | stored → `AZURE_OPENAI_API_KEY` (`api-key`), or `ApiKeyAuth.dynamic` with Entra ID | deployment names are model ids |
| `openai-compatible`, `anthropic-compatible` (templates) | required | Chat Completions / Messages | stored → `<ID>_API_KEY` | `OpenAiCompatible.custom(…)`, `Anthropic.compatible(…)`; conservative compat flags; the basis of `ProvidersConfig` entries for gateways |

Preset data is volatile: URLs, headers, variables and flags are re-verified against provider
documentation when a module is released, pinned by fixtures, and versioned with the module. No preset
is shipped for consumer-subscription logins (ChatGPT, Claude Pro/Max, Copilot, Gemini CLI): their
tokens are not documented for third parties; an authorized host can add them through `OAuthAuth`.

### 8.9 Recipes

| Task | Steps | Code |
|---|---|---|
| Provider on an existing API | `Provider.builder(id, api)` or a preset's `toBuilder()` with base URL, auth strategy, compat flags, models; optionally a `ProviderBundle`; or a `ProvidersConfig` entry | none beyond data |
| A model missing from the catalog | nothing (it works as `UNLISTED`), or `Model.builder(…)` on the provider, or wait for the next catalog release | none |
| New wire format | implement `WireApi` and `StreamDecoder` in `…/internal`; expose a `public static final WireApi`; add a preset; extend `WireApiContract` with golden and hand-off fixtures | one module |
| New API revision (for example Responses v2) | add a new `WireApi` constant sharing code where possible; declare it on presets; switch a preset's default in a minor release; deprecate the old constant, remove it in a major | codec delta only |
| Provider-only request field or hosted tool | a `ProviderOptions` class or `ProviderTool` factory in the provider module; the codec reads it | provider module only |
| Provider-only operation | a `ProviderApi<T>` constant implemented against `ProviderApiContext` | provider module only |
| Non-standard login | an `OAuthAuth` or `ApiKeyAuth` implementation driving `AuthInteraction` | provider module or host |

The portable model (`Conversation`, `Content`, `ChatEvent`, `ChatOptions`) never changes for a provider
addition; provider-only concepts travel through `ProviderOptions`, `ProviderTool`, `providerData()` and
`Unknown(raw)`. Promotion to the portable model requires two APIs with the same meaning.

---

## 9. Configuration model

### 9.1 Scopes and precedence

```
Provider (data, persistable)      Llm.Builder (runtime)           ChatOptions scopes               Conversation (data)
├─ id, name, baseUrl, headers     ├─ providers, discovery          runtime defaults(…)              ├─ system
├─ apis (default first)           ├─ credentials, environment        ▲ overridden by               ├─ tools
├─ auth strategies                ├─ catalog (freshness)           provider defaults(…)             ├─ messages
├─ models, modelSource            ├─ http (options | transport)      ▲ overridden by               └─ cacheBreakpoints
├─ compat                         ├─ responseCache, interceptor*   call ChatOptions
└─ defaults (ChatOptions)         ├─ listener*, executor           (generation, caching, operational,
                                  ├─ jsonMapper, clock              provider options, hooks)
                                  └─ build()  validates, no I/O
```

- **Call options**, field by field: call → provider defaults → runtime defaults → catalog-derived value
  (output limit, reasoning mapping, §14.2) → nothing sent. Collections add across scopes for listeners,
  tags and headers; a scalar set at a higher scope wins.
- **Prompt caching**: `SHORT` unless a scope says `NONE` or `LONG` (D1).
- **Model**: always a call argument; `llm.model(provider, id)` resolves it from the catalog or as
  `UNLISTED`; an unknown provider fails naming the known ids.
- **Compat flags**: preset → provider configuration → model, field by field.
- **Headers**: provider → call, case-insensitive. Credential, content-type and protocol-version headers
  are SDK-controlled; setting them is rejected at build with a pointer to the right setting.
- **Provider options**: a call's `ProviderOptions` of the target API are applied; others are inert and
  reported (§14.6).
- **Environment**: read only by the auth chain through `Environment` (default `system()`); a missing key
  fails with the variable names. Nothing else reads environment variables or system properties.
- **Snapshots**: built runtimes never observe later builder edits. A configuration reload builds a new
  runtime; calls in flight keep their configuration.

### 9.2 Defaults (documented; changing one is a behavioural change with a changelog entry)

| Setting | Default | Rationale |
|---|---|---|
| Transport | JDK `HttpClient`, HTTP/2 (HTTP/1.1 for cleartext loopback), one per runtime | Zero dependencies; connection reuse |
| Connect timeout | 10 s | Fail fast on unreachable hosts |
| Stream idle | 5 min, streams only, reset by any bytes | Detect dead connections without killing silent reasoning phases |
| Total deadline | 10 min per call: credentials, attempts, backoff, and stream consumption | Matches common provider SDK practice; long jobs raise it explicitly |
| Retries | 3 attempts on pre-send failures and 408, 409, 429, 500, 502, 503, 529; `Retry-After` ≤ 60 s; backoff 500 ms × 2, cap 8 s, full jitter | Recovers from documented not-processed responses only |
| Prompt caching | `CacheRetention.SHORT`; markers placed by the codec | Multi-turn consumers save most of their input cost (D1); `NONE` at any scope turns it off |
| Reasoning across models | level mapped to the nearest supported; content kept (`ReasoningHandoff.KEEP`) | Continuity when switching models (D2) |
| Unsupported settings | soft adaptation with warnings; hard ones fail; `strict()` off | Model switching is the norm (D3) |
| Output limit | model maximum, clamped to the context window minus the estimated input; sent only where the API requires it | Right for every model without configuration |
| Sampling parameters | none sent | Provider defaults apply; unset ≠ default |
| Model catalog | bundled data + catalog artifact; background refresh every 24 h from discovered feeds (the public metadata feed is on, D11) and live listings; snapshot in memory | Freshest data (D4) without blocking reads; `noFeeds()` or `offline()` for restricted networks |
| Response cache | none | Caching model output is a product decision |
| Stream accumulation limit | 32 MiB | Bounded memory for `result()` |
| Credential store | in memory | Nothing touches disk unless asked |
| Environment | `Environment.system()` | Keys from the usual variables; servers choose `none()` |
| TLS | verification on; redirects off | Credentials never follow redirects |
| Logging | `System.Logger`; no bodies, prompts, outputs or secrets; wire log `OFF` | Privacy by default |
| JFR | events emitted; recorded only when a recording is active | Near-zero cost |
| Connection test | `test()` 15 s, no inference | Non-billable by default |

### 9.3 Footgun probes

| Probe | Decision |
|---|---|
| `Duration.ZERO` or negative timeouts or refresh intervals | Rejected at the setter; `noTotalTimeout()` and `manualRefresh()` are the named opt-outs |
| `maxAttempts(0)` | Rejected; `RetryPolicy.none()` means one attempt |
| Many retries with `noTotalTimeout()` | Warned at build |
| `Secret.of("")`, blank ids, blank model ids | Rejected at the call site |
| A provider without any auth strategy | Rejected at build; keyless servers declare `ApiKeyAuth.none()` |
| Swapped `llm.model(modelId, providerId)` | Unknown provider → `IllegalArgumentException` naming the known ids, at once |
| `http://` to a non-loopback host | Allowed with a build warning; credentials never sent over it unless `allowInsecureCredentials()` is set on the provider |
| Query-parameter keys | Only when a strategy declares them; never in logs, events, previews or errors |
| Credentials in `ProvidersConfig` | Impossible: the format has no credential field; unknown fields fail |
| Protected headers in `headers()` or payload edits touching auth, model or stream fields | Rejected |
| `CacheMode.OFFLINE` without a response cache | Rejected at the call, naming the missing setting |
| `insecureSkipTlsVerification()` | Explicit name; warning at build and in `describe()` |
| Operator environment keys on a multi-tenant server | `Environment.none()` documented for servers; `describe()` shows which environment is used |
| Confusable parameters | `Model` vs text, `Secret` vs plain strings, `Duration` everywhere, no positional booleans on public methods |

### 9.4 Portable configuration and secrets

- `ProvidersConfig.read/write` use a versioned schema (`llm-transport.providers/1`, example in §4.11).
  Unknown presets fail with the known ids; unknown fields fail unless prefixed `x-`; no classes are
  loaded by name. The format stores only differences from the preset.
- The format has no credential field. Keys and tokens live in a `CredentialStore` under the provider id
  (a file, an OS keychain, a vault or a database — each is a store implementation); OAuth credentials
  are bound to issuer, client and account.
- `ChatOptions` portable fields and `Conversation` have canonical JSON forms for workflow nodes and chat
  histories.
- The core reads only JSON. YAML, `.properties`, Spring Boot and similar formats live in optional modules
  that map onto the same builders.

### 9.5 Forms for UIs

`ApiKeyAuth.fields()` (key and provider settings) and `Model.parameters()` return the same
`FieldDescriptor` type, so one renderer draws "connect provider" forms and parameter forms.
`ChatOptions.Builder.set(key, raw)` accepts canonical strings (`.` decimals, ISO-8601 durations, JSON for
`JSON` fields) and routes them to the typed setters; `build()` reports every invalid field with its key.
Hosts parse locale-specific input before calling `set`. Interactive flows use `AuthInteraction`
instead of forms. Rendering a form, reading descriptors or validating performs no I/O.

---

## 10. Execution contracts

### 10.1 Sync (primary)

`complete()` blocks until a reply, the total deadline or cancellation. Retries happen inside; the caller
sees one outcome. Interrupting the calling thread aborts the transport, throws
`RequestCancelledException` with the `InterruptedException` as cause, and re-asserts the interrupt flag.
Runtimes, views and sub-APIs are thread-safe; many virtual threads may call one runtime concurrently.

### 10.2 Timeouts

| Timeout | Applies to | Enforcement |
|---|---|---|
| `connect` | Each connection establishment | `HttpClient` connect timeout |
| `streamIdle` | Streams only: time without any received bytes, keep-alives included | Watchdog closes the body; `RequestTimeoutException(stream_idle_timeout)`, `outcomeUnknown = true` |
| `total` | Whole call: credential resolution and refresh, every attempt and backoff, the non-streamed response, and stream consumption until the terminal event | Deadline carried in the call context; `RequestTimeoutException(deadline_exceeded)` |

There is deliberately no "first byte" timeout: a non-streamed response delivers its headers only after
generation finishes, so such a timeout would abort long reasoning calls (finding B2). For very long
generations, prefer `stream(…)` with a raised `total`: the idle watchdog then detects dead connections
early, and intermediaries see traffic.

### 10.3 Retries, idempotency and outcome certainty

- Retried: connection failures before the request was sent, and responses the provider documents as
  not processed (default set in §9.2, refined per API by `decodeError`). `Retry-After` is honoured up to
  `maxRetryAfter`; backoff uses full jitter; every retry emits `RequestEvent.Retrying`.
- Not retried: any failure after the request may have been processed — timeouts after send, resets,
  `504` — which surfaces with `outcomeUnknown() == true` so the host can decide (double billing).
- Never retried after the first stream event; the stream fails instead and `partial()` remains usable.
- One OAuth exception: a `401` on OAuth credentials forces one refresh (inside `CredentialStore.update`)
  and one retry, because the request was rejected before execution.
- One total deadline covers everything; attempts stop when the next backoff would exceed it.
- Only replayable bodies are retried (JSON bodies are; streamed uploads are not). Where an API supports
  idempotency keys, one key is used for all attempts of a logical call.
- No automatic fallback to another provider, model or credential; hosts do that explicitly.

### 10.4 Streaming

- Framing across network chunks, multi-line SSE fields and NDJSON lines is the library's job; consumers
  only see whole events.
- Deltas, cumulative snapshots and replacements are normalized to deltas per part; interleaved parts and
  tool calls keep their `index`; consumers must not assume a part's deltas are contiguous.
- Partial tool arguments are parsed best-effort from the fragments so far (repaired JSON) and never
  validated; `PartEnd` carries the authoritative call.
- Completion requires the protocol's terminal evidence: trailing usage is processed, and a premature
  EOF or a late provider error is a failure, not a success.
- On success iteration ends after `Done`; on failure the iterator throws the typed exception once (no
  duplicate error event), carrying `partial()`. Refusals and length limits are successful terminal
  outcomes with their stop reasons.
- Memory is bounded by framing buffers, the current part, and the accumulation limit; a tool call or
  native payload exceeding its bound fails explicitly.
- `result()` drains and returns the aggregate — equal to `complete()` for the same exchange; `partial()`
  never drains and returns an immutable snapshot.
- There is no transparent reconnection. A protocol-level resume capability, where one exists, would be
  an explicit operation with its own cursor semantics.

### 10.5 Cancellation and async

| Operation | Cancel with | Effect |
|---|---|---|
| any call | `CancelToken.cancel()` on its token or an ancestor | before start: never sent; during: transport aborted; `RequestCancelledException` with `partial()`; `Finished(CANCELLED)` event |
| `stream()` | `stream.close()` from any thread | same |
| `completeAsync()` | `future.cancel(true)` | cancels the call's internal child token; the future completes exceptionally |
| `complete()` | interrupting the calling thread | transport aborted; interrupt flag restored |
| `login()` | its `CancelToken`, or a throw from `prompt()` | loopback listener stopped; `AuthenticationException(login_cancelled)` |
| everything | `llm.close()` | cancels in-flight calls and background refresh it owns; later calls throw `IllegalStateException` |

Closing a socket is not proof that the provider stopped work or billing; the `Finished(CANCELLED)` event
reports only what the client observed. `completeAsync()` runs on the runtime executor (a virtual thread
per call by default, owned by the runtime; or the injected executor, borrowed), never on an I/O thread,
and fails with the same exception types as `complete()`; `join()` wraps them in `CompletionException` as
usual. `Flow.Publisher` and Kotlin coroutine adapters come later over the same contracts.

### 10.6 Threading and ownership

| Resource | Created by | Closed by |
|---|---|---|
| Default transport (`HttpClient`) | `Llm.build()` | `llm.close()` |
| Background catalog refresh | the runtime, on first use | `llm.close()` |
| Injected transport, executor, credential store, response cache, JSON mapper | host | host, never the runtime |
| OAuth loopback listeners | `login()` | the login itself, in every outcome |
| `ChatStream` response body | `stream()` | caller (`close()`); `llm.close()` aborts it |
| Listener registrations | `addListener` | `Registration.close()` or `llm.close()` |
| Views from `withCredentials` | parent | nothing to close; they fail like the parent once it is closed |
| Remote resources (files, cached contents, batches) | explicit provider-API calls | explicit calls; never by `close()` |

Runtimes, sub-APIs, codecs, transports and catalogs are thread-safe; models, configuration, messages,
events and exceptions are immutable; builders, iterators and interactions are confined to one thread
(except the documented thread-safe `cancel`/`close`). No lock is held across network I/O: credential
refresh is serialized per key by `CredentialStore.update`, and waiters join the in-flight result. On
Java 26 `synchronized` no longer pins virtual threads (JEP 491), so either lock form is acceptable for
short critical sections. The call context — request id, tags, deadline — travels in a `ScopedValue`,
never a `ThreadLocal`. Runtime `close()` is bounded (five seconds by default) and reports unfinished
cleanup through the logger.

---

## 11. Model catalog — the freshest data wins

### 11.1 Why a catalog

Model pickers, cost display, output-limit defaults, reasoning controls and capability checks all need
per-model facts: context window, output limit, modalities, reasoning levels, prices. Provider list
endpoints rarely return them — most return ids and little else — so the SDK ships and maintains a
catalog, keeps reads synchronous (pickers never wait for the network), and refreshes it from the
freshest sources available (D4).

### 11.2 Sources and the freshness merge

| Source (`Model.Source`) | Where it comes from | Timestamp used | Typical content |
|---|---|---|---|
| `BUNDLED` | `models.json` inside each provider module, generated at release | generation time | every field the metadata source knew at release |
| `CATALOG` | the `llm-transport-catalog` artifact (`ProviderBundle.catalogModels()`), date-versioned, released weekly | generation time | same fields, newer |
| `FEED` | a `CatalogFeed` at runtime; `llm-transport-catalog` contributes one for the public metadata database the catalog is generated from, on by default (D11) | entry update time, else fetch time | limits, prices, reasoning levels, modalities |
| `LIVE` | the provider's `ModelSource` (vendor `/models`, OpenRouter, Ollama, gateways), with the provider's credentials | fetch time | existence, names; prices and limits where the provider reports them |
| `CUSTOM` | the host: `Model.builder(…)` on a provider, `ProvidersConfig` models | — (always wins) | whatever the host states |
| `UNLISTED` | synthesized by `require(…)` for an id no source knows | — | provider defaults only |

Merge rules, per `(providerId, modelId)` and per field:

1. **Explicit beats fresh.** A value the host configured (`CUSTOM`) always wins.
2. **Fresh beats old.** Otherwise the newest non-absent value wins, by the timestamp of its source.
   Absent values never overwrite present ones; unknown stays absent (principle 4).
3. **Freshest evidence of availability.** When a provider's live listing has succeeded, `available()`
   shows only models it lists (plus `CUSTOM` ones); `all()` still returns every known model, and
   `deprecatedAt()` is kept from any source.
4. **No silent cross-provider merging.** Feed adapters map their ids to provider ids explicitly; a model
   served by two providers is two entries with independent prices.
5. **Provenance is visible.** `Model.source()` names the freshest contributing source and `updatedAt()`
   its timestamp; `describe()` lists every source with its age.

### 11.3 Models the catalog does not know

Models ship faster than any catalog. `llm.model("openai", "gpt-6-preview")` returns an `UNLISTED` model
with the provider's default API and compat flags and no limits or prices; the first call reports
`Warning(unlisted_model)`, the output limit falls back to the API's requirement (§14.2), and `cost()` is
absent. The next refresh that finds the model replaces the synthesized entry.

### 11.4 Refresh

- **Background refresh is on by default** (`refreshInterval` 24 h). It is scheduled on first use of the
  catalog or of a call once the snapshot is older than the interval, runs on a virtual thread owned by the
  runtime, and never blocks a read or a call: readers see the previous snapshot until the new one is
  published atomically.
- Live listings run only for providers whose auth is configured (listings need credentials); feeds need
  none. Each source is fetched with its own deadline; a failed source keeps its previous data and is
  reported in `RefreshReport` and `CatalogEvent.Refreshed`.
- Feed requests carry no credentials, prompts, usage or host identifiers beyond the SDK's user agent;
  `describe()` lists every feed host.
- `models().refresh(…)` fetches now, for chosen providers or all; pickers call it from a "Refresh" button.
- `catalog(c -> c.snapshotFile(path))` persists the merged snapshot with its timestamps, so a restarted
  application starts from the freshest data it has seen rather than from bundled data.
- `manualRefresh()` turns background refresh off; `offline()` uses bundled data, the catalog artifact
  and the snapshot only, with no network at all (air-gapped hosts, reproducible tests); `noFeeds()` and
  `noLiveListings()` narrow the sources.

### 11.5 Producing the bundled data

`updateModelCatalog` (§6.5) regenerates each module's `models.json` and the `llm-transport-catalog`
resources from the public metadata source plus curated overrides (for fields the source gets wrong),
validated against presets and pinned by tests. A scheduled CI job runs it weekly and releases a new
date-versioned `llm-transport-catalog`; provider modules pick up the data at their next release. Hosts
that want the newest data without a code upgrade bump only the catalog artifact — and the runtime
refresh supersedes both whenever it succeeds.

### 11.6 What the catalog drives

| Use | Fields |
|---|---|
| Output-limit default and clamping | `maxOutputTokens`, `contextWindow` |
| Reasoning level mapping and budgets | `reasoningLevels`, provider values per level |
| Cost on every reply | `prices` with tiers |
| Soft/hard capability checks | `capabilities`, `input` |
| Image downgrade for text-only models | `input` |
| Parameter forms | all of the above via `parameters()` |
| Codec behaviour | `compat` overrides per model |

---

## 12. Cache management

### 12.1 Overview

| Concern | What is cached | Configured by | Default | Invalidation | Observed through |
|---|---|---|---|---|---|
| Provider prompt caching | Prompt prefixes, on the provider's side | `cacheRetention`, `sessionId`, `cacheBreakpoint()` | **On**: `SHORT` (D1) | Provider TTL; `NONE` at any scope | `Usage.cacheRead()`, `cacheWrite()`, cache prices in `Cost` |
| Response cache | Complete successful replies, client-side | `responseCache(…)`, `ChatOptions.responseCache(CacheMode)` | Off | TTL, `remove`, `clear`, `REFRESH`, changed request bytes | `info().fromCache()`, `Finished.fromCache()`, JFR |
| Model catalog | Merged model metadata | `catalog(…)` | Background refresh 24 h; memory | `refresh()`, newer sources | `Model.source()`, `updatedAt()`, `CatalogEvent` |
| Credentials | Resolved keys, OAuth tokens, dynamic tokens | `CredentialStore`, `ApiKeyAuth.dynamic` | In memory | `logout()`, `revoke()`, expiry, store updates | `AuthStatus`, `CredentialEvent` |

Provider-side cache *resources* with their own lifecycle — Gemini cached contents — are managed
explicitly through `llm.providerApi("google", Gemini.CACHES)` and referenced from calls with
`GeminiOptions.cachedContent(…)`. Closing a runtime never deletes them.

### 12.2 Provider prompt caching

On by default: `SHORT` applies to every call unless a scope sets `NONE` or `LONG`. Codecs place markers
automatically — after the tools and system prompt, and after the history before the newest user turn —
unless the conversation carries explicit `cacheBreakpoint()`s, which then replace automatic placement.
`sessionId` adds a routing or affinity key where the API has one.

| API | `SHORT` | `LONG` | `NONE` | `sessionId` | Usage normalization |
|---|---|---|---|---|---|
| Anthropic Messages | `cache_control` (default TTL) on the marked prefixes; the breakpoint limit is validated (excess markers dropped with a warning) | 1-hour TTL where the model supports it, else `SHORT` + `option_adapted` | no markers | — | cache read and creation counters into `cacheRead`, `cacheWrite` |
| OpenAI Responses and Chat Completions | automatic prefix caching; markers are satisfied hints | extended retention where supported | no cache key or retention sent; the provider's implicit caching may still apply | `prompt_cache_key` | cached tokens into `cacheRead`; `input` excludes them |
| Gemini generateContent | implicit caching | implicit caching; explicit caches via `Gemini.CACHES` | nothing sent | — | cached-content counter into `cacheRead` |
| Compatible providers | per compat flag: automatic (DeepSeek), `cache_control` pass-through where accepted, otherwise satisfied hints | per compat flag | no markers | per compat flag (`sessionHeader`) | counters normalized by the codec |

Determinism rules make caching effective: JSON keys keep insertion order, the SDK injects no volatile
values (timestamps, random ids) into prompts, tools keep declaration order, and identical requests
produce identical bytes (pinned by `WireApiContract`; visible in `preview().body()`). Providers that
charge a premium for cache writes (Anthropic) make single-shot calls slightly more expensive with
`SHORT`; single-shot hosts set `NONE` once in the runtime defaults.

### 12.3 Response cache (client-side, opt-in)

- **Key**: SHA-256 over provider id, base URL, API id and revision, the credential scope (the credential
  store scope and, for OAuth, the account — known without resolving a secret, so cassettes replay without
  keys), method, resolved path, cache-relevant headers (protocol version and beta flags, never
  credentials) and the canonical body. A shared cache therefore never serves one tenant's reply to
  another, and a codec change that alters the wire bytes naturally misses.
- **Stored**: status, redacted headers, and the body (non-streamed) or frames (streamed) of 2xx
  exchanges that finished with terminal evidence, plus the request body for diagnosing misses — the
  `llm-transport.exchange/1` format. Never errors, cancellations or partial streams.
- **Hit**: replayed through the same codec or stream decoder, so types, warnings and events are those of
  a live call; streams replay without artificial delays; `fromCache()` is true; usage is the recorded
  usage with `spent() == false`.
- **Modes** (`CacheMode`): `READ_WRITE` (default when a cache is set), `REFRESH` (skip reads, write
  results — "force re-run"), `BYPASS` (neither), `OFFLINE` (read only; a miss throws
  `InvalidRequestException(cache_miss)` naming the request, and never touches the network).
- **Stores**: `ResponseCache.inMemory(maxEntries[, ttl])` (LRU) and `ResponseCache.directory(path)` (one
  readable JSON file per entry — test cassettes and development). Persistent production stores are host
  SPI implementations that own encryption and retention: a response cache holds prompts and outputs.
- **Scope**: exact-match memoization only. Semantic caching, which trades correctness for hit rate, is
  an anti-goal. Whether memoizing sampled output is acceptable is the caller's decision.

### 12.4 Credential caching

Stored credentials are read through the `CredentialStore` on each call's auth resolution (stores cache
as they see fit). OAuth tokens are refreshed before expiry minus a skew (default 60 s) inside
`CredentialStore.update`, so concurrent calls — and processes sharing a locking store — refresh once;
a rotated refresh token is persisted in the same step. Dynamic tokens (`ApiKeyAuth.dynamic`) are cached
until expiry minus the skew, single-flight. A caller cancelled while waiting for a shared refresh stops
waiting without cancelling the refresh others need.

---

## 13. Authentication and OAuth

### 13.1 Resolution order

For every call, the `AuthResolver` asks the provider's strategies, in this order:

1. **Stored credential** under the provider id in the runtime's (or view's) `CredentialStore`: an
   `OAuthCredential` (refreshed if expiring) or an `ApiKeyCredential`. A stored credential *owns* its
   provider: a failed OAuth refresh reports `REFRESH_FAILED` and never falls back to an environment key.
2. **Environment and ambient sources** read by `ApiKeyAuth.resolve` through `Environment` — the preset's
   variables (`ANTHROPIC_API_KEY`, …), and for cloud presets ambient identity (`ApiKeyAuth.dynamic`).
3. **Keyless** (`ApiKeyAuth.none()`) for local servers.
4. Otherwise `AuthenticationException(login_required)` naming the variables and the login methods.

Every resolution yields a `source` label, shown by `auth().status(…)` and `describe()`. `status()` and
`available()` use `ApiKeyAuth.configured(…)`, which never fetches tokens or touches the network.
`Environment.none()` removes step 2 for multi-tenant servers.

### 13.2 Credential application — every attempt

```
ResolvedAuth ─┬─ headers ──────► Authorization: Bearer … | x-api-key: … | api-key: … (+ provider version headers)
              ├─ query ────────► ?key=… (only when a strategy declares it; never logged)
              └─ baseUrl ──────► per-credential base URL (e.g. GitHub Copilot accounts), validated against the provider's
                                 allowed origins
OAuthCredential ─► expiring? ─► CredentialStore.update(id, refresh) ─► toAuth(credential) ─► ResolvedAuth
```

### 13.3 Interactive login — one protocol for every flow

`auth().login(providerId, type, interaction)` runs the provider's flow on the calling thread (a virtual
thread in servers) and stores the resulting credential. The flow talks to the user only through the
`AuthInteraction` it was given:

```
login() ─► flow ─┬─ API key:   prompt(SecretText "Enter DeepSeek API key") [+ prompt(Text) per provider setting] ─► save
                 ├─ browser:   notify(OpenUrl) ─► loopback callback on 127.0.0.1 ─┐
                 │             prompt(Code) as a fallback (pasted code) ◄──────────┴─ whichever arrives first wins
                 ├─ web:       notify(OpenUrl) → host redirects ─► prompt(Code) completed by RedirectInteraction.complete(uri)
                 ├─ device:    notify(DeviceCode) ─► polling at the server's interval ─► notify(Progress)
                 └─ select:    prompt(Select) for flows with choices (AWS profile, subscription tier)
                 ─► exchange (state and PKCE validated) ─► CredentialStore.update ─► DONE
cancel token / throw from prompt() / timeout / denial ─► listener stopped ─► AuthenticationException(login_cancelled | …)
```

| Flow | Selected by | Behaviour |
|---|---|---|
| API key entry | `AuthType.API_KEY` | Prompts for `ApiKeyAuth.fields()`; stores an `ApiKeyCredential` with settings |
| Authorization code + PKCE, loopback | any other `AuthInteraction`, when the config has no fixed `redirectUri` | Listener on `127.0.0.1`, ephemeral or fixed port, one request, `state` validated, minimal success page, then stopped |
| Authorization code + PKCE, external redirect | `AuthInteraction.redirect(redirectUri, …)`, or a fixed `OAuthConfig.redirectUri` | No listener; the host's callback route completes the interaction |
| Device authorization | config grant `DEVICE_CODE` (preferred when no browser is available) | Polls at the server's interval; handles pending, slow-down, denial and expiry |
| Client credentials | config grant `CLIENT_CREDENTIALS` | Non-interactive; also used transparently on first call |
| Refresh | automatic, or on a `401` once | Proactive before expiry; inside `CredentialStore.update` |
| Logout / revoke | `auth().logout()` / `auth().revoke()` | Local delete, no I/O / remote revocation where supported, then local delete |

### 13.4 Multi-user hosts

A web backend keeps one runtime and gives each user a view: `llm.withCredentials(store.scoped(userId))`
shares providers, transport, catalog and caches, and reads and writes only that user's credentials.
Pending web logins are bound to the initiating application user by the host; the SDK validates the
OAuth transaction (state, PKCE, issuer), not the application's users. Servers set
`Environment.none()` so operator variables never authenticate a tenant.

### 13.5 Security contract

- PKCE S256 and a fresh `state` per transaction; no implicit or password grants. HTTPS for authorization
  and token endpoints, except loopback.
- The loopback listener binds `127.0.0.1` only, accepts one callback, and closes on success, failure,
  timeout or cancellation. Nothing listens on external interfaces.
- Authorization and token endpoints come from trusted configuration (presets or host `OAuthConfig`); an
  arbitrary issuer URL is not permission to contact a host. Inference, OAuth and proxy destinations are
  separate scopes; redirects are not followed with credentials; a per-credential base URL must match the
  provider's allowed origins.
- Credentials are keyed by provider id within a store scope and bound to issuer, client id and account;
  a refresh or re-login cannot silently change principal; a credential whose issuer or client no longer
  matches the provider is rejected as `login_required`.
- Refresh is serialized per key by `CredentialStore.update`; rotated refresh tokens are persisted in the
  same update; a refresh rejected as invalid marks the credential `REFRESH_FAILED` and yields
  `login_required`.
- `logout()` is local and immediate; `revoke()` contacts the server. After either, a late login or
  refresh completion cannot store tokens.
- Login URLs, device codes and prompts reach only the `AuthInteraction` passed to `login()`; listeners
  receive no login events. Tokens and keys never appear in events, logs, exceptions, previews or
  `toString()`; only `Secret.fingerprint()` does.
- Successful authorization means credentials were obtained — not that a model, quota or billing account
  is usable; `test(model)` answers that.
- These rules follow OAuth for native apps (RFC 8252), the OAuth security best current practice
  (RFC 9700) and device authorization (RFC 8628).

### 13.6 Provider-specific shapes without special code paths

| Case | Mechanism |
|---|---|
| OpenRouter PKCE returns an API key, not tokens | `OAuthConfig.tokenResponseMapper` maps `{"key": …}` to an `OAuthCredential` without expiry |
| Cloudflare-style account and gateway ids | `ApiKeyAuth.fields()` declares them; stored in `ApiKeyCredential.settings()`; codecs read them from the resolved auth |
| Azure OpenAI with Entra ID, Vertex AI with Google credentials | `ApiKeyAuth.dynamic(name, () -> new AccessToken(Secret.of(cloudSdk.token()), Optional.of(cloudSdk.expiry())))` |
| Per-account base URL (GitHub Copilot style) | `OAuthAuth.toAuth` returns `ResolvedAuth.baseUrl()` within allowed origins |
| Corporate gateway with client credentials | `OAuthConfig` grant `CLIENT_CREDENTIALS`; `clientSecretKey()` names the credential-store entry holding the secret |
| AWS Bedrock SigV4 | a signing `WireInterceptor` in the optional `llm-transport-bedrock` module |
| mTLS gateways | `HttpOptions.clientCertificate(…)`; transport identity, separate from token presentation |

---

## 14. Portability — one conversation, any model

### 14.1 Portable concepts and their API mappings

Illustrative; each row is pinned by golden fixtures in the API's `WireApiContract` suite and re-verified
when a module is released.

| Portable concept | OpenAI Responses | Chat Completions (+ compatible) | Anthropic Messages | Gemini generateContent |
|---|---|---|---|---|
| `Conversation.system()` | `instructions` | system or developer message (compat) | top-level `system` | `systemInstruction` |
| `Tool.of(…)` | function tool | `tools[].function` | `tools[]` with `input_schema` | `functionDeclarations` |
| `ToolCall` | `function_call` item | `tool_calls[]` | `tool_use` block | `functionCall` part, id synthesized |
| `ToolResult` | `function_call_output` item | `tool` role message | `tool_result` block in a user turn | `functionResponse` part |
| `ReasoningLevel` | `reasoning.effort` | `reasoning_effort` or the compat reasoning format | effort, or budget from the catalog or the API table | `thinkingConfig` level or budget |
| Reasoning continuity (same origin) | reasoning items with encrypted content | compat flag (`reasoning_content` replay) | `thinking` blocks with signatures | thought signatures |
| `OutputFormat.jsonSchema(…)` | `text.format` JSON Schema | `response_format` JSON Schema | native structured output, or a documented forced-tool adaptation | response MIME type + schema |
| `maxTokens` | `max_output_tokens` | `max_completion_tokens` or `max_tokens` (compat) | `max_tokens` (required) | `maxOutputTokens` |
| `seed` | not in the protocol → soft adaptation | `seed` | not in the protocol → soft adaptation | `seed` |
| `cacheRetention` | automatic; retention; `prompt_cache_key` | automatic, or `cache_control` (compat) | `cache_control` TTLs | implicit, or explicit caches |
| Streaming | typed SSE events | SSE chunks + `[DONE]` | typed SSE events | SSE |
| Usage | input, output, cached, reasoning details | `usage` (stream usage per compat) | input, output, cache read, cache creation | `usageMetadata` |

### 14.2 Defaults resolved when a call leaves a setting unset

| Setting | Resolution |
|---|---|
| `maxTokens` | provider defaults → runtime defaults → the model's maximum output clamped to `contextWindow − estimate(conversation) − margin` → sent only where the API requires a value; when limits are unknown and the API requires one, the API's documented default with `Warning(max_tokens_defaulted)`; a catalog-derived value is a preview note (`max_tokens_from_catalog`), not a warning |
| `reasoning` | provider or runtime defaults → not sent (the model's own default) |
| Budget-based APIs | the level becomes a token budget from the catalog, else the API's table (`MINIMAL` 1 024, `LOW` 2 048, `MEDIUM` 8 192, `HIGH` 16 384, `XHIGH` 32 768, `MAX` = model maximum minus answer room); `AnthropicOptions.thinkingBudget` overrides; the output limit is raised to budget + at least 1 024 answer tokens, capped at the model maximum |
| `cacheRetention` | `SHORT` (D1) |
| `reasoningHandoff` | `KEEP` (D2) |
| Sampling (`temperature`, `topP`, `topK`, `stop`, `seed`) | not sent |

### 14.3 Soft and hard adaptations

A **soft** adaptation keeps the meaning of the request close enough for the call to be useful and is
reported as a `Warning` on the reply, a `Finished.warnings()` count and in `preview()`. Under `strict()`
soft adaptations fail with `InvalidRequestException(unsupported_feature)` naming the field and the API.
**Hard** mismatches always fail.

| Situation | Default | `strict()` |
|---|---|---|
| Reasoning level the model does not support | mapped to the equivalent level (§14.4) + warning | fail |
| Reasoning requested on a model without reasoning control | not sent + warning | fail |
| A parameter the model rejects (compat flag, for example temperature on some reasoning models) | dropped + warning | fail |
| `maxTokens` above the model maximum | clamped + warning | fail |
| `seed` or another portable setting the API cannot express | dropped + warning | fail |
| Images for a model without image input | placeholder text + warning | fail |
| A `ProviderTool` sent to another API | dropped + warning | fail |
| Foreign `ProviderOptions` | inert + warning | inert + warning (declared provider-specific) |
| Cache retention or breakpoints the API cannot express | nearest value or dropped markers + warning | same (caching is advisory) |
| Tools on a model marked `UNSUPPORTED` | fail | fail |
| Output schema with neither native nor documented adapted support | fail | fail |
| Document or audio input the API cannot carry | fail | fail |

Documented equivalent adaptations are part of an API's contract and are not warnings: system
placement, role mapping, tool-id synthesis, usage normalization. Numeric ranges are never rescaled: a
temperature outside the API's range is rejected, not mapped. A capability marked `UNKNOWN` proceeds and
lets the provider decide.

### 14.4 Reasoning across models (D2)

**Reasoning level.** The requested `ReasoningLevel` is translated per call into the equivalent level the
target model supports, so one `ChatOptions` works unchanged across a model switch:

- A supported level is used as is.
- Otherwise the nearest supported level in the order `OFF < MINIMAL < LOW < MEDIUM < HIGH < XHIGH < MAX`
  is used; a tie resolves upward, preserving the caller's quality intent. `XHIGH` on a model offering
  `LOW, MEDIUM, HIGH` becomes `HIGH`; `MINIMAL` on `LOW, HIGH` becomes `LOW`.
- `OFF` on a model that cannot disable reasoning becomes its lowest level; any level on a model
  without reasoning control is not sent.
- Each translation is reported (`reasoning_clamped: XHIGH → HIGH`); `strict()` fails instead.
- Levels map to provider values (effort names, budgets, template switches) from catalog data and the
  compat flags, never from hard-coded model lists.

**Reasoning content.** The thinking text a previous model produced is carried into the next call:

| Reasoning part in history | Same origin (provider, API, model) | Other origin, `ReasoningHandoff.KEEP` (default) | Other origin, `ReasoningHandoff.DROP` |
|---|---|---|---|
| Readable reasoning text | replayed natively, with its signature | passed on as a text part at the start of that assistant turn, delimited as `<thinking>…</thinking>` so the new model reads it as earlier reasoning, not as an answer; `reasoning_converted` | omitted; `reasoning_dropped` |
| Signatures, encrypted or redacted reasoning | replayed natively (some APIs require it for tool-use continuity) | cannot be transferred — valid only for their origin — so omitted; `reasoning_dropped` | omitted |

The transformation applies to the copy sent in one call; the stored `Conversation` keeps every part as
produced. Switching back to the original model therefore replays its own reasoning natively again.
`DROP` never removes same-origin reasoning, because the originating API may reject a tool-use turn
without it.

### 14.5 Hand-off — the full table

Applied by `HandoffTransformer` before encoding, for target provider P, API A and model M. Adaptations
beyond an id rename are reported as warnings.

| History part | Origin = (P, A, M) | Any other origin |
|---|---|---|
| Text | as is | as is; provider text signatures dropped |
| Reasoning | §14.4 | §14.4 |
| Tool call | as is | id normalized by `A.normalizeToolCallId` (for example OpenAI ids of 450+ characters into Anthropic's `^[a-zA-Z0-9_-]{1,64}$`); provider signatures dropped; matching tool results remapped |
| Tool result | as is | id remapped with its call; images downgraded when M lacks image input |
| Images in user messages, M without `IMAGE` input | placeholder text + `image_omitted` | placeholder text + `image_omitted` |
| Refusal | as is | converted to assistant text (`history_adapted`) |
| Aborted or failed assistant turns (`partial()`) | content kept; empty turns dropped | same |
| `Content.Unknown` | replayed | dropped (`history_adapted`) |

### 14.6 Provider option scoping — why one call survives a provider switch

- Every `ProviderOptions` class names its API family (`api()`).
- It applies when the target model's API matches. Otherwise it is **inert**: not sent, reported as
  `Warning(option_not_applicable)` and in `preview()`, regardless of `strict()` — it was declared
  provider-specific, so ignoring it elsewhere changes no portable meaning.
- An applicable option the model or revision cannot express is a soft adaptation.
- Consequently one call, or one workflow-node configuration, can carry tuning for several providers and
  run unchanged against any of them.

### 14.7 API versions and revisions

- A `WireApi` constant is an API family and revision; it never changes meaning. An incompatible revision
  becomes a new constant; the old one is deprecated with `since` and `forRemoval` and removed in the next
  major version.
- Version parameters (Anthropic's version header, Azure's `api-version`, Gemini's `v1beta` path) belong to
  the revision or the preset. Overriding them through provider options is allowed and reported as
  `untested_api_version`.
- Newer server revisions degrade gracefully in older SDKs: unknown fields, parts, events, stop reasons
  and error codes are preserved (`Unknown`, open value types), never fatal.

---

## 15. UI and workflow-builder integration guide

| Need | SDK answer | I/O |
|---|---|---|
| List providers and render "connect" forms | `Providers.presets()`, `Provider.apiKeyUrl()`, `ApiKeyAuth.fields()` | no |
| "Sign in" / "Connect" dialogs for any flow | `auth().methods(id)`, `auth().login(id, type, interaction)` with `AuthPrompt`/`AuthNotice` | yes |
| Save a key from a static form | `auth().save(id, new ApiKeyCredential(…))` | store only |
| Auth status chip with its source | `auth().status(id)` → state, source, expiry | no |
| Save / load provider configuration | `ProvidersConfig.write/read`; secrets in the credential store | no |
| "Test connection" button | `test(model)` → staged `ConnectionReport` | yes, non-billable |
| Model picker with capabilities, context, prices, freshness | `models().available()`, `Model` fields, `updatedAt()` | no (background refresh keeps it current) |
| "Refresh models" button | `models().refresh(…)` → `RefreshReport` | yes |
| Parameter form | `model.parameters()`; `ChatOptions.Builder.set(key, raw)` | no |
| Warn about settings a model cannot honour | `preview()` warnings and problems; `Capabilities.support(…)` | no |
| Preview / dry-run a node | `preview(model, conversation, options)`: exact body, redacted headers, warnings | no |
| Run a node with progress and Stop | `stream(…)` or `completeAsync(…)`; `RequestEvent`s; a run `CancelToken` | yes |
| Stop a whole run | `run.cancel()` on the run's token; nodes use `run.child()` | no |
| Show partial output after a failure or stop | `e.partial()`, `stream.partial()` | no |
| Cost of a run | `Usage.cost()`, `Finished.cost()`; `spent()` excludes replayed calls | no |
| Re-run a graph without re-billing unchanged nodes | response cache with `READ_WRITE`; `REFRESH` for "force re-run" | depends |
| Correlate events with a workflow run | `ChatOptions.tag("run", id)`, per-call `listener(…)` | no |
| Switch a node's model | change the model argument; soft adaptations are reported, `strict()` for strict nodes | — |
| Persist node settings and outputs | `Json.valueOf(options)` (portable fields), `Json.valueOf(conversation)` | no |
| No execution on rendering | nothing in `Provider`, `Model`, `preview()`, `status()`, `describe()`, form descriptors performs I/O | — |

---

## 16. Testing and debugging toolkit

### 16.1 Testing (`llm-transport-testing`)

```java
package net.ai.gate.testing;

/// A scripted provider behind a real Llm: resolution, hand-off, validation, events, streaming aggregation,
/// retries and caching behave exactly as in production. Several fake providers can coexist in one runtime,
/// so model-switching tests need no mocks.
public final class FakeProvider {
    public static FakeProvider create()                                     { … }  // provider "fake", models "fake", "fake-thinker"
    public static FakeProvider create(String providerId, Model... models)   { … }
    public FakeProvider reply(String text)                                  { … }  // next call answers with text
    public FakeProvider reply(Consumer<ScriptedReply.Builder> reply)        { … }  // reasoning, tool calls, usage, stop reason
    public FakeProvider fail(LlmException error)                            { … }  // for example LlmErrors.rateLimited(…)
    public FakeProvider respond(Function<ApiRequest, AssistantMessage> handler) { … }  // dynamic answers
    public FakeProvider pacing(int tokensPerSecond)                         { … }  // real-time streaming for UI tests
    public Provider provider()                                              { … }
    public Model model()                                                    { … }
    public Model model(String id)                                           { … }
    public List<ApiRequest> requests()                                      { … }  // adapted requests, in order
    public void assertAllRepliesConsumed()                                  { … }
}

/// One scripted answer; streamed in chunks (tool arguments included); usage estimated when not given;
/// prompt-cache reads and writes simulated per sessionId and cacheRetention.
public final class ScriptedReply {
    public static final class Builder {
        public Builder text(String text)                                    { … }
        public Builder reasoning(String text)                               { … }
        public Builder toolCall(String name, JsonObject arguments)          { … }
        public Builder usage(long input, long output)                       { … }
        public Builder stopReason(StopReason reason)                        { … }
        public ScriptedReply build()                                        { … }
    }
}
```

| Tool | Purpose |
|---|---|
| `FakeProvider` | Host tests without network, keys or mocks of the facade; multi-provider and hand-off tests |
| `ScriptedTransport` | Wire-level scripts (status, headers, SSE chunks split anywhere, delays, resets) for codec and transport tests |
| `RecordingListener` | Captures events for assertions on progress, retries, warnings and cost; the reference listener |
| `LlmErrors` | Factories for every exception with realistic fields |
| `TestClock` | Advances time for expiry, backoff and catalog age without sleeping |
| `FakeAuthorizationServer` | Local OAuth server: PKCE, device flow, refresh rotation, denial, slow-down, expiry |
| `ScriptedInteraction` | An `AuthInteraction` answering prompts from a script and recording notices |
| Contract kits | `WireApiContract` (golden, streaming and hand-off fixtures), `HttpTransportContract`, `CredentialStoreContract` (atomic `update`, concurrent refresh across two processes), `ResponseCacheContract`, `JsonMapperContract`, `CatalogFeedContract`: abstract JUnit classes every implementation extends |
| Cassettes | `ResponseCache.directory(…)` + `CacheMode.OFFLINE` in CI; `REFRESH` to re-record |

| Level | Tooling | Proves |
|---|---|---|
| Host unit tests | `FakeProvider`, `RecordingListener`, `LlmErrors`, `ScriptedInteraction` | Host logic, model switching and error handling, offline |
| API conformance | `WireApiContract`, golden fixtures, `ScriptedTransport` | Wire mapping, streaming, error and overflow mapping, hand-off |
| Replay | cassettes | Behaviour against real recorded traffic, deterministic |
| Live | `liveTest` suite (`-Plive`) | Real endpoints, bounded cost, nightly |

### 16.2 Debugging

| Question | Tool |
|---|---|
| What exactly will be sent? | `preview(model, conversation, options)`: body, redacted headers, warnings, problems |
| Reproduce outside Java? | `preview.toCurl()` with credential placeholders |
| Which configuration, credentials source and catalog data are in effect? | `llm.describe()`: providers, APIs, compat, auth sources, catalog sources and ages, policies; redacted |
| What went over the wire? | `http(h -> h.wireLog(WireLog.HEADERS or BODIES))` → logger `net.ai.gate.wire`, redacted |
| Why slow, how many retries, cached or not, what did it cost? | `info()` (attempts, latency, time to first output, `fromCache`), `usage().cost()`, events, JFR |
| Key, network, or model problem? | `test(model)` staged report; `auth().status(id)` |
| Which provider request failed? | `LlmException.providerRequestId()`, `requestId()`, `errorBody()`, `code()` |
| What changed when I switched models? | `reply.warnings()` (hand-off and adaptations) |
| Production incident | JFR (`jcmd <pid> JFR.start`) with `net.ai.gate.*` events; no restart, no secrets |

Every value type has a readable, redacted `toString()`, for example
`Conversation[system=34 chars, tools=[read_file], messages=3]` and
`ChatOptions[reasoning=MEDIUM, cacheRetention=SHORT, tags={run=42}]`.

---

## 17. Observability

- **Events** (§7.12): seven sealed, content-free event classes — `RequestEvent.Started`, `FirstOutput`,
  `Retrying`, `Finished`; `CredentialEvent.Refreshed`, `RefreshFailed`; `CatalogEvent.Refreshed` —
  delivered in order per call, synchronously and isolated, with `ChatOptions.tags()` attached. Login
  progress is not an event: it goes to the `AuthInteraction` only.
- **One vocabulary.** Following pi-telemetry's rule that the domain owns its schema, attribute names are
  defined once and used by events, JFR, logs and the OpenTelemetry module: `llm.provider`, `llm.api`,
  `llm.model`, `llm.request_id`, `llm.attempts`, `llm.usage.input`, `llm.usage.cache_read`,
  `llm.usage.cache_write`, `llm.usage.output`, `llm.usage.reasoning`, `llm.cost.total`,
  `llm.outcome`, `llm.error.code`, `llm.from_cache`, plus host tags.
- **No-op by default, reference adapter, conformance.** Without listeners nothing is recorded;
  `RecordingListener` is the reference implementation; adapters (metrics, OpenTelemetry) are tested with a
  small listener contract kit.
- **JFR**: `net.ai.gate.Request` (provider, API, model, streaming, outcome, attempts, tokens, cost,
  latency, time to first output, from cache), `net.ai.gate.Retry` (attempt, delay, reason),
  `net.ai.gate.CredentialRefresh` (provider, outcome, latency), `net.ai.gate.CatalogRefresh`
  (sources, outcome). Emitted always; recorded only when a recording is active.
- **Logging**: `System.Logger` — `net.ai.gate` (lifecycle at DEBUG, insecure configuration, adapted
  settings and isolated listener failures at WARNING), `net.ai.gate.auth`, `net.ai.gate.catalog`,
  `net.ai.gate.wire` (only when wire logging is enabled). No payloads or secrets by default; the SDK
  never configures logging.
- **OpenTelemetry** (optional `llm-transport-otel`): a listener and an interceptor mapping calls to GenAI
  semantic-convention spans and metrics, propagating the current OpenTelemetry context, with the
  convention version pinned by the module and content capture opt-in.

---

## 18. Evolution, compatibility and complexity budget

### 18.1 Compatibility rules

- Semantic versioning; `0.x` carries no promise, stated in the README. japicmp runs against the previous
  minor on every release; every intended break is listed in `CHANGELOG.md`. `llm-transport-catalog` is
  date-versioned and carries model data and its feed adapter; its data schema changes only with a core release.
- API interfaces (`Llm`, `ModelCatalog`, `Auth`, `ChatStream`, …) are `@ApiStatus.NonExtendable`: methods
  may be added in minors. SPI interfaces grow only through `default` methods; a new abstract SPI method
  needs a major version.
- Sealed hierarchies derived from providers (`Content`, `ChatEvent`, `LlmEvent`) may gain variants in
  minors; consumers keep a `default` branch and every wire-derived family has an `Unknown` variant.
  `AuthPrompt`, `AuthNotice`, `Message`, `Credential` and `JsonValue` are closed: changing them is a major
  version.
- Wire-derived and evolving models are final classes with accessors and builders; records are used only
  for small, frozen shapes (ids, deltas, prompts, notices, keys).
- Defaults, ordering, error codes, warning codes, merge rules and redaction are observable behaviour:
  changes are at least minor, with a changelog entry and a renamed test.
- The JSON forms (`Conversation`, `ChatOptions`, `ProvidersConfig`, the exchange/cassette format, the
  catalog snapshot) are versioned; readers accept the previous version.
- Experimental surfaces (`providerApi(…)`, `RawApi`, early provider APIs) carry
  `@ApiStatus.Experimental` and are outside the promise until two real consumers have shaped them. No
  `v1`/`v2` packages: extend in place, deprecate with a named replacement.

### 18.2 Complexity budget

| Measure | Budget | This design |
|---|---|---|
| Concepts a caller must learn (glossary) | ≤ 10 | 8 |
| Types in the Simple example | ≤ 5 | 3 (`Llm`, `Model`, `AssistantMessage`) |
| Method names on the facade | ≤ 13 | 13 |
| Names on the runtime builder | ≤ 13 | 13 (+ `build`) |
| Configuration scopes merged per call | ≤ 3 + derived | 3 (runtime, provider, call) + catalog-derived |
| Types a protocol author must implement | ≤ 3 | 2 (`WireApi`, `StreamDecoder`) |
| Abstract methods per SPI | host-fed ≤ 3; provider/protocol ≤ 4 | met |
| Exported packages | ≤ 6 | 5 |
| Public cache types | ≤ 3 | 3 (`CacheRetention`, `CacheMode`, `ResponseCache`) |
| Listener event classes | ≤ 8 | 7 |
| Runtime dependencies of the core | 0 | 0 |
| Package depth below the root | ≤ 3 | 2 |

Every public type traces to a use case in §4 or an extension in §8. A new public type without a new
glossary concept or a new use case is a review failure.

### 18.3 Not added, on purpose

| Idea (source) | Why not |
|---|---|
| Router, fallback chains, circuit breakers, key pools, client-side rate limiter (requirements) | Host policy; hides which account is billed; key pools may breach terms. Events and `outcomeUnknown` expose what hosts need |
| Agent loop, tool execution, MCP client, memory (requirements) | Anti-goals; `append(reply, results)` removes the boilerplate without owning the loop |
| Semantic response caching | Trades correctness for hit rate; exact-match caching covers tests and re-runs |
| Vendor SDKs inside adapters (pi) | Per-provider timeout, retry and error semantics; dependency conflicts |
| Failures only as data (pi) | Silent failures; exceptions carry `partial()` instead |
| `stream`/`streamSimple` split (pi) | One method; typed provider options |
| Base-URL sniffing as the compat mechanism (pi) | Explicit presets; detection only for `OpenAiCompatible.custom(…)`, reported |
| Mid-conversation system messages with sections and tool diffs, deferred responses, WebSocket transport, stream-frame encoder, image generation and classifiers in the chat surface (pi) | Scope; each returns as its own verb or option when a consumer needs it |
| Generic operation dispatch `execute(Operation)` (A) | Hides a large vocabulary behind one method; lifetimes differ |
| Per-provider client classes (A) | Forces hosts to branch per provider |
| `Fact<T>` wrappers on every metadata field (A) | Verbose for every UI; provenance is kept per `Model` (`source()`, `updatedAt()`) |
| One client per endpoint, `withOptions` views, five merge scopes (FM) | Replaced by the multi-provider runtime and `ChatOptions` |
| Secret references in configuration documents (FM) | Configuration has no credential fields; vaults are credential stores |
| Config files, YAML and interpolation in core (requirements) | Optional modules map onto the same builders |
| Reactive libraries, Kotlin in the core | JDK types only; adapters are optional modules |
| Stream reconnection after output | Unsafe: may duplicate billed generation |

### 18.4 Anticipated review questions

| Question | Answer |
|---|---|
| Isn't dispatching by the model's provider the "router" FM rejected? | No: the provider is named explicitly by the model the caller chose; there is no selection, fallback or balancing. Billing stays as explicit as with one client per endpoint, without the map of clients |
| Why is `Llm` an interface rather than a final class? | Hosts decorate it (tenant routing, auditing) and tests may substitute it; `@NonExtendable` keeps method additions non-breaking. `FakeProvider` is the supported substitute for tests |
| Why does the SDK ship model data at all? | Limits, prices and reasoning levels drive defaults, cost and UIs, and list endpoints rarely provide them. Data is versioned and released separately, and runtime refresh prefers fresher sources (§11) |
| Doesn't background catalog refresh break "I/O only in verbs"? | It is configured work, on by default for freshness, never on the caller's thread, never blocking reads, and off with `manualRefresh()` or `offline()`; `build()` and reads still perform no I/O |
| Why is prompt caching on by default? | Both named consumers are multi-turn; cached reads cost a fraction of input. Single-shot hosts turn it off once (`defaults(o -> o.cacheRetention(NONE))`); `preview()` shows the markers (D1) |
| Why pass another model's reasoning on by default? | Continuity of intent across a model switch (D2); signatures and encrypted reasoning are not transferable and are dropped; `ReasoningHandoff.DROP` turns it off; stored history is never modified |
| Why adapt rather than fail by default? | Model switching is the norm for agents and workflow builders; every adaptation is a warning, and `strict()` restores failure (D3) |
| Why a hand-written JSON library? | Zero runtime dependencies avoid version conflicts in IDE plugins and applications; stable key order is a functional requirement for prompt caching and cache keys; the scope is small. POJOs use the optional Jackson module |
| Is a response cache overengineering for a transport? | It is opt-in, one SPI with two abstract methods, and serves three needs with one mechanism: deterministic test cassettes, re-runs without re-billing in workflow builders, and offline demos |
| Why is OAuth in the core rather than a separate artifact? | The implementation needs only the JDK; the core already owns credential resolution and refresh, so splitting would duplicate the pipeline. The security surface is isolated in `internal.oauth` and tested against `FakeAuthorizationServer` |
| Why a blocking `AuthInteraction.prompt()`? | On virtual threads blocking is cheap, and one protocol then serves terminals, desktop dialogs and web callbacks (`RedirectInteraction`) without callback chains |
| Why sync-first in 2026? | Virtual threads make blocking code the scalable default; `completeAsync()` covers futures; reactive and coroutine adapters can follow without changing the core contract |
| `complete(model, conversation, Invoice.class)` returns only the record — where is the usage? | The shortcut serves extraction; `complete(…)` plus `reply.as(Invoice.class)` keeps all metadata. Both use one binder |
| Why records for deltas but classes for lifecycle events? | A record freezes its constructor and deconstruction pattern; deltas are stable, lifecycle events grow |
| Isn't a hundred-plus public types a lot? | Callers learn eight nouns and the Simple path uses three types; every other type is reached by completion and backs a use case in §4 or an extension in §8 (§18.2) |

---

## 19. Delivery roadmap — minimal skeleton first

| Slice | Deliverable | Exit evidence |
|---|---|---|
| **0 — API proof** | Core types and facade (`Llm`, `Provider`, `Model`, `Conversation`, `AssistantMessage`, `ChatOptions`, `CancelToken`), `FakeProvider`, §4 examples in Java and Kotlin, ArchUnit rules, japicmp baseline; the API sketch compiled as in §20.3 | §4 examples compile and run offline; misuse and ownership tests pass |
| **1 — Engine and two dissimilar APIs** | JDK transport, SSE framing, execution engine (deadlines, retries, cancellation, events, JFR), resolver and hand-off transformer, Chat Completions with `OpenAiCompatible` presets and compat flags **and** Anthropic Messages, tools, record-typed output, streaming with partial tool arguments, prompt caching mappings, `preview`/`toCurl`, overflow detector, cost, `test()`, response cache (memory and directory), bundled catalogs and the `updateModelCatalog` pipeline | Both APIs pass `WireApiContract` with golden and hand-off fixtures (Anthropic ↔ Chat Completions), split chunks, late errors and premature EOF; cassettes replay offline; one live run per API |
| **2 — Auth vertical slice** | Resolution chain with sources, `CredentialStore` (memory, file), `AuthInteraction.console()` and `redirect(…)`, `OAuthAuth.standard` (loopback, external redirect, device, client credentials), refresh and rotation inside `update`, OpenRouter OAuth preset, `withCredentials`, `FakeAuthorizationServer` | Desktop, terminal and web examples log in, cancel, expire and refresh against the fake server; concurrent refresh is single-flight across two processes sharing a file store |
| **3 — Breadth and freshness** | OpenAI Responses, Google generateContent, reasoning continuity and level mapping per API, multimodal input, rate limits, remaining presets, `llm-transport-catalog` weekly release, catalog feed, live listings, background refresh and snapshot file | Four APIs green; conversations round-trip across every pair; catalog merge rules pinned by tests with `TestClock` |
| **4 — Additional operations** | Provider APIs for files, batches and Gemini caches; embeddings and images as new verbs over model kinds; token counting — ordered by consumer demand | Each operation documents side effects, billing, cancellation, ownership and retention |
| **5 — Integrations** | Kotlin coroutines, Jackson, OpenTelemetry, keychain credential store, config and Spring Boot bindings, Ollama native, Bedrock, Vertex | Optional modules preserve core contracts and dependency isolation |

Slices 0–2 form the first usable release for both named consumers. Slice 1 deliberately pairs the most
widely compatible protocol with the most different one, so the portable model and the hand-off rules are
tested early.

---

## 20. Verification

### 20.1 Tests are the specification

The README examples compile and run in `:examples:test`. Named tests pin every documented rule, for
example:

- `simple example sends one request with default timeouts, SHORT prompt caching and no sampling parameters`
- `unknown provider in llm.model fails naming the known provider ids`
- `unset temperature is absent from the wire body`
- `call options override provider defaults, which override runtime defaults, field by field`
- `cacheRetention NONE at runtime scope removes every cache marker`
- `anthropic max_tokens defaults to the catalog output limit clamped to the context window`
- `XHIGH on a model offering LOW MEDIUM HIGH is sent as HIGH with reasoning_clamped; strict fails`
- `foreign reasoning is passed as delimited text under KEEP and omitted under DROP; signatures are never transferred`
- `same-origin reasoning is replayed natively even under DROP`
- `stored conversation is unchanged after a hand-off; switching back replays native signatures`
- `openai tool-call ids are normalized for anthropic and tool results are remapped`
- `images for a text-only model become placeholders with image_omitted; strict fails`
- `foreign provider options are inert and reported as option_not_applicable`
- `429 is retried up to maxAttempts honouring Retry-After, then RateLimitedException`
- `timeout after send and 504 are not retried and report outcomeUnknown`
- `complete is not limited by the stream-idle timeout`
- `stream delivers whole SSE events when chunks split lines and UTF-8 sequences`
- `stream is never retried after the first event`
- `stream result equals complete result for the same recorded exchange`
- `partial tool arguments are parsed from incomplete JSON and never validated`
- `premature EOF and late provider errors fail the stream with partial()`
- `cancelling a parent CancelToken cancels every child call and emits Finished(CANCELLED)`
- `completeAsync future cancel aborts the call`
- `complete with Invoice.class throws output_truncated on a length stop`
- `record schema derivation is strict-compatible and binds through canonical constructors only`
- `unknown content part, event, stop reason and error code are preserved, not rejected`
- `missing usage is absent, not zero; unknown price makes cost absent`
- `cost applies the price tier selected by total input`
- `context overflow is detected from provider messages and from silent truncation`
- `catalog merge: custom beats fresh, newest non-absent value wins, absent never overwrites`
- `available shows only live-listed models after a successful listing`
- `background catalog refresh never blocks a read and keeps data when a source fails`
- `offline catalog performs no network I/O`
- `status and available never fetch tokens or touch the network, even with dynamic token suppliers`
- `feed requests carry no credentials, prompts or usage`
- `unlisted model is callable and reports unlisted_model`
- `response cache key includes credential scope; two tenants never share an entry`
- `OFFLINE cache miss fails without network and names the request`
- `listener exception is logged and does not fail the call`
- `injected transport, credential store and response cache are not closed by the runtime`
- `API key never appears in toString, logs, events, previews, curl output or exceptions`
- `login prompts and URLs reach only the interaction, never listeners`
- `loopback login validates state, uses PKCE S256 and stops the listener in every outcome`
- `concurrent expired-token calls refresh exactly once inside CredentialStore.update; the rotated token is persisted`
- `a failed refresh of a stored credential never falls back to an environment key`
- `Environment.none ignores operator environment variables`
- `withCredentials views isolate users and share transport and catalog`
- `connection test stops at AUTHENTICATION for a wrong key and never performs inference`
- `preview performs no I/O and matches the bytes complete transmits`
- `ProvidersConfig round-trips and has no credential fields`
- `kotlin caller uses builders and consumer-builders without platform types`
- `japicmp reports no unintended binary change against the previous minor`

Every SPI implementation, shipped or third-party, extends its contract kit. Footgun probes (§9.3) each
have a test. Memory is measured against stream length with a fixed configured buffer; cancellation
latency and dependency footprint are tracked in CI. No "zero-copy" or throughput claims are made without
such evidence.

### 20.2 Platform facts verified while preparing the design

Checked on a local Temurin **26.0.2.1** JDK while preparing the first merge (FM); they do not depend on the
API shape and remain valid:

- `java.net.http.HttpClient.Version.HTTP_3` and `HttpOption.H3_DISCOVERY` exist (JEP 517).
- `ScopedValue`, stream gatherers, unnamed patterns and compact source files with `import module`
  compile and run without preview flags; `StructuredTaskScope`, `LazyConstant` and primitive patterns
  are rejected as preview features.
- A sealed interface compiled in a named module with a permitted subclass in another package loads and
  pattern-matches both on the module path and on the classpath.
- Reflective mutation of a final field prints the JEP 500 warning, confirming that binding through
  canonical constructors is the forward-compatible choice.
- Markdown documentation comments (`///`) render with the JDK 26 `javadoc` tool.

### 20.3 Compile evidence and what is not yet verified

FM's API sketch was transcribed into 177 stub sources in eight Gradle subprojects (Gradle 9.7.1, JDK 26,
`--release 26`, the §6.5 conventions, `-Xlint:all -Werror`) and compiled together with its examples; that
check fixed `transitive` requirements, list-valued keys, the JUnit BOM on the testing artifact and the
exception design, and confirmed that `missing-explicit-ctor` enforces private constructors. Those
build-level results carry over (§6.4, §6.5, §7.15).

This final sketch changes the facade, the call shapes, auth, caching and the SPI, and **has not been
compiled yet**. Before slice 0 exits, the §7 and §8 sketches and every §4 example must be transcribed and
compiled the same way. Also not verified: runtime behaviour, the Kotlin example, provider wire formats and
live interoperability, catalog-source formats and licences, performance and memory bounds — these are the
exit criteria of slices 0–3 (§19). The design decisions taken from pi rest on reading its sources and
README (`types.ts`, `models.ts`, `auth/types.ts`, provider factories, `api/transform-messages.ts`,
`api/simple-options.ts`, `utils/event-stream.ts`, `utils/retry.ts`, `utils/overflow.ts`, pi-telemetry's
README), not on running it.

---

## 21. Open decisions and risks

| # | Topic | Recommendation | Why it is open |
|---|---|---|---|
| 1 | Java 26 baseline | **Decided (D9).** Should an LTS runtime become necessary, switching `options.release` to 25 is a one-line change (only opt-in HTTP/3 is lost) | Remaining risk: Java 26 is a non-LTS release; its class files do not load on Java 25 |
| 2 | Kotlin toolchain | Pin a Kotlin version that reads Java 26 class files before enabling the Kotlin examples | Kotlin support for a new JDK usually trails the JDK release |
| 3 | Internal JSON | Build the bounded parser, stable-order writer, repair parser and record binder with a JSON conformance suite and fuzzing | Hand-written parser correctness and performance |
| 4 | Catalog data source | Generate from the public metadata database pi also uses, plus curated overrides; the runtime feed is on by default (D11); confirm the licence and pin the format with `CatalogFeedContract` | Licence; format stability of a third-party source; correctness of prices |
| 5 | Maven coordinates | Root package decided (`net.ai.gate`, D10); choose the group id (for example `net.ai.gate`) and artifact names before the first publication | Publication naming |
| 6 | Model aliases (`fast`, `smart`) | Defer to 1.x; hosts keep `ModelRef` values | Adds a resolution rule; wait for a second consumer |
| 7 | Streaming structured output (partial objects) | Defer; partial tool arguments cover the agent case; bind output at the end | Needs a partial-object API |
| 8 | `Flow.Publisher` and coroutine adapters | Slice 5, over the same contracts | Cancellation propagation must be proven per adapter |
| 9 | Record binding in modular applications | Document `exports`/`opens` for bound record packages; add a `MethodHandles.Lookup` overload only if users struggle | JPMS accessibility of user types |
| 10 | Provider facts | Re-verify base URLs, headers, limits and compat flags at every module release | Volatile |
| 11 | Consumer-subscription OAuth | No presets; the `OAuthAuth` SPI only | Terms of service and vendor enforcement |
| 12 | HTTP/3 | Opt-in; HTTP/2 remains the default | New in JDK 26; proxies and middleboxes vary |
| 13 | Reasoning text on models that echo it | Monitor with live tests whether delimited foreign reasoning is ever repeated as an answer; the delimiter is part of the contract and can only change in a minor with a changelog entry | Model behaviour varies |
| 14 | Root package size (~60 types) | Accept, as a hub; split only along a real seam | Browsing a large package |

---

## Appendix A — Traceability

| Requirement | Where |
|---|---|
| `goals.md`: wide range of LLM calls; hide OpenAI Responses, Anthropic Messages, Gemini, Grok, DeepSeek, Qwen | §7.4–§7.9, §8.8, §14 |
| `goals.md`: provider and gateway connection and authentication, OAuth included | §4.12, §7.11, §13 |
| `goals.md`: UI hooks — browser opening, events, progress, information | §4.12–§4.14, §7.11–§7.12, §13.3, §15 |
| `goals.md`: coding agent and node-based workflow builder | §3.2, §4, §15 |
| `goals.md`: hierarchical and modular; API and providers separated; start minimal and grow | §5, §6, §19 |
| `goals.md`: factories, abstractions, facades, interfaces; plug in a provider; upgrade an API version | §5.4, §5.5, §7, §8, §14.7 |
| `goals.md`: project structure, configuration endpoints and examples, factories, stubs | §6, §7, §8.8, §9 |
| `goals.md`: lightweight, no god classes, good or automatic defaults | §2, §5.3, §9.2, §11, §14.2, §18.2 |
| Readable, concise API with speaking names and minimal overhead | §3, §4, §7 |
| Switch provider, protocol, API version or model without rewriting code | §4.3, §14 |
| Tools, streaming, output formats | §4.4–§4.6, §7.5–§7.9, §10.4 |
| Cache management | §4.9, §7.14, §12 |
| Current model metadata | §11 |
| Easy testing and debugging | §4.17–§4.18, §16 |
| Flexible settings | §7.1, §7.8, §9 |
| Java 26 + Gradle; modern Java features | §6.4–§6.6, §20.2–§20.3 |
| Balance between richness and overengineering | §18.2–§18.3 |
| Best of A, B, FM and pi, with the owner's decisions | §1 |

## Appendix B — Name mapping

| Proposal A | Proposal B | FM (first merge) | pi | Final |
|---|---|---|---|---|
| `LlmClient` (final class) | `LlmClient` (interface) | `LlmClient` per endpoint | `Models` collection | `Llm` (multi-provider runtime) |
| `Provider`, `ProviderSession` | `LlmProvider`, `Endpoint` | `LlmProvider`, `Endpoint` | `Provider` | `Provider` (configured instance, presets via `toBuilder()`) |
| `InferenceAdapter` | `Dialect`, `ChatCodec`, `StreamDecoder` | same as B | API implementation (`ProviderStreams`) | `WireApi`, `StreamDecoder` |
| model string | `String model` | `ModelId`, `ModelInfo` | `Model` | `Model`, `ModelRef` |
| `models().list(ModelQuery)` | `models().list()` | `ModelsApi` with TTL | `getModels()`, `refresh()` | `ModelCatalog` (freshest-wins merge, background refresh) |
| `generate(…)` | `chat().send(…)` | `chat().send(…)` | `complete(…)`, `completeSimple(…)` | `complete(…)` |
| `GenerationRequest` | `ChatRequest` | `ChatRequest` | `Context` | `Conversation` + `ChatOptions` |
| `GenerationResult` | `ChatResponse` | `ChatResponse` | `AssistantMessage` | `AssistantMessage` |
| `GenerationSettings`, `CallOptions` | settings on `ChatRequest` | `GenerationSettings`, `CallOptions`, `withOptions` | `StreamOptions`, `SimpleStreamOptions` | `ChatOptions` |
| `GenerationCall` | — | `ChatCall` | `AbortSignal` | `CancelToken`, `completeAsync` |
| `GenerationStream` | `ChatStream` | `ChatStream` | `AssistantMessageEventStream` | `ChatStream` |
| `GenerationEvent` | `ChatEvent` | `ChatEvent` | `AssistantMessageEvent` | `ChatEvent` |
| `OperationObserver` | `LlmListener` | `LlmListener`, 14 events | `TelemetryContext` | `LlmListener`, 7 events |
| typed provider options | `OptionKey<T>`, `QuirkKey<T>` | same as B | per-API options, `compat` | `ProviderOptions`, `ApiCompat` |
| — | `Reasoning` | `Reasoning`, `Effort` | `ThinkingLevel`, `thinkingLevelMap` | `ReasoningLevel`, `ReasoningHandoff` |
| — | cache hints | `PromptCache`, `CacheBreakpoint`, `ResponseCache`, `CacheMode` | `cacheRetention`, `sessionId` | `CacheRetention`, `sessionId`, `cacheBreakpoint()`, `ResponseCache`, `CacheMode` |
| `inspect()` | `prepare()` | `preview()` | `onPayload` | `preview()` + payload hook |
| `connection().check(…)` | `testConnection()` | `connection().test()` | `checkAuth()` | `test(model)`, `auth().status(id)` |
| `ParameterDescriptor` | `ConfigField` | `FieldDescriptor` | auth prompts | `FieldDescriptor`, `Model.parameters()`, `AuthPrompt` |
| `CredentialSource`, `Secret` | `Credentials`, `ApiKey` | sealed `Credentials`, `Secret` refs | `Credential`, `ApiKeyAuth`, `OAuthAuth` | `Credential`, `ApiKeyAuth`, `OAuthAuth`, `Secret` |
| `TokenStore` | `TokenStore` | `TokenStore` | `CredentialStore.modify` | `CredentialStore.update` |
| `AuthorizationSession` | `LoginSession`, `AuthPrompt` | `LoginSession`, `LoginPrompt`, `BrowserLauncher` | `AuthInteraction`, `AuthPrompt`, `AuthEvent` | `AuthInteraction`, `AuthPrompt`, `AuthNotice` |
| `OAuthCredentials` | — | host-owned `OAuthCredentials` | — | `withCredentials(store.scoped(user))` |
| — | endpoint JSON | endpoint JSON with secret references | `models.json` | `ProvidersConfig` (no credential fields) |
| scripted provider | `FakeLlm` | `FakeLlm` | `fauxProvider` | `FakeProvider` |
| `service(ServiceKey<S>)` | `raw()` | `providerApi(…)`, `raw()` | — | `providerApi(id, …)`, `RawApi` |
| `FailureDetails` | fields on `LlmException` | `LlmException`, open `ErrorCode` | `stopReason: "error"` | `LlmException` with `partial()`, open `ErrorCode` |
| — | `ResponseFormat` | `OutputFormat` with records | — | `OutputFormat` with records |
