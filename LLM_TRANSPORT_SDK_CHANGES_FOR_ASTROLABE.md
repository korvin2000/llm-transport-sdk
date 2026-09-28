# `llm-transport-sdk` (AI Gate): changes required to serve as ASTROLABE's transport layer

**Date:** 2026-09-28
**Inputs:** `transp_integr_request.md`, `TRASPORT_INTEGRATION_ANALYZE.md` (called **A** below), `TRASPORT_INTEGRATION_ANALYZE_01.md` (called **B** below), and a fresh source reading of both repositories (ASTROLABE `dba6344`, llm-transport-sdk `d8dc26a`).
**Companion document:** `ASTROLABE_CHANGES_FOR_LLM_TRANSPORT_SDK.md` (the ASTROLABE side). Change IDs are shared: `S-nn` = SDK change (this document), `A-nn` = ASTROLABE core/provider-api change, `G-nn` = adapter-module work (companion document).
**Scope rule:** every change here is **general-purpose**. The SDK never learns ASTROLABE types, its seven tool families, its budget classes or its journal. The needs below are those of *any* autonomous agent host: exact accounting after cancellation, safe partial replies, cache control, admission counts, truthful capability facts, and UI-friendly configuration.

---

## 0. How the two analyses were reconciled

Both analyses reach the same verdict: the SDK is a good transport foundation, no rewrite is needed, an adapter module does the translation, and a short list of additive SDK extensions removes every workaround. They differ in emphasis and in a few proposals. The table records what was kept.

| Topic | A | B | Resolution used here |
|---|---|---|---|
| Usage lost on cancelled/failed streams | SDK-1: `ChatEvent.UsageUpdate` + call handle | SDK-1/SDK-2: same gap, plus partial/final completeness metadata | **Both**: S-01 (usage updates with completeness) and S-02 (call handle). Verified in source (§1). |
| Half-generated tool calls in `partial()` | SDK-2: mark incomplete parts | Covered under stop-reason policy; adapter buffers | S-03, because `Accumulator.Part.content()` synthesises `call_unknown` and a host cannot tell. |
| Cache-write classes 5m/1h, `orElse(0)` defaults | SDK-3 concrete API | SDK-6 "dimension map" | S-04 uses A's concrete API plus B's rule that absent stays absent. |
| Per-breakpoint TTL and cache facts | SDK-4 | Part of protocol descriptor | S-05 (breakpoint record) and S-06 (descriptor). |
| Capability/protocol descriptor | SDK-6 (features + billable probe) | SDK-5 (must state output-cap support; Codex case) | S-06 merges both; the Codex `maxOutputTokens(false)` finding is B's and is verified. |
| Token counting | SDK-5 endpoint-backed `countTokens` | Optional SPI over the effective representation; "prepare once" | S-07 (prepare once, streaming-aware) and S-09 (counting) are separate items; S-09 builds on S-07. |
| `strict` does not cover `max_tokens` raise | SDK-8 | Mentioned as "warnings must be inspected" | S-08, verified at `MessagesCodec.java:155-160` (`ctx.warn`, not `ctx.adapt`). |
| Attempt ledger / retry spend uncertainty | Not raised | SDK-6 attempt evidence | S-11. |
| History adaptation policy | Adapter rejects foreign `ReasoningRef` | SDK-7 explicit policy + structured issues | S-12, lower priority: the adapter can enforce same-origin first. |
| Replay envelope / canonical JSON omits raw usage | Store provenance in `ReasoningRef`, keep `Item.native` null | SDK-3 versioned envelope, audit codec retention | S-13 is an *audit plus optional export*; no envelope type is needed for the first release because A's provenance rule keeps everything in existing `Content.Reasoning` fields. |
| Continuation / compaction | SDK-7 later | SDK-9 later | S-14, later. |
| Publishing the artifact | SDK-11 | SDK-8 | S-15. |
| Coroutine module | Not raised | SDK-10 optional | S-16, optional, separate artifact. |
| UI configuration surface | Not raised | Not raised | S-17, new: both projects will sit under a UI; the SDK already has `FieldDescriptor`, `ChatOptions.set`, `ProvidersConfig`, `AuthInteraction`. Gaps are listed. |

No proposal from either analysis was found to be wrong against the source. B's warning that `preview()` prepares in non-streaming mode is confirmed (`Engine.java:80` calls `prepare(..., false)`).

---

## 1. Verified gaps (source evidence)

| # | Fact in the current SDK | Where | Consequence for an agent host |
|---|---|---|---|
| 1 | The Anthropic stream decoder keeps `message_start`/`message_delta` usage in a local `usage` object and emits it only inside `ChatEvent.Done` at `message_stop`. | `vendors/anthropic/internal/MessagesCodec.java:374-397` | Input tokens known from the first frame are lost when the stream is cancelled or interrupted. |
| 2 | `Accumulator.snapshot()` builds the partial reply with `stopReason(ABORTED)` and **no usage**; `DefaultChatStream.fail()` passes that snapshot to `Call.fail(error, partial)`. | `internal/core/Accumulator.java:85-88`, `DefaultChatStream.java:242-250` | `LlmException.partial().usage()` is always `Usage.empty()`. |
| 3 | `completeAsync()` returns a future whose `cancel()` calls `token.cancel()` **and** `super.cancel()`; the worker's later `future.complete(...)` is then discarded. | `internal/core/DefaultLlm.java:84-106` | A host that cancels the future loses the outcome. The only safe path is `stream()` on its own thread with a `CancelToken`. |
| 4 | Unfinished tool calls in a partial reply are materialised as `ToolCall.of("call_unknown", "unknown", partialArgs)` (per `Accumulator.Part.content()`). | `internal/core/Accumulator.java` | A partial reply is indistinguishable from a complete one; the documented "appendable" partial can replay a half call. |
| 5 | Anthropic usage defaults `cache_read_input_tokens` and `cache_creation_input_tokens` to `0` when absent; the 5m/1h split under `cache_creation.*` is not mapped. `Usage` has a single `cacheWrite`; `Prices` a single `cacheWritePerMillion`. | `MessagesCodec.java:349-356`, `metadata/Usage.java`, `model/Prices.java` | Contradicts the SDK's own "absent means not reported" rule; 1h writes cannot be priced exactly. |
| 6 | Budget thinking raises `max_tokens` above the caller's value with `ctx.warn("option_adapted")`, never `adapt`, so `strict()` does not reject it. | `MessagesCodec.java:155-160` | A host that reserved exactly `maxTokens` is billed beyond its reservation without a failure. |
| 7 | Cache breakpoints are plain `List<Integer>` message indices; retention is one `CacheRetention` per call; at most four markers are honoured, extra ones are silently dropped. | `chat/Conversation.java`, `MessagesCodec.java:210-225` | No per-prefix TTL (long for stable prefix, short for the transcript); marker limits are not discoverable. |
| 8 | No token-counting API; `Resolver.estimateTokens` is `chars/4 + 16` and internal. | `internal/core/Resolver.java:73-86` | Admission stays heuristic. |
| 9 | `preview()` prepares with `streaming = false`; `Engine.prepare` is package-private; a call is re-resolved at dispatch. | `internal/core/Engine.java:56,78-81` | A host cannot admit the exact request it will send in streaming mode. |
| 10 | The Codex preset is `streamingOnly(true).maxOutputTokens(false)`; the Responses codec then emits `ctx.adapt("option_dropped")` when `maxTokens` is set. | `vendors/openai/OpenAi.java:41-51`, `ResponsesCodec.java:93-94` | Under `strict` the call fails; otherwise the output cap is silently unenforced. Neither is discoverable before the call. |
| 11 | `previous_response_id` is sent while the full `input` is still encoded. | `ResponsesCodec.java:90,150` | Continuation is not usable without the host trimming history itself. |
| 12 | `RequestEvent.Finished` and `ResponseInfo` carry `attempts` as a count only. | `event/RequestEvent.java`, `metadata/ResponseInfo.java` | No per-attempt facts (which attempt was billed, why it was retried). |
| 13 | `Conversation.toJson()` omits `Usage.raw()` and `ResponseInfo` (transient by design). | `internal/serialization/ConversationJson.java` | Hosts that archive raw usage must do so outside the canonical form (fine, but must be documented). |
| 14 | No `maven-publish`; the artifact exists only as a composite/source build. | `llm/build.gradle.kts` | Downstream needs a sibling checkout. |
| 15 | `ToolResult` can only be built from a `ToolCall` object. | `chat/content/ToolResult.java` | Hosts that persist history as their own items must rebuild a `ToolCall` to construct a result. |

What is **already right** and must be preserved: pure codecs; `strict` versus soft adaptation through `Notes`; `Usage` optional buckets with `raw()`; `CancelToken` trees; `RetryPolicy` never retrying after stream output; `PreparedRequest` without network; `Provider.transport(HttpTransport)` for recorded fixtures; `FakeProvider`; `FieldDescriptor`, `ChatOptions.set/toJson/fromJson`, `ProvidersConfig`, `AuthInteraction` for UIs.

---

## 2. Design constraints for every change

1. **Additive and source-compatible.** Existing methods keep their behaviour. New layer-3 APIs carry `@ApiStatus.Experimental` until a release.
2. **JDK-only runtime.** No new runtime dependency. Kotlin stays test-only in the core artifact (S-16 is a separate artifact).
3. **Codecs stay pure.** New facts are emitted as events or values; I/O, retries and cancellation stay in the core.
4. **Nothing ASTROLABE-specific.** Names are generic (`CallOutcome`, `ApiFeatures`, `CacheBreakpoint`), and every item is justified by a second consumer type (chat UIs, batch pipelines, other agents).
5. **Verified with the existing kit.** Each change ships with a `FakeServer`/`FakeWireApi` fixture and, where a codec changes, a recorded frame test.
6. **ArchUnit rules hold.** At most 20 top-level types per package; internals not exported.

---

## 3. Change list (prioritised)

Priority: **P0** = needed before an agent host can claim exact accounting or safe cancellation; **P1** = needed for a clean, hack-free adapter; **P2** = valuable, not blocking; **P3** = later.

| ID | Priority | Change | Size | Replaces which adapter workaround (companion doc §10) |
|---|---|---|---|---|
| S-01 | P0 | Usage updates during a stream; partial replies carry observed usage with completeness | M | "usage missing on cancel/interrupt" |
| S-02 | P0 | Call handle with a terminal outcome that survives cancellation | M | hand-rolled virtual-thread state machine |
| S-03 | P0 | Incomplete parts marked in partial replies | S | "strip all tool calls from partials" |
| S-04 | P0 | Cache-write classes in `Usage`/`Prices`; absent counters stay absent | S–M | parsing `raw()` per wire API |
| S-05 | P1 | Per-breakpoint cache retention | M | one TTL per call |
| S-06 | P1 | Protocol feature descriptor (`ApiFeatures`) and optional billable probes | M | hand-declared capabilities |
| S-07 | P1 | Prepare once (streaming-aware), then execute the prepared call | M | preview/dispatch mismatch |
| S-08 | P1 | `strict` covers billing- and limit-changing adaptations; effective `max_tokens` exposed | S | scanning preview warnings |
| S-09 | P2 | Token counting API (`countTokens`, `Tokenizer` SPI) | M | heuristic admission only |
| S-10 | P2 | `ToolResult` factory from `(callId, toolName, parts, isError)` | XS | placeholder `ToolCall` lookup |
| S-11 | P2 | Attempt ledger and cancellation facts | S | "attempts count only" |
| S-12 | P2 | History policy and structured compatibility issues | M | adapter-side origin check |
| S-13 | P2 | Replay-fidelity audit; archive export of reply facts | S | none (documentation) |
| S-14 | P3 | First-class continuation, later compaction | L | continuation disabled |
| S-15 | P1 | `maven-publish`, versioning policy | XS | composite build only |
| S-16 | P3 | Optional `ai-gate-kotlin` coroutine artifact | S | none |
| S-17 | P1 | UI-facing descriptors and validation | S–M | none (new UI need) |

### S-01 Usage during a stream; partial replies with observed usage

**Problem.** Gaps 1 and 2. Usage that already arrived on the wire is not available on failure or cancellation.

**API.**

```java
// chat/stream/ChatEvent.java — new sealed member
record UsageUpdate(Usage observed, boolean finalForCall) implements ChatEvent { }

// metadata/Usage.java — new accessor and builder field (default: true for Done usage, false for updates)
public boolean finalForCall();           // false: counters may still grow (output), or remaining buckets may be reported later
public Builder finalForCall(boolean value);

// chat/AssistantMessage.java — a partial reply reports what was observed
public Usage usage();                    // unchanged; for a partial reply it is the last UsageUpdate, finalForCall=false
```

**Implementation.**
- `MessagesCodec.streamDecoder`: emit `UsageUpdate(usage(message.usage), false)` on `message_start`, and again on every `message_delta` that carries usage (merged with the running object, exactly as today); keep `Done` authoritative with `finalForCall = true`.
- `CompletionsCodec` and `ResponsesCodec`: emit on `usage` chunks / `response.completed` where the wire reports them (Responses reports usage only at completion; then only `Done` carries it, which is correct).
- `Accumulator`: keep the latest `UsageUpdate`; `snapshot()` sets `usage(latest)`; `aggregate()` uses the decoder's `Done` usage unchanged (no summing).
- `Call.fail(error, partial)` and `RequestEvent.Finished` pass the snapshot through unchanged, so `LlmException.partial().usage()` and `Finished.usage()` are populated.
- Response-cache `Exchange` recording is unaffected (frames are recorded as received).

**Tests.** A `FakeWireApi`/`FakeServer` script that sends usage, some text, then interrupts before `Done`: the thrown `LlmException.partial().usage().input()` is present and `finalForCall()` is false. A success script proves `Done` usage equals `complete()` usage (no double counting).

**Other consumers.** Chat UIs showing live token counters; batch runners reconciling cost after a deadline.

### S-02 Call handle with a terminal outcome

**Problem.** Gap 3. The result future and the stream lifetime are separate; every host reconstructs "what happened after I cancelled".

**API (experimental).**

```java
// net/ai/gate/LlmCall.java
@ApiStatus.Experimental
public interface LlmCall {
    String requestId();
    /// Completes with the reply, or exceptionally with the LlmException. Cancelling this stage requests cancellation
    /// of the call but never affects outcome().
    CompletionStage<AssistantMessage> reply();
    void cancel();
    /// Completes exactly once, after the call is fully settled (including pre-dispatch failures and executor rejection).
    CompletionStage<CallOutcome> outcome();
}

// net/ai/gate/CallOutcome.java
public record CallOutcome(
        String requestId,
        @Nullable AssistantMessage reply,        // the complete reply, when the call finished normally
        @Nullable LlmException error,            // the failure, otherwise
        @Nullable AssistantMessage partial,      // what arrived before failure/cancellation (S-03 marks incomplete parts)
        Usage usage,                             // last observed usage (S-01); finalForCall says whether it is complete
        Cancellation cancellation,               // NONE | REQUESTED_BEFORE_SEND | REQUESTED_AFTER_SEND
        boolean outcomeUnknown,                  // the request may have been processed and billed
        List<Attempt> attempts) { }              // S-11; a single element until then

// Llm.java
LlmCall start(Model model, Conversation conversation, ChatOptions options);   // streams internally; returns immediately
```

**Implementation.** Implement on `Engine.stream(prepared, store)` in a worker on `core.executor()`; use `CancelToken.child()` of the caller's token; `reply()` is a `CompletableFuture` whose `cancel` override calls `cancel()` and returns `super.cancel(...)`, while `outcome()` is completed from the worker's `finally` path. `completeAsync` stays as is (document its semantics; optionally reimplement it as `start(...).reply()`).

**Tests.** Cancel before send (no HTTP call; outcome settles with `REQUESTED_BEFORE_SEND`); cancel during body (partial retained, usage observed); executor rejection (outcome settles exceptionally); success (reply == outcome.reply).

**Other consumers.** Any UI with a Stop button that must still show the bill.

### S-03 Incomplete parts in partial replies

**Problem.** Gap 4.

**API.**

```java
// chat/AssistantMessage.java
public List<Integer> incompleteParts();                 // indices into content(); empty for complete replies
public boolean complete();                               // incompleteParts().isEmpty()
public Builder incompletePart(int index);
```

Encoders skip incomplete parts when a partial reply is replayed (`Handoff`), and `Conversation.append(partial, results)` throws when a result targets an incomplete call. `Accumulator.Part.content()` keeps producing a `ToolCall` for the unfinished part (so UIs can render it) but the index is recorded as incomplete; the synthetic `call_unknown` id is replaced by the real id when `ToolCallStart` was seen.

**Tests.** Interrupt mid-arguments: `partial().incompleteParts()` names the tool part; re-encoding the partial omits it.

### S-04 Cache-write classes, truthful absent counters

**API.**

```java
// metadata/Usage.java
public OptionalLong cacheWrite();                        // unchanged: sum of the classes when any is known
public Map<CacheRetention, Long> cacheWrites();          // SHORT → 5m, LONG → 1h; empty when the wire reports one undifferentiated number
public Builder cacheWrite(CacheRetention retention, long tokens);

// model/Prices.java
public Optional<BigDecimal> cacheWriteLongPerMillion();
public Builder cacheWriteLong(BigDecimal perMillion);    // cost() prices LONG writes with it, else falls back to cacheWrite
```

**Codec rules.** In `MessagesCodec.usage`: map `cache_creation.ephemeral_5m_input_tokens` → `SHORT`, `ephemeral_1h_input_tokens` → `LONG`; when only `cache_creation_input_tokens` exists, set the undifferentiated `cacheWrite`. Replace `orElse(0)` with `ifPresent` for `cache_read_input_tokens` and `cache_creation_input_tokens`. Audit `ResponsesCodec.usage` (`cached_tokens`, `cache_write_tokens` at lines 296-298) and `GenerateContentCodec` the same way: a counter the wire omitted stays absent. `Usage.totalInput()` keeps requiring all three buckets, so callers see `empty()` rather than a wrong total.

**Tests.** Recorded Anthropic frames with both TTL classes; a gateway reply without cache fields yields `cacheRead().isEmpty()`.

### S-05 Per-breakpoint cache retention

**API.**

```java
// chat/CacheBreakpoint.java
public record CacheBreakpoint(int index, Optional<CacheRetention> retention) { }

// chat/Conversation.java
public List<CacheBreakpoint> cacheBreakpointsWithRetention();
public List<Integer> cacheBreakpoints();                 // unchanged view
public Builder cacheBreakpoint(CacheRetention retention); // alongside the existing cacheBreakpoint()
```

`MessagesCodec.cacheMarkers` emits `ttl` per marker, respecting the Anthropic rule that longer TTLs precede shorter ones (validate in the encoder; `adapt` on violation). Other codecs ignore per-marker retention and report `cache_hint_ignored` once (S-08 decides whether that is fatal). `ConversationJson` gains the optional retention field (format version bump, older forms still read).

### S-06 Protocol feature descriptor and optional probes

**Problem.** Gaps 7, 10. Hosts need facts the catalog does not hold, and the SDK's own compat objects already know them.

**API.**

```java
// spi/protocol/ApiFeatures.java (value type, built by each WireApi from its ApiCompat)
public record ApiFeatures(
        String api,
        OutputCap outputCap,                     // ENFORCED | UNSUPPORTED | UNKNOWN  (Codex: UNSUPPORTED)
        int outputCapMinimum,                    // Responses: 16
        CacheMode cache,                         // NONE | AUTOMATIC | EXPLICIT_MARKERS | NAMED_RESOURCE
        int maxCacheMarkers,                     // Anthropic: 4
        Set<CacheRetention> retentions,
        boolean streamingRequired, boolean streamingSupported,
        boolean parallelToolCallsControllable,
        Set<String> schemaDialects,              // e.g. "json-schema-2020-12", "openai-strict"
        boolean strictSchemas,
        Set<String> reportedUsageFields,         // e.g. "input", "cache_read", "cache_write_5m", "cache_write_1h", "output", "reasoning"
        boolean usageMayBePartial,               // true when usage can arrive before the final frame
        boolean nativeReasoningReplay, boolean continuation, boolean hostedTools) { }

// spi/protocol/WireApi.java
default ApiFeatures features(Model model, Optional<ApiCompat> compat) { ... conservative defaults ... }

// Llm.java
ApiFeatures features(Model model);               // resolved for the provider/model/compat that a call would use
```

**Probes (opt-in, billable).** Extend `ConnectionTest.Builder` with `toolRoundTrip()`, `cacheRoundTrip()`, `usageFields()`; results become `ConnectionReport.Step`s of new kinds `TOOLS`, `CACHE`, `USAGE`. A gateway is thereby *probed*, not inferred, which is exactly what agent hosts require of themselves.

**Tests.** Each bundled `WireApi` returns its features; Codex reports `UNSUPPORTED` output cap; probes run against `FakeServer`.

### S-07 Prepare once, then execute

**Problem.** Gap 9.

**API.**

```java
// diagnostics/PreparedCall.java (experimental)
public interface PreparedCall {
    PreparedRequest request();                   // body, uri, headers with credential placeholders, warnings, notes
    ChatOptions effectiveOptions();              // after Resolver: clamped maxTokens, nearest reasoning level, …
    Conversation effectiveConversation();        // after Handoff
    boolean streaming();
    String digest();                             // stable over (api, model, effective options, effective conversation)
}

// Llm.java
PreparedCall prepare(Model model, Conversation conversation, ChatOptions options, boolean streaming);
LlmCall start(PreparedCall prepared);            // executes exactly what was prepared; only credentials are resolved at dispatch
AssistantMessage complete(PreparedCall prepared);
```

`preview()` becomes `prepare(..., false).request()`. `Engine.Prepared` already holds everything needed; this exposes it behind an interface without exporting internals.

**Tests.** Prepared digest is stable across two preparations of equal inputs; `start(prepared)` sends the previewed body byte-for-byte (compare against `FakeServer.requests()`).

### S-08 `strict` covers billing- and limit-changing adaptations

Reclassify as `adapt` (fatal under `strict`): the thinking `max_tokens` raise (`MessagesCodec.java:155-160`), dropping cache markers beyond the endpoint maximum, and a `LONG` retention fallback (`cache_hint_ignored`). Add a finer control so hosts choose:

```java
// chat/options/ChatOptions.Builder
public Builder strictCodes(Set<String> warningCodes);    // these warning codes fail the call even without strict()
```

and expose the effective output limit in `PreparedRequest`/`PreparedCall.effectiveOptions().maxTokens()`.

**Tests.** Strict call with budget thinking and a small `maxTokens` fails with `UNSUPPORTED_FEATURE`; non-strict keeps warning.

### S-09 Token counting

```java
// metadata/TokenCount.java
public record TokenCount(long inputTokens, boolean exact, String method, OptionalLong marginTokens) { }
// method: "provider-endpoint" | "local-tokenizer" | "estimate"

// spi/catalog or spi/protocol: Tokenizer SPI
public interface Tokenizer { boolean supports(Model model); long count(String text); }

// Llm.java
TokenCount countTokens(PreparedCall prepared);   // exact when the API has a counting endpoint (Anthropic count_tokens, Gemini countTokens, OpenAI input_tokens), else local tokenizer, else estimate with a declared margin
```

Counting operates on the **prepared** wire body (S-07), so tools, system text and reasoning items are counted in their encoded form. A host may register a `Tokenizer` (for example jtokkit) through `Llm.Builder.tokenizer(...)`. The existing `Resolver.estimateTokens` remains the fallback and is reported as `"estimate"`, never as exact.

### S-10 `ToolResult` factory

```java
public static ToolResult of(String callId, String toolName, List<Content> parts, boolean error);
```

Gap 15. Also lets `error` results carry non-text parts.

### S-11 Attempt ledger and cancellation facts

```java
// metadata/Attempt.java
public record Attempt(int index, Instant startedAt, Duration duration, @Nullable Integer httpStatus,
                      @Nullable ErrorCode error, boolean sent, boolean outcomeUnknown, Usage usage) { }
// ResponseInfo.attemptsDetail(), RequestEvent.Finished.attemptsDetail(), CallOutcome.attempts()
```

`Call` already loops attempts; record each. `CallOutcome.Cancellation` distinguishes "before send" from "after send" (the latter may be billed). This is the truthful answer to "was the first attempt free?" without a second retry engine.

### S-12 History policy and structured issues

```java
// chat/options/HistoryPolicy.java
public enum HistoryPolicy { SAME_ORIGIN_REQUIRED, REJECT_LOSSY, ALLOW_ADAPTATION }   // default ALLOW_ADAPTATION (today's behaviour)

// ChatOptions.Builder.historyPolicy(HistoryPolicy)
// metadata/Warning.java gains optional location: message index and content index of the affected part
// Llm.check(Model, Conversation, ChatOptions) → List<Warning>   // Handoff dry run: what would be dropped or converted
```

`Handoff` already computes every conversion; this makes it queryable and enforceable. The adapter-side origin check (companion doc, G-03) remains valid until this lands.

### S-13 Replay-fidelity audit and archive export

Not an API change in itself. Document per codec which native fields are retained in `Content` (Responses drops the function item `id`, keeps `call_id`; reasoning `status` is stripped; Gemini thought signatures live in `Reasoning.providerData`). Add a small export that a host can archive next to its own journal:

```java
// chat/AssistantMessage.java
public JsonObject toJson();                      // exists; ensure it includes usage (with raw) and info when present, versioned "ai-gate.reply/1"
public static AssistantMessage fromJson(JsonObject json);
```

Acceptance: a recorded Anthropic thinking+tool reply and a Responses encrypted-reasoning reply survive export/import and re-encode to the same wire body.

### S-14 Continuation and compaction (later)

```java
// chat/Continuation.java
public record Continuation(ModelRef model, String api, String opaqueId, Optional<Instant> expiresAt, OptionalLong effectiveHistoryTokens) { }
// AssistantMessage.continuation(): Optional<Continuation>
// ChatOptions.Builder.continueFrom(Continuation): the codec sends only messages after the continued reply
// ErrorCode.CONTINUATION_EXPIRED
// later: Llm.compact(Model, Conversation, ChatOptions) → Continuation (provider compaction APIs)
```

Fixes gap 11 properly instead of leaving `previous_response_id` as a raw option.

### S-15 Publish the artifact

Add `maven-publish` (group `net.ai.gate`, artifact `ai-gate`, sources and javadoc jars already configured), a `version` sourced from `gradle.properties`, and a CHANGELOG entry per experimental API promotion. Publish to `mavenLocal()` for joint development and to the chosen repository for releases. Keep `0.x` semantics: experimental APIs may change at minor versions.

### S-16 Optional `ai-gate-kotlin` artifact

A separate Gradle module in the same build: `suspend fun Llm.completeSuspending(...)`, `fun Llm.events(...): Flow<ChatEvent>`, `LlmCall.awaitReply()`/`awaitOutcome()` that never cancel `outcome()`. The core artifact stays JDK-only.

### S-17 UI-facing descriptors and validation

The SDK already offers the building blocks a settings UI needs: `FieldDescriptor` (`config/FieldDescriptor.java`), `ApiKeyAuth.fields()`, `Model.parameters()` (`model/Model.java:73-90`), `ChatOptions.set(key, raw)` with problems collected at `build()`, `ChatOptions.toJson/fromJson`, `ProvidersConfig.read/write`, `Auth.status/methods/login(ui)/logout`, `AuthInteraction` with `AuthPrompt.{Text,SecretText,Select,Code}`, `RedirectInteraction`, `Llm.test()` → staged `ConnectionReport`, `LlmListener` events. Fill the remaining gaps so a UI never has to know codec internals:

```java
// Provider.java
public List<FieldDescriptor> fields();                   // baseUrl, headers, preset, compat flags (from ApiCompat.fields())
// spi/protocol/ApiCompat.java
default List<FieldDescriptor> fields() { return List.of(); }   // each compat implements: betaHeaders, cacheTtl, streamingOnly, maxOutputTokens, …
// spi/protocol/ProviderOptions.java
default List<FieldDescriptor> fields() { return List.of(); }   // e.g. OpenAiResponsesOptions: serviceTier, reasoningSummary, store, …
// ChatOptions.java
public static List<FieldDescriptor> fields(Model model);       // the union Model.parameters() + runtime options (timeout, retry, strict, sessionId, parallelToolCalls, reasoningHandoff)
// providers/ProvidersConfig.java
public static List<Problem> validate(String json, List<Provider> presets);   // record Problem(String path, String message)
// diagnostics: ConnectionReport.Step already carries kind/status/latency/message — sufficient for progress UIs
```

Also add `LlmEvent` variants a UI can subscribe to without owning the stream: `RequestEvent.Progress(requestId, outputTokensSoFar, textChars)` emitted at a bounded rate from the stream loop (content-free, like every other event). This is the general form of "show the model typing" for hosts whose transport layer, not the UI, owns the `ChatStream`.

---

## 4. Implementation plan

Order follows dependencies and removes the highest-risk workarounds first. Each step is independently releasable.

| Step | Changes | Depends on | Exit criteria |
|---|---|---|---|
| 1 | S-15 publish; S-10 factory | – | `./gradlew publishToMavenLocal` produces `net.ai.gate:ai-gate:0.1.0-SNAPSHOT`; consumer test compiles against it. |
| 2 | S-01 usage updates; S-03 incomplete parts | – | Interrupted-stream fixtures (Anthropic, Completions) retain usage; partial replies expose incomplete indices; `result()` still equals `complete()`. |
| 3 | S-04 cache classes and absent counters | – | Anthropic 5m/1h fixture; gateway-without-cache-fields fixture; `Prices.cost()` prices LONG writes. |
| 4 | S-02 call handle; S-11 attempts | 2 | Cancel-before-send, cancel-during-body, executor-rejection and success tests settle `outcome()` exactly once; `completeAsync` unchanged. |
| 5 | S-08 strict coverage; S-07 prepare once | – | Strict rejects `option_adapted` on `max_tokens`; prepared digest stable; `start(prepared)` sends the previewed body. |
| 6 | S-06 features and probes; S-05 per-breakpoint retention | 5 | Every bundled API returns `ApiFeatures`; Codex reports `UNSUPPORTED` cap; probes pass on `FakeServer`; Anthropic emits per-marker `ttl`. |
| 7 | S-17 UI descriptors; S-16 Kotlin artifact | 4 | A sample settings form renders every provider/model/option field from descriptors alone; Kotlin wrappers preserve `outcome()`. |
| 8 | S-09 token counting | 5 | Anthropic `count_tokens` fixture returns `exact = true`; fallback returns `"estimate"` with margin. |
| 9 | S-12 history policy; S-13 audit/export | – | `check()` lists conversions; export/import round-trips recorded replies. |
| 10 | S-14 continuation | 6 | Responses `previous_response_id` sends only the suffix; expired id maps to `CONTINUATION_EXPIRED`. |

**Compatibility rules per step:** no removed public method; new record components only on new records; `ConversationJson`/`ChatOptionsJson` version fields bumped when a field is added; `@ApiStatus.Experimental` on S-02, S-06, S-07, S-09, S-12, S-14 until two consumers use them.

**Files touched (main):** `Llm.java`, `chat/Conversation.java`, `chat/AssistantMessage.java`, `chat/stream/ChatEvent.java`, `chat/options/ChatOptions.java`, `chat/content/ToolResult.java`, `metadata/Usage.java`, `metadata/ResponseInfo.java`, `model/Prices.java`, `event/RequestEvent.java`, `diagnostics/*`, `spi/protocol/WireApi.java`, `spi/protocol/ApiCompat.java`, `internal/core/{Engine,Call,Accumulator,DefaultChatStream,DefaultLlm,Resolver,Handoff,Notes}.java`, `internal/serialization/*Json.java`, `vendors/*/internal/*Codec.java`, `providers/ProvidersConfig.java`, `build.gradle.kts`.

---

## 5. What stays out of the SDK

The coding loop, tool execution and masks, context layout and eviction, admission policy, dated price tables and campaign budgets, evidence journals, permission authority. The SDK "transports calls; it never executes them". Every item above is a transport fact (what was sent, what came back, what it cost, what the endpoint can do) or a configuration/diagnostic surface for hosts and UIs.

---

## 6. UI considerations (for the SDK)

A UI built over both libraries will, on the SDK side: list presets and configured providers (`Providers.presets()`, `ProvidersConfig`), edit endpoints and compat flags (S-17 descriptors), manage credentials (`Auth`, `CredentialStore`, `AuthInteraction` implemented by the UI: prompts become dialogs, `notify` becomes a toast, `RedirectInteraction` opens the browser), pick models (`ModelCatalog.all(provider)`, `Model.parameters()`), run diagnostics (`Llm.test` steps as a checklist), show live progress (`ChatStream` events when the UI owns the stream, `RequestEvent.Progress` when the host does), and show the bill (`Usage` with S-04 classes, `Cost`, `CallOutcome`). Everything is serialisable (`toJson`) so settings can be persisted and diffed. No secrets ever appear in `ProvidersConfig` or in events; they stay in the `CredentialStore`, which a desktop UI can back with the OS keychain through the `CredentialStore` SPI.
