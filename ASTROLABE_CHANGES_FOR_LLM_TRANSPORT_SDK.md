# ASTROLABE: changes required to use `llm-transport-sdk` (AI Gate) as its LLM transport layer

**Date:** 2026-09-28
**Inputs:** `transp_integr_request.md`, `TRASPORT_INTEGRATION_ANALYZE.md` (**A**), `TRASPORT_INTEGRATION_ANALYZE_01.md` (**B**), and a fresh source reading of both repositories (ASTROLABE `dba6344`, llm-transport-sdk `d8dc26a`).
**Companion document:** `LLM_TRANSPORT_SDK_CHANGES_FOR_ASTROLABE.md` (the SDK side). Shared IDs: `S-nn` = SDK change, `A-nn` = ASTROLABE core/provider-api change, `G-nn` = work inside the new adapter module.

---

## 0. How the two analyses were reconciled

Agreement: the SDK replaces exactly the layer ASTROLABE deferred to P7; the seam is `ProviderAdapter`; a new module does the translation; `core` and `provider-api` stay free of the SDK; `FakeAdapter` stays for tests. Differences and the resolution:

| Topic | A | B | Resolution |
|---|---|---|---|
| Where provenance/replay data lives | `ReasoningRef.providerTag` + `opaque`; **never** `Item.native` (any non-null `native` makes the estimate unknown, `Estimate.kt:92`) | Versioned envelope in `Item.native`, therefore estimator injection is mandatory | **A's rule** for the first release (no unknown-history refusal, no residency zero-sizing); estimator injection (A-01) is still done, because routing and exact admission need it. B's envelope becomes an SDK audit (S-13), not a storage rule. |
| Estimator hard-coded in `Astrolabe.campaign` | A1: make it injectable | Same, plus `Controller.route` reuses estimator and `maxOutputTokens` for a routed profile | A-01 covers both; the routing reuse is a verified defect (`Controller.kt:1262` can throw in `CellModel.init` when the routed profile has a smaller output limit). |
| Breakpoints on automatic-cache providers | A3: emit by capability or ignore by policy | Same | A-03: `Layout` emits breakpoints only when the profile's `caching.breakpoints` is true; `Validations.standard` stays strict. |
| New `ProviderError` kinds | A2: `ContextOverflow`, `Authentication` | "auth and timeout categories missing" | A-02 adds `ContextOverflow`, `Authentication`, `Timeout`; `Cell` rebuilds on overflow. |
| Tool calls on `OutputLimit` | Adapter strips them | `Response` should forbid them like `Truncated` | A-05: adapter strips **and** `Response` forbids (contract tightening, fixtures checked). |
| Eviction versus reasoning replay | A5: keep the current loop's `ReasoningRef` with its calls | Atomic replay groups, test reduction | A-06: residency rule plus a test; no new group abstraction until a fixture proves it is needed. |
| Codex (`openai-codex`) preset | Not covered | Output cap unsupported; strict rejects | Covered in §5.5 as a profile policy (`gate.outputCap = "unsupported"`); not a first-release profile. |
| Lifecycle | Host closes `Llm` after `Astrolabe.close()` | `Astrolabe.close` does not own the adapter; define order | A-07 documents order and adds an optional owned-adapter mode. |
| Streaming to a UI | Not covered | "provider API exposes completed responses only" | A-08: an optional progress listener in `provider-api` and a `ModelProgress` event, so a UI can show partial output without changing the `Invocation` contract. |
| Module name | `:provider-gate` | `:provider-ai-gate` | `:provider-ai-gate`, package `io.astrolabe.provider.aigate`. |
| Effort | ~1.5–2.2 kLOC incl. tests | No estimate | A's estimate kept, adjusted in §7. |

Both analyses' claims were checked against the source; none was found wrong. The two documents are complementary: A is more concrete on APIs, B is more complete on risks (routing, residency, lifecycle, catalog freeze, response cache, Codex).

---

## 1. Verified current state (what the integration has to work with)

| Fact | Where | Effect |
|---|---|---|
| The only `ProviderAdapter` is the test fixture `FakeAdapter`; production hosts pass an adapter into `Astrolabe(config, adapter, authority, …)`. | `core/src/testFixtures/.../FakeAdapter.kt`, `core/.../Astrolabe.kt:52-62` | The stub is replaced by *injection*, not by removing code. |
| `Astrolabe.campaign` builds `CellModel(adapter, profile, HeuristicEstimator())`. | `Astrolabe.kt:101` | No provider-aware estimator can reach admission. |
| `Controller.route` builds `CellModel(model.adapter, routed.profile, model.estimator, routed.effort, model.maxOutputTokens)`. | `campaign/Controller.kt:1262` | Routed profile keeps the old estimator; `CellModel.init` requires `maxOutputTokens ≤ profile.capabilities.outputLimitTokens` (`cell/CellContext.kt:54-58`) and throws for a smaller routed profile. |
| `Layout.render` marks S, R, K and T with `breakpoint = true` unconditionally; `Validations.standard` rejects any breakpoint when `caching.breakpoints == false`. | `cell/Layout.kt:196-227`, `provider-api/.../ProviderAdapter.kt:71-77` | A truthful profile for OpenAI/Gemini/gateways (automatic caching) fails validation. |
| Any item with `native != null` estimates as unknown history; `ContextAdmission.check` refuses unknown history; residency sizes items with the same function. | `provider-api/.../Estimate.kt:92`, `context/ContextAdmission.kt:46-50`, `cell/Cell.kt:647-657` | Replay data must not go into `native` unless the estimator is replaced. |
| `Residency.batch` trims old assistant messages by rebuilding `Message(role, parts)` without `native`; calls are separate items and stay. | `cell/Residency.kt:286-295` | Anything stored on a message's `native` disappears on trim. |
| `Cell` run: build `Request` → `estimator.estimate` → `adapter.validate` → `ContextAdmission.check` → budget/accounting reserve → `adapter.start` → `await` → `terminal()` under `NonCancellable` → reconcile and record → journal → append → dispatch. `ProviderError` fails the cell; a `ContextOverflow` **validation** problem triggers a rebuild. | `cell/Cell.kt:308-415` | The adapter must settle `terminal()` on every path, and provider-side overflow must become a validation-like rebuild (A-02). |
| `Response` forbids `ToolCall` for `Truncated` and `Cancelled` only. | `provider-api/.../Request.kt:116-119` | `OutputLimit` with a call would be executed by core. |
| `ProviderAdapters.mapError` maps any non-`ProviderError` to `Transport`. | `provider-api/.../JavaProviderAdapter.kt:82-86` | The adapter must map `LlmException` itself. |
| `Astrolabe.close()` cancels the scope and closes events; it does not close the adapter. | `Astrolabe.kt:114-117` | Host defines shutdown order. |
| Host-facing events for a model call: `AgentEvent.Cell.ModelRequested` and `ModelResponded` only. | `event/AgentEvent.kt:90-94` | No progress or delta events exist for a UI. |
| `Config`, `Profile`, `Capabilities`, `PriceTable` are `@Serializable`; `Profile.config` is an opaque `JsonObject`. | `Config.kt`, `provider-api/.../Capabilities.kt` | A UI can persist and edit profiles as JSON; adapter settings go under `Profile.config`. |
| Build: JDK 26 toolchain, Kotlin 2.4.20, `explicitApi()`, ABI validation, `maven-publish`, `FAIL_ON_PROJECT_REPOS`. | `build-logic/.../astrolabe.kotlin-library.gradle.kts`, `settings.gradle.kts` | Matches the SDK's JDK 26 / Kotlin 2.4.20; composite build works. |

---

## 2. Target architecture

```
host (CLI / service / UI)
 ├─ builds the SDK runtime:  Llm  ← ProvidersConfig (gateways, no secrets) + CredentialStore (secrets) + HttpOptions
 ├─ builds ASTROLABE Config: profiles (Profile: provider, model, capabilities, priceTable, config.gate{...}), roles, flags
 ├─ builds the adapter:      AiGateAdapter(llm, bindings)              ← :provider-ai-gate
 └─ Astrolabe(config, adapter, authority, estimators = adapter::estimator)   ← :core

:provider-api   unchanged in dependencies; small contract additions (A-02, A-05, A-08)
:core           small changes (A-01, A-03, A-04, A-06, A-07, A-08)
:provider-ai-gate  NEW — the only module that depends on net.ai.gate
```

Ownership stays as both analyses require: the SDK owns HTTP, credentials, codecs, retries, deadlines, cancellation plumbing, catalog and diagnostics. ASTROLABE owns the loop, tool execution and masks, layout and eviction, admission, budgets and dated prices, evidence. The adapter owns translation and the D-51 state machine.

Configuration layering (UI-relevant, see §9):

```
providers.json    SDK ProvidersConfig ("ai-gate.providers/1"): provider ids, endpoints, presets, compat flags, custom models. No secrets.
credentials.json  SDK CredentialStore.file (or a keychain-backed CredentialStore). Secrets only.
astrolabe.json    ASTROLABE Config: profiles{ id, provider, model, capabilities, priceTable, config: { "gate": {...} } }, profileRoles, tierTable, flags, …
```

`Profile.provider` is the SDK provider id, `Profile.model` the SDK model id. The `gate` block is the adapter's versioned settings (§5.6).

---

## 3. Changes in `provider-api` and `core` (A-01 … A-09)

Small, contract-level, all backward compatible for `FakeAdapter` and existing tests. Each keeps `provider-api` free of networking and of core.

### A-01 Injectable, profile-aware estimator and routing rebind

**Why.** `Astrolabe.kt:101` hard-codes `HeuristicEstimator()`; `Controller.kt:1262` reuses the estimator and `maxOutputTokens` of the previous profile.

**Change.**

```kotlin
// core/src/main/kotlin/io/astrolabe/Astrolabe.kt
public class Astrolabe @JvmOverloads public constructor(
    public val config: Config,
    private val adapter: ProviderAdapter,
    private val authority: Authority,
    private val clock: Clock = Clock.systemUTC(),
    private val idGen: IdGen = RandomIdGen(),
    layers: OptionalLayers = OptionalLayers(),
    private val deployer: Deployer? = null,
    /** The estimator admission is decided with, per profile (D-06, I-17); the default is the planning heuristic. */
    private val estimators: EstimatorFactory = EstimatorFactory { HeuristicEstimator() },
) : AutoCloseable

// core/src/main/kotlin/io/astrolabe/cell/CellContext.kt
public fun interface EstimatorFactory { public fun estimatorFor(profile: Profile): TokenEstimator }

public class CellModel @JvmOverloads constructor(
    public val adapter: ProviderAdapter,
    public val profile: Profile,
    public val estimator: TokenEstimator,
    public val effort: Effort = Effort.Medium,
    public val maxOutputTokens: Int = profile.capabilities.outputLimitTokens,
    /** `true` when the caller narrowed [maxOutputTokens] below the profile limit; routing then keeps the narrowing, capped by the routed profile. */
    public val narrowedOutput: Boolean = maxOutputTokens != profile.capabilities.outputLimitTokens,
) {
    /** The same binding for another profile: its own estimator, its own headroom unless narrowed. */
    public fun rebind(routed: Profile, effort: Effort, estimators: EstimatorFactory): CellModel {
        val limit = routed.capabilities.outputLimitTokens
        val headroom = if (narrowedOutput) minOf(maxOutputTokens, limit) else limit
        return CellModel(adapter, routed, estimators.estimatorFor(routed), effort, headroom, narrowedOutput)
    }
}
```

`Astrolabe.campaign` builds `CellModel(adapter, profile, estimators.estimatorFor(profile))`; `Controller` receives `estimators` and `route` calls `model.rebind(routed.profile, routed.effort, estimators)`. `AstrolabeJava` gets the same optional parameter (a Java-friendly functional interface). `ProviderAdapter` does **not** gain an estimator method: the adapter module exposes `AiGateAdapter.estimators()` and the host passes it, which keeps `provider-api` unchanged for this item.

**Tests.** A routing test with a helper profile whose `outputLimitTokens` is smaller than the main profile's: the cell compiles and reserves against the helper's limit. `Controller` tests pass with the default factory.

### A-02 Provider errors the loop can act on

```kotlin
// provider-api/src/main/kotlin/io/astrolabe/provider/ProviderAdapter.kt
public sealed class ProviderError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    // existing: Transport, RateLimit, OutputLimit, Refusal, ExpiredContinuation, InvalidRequest, UnsupportedSchema, MissingUsage
    /** The provider rejected the request for size after admission: an estimation miss (I-17), never a malformed history. */
    public class ContextOverflow(message: String, public val reportedInputTokens: Long? = null) : ProviderError(message)
    /** Credentials missing, expired or refused; blocks the campaign rather than being retried as transport. */
    public class Authentication(message: String, cause: Throwable? = null) : ProviderError(message, cause)
    /** Deadline or idle timeout; the outcome may be unknown. */
    public class Timeout(message: String, public val outcomeUnknown: Boolean) : ProviderError(message)
}
```

`Cell.kt:386-389` today: any `ProviderError` → `failed(...)`. Change:

```kotlin
failure?.let { error ->
    when (error) {
        is ProviderError.ContextOverflow -> {
            contextAdmission.rejected(estimate)
            if (rebuilds >= 1) return partial(PartialReason.Pressure, "replan: the provider refused the request for size after a rebuild")
            rebuild("provider context overflow: ${error.message}"); return null
        }
        is ProviderError.Authentication -> return blocked(BlockedRequest("provider authentication: ${error.message}",
            evidence = listOf("invocation ${invocationId.value}"), question = "re-authenticate profile ${ctx.model.profile.id}", turn = turn))   // existing Blocked exit (Cell.kt:900)
        is ProviderError -> return failed("provider ${error::class.simpleName}: ${error.message}")
        else -> throw error
    }
}
```

`ProviderAdapters.mapError` is unchanged; a Java adapter must throw the new kinds itself.

### A-03 Breakpoints emitted by capability

```kotlin
// cell/Layout.kt
@JvmStatic
public fun render(role: Role, mask: ToolMask, mode: ExecutionMode, prime: String, k: CompiledK, transcript: Transcript,
                  explicitBreakpoints: Boolean = true): List<Segment>
// segment(...) and the T segment use `breakpoint = explicitBreakpoints`
```

`Cell` passes `ctx.model.adapter.capabilities(ctx.model.profile).caching.breakpoints`. Byte-stability is unaffected (the flag changes no text). `Validations.standard` keeps rejecting breakpoints an adapter cannot honour, so a profile can never over-declare to pass validation. The Anthropic profile declares `breakpoints = true, maxBreakpoints = 4`; S/R/K/T produce at most four markers, which is exactly the Anthropic maximum.

### A-04 Provenance and `native` conventions (documentation + one helper)

Document on `Item`/`ReasoningRef`:

- `ReasoningRef.providerTag` is `"<provider>/<model>@<api>"` (for example `anthropic/claude-sonnet-4-6@anthropic-messages`); `opaque` holds `{ "text", "signature", "redacted", "providerData" }` exactly as the SDK's `Content.Reasoning` fields.
- Adapters **do not** put replay data or the raw reply into `Item.native`. The full native reply is already persisted by `Cell.journalOutput` and may additionally be returned as `UsageItem.native` (never replayed, estimated as zero).
- Add `ReasoningRef.origin(): Origin?` helper (`data class Origin(provider, model, api)`) in `provider-api` for adapters and validators.

### A-05 `Response` never exposes calls on `OutputLimit`

```kotlin
// provider-api/.../Request.kt
init {
    if (stop == StopReason.Truncated || stop == StopReason.Cancelled || stop == StopReason.OutputLimit) {
        require(items.none { it is ToolCall }) { "a $stop response cannot expose tool calls" }
    }
}
```

`Cell.kt:1090` already nudges "no call was exposed or executed" for `OutputLimit`; this makes the contract enforce it. Check `ScriptedModel`/`FakeAdapter` fixtures that build `OutputLimit` replies with calls and drop the calls there (AX-03 expects none).

### A-06 Residency keeps the current tool loop's reasoning with its calls

Anthropic extended thinking with tool use and Gemini thought signatures must be replayed together with their tool calls on the next turn. Rule in `Residency`: a `ReasoningRef` that precedes `ToolCall`s of the same assistant turn is pinned while any of those calls is live; older turns' reasoning may be stubbed or dropped like a trimmed message. Add a fixture: thinking + two calls + results, one eviction pass, then assert the request still pairs and keeps the reasoning item.

### A-07 Lifecycle and ownership

Document: host order is *stop campaigns → `Astrolabe.close()` (cells settle terminal accounting under `NonCancellable`) → `adapter.close()` → `llm.close()`*. Optionally let `Astrolabe` close an adapter it was told it owns:

```kotlin
public constructor(..., private val ownsAdapter: Boolean = false)   // close() then calls (adapter as? AutoCloseable)?.close() after the scope is cancelled and joined
```

`AiGateAdapter` implements `AutoCloseable` (joins its worker threads); it borrows the `Llm` unless constructed with `ownsLlm = true`.

### A-08 Progress seam for a UI (optional, no change to `Invocation`)

```kotlin
// provider-api/.../ProviderAdapter.kt
/** Content-free progress of one invocation; delivered on the adapter's thread, must not block. */
public sealed interface InvocationProgress {
    public val id: InvocationId
    public data class Started(override val id: InvocationId, val providerRequestId: String?) : InvocationProgress
    public data class Output(override val id: InvocationId, val textChars: Long, val outputTokens: Long?) : InvocationProgress
    public data class Retrying(override val id: InvocationId, val attempt: Int, val reason: String) : InvocationProgress
}
public fun interface InvocationListener { public fun onProgress(progress: InvocationProgress) }
public interface ObservableAdapter : ProviderAdapter { public fun addListener(listener: InvocationListener): AutoCloseable }
```

`core` registers one listener when the adapter is `ObservableAdapter` and emits `AgentEvent.Cell.ModelProgress(ids, invocationId, textChars, outputTokens, attempt)` at a bounded rate, so a UI subscribes to the existing `Events` bus (`EventSink` for Java) and needs nothing else. Text deltas are deliberately not forwarded through core (the journal stays the single source of model text); a UI that wants live text subscribes to the adapter's own text-delta listener (G-09), which is outside core.

### A-09 Java host parity

`AstrolabeJava` gains the `estimators` parameter (A-01) and `ownsAdapter` (A-07). `JavaProviderAdapter` is unchanged; the adapter module is Kotlin and implements `ProviderAdapter` directly (one bridge fewer). A Java host still receives it as a `ProviderAdapter` through `Astrolabe`'s constructor; if a Java-only adapter is ever needed, `ProviderAdapters.fromJava` remains.

---

## 4. The adapter module `:provider-ai-gate` (G-01 … G-10)

Package `io.astrolabe.provider.aigate`. About 1,000–1,300 lines of Kotlin plus tests. Depends on `:provider-api` (api), `net.ai.gate:ai-gate` (api, because `AiGateAdapter` takes an `Llm`), `kotlinx-coroutines-core`, `kotlinx-serialization-json`.

| ID | Class | Responsibility |
|---|---|---|
| G-01 | `AiGateAdapter : ObservableAdapter, AutoCloseable` | `id = "ai-gate"`; profile bindings; `capabilities`, `validate`, `start`, `normalizer`; `estimators()`; listener registry. |
| G-02 | `ProfileBinding` / `ProfileBindings` | Frozen per-profile resolution: SDK `Model`, `WireApi` id, `ChatOptions` template, cache policy, output-cap policy, expected usage fields. Built once at adapter start from `Profile.config.gate` and cross-checked against the catalog. |
| G-03 | `RequestTranslator` | `Request` → `Conversation` + `ChatOptions`: segments to messages, breakpoints to indices, flat items to turns, tools to `FunctionTool`, effort to `ReasoningLevel`, origin checks. |
| G-04 | `ResponseTranslator` | `AssistantMessage` → `Response`: content to items, stop mapping, tool-call stripping, provenance tags, usage via normalizer. |
| G-05 | `AiGateInvocation : Invocation` | The D-51 state machine on a virtual thread with a `CancelToken`; terminal settles exactly once. |
| G-06 | `ErrorMapper` | `LlmException` → `ProviderError` (A-02 kinds included) or a `Truncated` response for `stream_interrupted`. |
| G-07 | `UsageNormalizers` | `UsageNormalizer` per wire API from `Usage.raw()`: `anthropic-messages`, `openai-responses`, `openai-completions`, `google-generate-content`; absent → `unknown`. |
| G-08 | `AiGateEstimator : TokenEstimator` | Profile-aware estimate: translate, `llm.preview` (or S-07 `prepare`) for framing, heuristic text count with declared margin; exact when S-09 exists. |
| G-09 | `TextDeltaListener` | Optional live text for UIs, adapter-local (not through core). |
| G-10 | `JsonBridge` | kotlinx `JsonElement` ↔ `net.ai.gate.json.JsonValue` (string round trip; cached per `SchemaSet` digest for tools). |

### 4.1 `AiGateAdapter` (G-01, G-02)

```kotlin
public class AiGateAdapter(
    private val llm: Llm,
    profiles: Collection<Profile>,
    private val options: AdapterOptions = AdapterOptions(),   // strict=true, defaultCacheRetention=SHORT, tagPrefix="astrolabe"
    private val ownsLlm: Boolean = false,
) : ObservableAdapter, AutoCloseable {
    override val id: String = "ai-gate"
    private val bindings: Map<String, ProfileBinding> = profiles.associate { it.id to ProfileBinding.compile(llm, it, options) }
    private val invocations = ConcurrentHashMap<InvocationId, AiGateInvocation>()
    private val listeners = CopyOnWriteArrayList<InvocationListener>()

    override fun capabilities(profile: Profile): Capabilities = binding(profile).capabilities   // profile.capabilities after cross-check
    override fun validate(request: Request, estimate: Estimate): Validation { … see §4.6 … }
    override fun start(request: Request, id: InvocationId): Invocation {
        require(!invocations.containsKey(id)) { "invocation $id already registered" }
        val b = binding(request.profile)
        val translated = RequestTranslator.translate(request, b)                    // may throw TranslationException → InvalidRequest at await()
        return AiGateInvocation(id, llm, b, translated, listeners, ::settled).also { invocations[id] = it }.start()
    }
    override val normalizer: UsageNormalizer = UsageNormalizer { native, profile -> UsageNormalizers.forApi(binding(profile).api).normalize(native, profile) }
    public fun estimators(): EstimatorFactory = EstimatorFactory { profile -> AiGateEstimator(llm, binding(profile)) }
    override fun addListener(listener: InvocationListener): AutoCloseable { listeners += listener; return AutoCloseable { listeners -= listener } }
    override fun close() { invocations.values.forEach(AiGateInvocation::cancel); if (ownsLlm) llm.close() }
}
```

`ProfileBinding.compile` does, once per profile: `llm.model(profile.provider, profile.model)` (fails fast for an unlisted model; gateways list custom models in `ProvidersConfig`), resolves the wire API (`gate.api` or the provider default), parses `gate.options` with `ChatOptions.fromJson` and applies overrides, cross-checks `profile.capabilities.contextLimitTokens ≤ model.contextWindow` and `outputLimitTokens ≤ model.maxOutputTokens` when the catalog reports them (warn or fail per `gate.catalogCheck`), refuses `continuation = true`, `nativeCompaction = true`, `hostedExecution = true` until their contracts exist, and records the expected usage fields per API. With S-06 it reads `llm.features(model)` and refuses a profile whose declared capabilities exceed the descriptor (for example `breakpoints = true` on an automatic-cache API, or an enforced output cap on Codex).

### 4.2 Request translation (G-03)

| ASTROLABE | SDK | Rule |
|---|---|---|
| `S` (one `System` message) | `Conversation.system(text)`; breakpoint index `0` | Only `S` may be `System`; a `System` item elsewhere → `InvalidRequest`. |
| `R`, `K` (one `User` message each) | `UserMessage.of(text)`; breakpoint after each | Anthropic merges consecutive user turns; fine for all four APIs. |
| `T` pinned messages | `UserMessage` each | |
| `T` items | Turn grouping (below); breakpoint after the last message of `T` | |
| `A` (one `User` message, no breakpoint) | trailing `UserMessage` | |
| `Segment.breakpoint` | `Conversation.withMessages(messages, breakpoints)` with `breakpoint = messages emitted so far` at each marked segment end | Omitted when the binding's cache mode is not explicit markers (A-03 already leaves them false). Retention: `gate.cacheRetention`; with S-05, `LONG` for S/R/K and `SHORT` for T. |
| `tools` | `Tool.function(name).description(d).parameters(JsonSchema.of(JsonBridge.toGate(schema))).build()`; `strict` off | Cached per `SchemaSet` digest. The Responses codec emits `strict:false` explicitly; ASTROLABE schemas have optional fields and open objects, so non-strict is the only truthful mapping today (dialect `json-schema-2020-12`). |
| `mask` | not sent | `[S]` states "enabled this turn"; the executor enforces. |
| `effort` | `ChatOptions.reasoning(Minimal→MINIMAL, Low→LOW, Medium→MEDIUM, High→HIGH)` | Clamped by the SDK to the model's levels (a warning under strict is inspected in `validate`). |
| `maxOutputTokens` | `ChatOptions.maxTokens(n)` | With `gate.outputCap = "unsupported"` (Codex) it is not sent and the profile's `Capabilities.outputLimitTokens` is the reserved bound only. |
| `profile` | `binding.model`, `binding.api` | |
| `continuation` | must be `null` | `capabilities.continuation = false` until S-14. |
| per-call options | `.cancel(token).tag("invocation", id.value).tag("profile", profile.id).strict(options.strict)`, timeouts/retry from `gate` | The invocation id travels as an SDK tag, so `RequestEvent`s and JFR correlate with the journal. |

**Turn grouping of `T` items** (order preserved):

- `Message(User)` → `UserMessage`.
- A maximal run of assistant-side items (`Message(Assistant)`, `ReasoningRef`, `ToolCall`) → one `AssistantMessage.builder(origin.modelRef, origin.api)` with content in the original order (`Text`, `Content.Reasoning.of(text, signature, redacted, providerData)`, `ToolCall.of(id, name, argsJson)`). The origin is the `ReasoningRef.providerTag` inside the run; a run without reasoning uses the neutral origin `ModelRef("astrolabe", "history")`/`api = "astrolabe"`, for which the SDK only normalises tool-call ids and remaps results. A run is split when the origin tag changes.
- A maximal run of `ToolResult` → one `ToolResultMessage.of(results)`; each result is built from the matching `ToolCall` found by `callId` (`Items.pairs()` guarantees it exists) with `ToolResult.of(call, parts)` or `ToolResult.error(call, text)`; S-10 removes the lookup. `Opaque` parts inside results → `InvalidRequest` (core never produces them today).
- `UsageItem`, `OpaqueContinuation` → not sent.

**Origin policy in `validate`:** a `ReasoningRef` whose origin model differs from the target is rejected as `InvalidRequest` unless `gate.reasoningHandoff = "drop"` is set, in which case the SDK drops signatures and converts or omits text (`Handoff` rules). This is AX-07 ("model-family change at a packet boundary: opaque reasoning not replayed"). With S-12 the check delegates to `llm.check(...)`.

### 4.3 Response translation (G-04)

| SDK content | ASTROLABE item |
|---|---|
| `Text` (adjacent parts merged) | `Message(Assistant, [Text])` |
| `Reasoning` | `ReasoningRef(providerTag = "<provider>/<responseModel ?: model>@<api>", opaque = {text, signature, redacted, providerData})`, `native = null` |
| `ToolCall` | `ToolCall(id, name, argumentsJson)` — only when the stop mapping allows calls |
| `Refusal` | `Message(Assistant, text)` + `StopReason.Refusal` |
| `Unknown`, generated media | journaled through `UsageItem.native` (the SDK reply's `toJson()`), never replayed |
| `usage` | `normalizer.normalize(JsonBridge.toKotlinx(usage.raw()), profile)` |

| SDK `StopReason` | ASTROLABE `StopReason` | Calls |
|---|---|---|
| `TOOL_USE` | `ToolUse` | kept (complete parts only; S-03 makes "complete" explicit) |
| `STOP` | `ToolUse` if calls present, else `EndTurn` | kept |
| `LENGTH` | `OutputLimit` | **dropped** (AX-03; A-05 enforces) |
| `REFUSAL`, `CONTENT_FILTER` | `Refusal` | dropped |
| `ABORTED` | `Cancelled` if cancel was requested, else `Truncated` | dropped |
| `ERROR`, `OTHER`, unknown raw | `Truncated` | dropped; native stop kept in the journal |

The usage of a cache **replay** (`Usage.spent() == false`) would be charged again by `Accounting`; therefore the adapter never installs an SDK `ResponseCache` and refuses `gate.options.responseCache` other than `OFF`.

### 4.4 Invocation and cancellation (G-05)

```kotlin
internal class AiGateInvocation(
    override val id: InvocationId, private val llm: Llm, private val binding: ProfileBinding,
    private val translated: Translated, private val listeners: List<InvocationListener>, private val onSettled: (InvocationId) -> Unit,
) : Invocation {
    private val token = CancelToken.create()
    private val response = CompletableDeferred<Response>()
    private val terminalDeferred = CompletableDeferred<Terminal>()
    @Volatile override var state: InvocationState = InvocationState.Requested; private set
    @Volatile private var cancelRequested = false

    fun start(): AiGateInvocation { Thread.ofVirtual().name("astrolabe-inv-${id.value}").start(::run); return this }

    private fun run() {
        val outcome: Terminal = try {
            val options = translated.options.toBuilder().cancel(token).tag("invocation", id.value).build()
            llm.stream(binding.model, translated.conversation, options).use { stream ->
                for (event in stream) forward(event)                                   // progress to listeners; text deltas to G-09
                val reply = stream.result()
                val r = ResponseTranslator.translate(reply, binding, cancelRequested)
                Terminal(id, r, null, emptyList(), r.usage, cancelled = false)
            }
        } catch (e: RequestCancelledException) {
            val late = ResponseTranslator.textOnly(e.partial().orElse(null), binding)   // never a ToolCall
            val usage = ResponseTranslator.usageOrMissing(e.partial().orElse(null), binding)  // missing until S-01
            Terminal(id, Response(emptyList(), StopReason.Cancelled), null, late, usage, cancelled = true)
        } catch (e: LlmException) {
            ErrorMapper.terminal(id, e, binding)                                         // §4.5
        } catch (e: TranslationException) {
            Terminal(id, null, ProviderError.InvalidRequest(e.message ?: "untranslatable request"), emptyList(), null, cancelled = false)
        } catch (e: Throwable) {
            Terminal(id, null, ProviderError.Transport(e.message ?: e.javaClass.name, e), emptyList(), null, cancelled = false)
        }
        state = InvocationState.ProviderAcknowledged
        outcome.response?.let(response::complete) ?: response.completeExceptionally(outcome.error!!)
        terminalDeferred.complete(outcome)
        state = InvocationState.TerminalReconciled
        onSettled(id)
    }

    override suspend fun await(): Response = try { response.await() } catch (c: CancellationException) { cancel(); throw c }
    override fun cancel() { cancelRequested = true; if (state == InvocationState.Requested) state = InvocationState.CancelRequested; token.cancel() }
    override suspend fun terminal(): Terminal = terminalDeferred.await()
}
```

Rules: the worker never cancels a `CompletableFuture` (`completeAsync` semantics would discard the outcome); `terminal()` completes exactly once on every path including translation failure and thread start failure (wrap `Thread.start` in try/catch and settle); `ProviderAcknowledged` means "the SDK call returned" and is documented as local knowledge (`outcomeUnknown` from the exception is recorded in the journal text). With S-02 the class becomes a thin wrapper over `LlmCall.outcome()`.

### 4.5 Error mapping (G-06)

| SDK `ErrorCode` | ASTROLABE | Note |
|---|---|---|
| `rate_limited`, `quota_exhausted`, `overloaded` | `ProviderError.RateLimit(retryAfter.seconds)` | after the SDK's own retries |
| `context_overflow`, `request_too_large` | `ProviderError.ContextOverflow` (A-02) | core rebuilds |
| `invalid_request`, `model_not_found`, `unsupported_feature` | `InvalidRequest`; `UnsupportedSchema` when the message concerns tools/schema | |
| `invalid_credentials`, `login_required`, `refresh_failed`, `login_cancelled`, `permission_denied`, `credential_store` | `ProviderError.Authentication` (A-02) | blocks, not retried |
| `deadline_exceeded`, `stream_idle_timeout` | `ProviderError.Timeout(outcomeUnknown)` (A-02) | |
| `output_refused` | `Refusal` | |
| `output_truncated` | `OutputLimit` | |
| `stream_interrupted` | **response** `Truncated` with text-only partial | AX-01 |
| `cancelled` | response `Cancelled` + `Terminal(cancelled = true)` | §4.4 |
| `connect_failed`, `server_error`, `outcome_unknown`, `malformed_response`, other | `Transport` | `outcomeUnknown` recorded; usage stays unknown |

Retry ownership: the SDK `RetryPolicy` (from `gate.retry`, default 3 attempts on 408/409/429/503/529, never after stream output) is the only provider-retry loop. ASTROLABE adds none. Because ASTROLABE reserves per invocation while the SDK may retry inside it, the first release sets `gate.retry.maxAttempts = 2` by default and records `ResponseInfo.attempts()` in the journal; S-11 makes attempt spend explicit.

### 4.6 `validate` (G-01)

1. `Validations.standard(request, estimate, capabilities)`.
2. Translate; on `TranslationException` → `Rejected(InvalidRequest)`.
3. `llm.preview(model, conversation, options.strict())` (S-07: `prepare(..., streaming = true)`). A rejected `PreparedRequest` → `InvalidRequest`. Warnings mapped to problems: `option_adapted` on `max_tokens` (until S-08 makes it fatal), `cache_hint_ignored` when the profile declares explicit markers, `reasoning_dropped`/`reasoning_converted` when the profile forbids handoff, `option_dropped` for `maxTokens` when `gate.outputCap = "enforced"`.
4. Origin check on `ReasoningRef`s (§4.2).
5. With S-09, an exact count replaces the heuristic: `ContextOverflow` when `count + maxOutputTokens > contextLimitTokens`.

### 4.7 Usage normalisation (G-07)

Each normaliser reads the raw provider usage object (the SDK keeps it in `Usage.raw()`; for Anthropic streams it is the merge of `message_start` and `message_delta`). Absent counters go to `unknown`; the SDK's `0` defaults are never trusted (S-04 removes them).

| Wire API | `uncached_input` | `cache_read` | `cache_write_5m` / `cache_write_1h` | `output` | Provenance protocol |
|---|---|---|---|---|---|
| `anthropic-messages` | `input_tokens` | `cache_read_input_tokens` | `cache_creation.ephemeral_5m_input_tokens` / `ephemeral_1h_input_tokens`; if only `cache_creation_input_tokens` exists, attribute to the requested retention class and mark the other class unknown | `output_tokens` (thinking included, `reasoningIncludedInOutput = true`) | `anthropic-messages` |
| `openai-responses` | `input_tokens − cached_tokens − cache_write_tokens` | `input_tokens_details.cached_tokens` | `cache_write_tokens` when reported, else unknown | `output_tokens` (reasoning included) | `openai-responses` |
| `openai-completions` | `prompt_tokens − cached_tokens` | `prompt_tokens_details.cached_tokens` (unknown when absent: gateways strip it) | n/a (not declared in `usageFields`) | `completion_tokens` | `openai-completions` |
| `google-generate-content` | `promptTokenCount − cachedContentTokenCount` (+ `toolUsePromptTokenCount`) | `cachedContentTokenCount` | n/a | `candidatesTokenCount + thoughtsTokenCount` | `google-generate-content` |

`UsageProvenance(profile.provider, reply.responseModel ?: profile.model, api)`. The profile's `Capabilities.usageFields` lists the dimensions expected for that API so that a gateway which strips fields is recorded as *unknown*, never zero. `BillableUsage.native` is the raw usage object; `Accounting.Quantities.bytesTransmitted` keeps its current meaning (serialised ASTROLABE request), with the SDK's `PreparedRequest.body()` size journaled separately when wanted.

### 4.8 Estimator (G-08)

```kotlin
internal class AiGateEstimator(private val llm: Llm, private val binding: ProfileBinding) : TokenEstimator {
    override val id = "ai-gate/${binding.api}"; override val version = "1"
    private val text = HeuristicEstimator()
    override fun estimate(text: String) = this.text.estimate(text)
    override fun estimate(request: Request): Estimate {
        val translated = RequestTranslator.translate(request, binding)
        // S-09: llm.countTokens(prepared) → Estimate(exact = true) when method == "provider-endpoint" or "local-tokenizer"
        val body = llm.preview(binding.model, translated.conversation, translated.options).body().toJson()   // framing included
        val e = text.estimate(body)
        return e.copy(estimatorId = id, version = version, marginTokens = maxOf(e.marginTokens, e.tokens / 10))
    }
}
```

Counting the *previewed wire body* is closer to what the provider tokenises than the item-level planning estimate, keeps `unknownHistory = false` (no `native` in play), and lets `ContextAdmission`'s learned margin do the rest. `Item.estimate` in residency keeps using the heuristic through `TokenEstimator.estimate(text)`.

### 4.9 JSON bridge (G-10)

`kotlinx.serialization.json.JsonElement` ↔ `net.ai.gate.json.JsonValue` through `Json.parse(element.toString())` and `Json.parseToJsonElement(value.toJson())`. Integers and decimals survive both parsers as text; nulls stay explicit. Tool schemas are converted once per `SchemaSet` digest; S/R/K message texts need no conversion.

---

## 5. Profiles, providers and settings

### 5.1 Host composition

```kotlin
val llm = Llm.builder()
    .apply { ProvidersConfig.read(Files.readString(providersJson), Providers.presets()).forEach(::provider) }
    .credentials(CredentialStore.file(stateDir.resolve("credentials.json")))
    .catalog { it.offline() }                       // or snapshotFile(...): frozen limits per attempt (invariant 12)
    .defaults { it.strict() }
    .listener(telemetryListener)
    .build()
val adapter = AiGateAdapter(llm, config.profiles.values)
val astrolabe = Astrolabe(config, adapter, authority, estimators = adapter.estimators())
```

### 5.2 Profile source of truth

`Profile` stays ASTROLABE's frozen truth for limits, capabilities and prices. The adapter cross-checks it against the SDK catalog at start-up and refuses contradictions; it never *fills* a profile from the live catalog mid-attempt (catalog background refresh is disabled by `offline()`/`snapshotFile`).

Recommended first-release declarations (per wire API), until S-06 supplies them:

| API | `caching` | `continuation` / `nativeCompaction` / `hostedExecution` | `cancellation` | `schemaDialects` | `usageFields` |
|---|---|---|---|---|---|
| `anthropic-messages` | `breakpoints=true, maxBreakpoints=4, minimumTokens=1024 (2048 for Haiku), writeClasses={5m,1h}` | false / false / false | true (local abort) | `{json-schema-2020-12}` | `{uncached_input, cache_read, cache_write_5m, cache_write_1h, output}` |
| `openai-responses` | `breakpoints=false` (automatic prefix cache; `sessionId` → `prompt_cache_key`) | false / false / false | true | `{json-schema-2020-12}` | `{uncached_input, cache_read, output}` |
| `openai-completions` (gateways, local servers) | `breakpoints=false` | false / false / false | true | `{json-schema-2020-12}` | `{uncached_input, output}` plus `cache_read` only after probing |
| `google-generate-content` | `breakpoints=false` (implicit caching) | false / false / false | true | `{json-schema-2020-12}` | `{uncached_input, cache_read, output}` |

### 5.3 `ProvidersConfig` (SDK, secret-free)

Gateways and custom endpoints are declared here, including custom `models` entries so `llm.model(provider, model)` resolves, and `compat` flags (for example `anthropic-compatible` with `cacheTtl=false` for a gateway that has no 1h TTL).

### 5.4 Credentials

`CredentialStore.file` for a CLI; a keychain-backed `CredentialStore` for a desktop UI; `Environment` for CI. OAuth logins (`llm.auth().login(providerId, AuthType.OAUTH, ui)`) run **outside** a campaign, driven by the host's `AuthInteraction`. ASTROLABE's `Authority` (permission for effects) and the SDK's `Auth` (provider credentials) remain separate concepts.

### 5.5 Codex (`openai-codex`) policy

The preset is streaming-only and takes no `max_output_tokens` (`OpenAi.java:41-51`); under `strict` the Responses codec rejects `maxTokens` with `option_dropped`. A Codex profile therefore sets `gate.outputCap = "unsupported"`: the adapter does not send `maxTokens`, `validate` accepts, and ASTROLABE still reserves `maxOutputTokens` (conservative; a `LENGTH` stop is impossible to force, so the profile's `outputLimitTokens` is a planning bound). Not a first-release profile; qualify it after the generic bridge passes the fixtures.

### 5.6 The `gate` block of `Profile.config` (versioned)

```json
{
  "gate": {
    "v": 1,
    "api": "anthropic-messages",
    "options": { "maxTokens": 16000, "reasoning": "medium", "cacheRetention": "short", "parallelToolCalls": true, "strict": true },
    "provider": { "api": "openai-responses", "serviceTier": "default" },
    "timeouts": { "connect": "PT10S", "streamIdle": "PT2M", "total": "PT15M" },
    "retry": { "maxAttempts": 2 },
    "sessionId": "astrolabe-{profile}",
    "reasoningHandoff": "reject",
    "outputCap": "enforced",
    "catalogCheck": "fail"
  }
}
```

`options` is `ChatOptions.toJson()` form (so `ChatOptions.fromJson` parses it and `ChatOptions.set(key, raw)` edits it from a form); `timeouts`/`retry` are `TimeoutPolicy.toJson()`/`RetryPolicy.toJson()` forms; `provider` is a `ProviderOptions` JSON. Per-request values that ASTROLABE owns (`maxOutputTokens`, `effort`) override the template. Add `Config.violations()` checks for the `gate` block through a `ProfileConfigValidator` SPI that the adapter module registers (core keeps no SDK knowledge; the validator is an interface in `provider-api`, implemented in the adapter module, invoked by the host before `Astrolabe` is constructed).

---

## 6. Build wiring

```kotlin
// ASTROLABE/settings.gradle.kts
includeBuild("../llm-transport-sdk/llm")      // composite build; group/name already resolve to net.ai.gate:ai-gate
include(":provider-api", ":core", ":eval", ":index-treesitter", ":provider-ai-gate")

// ASTROLABE/gradle/libs.versions.toml
[versions] ai-gate = "0.1.0-SNAPSHOT"
[libraries] ai-gate = { module = "net.ai.gate:ai-gate", version.ref = "ai-gate" }

// ASTROLABE/provider-ai-gate/build.gradle.kts
plugins { id("astrolabe.kotlin-library") }
description = "ASTROLABE provider adapter over AI Gate (llm-transport-sdk): HTTP, credentials, codecs, streaming, cancellation."
dependencies {
    api(project(":provider-api"))
    api(libs.ai.gate)                                   // Llm appears in the adapter's constructor
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
```

The convention plugin enables `explicitApi()` and ABI validation, so the module ships an API dump. Once S-15 lands, replace the composite build with a published version (`mavenLocal()` in `dependencyResolutionManagement.repositories`, then the release repository); consumers of ASTROLABE never need the sibling checkout. The SDK's `module-info` does not affect ASTROLABE (classpath consumer; `META-INF/services` discovery works).

---

## 7. Implementation plan

| Phase | Work | Exit criteria |
|---|---|---|
| 0 | Build wiring (§6); empty `AiGateAdapter` compiling against `net.ai.gate`; API dump. | `./gradlew :provider-ai-gate:build` green on JDK 26. |
| 1 | Core seams: A-01, A-02, A-03, A-05, A-04 docs. Existing suites green with `FakeAdapter`. | `:core:test` and `:provider-api:test` green; new routing and overflow-rebuild tests. |
| 2 | Deterministic translation: G-03, G-04, G-10 with unit tests (segment→index mapping, turn grouping, provenance tags, stop mapping, call stripping, JSON round trip). | Property tests over generated `Request`s; every AX-03/04/07 shape covered offline. |
| 3 | Runtime: G-05, G-06, G-07 with `FakeProvider`/`FakeServer` (cancel before send, cancel during body, stall + idle timeout, interrupted mid-arguments, `LENGTH` with pending call, refusal, missing usage, executor rejection, concurrent invocations). | Terminal settles exactly once in every case; core `TerminalAccountingTest` style test passes through the real adapter. |
| 4 | Profiles and validation: G-01/G-02, `gate` schema, catalog cross-check, preview warnings → problems; G-08 estimator wired through A-01. | A multi-turn cell (text + tools) completes against `FakeProvider` behind a real `Llm`; admission uses the adapter estimator. |
| 5 | Recorded protocol fixtures through `Provider.transport(HttpTransport)` for `anthropic-messages` and `openai-responses`: AX-01..10 (§8). | All ten pass with the **real** codecs, no network. |
| 6 | Residency rule A-06; lifecycle A-07; progress seam A-08/G-09; Java parity A-09. | Eviction fixture keeps reasoning with live calls; UI sample subscribes to `ModelProgress`. |
| 7 | Live smoke (authorised, capped): one Anthropic profile, then one Responses profile, on a disposable repository; `llm.test(model)` first. | Usage, cancellation and evidence inspected; gates stay `UNMEASURED` per I-19 until the evaluation suite runs. |
| 8 | Broaden: OpenAI-compatible gateways (probe before enabling `cache_read`), Gemini, Codex policy (§5.5). | Each profile passes §8 for its API. |
| 9 | Adopt SDK extensions as they land and delete the matching workaround (§10): S-01/S-03/S-04 → exact partial usage and no stripping; S-02 → thin invocation; S-06 → descriptor-driven profiles; S-07/S-09 → exact admission; S-08 → drop warning scanning; S-14 → enable `continuation`. | Workaround table empty. |

Effort: adapter ~1,000–1,300 LOC; normalisers ~200; profile/config ~200; core changes < 250; tests ~900+. Phases 0–5 are the first usable release.

---

## 8. Tests the adapter must pass (AX-01..10 mapped)

| Fixture | Setup (recorded frames or `FakeProvider`) | Expected |
|---|---|---|
| AX-01 interrupted stream mid tool call | SSE cut after `ToolCallDelta` | `Response(Truncated)` with text only; terminal usage unknown; no dispatch |
| AX-02 tool pairing | history with two calls, results in order; then one result evicted | first passes; second rejected by `Validations.standard` before dispatch |
| AX-03 output-limit stop | `LENGTH` with a complete-looking call | `OutputLimit`, no calls (A-05) |
| AX-04 refusal | `REFUSAL` / `CONTENT_FILTER` | `Refusal`, cell ends, no dispatch |
| AX-05 expired continuation | (disabled) `continuation != null` | `UnsupportedContinuation` at validate |
| AX-06 native compaction | (disabled) | capability false; never requested |
| AX-07 model-family change | `ReasoningRef` from another origin | rejected at validate unless `reasoningHandoff = "drop"`, then signatures dropped and text converted/omitted |
| AX-08 cancellation with late output | cancel during body; fake emits more frames | `Cancelled` response; `Terminal.lateItems` archived, never executed; exactly one terminal |
| AX-09 missing usage | reply without `usage` | `BillableUsage.missing(...)`; conservative charge; never zero |
| AX-10 cache-token normalisation | Anthropic 5m+1h; Responses `cached_tokens`; gateway without cache fields | dimensions as in §4.7; absent → unknown; no double counting of `cacheWrite` |

Plus: two calls in one turn; error result; invalid JSON arguments (no repair, explicit disposition); cancel before send (no HTTP call); stall + idle timeout → `Timeout`; auth failure → `Authentication`, no secret in logs; retry after 429 (attempts journaled); prefix stability (identical S/R/K bytes across turns, breakpoint indices stable); snapshot/restore of a lineage with `ReasoningRef`s; small-output routed profile (A-01); concurrent S3 child cells (independent tokens and ids); shutdown with in-flight invocations (all terminals settle).

---

## 9. UI integration considerations

The UI layer will sit above **both** libraries; the design above keeps every knob reachable without touching internals.

**Settings the UI edits, and where they live**

| Setting | Owner | API for a form |
|---|---|---|
| Providers, gateways, endpoints, headers, compat flags, custom models | SDK `ProvidersConfig` | `ProvidersConfig.read/write`; `Providers.presets()`; S-17 `Provider.fields()`/`ApiCompat.fields()` descriptors |
| Credentials, logins, status | SDK `Auth` + `CredentialStore` | `auth.status(provider)`, `methods`, `login(provider, type, ui)`, `logout`; the UI implements `AuthInteraction` (prompts → dialogs, `RedirectInteraction` → open browser); `ApiKeyAuth.fields()` |
| Models and their parameters | SDK catalog | `models().all(provider)`, `Model.parameters()` (`FieldDescriptor`s), `ChatOptions.set(key, raw)` → problems at `build()` |
| Per-profile limits, prices, capabilities, `gate` block | ASTROLABE `Profile` (JSON) | `Config` is `@Serializable`; a `ProfileCompiler` in the adapter module derives a draft `Profile` from an SDK `Model` (limits, prices snapshot dated today, capabilities from §5.2 or S-06) that the user reviews and freezes |
| Roles, flags, tier table, execution mode | ASTROLABE `Config` | `Config.violations()` for inline validation |
| Diagnostics | SDK | `llm.test(model)` → `ConnectionReport.Step`s as a checklist; `llm.preview(...)`/`toCurl()` for "show request" |

**Runtime observation for the UI**

- Campaign and cell events: ASTROLABE `Events` (`Flow<EventRecord>` for Kotlin, `EventSink` for Java), including `ModelRequested`, `ModelResponded` (stop, usage) and, with A-08, `ModelProgress`.
- Live model text (typing indicator): the adapter's `TextDeltaListener` (G-09), adapter-local, opt-in; the journal remains the source of record.
- Transport telemetry: SDK `LlmListener` (`RequestEvent.Started/FirstOutput/Retrying/Finished`, `CredentialEvent`, `CatalogEvent`), correlated to the journal by the `invocation` tag; JFR when enabled.
- Bill: `AgentEvent.Budget.*` and `Accounting` views (`Views`), which now carry `BillableUsage` with 5m/1h classes and `unknown` sets; a UI shows "unknown" as such, never as zero.

**Cancellation from the UI**: `CampaignHandle.cancel()` → the cell cancels the invocation → `CancelToken` aborts the SDK call → `terminal()` still settles and the bill is shown.

**Threading**: SDK futures/streams complete on virtual threads; ASTROLABE events are delivered on the bus dispatcher; the UI marshals to its own thread. No API in either library blocks a UI thread when used as documented.

---

## 10. Workarounds in the adapter until the SDK changes land

| Adapter behaviour (first release) | Removed by |
|---|---|
| Usage of a cancelled or interrupted call recorded as `missing`; ASTROLABE charges `max(estimate, reservation)` | S-01 |
| Hand-rolled virtual-thread state machine around `stream()` | S-02 |
| All `ToolCall`s stripped from any partial reply | S-03 |
| Per-API parsing of `Usage.raw()` for cache classes; codec `0` defaults ignored | S-04 |
| One cache retention per call (`SHORT`), no long TTL for the stable prefix | S-05 |
| Capabilities declared by hand per API (§5.2); gateways probed by a manual smoke | S-06 |
| Admission from a previewed non-streaming body plus heuristic margin | S-07, S-09 |
| `validate` scans preview warnings (`option_adapted`, `cache_hint_ignored`, `option_dropped`) | S-08 |
| `ToolResult` built through a `ToolCall` lookup by `callId` | S-10 |
| Attempts journaled as a count; retry spend treated as unknown | S-11 |
| Origin check on `ReasoningRef` done in the adapter | S-12 |
| `continuation`, `nativeCompaction`, `hostedExecution` declared `false` | S-14 |
| Composite build instead of a published artifact | S-15 |

None of these is a hack: each is a documented, conservative policy that the SDK extension later makes exact.

---

## 11. Risks and open points

- **Gateway parity**: an OpenAI-compatible endpoint may accept the request shape yet drop cache fields, usage details or parallel calls. Every gateway profile is unverified until probed (§5.2, S-06); declare only what the probe showed.
- **Heuristic admission on dense code**: the previewed-body estimate plus the learned margin recovers after a provider rejection (A-02 turns it into a rebuild, not a failed cell); S-09 removes the risk.
- **Reasoning replay**: the provenance rule (A-04) and the residency rule (A-06) must hold, or Anthropic thinking with tool use and Gemini function calls will be rejected on the next turn.
- **Two JSON stacks**: string round trips are correct but duplicate work on large requests; tool schemas are converted once per digest, S/R/K texts need no conversion.
- **Codex**: usable only under the explicit output-cap policy (§5.5).
- **Response cache**: never enabled for agent runs (`spent = false` replays would be double-charged).
- **Catalog drift**: freeze the catalog per attempt (`offline()`/`snapshotFile`); the adapter cross-checks, it never re-reads limits mid-attempt.
