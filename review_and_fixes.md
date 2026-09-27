# AI Gate prototype review and implementation plan

Date: 2026-09-27  
Status: review complete; changes below are proposed, not implemented  
Target: the working tree under `llm/`, including untracked sources  
Deliverable: this document; SDK sources and build configuration remain unchanged

## 1. Conclusion and scope

**Keep the existing facade, immutable domain model, explicit provider SPI, and single Gradle module. Reorganize packages by responsibility and repair core contracts before implementing real vendor protocols.**

The prototype is useful and builds successfully, but it is not yet a thoroughly verified architectural baseline. Its most important problems are behavioral: response-cache isolation, credentials used by catalog views, cancellation after response headers, incomplete deadline coverage, and unbounded stream buffers. Moving classes alone will not fix them.

The requested hierarchy is justified: the root package contains 61 top-level type/implementation files, while `spi` contains 21. Exceptions, tools, options, metadata, and lifecycle concepts need coherent homes. Vendor families should move under `net.ai.gate.vendors`, and OAuth under `net.ai.gate.auth.oauth`.

### 1.1 Requirements and precedence

1. The original prompt, preserved in the appendix, requests a deep review and actionable document, with code changes deferred. This review fulfills that scope.
2. [progress_and_session_info.md](progress_and_session_info.md) supplies the initial implementation task: Java 26, Kotlin usability, `net.ai.gate:ai-gate`, one Gradle module, standard source directories, and explicit vendor stubs.
3. [docs/proposals/final-architecture.md](docs/proposals/final-architecture.md) supplies intended behavior, especially sections 7–14, 19, and 20.
4. Code and tests establish what exists; a passing suite does not establish every documented guarantee.

The older architecture proposes several artifacts and delivery slices. The later implementation task explicitly chose one module. Retain that choice. This prototype includes parts of later roadmap slices; already exposed generic behavior must be truthful even while vendor implementations remain deferred.

### 1.2 Preserve

- `Llm` as the consumer entry point, blocking calls first, optional async calls.
- Distinct responsibilities for `Provider`, `Model`, `Conversation`, and structured replies.
- Copy-based conversation adaptation and host-owned tool execution.
- `WireApi` as the codec boundary; runtime ownership of transport, auth, retries, and lifecycle.
- Borrowed ownership for injected transports, executors, stores, and caches.
- Typed provider options, unknown content/error variants, immutable JSON, and fake-backed tests.
- One artifact and named module `net.ai.gate`; no new managers, generic pipeline framework, or interface for every class.

### 1.3 Priorities and evidence

- **P1:** correctness, isolation, resource lifetime, or misleading behavior that blocks a dependable generic runtime.
- **P2:** structure, completeness, or verification work needed to call the prototype phase complete.
- **Executed:** reproduced against the built SDK using local fixtures.
- **Inspected:** established from source; its proposed regression test has not been added or run.
- **Deferred:** outside this phase; retain explicit, testable unsupported behavior.

All findings describe proposed work. None is resolved merely because the current suite passes.

## 2. Verification performed

### 2.1 Baseline

From the repository root:

```powershell
$env:JAVA_HOME = (Resolve-Path 'tools/jdk-26.0.2.1+1').Path
& ./llm/gradlew.bat -p llm build --rerun-tasks --console=plain
& ./llm/gradlew.bat -p llm dependencies --configuration runtimeClasspath --console=plain
```

- Full `build --rerun-tasks`: **successful**, nine tasks executed, including Java/Kotlin compilation, tests, Javadoc, and jar/source/Javadoc artifacts. This was a forced rerun, not a `clean build`.
- **59 tests, zero failures and errors**, across ten classes. One test is a Kotlin consumer test; five are architecture rules.
- 212 Java sources including descriptors; 191 type/implementation files excluding package/module descriptors.
- Counts include root 61, `auth` 19, `spi` 21, and `internal.core` 17.
- Main `runtimeClasspath` includes `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` and its transitive `org.jetbrains:annotations:13.0`. Java-only main sources do not currently imply an empty external runtime dependency graph.

Actual compiler flags are `-Xlint:all,-processing,-serial -Werror`. Javadoc uses `Xdoclint:all,-missing`; passing it does not prove that all public contracts are documented.

### 2.2 Executed local probes

Temporary Java source-launcher programs used the built jar and synthetic data outside the repository. They contacted no vendor endpoints and changed no SDK source. Convert these recipes to maintained regressions during implementation.

| Setup | Observed result | Finding |
|---|---|---|
| Two `withCredentials(CredentialStore.inMemory())` views, same prompt, shared response cache | Second view receives first view's response with `fromCache=true` | F01 |
| Same request with `X-Tenant: A`, then `X-Tenant: B` | Second call receives first cached response | F01 |
| Already-cancelled token and matching cache entry | `complete()` returns cached answer | F03 |
| Non-streamed body blocked after headers; cancel token | Body stays open; releasing fixture produces successful completion | F03 |
| Error body blocked after headers; total timeout 50 ms; inspect after 200 ms | Body stays open, call pending; after release, `InvalidRequestException` instead of timeout | F04 |
| Root/view stores have different dummy keys; refresh catalog through view | Listing request uses root credential | F02 |
| Runtime connect/idle 2 s / 7 s; call sets only total 30 s | Effective connect/idle become 10 s / 5 min | F06 |
| Runtime `strict()`; call `set("strict", "false")` | Effective strict remains true | F06 |
| Two file-store instances for one path; first holds update callback open | Second update throws `OverlappingFileLockException` | F08 |
| Preview has `Cookie` header with synthetic credential value | `toCurl()` exposes that value | F09 |
| Provider JSON has numeric `providers` and unknown root member | Reader returns empty list without error | F11 |
| `Json.valueOf(Conversation.of("hi"))` | `IllegalArgumentException`: no canonical form | F11 |
| Origin-less `Content.fileRef(...)` supplied to fake provider | Preview sendable; origin cannot be checked from value | F12 |
| Two iterators from one `textDeltas()` result | Both accepted | F05 |
| `Json.parse("01")` | Accepted and written back as invalid JSON `01` | F13 |

The file-reference probe demonstrates a missing representation/validation boundary, not a real disclosure. File-lock behavior was exercised in one Windows JVM; cross-process/POSIX behavior remains unverified. A small Java 26 compilation/loading probe also confirmed that sealed subtypes in different packages of a compiled named module can be consumed from the classpath; test the actual migrated SDK separately.

No real vendor codec, OAuth network flow, custom TLS, load benchmark, or exhaustive race stress test was verified. Inspection of those areas establishes scope and boundaries only.

## 3. Proposed package architecture

### 3.1 Decision and alternatives

**Choose capability packages inside the existing module.** Hierarchy should explain ownership, rather than maximize depth.

| Shape | Benefit | Decision |
|---|---|---|
| Move only exceptions/vendors | Least import churn | Leaves most unrelated root concepts crowded together |
| Generic `api/model/service/impl` layers or one artifact per feature | Regular-looking tree | Splits cohesive concepts and adds build/dependency work without a current need |
| Capability packages with explicit SPI/internal boundaries | Discoverable ownership; matches requested organization | Chosen; requires one deliberate import/module migration |

Target at most 20 top-level type files per package, excluding descriptors and nested types. This is a navigation budget, not a reason to split cohesive families arbitrarily. The proposed map stays below it. Keep content/event variants and builders nested.

### 3.2 Public tree

```text
net.ai.gate
  Llm
  chat/                         conversation and message variants
    content/                    content parts, tool calls and results
    tool/                       definitions and selection policy
    stream/                     chat stream and events
    options/                    generation/output options
  model/                        identity, capabilities, modalities, reasoning, prices
  metadata/response/            usage, cost, response identifiers and rate limits
  catalog/                      catalog API, freshness policy and feed entry points
  providers/                    Provider, preset index and provider configuration
  config/                       execution policies, HTTP settings and form descriptors
  cache/                        response cache and cache policies
  diagnostics/                  preview, connection reports, warnings, wire-log policy
  lifecycle/                    cancellation and registrations
  error/                        exception family and error codes
  auth/                         facade, credentials, stores and key strategies
    oauth/                      OAuth configuration, credential and strategy
    interaction/                prompts, notices and host interactions
  event/                        runtime observation events
  json/                         immutable JSON API and mapper contract
  spi/
    protocol/                   codecs and contexts
    http/                       transport, calls, replies and interceptors
    catalog/                    model/feed fetching contracts
    provider/                   bundles and typed provider-only APIs
  vendors/
    openai/internal/            public family above; hidden codecs/bundle here
    anthropic/internal/
    google/internal/
    ... (other vendors)
  testing/                      fake provider and helpers
  internal/                     unexported runtime implementation
```

`metadata.response` groups facts about a response. Keep catalog provenance with `Model`, auth status with `auth`, and avoid a general-purpose `info` package.

### 3.3 Complete root-package move map

All names currently reside directly in `net.ai.gate`. This assigns all 61 files exactly once.

| Destination suffix | Types |
|---|---|
| root | `Llm` |
| `chat` | `Conversation`, `Message`, `UserMessage`, `AssistantMessage`, `ToolResultMessage`, `StopReason` |
| `chat.content` | `Content`, `ToolCall`, `ToolResult` |
| `chat.tool` | `Tool`, `FunctionTool`, `ProviderTool`, `ToolChoice` |
| `chat.stream` | `ChatStream`, `ChatEvent` |
| `chat.options` | `ChatOptions`, `OutputFormat`, `ReasoningHandoff` |
| `model` | `Model`, `ModelRef`, `Capability`, `Capabilities`, `Modality`, `SupportLevel`, `ReasoningLevel`, `Prices` |
| `metadata.response` | `Usage`, `Cost`, `ResponseInfo`, `RateLimits` |
| `catalog` | `ModelCatalog`, `CatalogOptions`, `RefreshReport` |
| `providers` | `Provider` |
| `config` | `HttpOptions`, `TimeoutPolicy`, `RetryPolicy`, `FieldDescriptor` |
| `cache` | `ResponseCache`, `CacheMode`, `CacheRetention` |
| `diagnostics` | `PreparedRequest`, `ConnectionTest`, `ConnectionReport`, `Warning`, `WireLog` |
| `lifecycle` | `CancelToken`, `Registration` |
| `error` | `ErrorCode`, `LlmException`, `AuthenticationException`, `InvalidRequestException`, `InvalidResponseException`, `ProviderException`, `RateLimitedException`, `RequestCancelledException`, `RequestTimeoutException`, `TransportException` |
| `spi.http` | `WireInterceptor` |
| `internal.validation` | `Checks`, with access changes below |

`ToolCall`/`ToolResult` live with content because they are content variants; `chat.tool` owns definitions. `WireInterceptor` is an execution extension and belongs with the HTTP SPI.

### 3.4 Existing package moves

| Current members | Destination under `net.ai.gate` |
|---|---|
| `openai..`, including internals | `vendors.openai..` |
| `anthropic..`, including internals | `vendors.anthropic..` |
| `google..`, including internals | `vendors.google..` |
| `auth.OAuthAuth`, `OAuthConfig`, `OAuthCredential` | `auth.oauth` |
| `auth.AuthInteraction`, `AuthPrompt`, `AuthNotice`, `RedirectInteraction` | `auth.interaction` |
| Remaining 12 `auth` types | Keep in `auth` |
| `spi.WireApi`, `ApiRequest`, `EncodeContext`, `DecodeContext`, `StreamDecoder`, `Frame`, `StreamFormat`, `ApiCompat`, `ProviderOptions` | `spi.protocol` |
| `spi.HttpTransport`, `HttpCall`, `HttpReply`, `TransportOptions` | `spi.http` |
| `spi.ModelSource`, `ProviderHttp`, `CatalogFeed`, `FeedHttp` | `spi.catalog` |
| `spi.ProviderBundle`, `ProviderApi`, `ProviderApiContext`, `RawApi` | `spi.provider` |
| `providers.Providers`, `ProvidersConfig` | Keep; `Provider` joins them |
| `catalog.ModelsDevFeed` | Keep as public feed entry point |
| `catalog.BundledCatalog` | `catalog.internal.BundledCatalog`; service implementation |
| `event`, `json`, `testing` | Keep; update imports |

The bundled resource can remain at `net/ai/gate/catalog/models.json`; change the loader to an absolute resource path when moving `BundledCatalog`. Move resources only with every lookup/fixture updated.

### 3.5 Internal ownership

Keep `internal.core` initially. Its 17 files already have focused collaborators: `DefaultLlm`, `Core`, `Engine`, `Call`, `Resolver`, `Handoff`, `CodecContext`, `Notes`, `Accumulator`, `DefaultChatStream`, `Watchdog`, `EventHub`, `Jfr`, `HttpErrors`, `Redaction`, `ConnectionTester`, and `LlmConfig`. Splitting each into a package would widen access without fixing behavior.

Make these focused changes:

- `internal.auth.oauth`: `StandardOAuth`; generic refresh coordination when F07 is implemented. Keep key resolution in `internal.auth`.
- `internal.auth.store`: `MemoryStore`, `FileStore`, `ScopedStore`, `CredentialJson`.
- `internal.auth.interaction`: `ConsoleInteraction`, `WebInteraction`.
- Keep `internal.cache`, `internal.catalog`, `internal.http`.
- Keep generic JSON parsing/writing/record binding in `internal.json`; move domain serialization to `internal.serialization` with F11/F13. Do not claim JSON independence while its implementation imports `Model`.

If lifecycle work materially grows `internal.core`, reconsider an `internal.execution` boundary with a small bridge. Do not combine that speculative split with mechanical public-package moves.

### 3.6 Dependency/access rules and migration hazards

1. Vendors/testing use public domain types and SPIs, never core internals or another vendor's internals.
2. Runtime internals do not import concrete vendors; discovery uses `ProviderBundle`.
3. `Providers` and `ProvidersConfig` are composition entry points and may reference bundled vendor factories. That exception does not apply to the `Provider` value type. Test by class, not a blanket rule on `providers`.
4. Generic JSON implementation must not depend on domain models. Domain serializers may use JSON. Public factories may delegate to their own hidden implementation.
5. Observation events expose immutable facts without secrets, wire bodies, or concrete vendor types. Replace the current root-only event dependency rule with the new domain packages.
6. Domain references such as exceptions carrying partial replies create real mutual dependencies. Do not force a global package-cycle ban that invents abstractions; enforce the important runtime/vendor/JSON boundaries.

Migration must include:

- `Checks` is package-private today. Keep it unexported in `internal.validation`, exposing only needed static methods across implementation packages; keep caller-specific validation with its type.
- `Provider` calls package-private `Model.rekeyed`. Replace it with a documented immutable `Model.withProviderId(...)` copy operation or a focused internal copy bridge. Do not indiscriminately make members public.
- Update sealed `permits` clauses, imports, Javadocs, test packages, and package-level nullness annotations. Compile OAuth's cross-package sealed credential through the named module; verify actual classpath and module-path consumers.
- Update `module-info.java` exports, `uses`, and `provides`, and both the name/content of `META-INF/services/net.ai.gate.spi.ProviderBundle` when the SPI moves.
- Keep every `internal` package unexported. Public visibility for service loading or cross-package calls does not make an implementation supported API.
- Update resources, Gradle/test imports, examples, architecture rules, and README together.
- Public FQN changes break source/binary compatibility. Treat this as a draft migration; avoid duplicate wrapper hierarchies unless a real published/downstream obligation is found. Record the move map and establish the API baseline after the intentional break.

### 3.7 Proposed caller experience

These imports are **proposed**; the operation shape already exists. Compile this as a consumer fixture after migration:

```java
import net.ai.gate.Llm;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.testing.FakeProvider;

void main() {
    var fake = FakeProvider.create().reply("Hello").reply("Streamed hello");
    var cancel = CancelToken.create();
    try (var llm = Llm.builder().provider(fake.provider())
            .catalog(c -> c.offline()).build()) {
        var model = llm.model("fake", "fake");
        var reply = llm.complete(model, "Say hello");
        var options = ChatOptions.builder().cancel(cancel)
                .timeouts(t -> t.total(java.time.Duration.ofSeconds(30))).build();
        try (var stream = llm.stream(model, Conversation.of("Say hello"), options)) {
            for (var text : stream.textDeltas()) System.out.print(text);
            var completed = stream.result();
        } catch (RequestCancelledException e) {
            // The host can retain e.partial(); the runtime remains usable.
        }
    }
}
```

Add the equivalent Kotlin `use` fixture, including typed options, failures, nullness, and external SPI implementation. Package changes should change imports without making callers assemble execution components.

## 4. Findings and required fixes

Source references are relative to `llm/src/main/java/net/ai/gate/` unless a full path is supplied. Symbols are durable anchors; line numbers refer to this reviewed tree.

### F01 — P1 — Cache identity does not isolate callers or effective requests

**Evidence:** Executed. `internal/core/Engine.java:187–198`, `internal/cache/Exchange.key`, `internal/auth/ScopedStore.java:36`.

`scopeOf()` returns the empty string for every unscoped store, and a prefix alone for scoped stores, without backing-store identity. Separate stores and equally named scopes on different stores collide. OAuth account identity is omitted. The key sees the codec's relative call before provider/call headers are assembled; changing `X-Tenant` has no effect. Auth-selected endpoints and interceptor edits can also change the effective request after key selection.

**Fix:** Define a non-secret cache namespace including store identity/scope and principal identity where known. Distinguish runtime isolation from explicitly configured stable cassette namespaces, so offline replay needs no credential resolution. Include normalized response-affecting provider/call headers, endpoint, API/revision, request mode, method, path, and body. Bypass caching when an interceptor/dynamic endpoint prevents reliable identity unless it supplies an explicit key contribution. Never use raw secrets as readable namespaces.

**Acceptance:** Different stores, same-named scopes on different stores, different accounts within one scope, and different tenant headers cannot share responses. Explicit offline cassettes still replay without fetching credentials.

### F02 — P1 — Catalog views use the root store for authenticated listings

**Evidence:** Executed. `internal/core/Core.java:92`, `DefaultLlm` constructor, `internal/catalog/CatalogView.refresh`, `CatalogService.refresh` at line 125.

The shared service closes over `providerHttp(p, credentials)` from the root runtime. A view supplies only its `configured` predicate. Its refresh therefore uses root credentials, and `liveData` keyed solely by provider ID shares account-specific availability. A configured view can also fail when only its own store has credentials.

**Fix:** Share public metadata, but bind authenticated listing execution and availability to the credential view. Pass its authenticated HTTP context and partition listing state by the identity rules from F01. Do not persist private listings as globally reusable availability.

**Acceptance:** Two views send their respective dummy credentials and see their own available models. An unconfigured root does not break a configured view. Public feed metadata remains shared.

### F03 — P1 — Cancellation is incomplete across call phases

**Evidence:** Executed for cache hits and non-streamed bodies; inspected for auth waits/token retention. `internal/core/Call.blocking`, `Engine.complete`, `DefaultLlm.completeAsync`, `CancelToken.child`.

The interrupt registration lasts only through `send()`. Body consumption after headers has no token hook; cancellation can be ignored and completion reported as successful. Cache hits return without `checkActive()`. Auth resolves before the blocking wrapper and can continue after cancellation. Successful calls leave their parent-token links registered: those detach only when the child is cancelled, retaining children until the parent is cancelled or collected.

**Fix:** One call lifetime must own cancellation registration, active reply, and parent linkage through every phase and terminal path. Check cancellation before replay, before transmission after auth, and before success. Close the active body and detach parent links on all outcomes. Preserve external interruption; do not clear an unrelated interrupt merely because a token also became cancelled. Cancelling a refresh waiter must leave shared refresh work usable by others.

**Acceptance:** Cancel before start, on cache replay, during auth/send/body/stream/backoff. Verify async cancellation aborts work, emits one terminal event, preserves partial data where appropriate, releases registrations, and leaves the runtime usable. Use latches rather than timing guesses.

### F04 — P1 — Deadlines and exchange ownership end too early

**Evidence:** Executed for blocked error bodies; other paths inspected. `Call.send`, `Engine.exchange`, `Core.fetchFeed`, `Core.close`, `internal/http/JdkHttpTransport`.

- Error bodies decode inside `Call.send()` without a body watchdog.
- Credential resolution/refresh can exceed the total deadline without an active guard.
- `Engine.exchange()` emits completion/untracks after headers, then returns a live reply. Raw APIs and listings consume it outside that lifetime.
- Feed body reads have no core deadline guard.
- JDK transport hardcodes default connect timeout and ignores `TransportOptions.connectTimeout()`.
- The five-second close budget applies only to executor termination, after transport close. The transport calls `client.close()` without a bounded shutdown strategy.

**Fix:** Carry one monotonic deadline from operation start through final consumption, auth, error decoding, and retries. A returned exchange reply must retain lifecycle ownership until consumed/closed; use an internal consume-within-lifetime operation where streaming is unnecessary. Apply provider defaults to provider-only operations. Honor connect policy deliberately; do not accept ineffective settings. Bound the entire owned-resource shutdown sequence and report unfinished cleanup.

**Acceptance:** Stall each phase, including error/raw/listing/feed bodies. All end within budget with typed failures; completion events do not precede body failure. Verify connect overrides, bounded close with open bodies, and borrowed resources staying open.

### F05 — P1 — Streams do not guarantee bounded memory or one consumer

**Evidence:** Executed for repeated text iterators; inspected for bounds/state races. `internal/http/FrameReader.readEvent`, `internal/core/Accumulator`, `DefaultChatStream`.

Framing caps each line at 8 MiB, but accumulates arbitrarily many `data:` lines into one event. Accumulation charges appended text only: authoritative `PartEnd` content, native/unknown payloads, and part counts bypass accounting. Cache recording retains every frame in an unbounded list. A decoder can return an arbitrary event batch to the pending queue.

`textDeltas()` takes a view once, but its returned `Iterable` creates unlimited iterators. Cross-thread `close()` uses `stepping` without one coherent state-ownership mechanism for `ended`, pending events, and terminal state. Source inspection does not establish the advertised thread-safety guarantee.

**Fix:** Bound whole frames/events, every retained content form, pending batches, and recorded exchanges. Document whether limits count bytes or characters. Stop recording or reject oversized recordings predictably without silently failing generation. Let one thread own stream state; cross-thread close requests cancellation rather than concurrently draining. Enforce one iterator for every view and safely publish final partial/failure snapshots.

**Acceptance:** Multiline events, many empty parts, large authoritative/native content, and long cached streams stay bounded. Test close before reading/during read/after terminal output and concurrent close/next. Iteration fails once, `result()` retains failure, `partial()` agrees, and bodies/watchdogs release. A second text iterator throws.

### F06 — P2 — Option inheritance and header precedence are incomplete

**Evidence:** Executed for timeouts/strict. `ChatOptions.java:122–123`, its builder, `Call.assemble`, `Provider` headers.

Nested policies materialize defaults in the call builder and replace inherited policy wholesale. Setting only total resets connect/idle. `set("strict", "false")` records no false, so inherited true persists. Empty stop lists cannot clear inherited stops. `reasoning(null)` means inherit during merge, despite documentation saying nothing is sent. Header maps merge case-sensitively, contrary to specified HTTP precedence.

**Fix:** Preserve unset versus explicit values through nested policy resolution. Keep a completed-value path for deliberate full replacement. Add explicit false/clear operations where needed; null must not mean both inherit and disable. Match headers case-insensitively and validate names/values locally. Document provider-option replacement by type.

**Acceptance:** Table-driven tests cover every field across runtime/provider/call scopes, including false/clear/empty and repeated builder edits. Header casing cannot produce duplicate logical values. Built snapshots remain independent.

### F07 — P1 — Generic OAuth coordination can refresh repeatedly

**Evidence:** Inspected. `internal/core/Call.java:142–165`, `internal/auth/AuthResolver.oauth`, `needsRefresh`, `key`.

After 401, `oauthRetried` remains true, so subsequent ordinary retries force refresh again. Concurrent 401 handling reads the currently stored credential rather than the credential actually rejected; a later waiter can refresh a token another call just refreshed. Refresh-failure state uses F01's insufficient scope identity. Storage errors during refresh can become `LOGIN_REQUIRED`, hiding their cause.

**Fix:** Track the rejected credential/version. Refresh once only if that version remains current, then resolve normally on later attempts. Key state by store/scope identity. Preserve storage/refresh/login-required distinctions. Emit refresh events only for actual refreshes, outside store locks. Specify interaction with the normal attempt budget.

**Acceptance:** Inject an in-process `OAuthAuth` double. Test concurrent 401s, `401 -> refresh -> 429 -> success`, rotation, failed refresh without environment fallback, and distinct stores. Count refreshes/requests exactly. Real OAuth endpoints remain deferred.

### F08 — P1 — File-store concurrency is per instance, not per file

**Evidence:** Executed. `internal/auth/FileStore.java:48`; `CredentialStore.file` contract.

Two instances synchronize on different objects and request overlapping JVM file locks. The second throws rather than waiting. `load()` ignores the schema marker. Owner-only permissions are applied only on POSIX; Windows files inherit directory ACLs.

**Fix:** Coordinate same-file access within the JVM using normalized file identity, retain the cross-process lock, and map storage/locking failures consistently. Validate schema, preserve atomic replacement and guaranteed temp cleanup. Define Windows ACL support or require/verify a private directory explicitly. Avoid blocking unrelated providers behind slow refreshes where narrower locking is feasible.

**Acceptance:** Two instances and two processes update without lost data or overlapping-lock failures; rotation survives reopen. Unsupported/corrupt schemas fail predictably. Verify Windows/POSIX permissions separately, including existing files. Waiting cancellation follows F03.

### F09 — P1 — Redaction is inconsistent and curl quoting is unsafe

**Evidence:** Executed for preview cookies; other sinks inspected. `Checks.PROTECTED_HEADERS`, `PreparedRequest.toCurl`, `internal/core/Redaction`, `HttpErrors.details`, `DefaultLlm.describe`, `Provider.Builder.build`.

Protection and redaction disagree: `Cookie` is accepted and exposed in preview/curl. Redaction is used by wire logging, but not consistently by previews/errors/descriptions. JSON error bodies/messages are retained unsanitized; the 4096-character limit covers only non-JSON fallback text. Provider URLs can contain user-info/query/fragment and are printed.

`toCurl()` embeds URI/header values in double-quoted shell text without escaping quotes, backticks, or command substitutions. Literal configuration can become executable shell syntax when a user runs it. No generated command was executed in this review.

**Fix:** Establish one protected/credential-header policy and apply it to all sinks. Reject secret-bearing configuration and unsuitable URL components; validate auth-selected endpoints before applying credentials. Support placeholders for custom strategies too. Bound/sanitize error bodies and messages while keeping structured codes. Define a shell grammar for curl output and separate intentional environment placeholders from literal arguments. Keep body logging opt-in with its content exposure documented.

**Acceptance:** Synthetic secrets in headers, URL components, nested errors, and auth failures never appear in ordinary diagnostics/previews/events/curl. Quotes, newlines, `$()`, backticks, and apostrophes remain literal in harmless argv-capture tests. Verify destination restrictions after interceptors and custom credential headers.

### F10 — P2 — Catalog merges lose field provenance and partial metadata

**Evidence:** Inspected. `internal/catalog/CatalogService.overlay`, `FRESHNESS`, snapshot load/save, `internal/json/ModelJson`.

A newer `Prices` object replaces the whole prior object even if it supplies one component; compat also replaces wholesale at this merge point. A merged entry gets one source/timestamp and is persisted, making inherited older fields appear as fresh as unrelated newer fields. Merged `CUSTOM` entries are excluded from persistence, potentially losing refreshed facts inherited into them. Manual refreshes bypass the background refresh guard.

**Fix:** Preserve source contributions or per-field provenance internally; merge structured metadata at the promised granularity. Keep host overrides separate from persistent feed facts. Define equal-timestamp precedence, serialize refresh publication, and preserve original freshness across restart. Clean temporary snapshot files on failure. Account listings remain separate under F02.

**Acceptance:** Input-price-only updates preserve other prices. Updating one field does not age another. Host overrides win without blocking persistence of feed facts. Restart yields equivalent data; concurrent refresh, source failure, and ties are deterministic.

### F11 — P2 — Portable formats are incomplete and malformed configuration is accepted

**Evidence:** Executed for missing history JSON/malformed provider envelope. `providers/ProvidersConfig`, `internal/json/RecordBinder`, `ModelJson`, `Conversation`, `ChatOptions`; architecture section 9.4.

Canonical `Conversation` and portable `ChatOptions` forms are absent. Provider defaults/compat are explicitly omitted; writing providers can silently discard these or other unrepresentable differences. Malformed/missing `providers` becomes an empty list. Unknown root members, duplicate IDs, and wrong-type model fields are insufficiently validated. Configuration does not load arbitrary classes, which should be preserved.

**Fix:** Define versioned domain schemas and focused serializers for history, portable options, and representable provider differences. Exclude callbacks, cancel objects, executors, payload hooks, and secrets. Preserve absence/clears, unknown content, and replay origin. Encode supported differences or reject unrepresentable configuration explicitly. Validate envelopes, duplicate IDs, types, and unknown fields with indexed paths. Keep `ai-gate.*` schema names unless a real compatibility obligation requires change; document divergence from older sketches.

**Acceptance:** Deterministic round trips retain tools/results, reasoning/signatures, unknown content, breakpoints, option presence, and supported provider differences. Negative fixtures identify invalid paths. Deserialization performs no file/network I/O; secrets and operational callbacks cannot be serialized.

### F12 — P2 — Opaque replay data lacks adequate origin information

**Evidence:** Executed representation probe; source inspected. `Content.Source.Ref`, `Content.fileRef`, `AssistantMessage`, `internal/core/Handoff.assistant`.

File refs carry only an ID. Reply origin has provider/model/API, without account or API revision. The core cannot enforce documented account scope or determine opaque replay validity after credential/revision changes. Tool-ID normalization does not check collisions. Removing turns drops explicit cache breakpoints rather than remapping them.

**Fix:** Add minimal non-secret origin metadata where validity requires it, using F01 identity semantics and F11 serialization. Define rejection or explicit host binding for imported origin-less opaque data. Check normalized ID uniqueness and result references. Remap valid breakpoints or emit a documented warning when they must be discarded.

**Acceptance:** Same-origin data survives replay/round-trip; another account/provider/revision cannot silently receive it. Switching back preserves source history. Test normalized-ID collisions, orphan/duplicate tool results, removed turns, and breakpoints. Real vendor transformations stay deferred.

### F13 — P2 — JSON strictness and domain independence need repair

**Evidence:** Executed for leading-zero numbers. `json/JsonNumber.of(String)`, `internal/json/JsonReader.number`, `RecordBinder`, `ModelJson`; current JSON architecture rule.

`JsonNumber.of("01")` accepts/preserves invalid JSON; parsing relies on it. Generic binding special-cases `Model`, so the rule checks direct public-package edges while implementation still depends on the domain. Model limits use `BigDecimal.longValue()`, silently truncating fractions. Schema/binder support has mismatches: character schemas are offered, but primitive `char` reaches numeric binding.

**Fix:** Validate complete JSON number grammar; keep best-effort partial parsing separate. Use exact integer conversion for integer fields. Separate domain serializers from generic JSON/record binding. Match every advertised schema type with binding tests and explicit rejection of unsupported generic shapes.

**Acceptance:** Reject malformed numbers/escapes, excessive depth, and malformed transport UTF-8; preserve valid lexical forms. Fractional/overflow model limits fail with paths. Generic JSON implementation imports no domain classes. Test record access from a named consumer module.

### F14 — P2 — Connection tests can report unverified credentials as authenticated

**Evidence:** Inspected. `internal/core/ConnectionTester.test`, `ConnectionTest.timeout`.

The network stage is unauthenticated. The auth stage only resolves local credentials, then reports `PASSED`; a wrong but present key can pass if no listing exists. A 401 from listing appears under `MODEL_ACCESS`. The configured timeout is not a shared overall budget.

**Fix:** Distinguish configured from verified credentials. Use a declared non-billable authenticated probe when available, otherwise report unverified/`NOT_SUPPORTED`. Classify rejected auth at the appropriate stage. Apply one deadline across all stages; keep inference opt-in and bypass response-cache replay for a requested live probe.

**Acceptance:** Wrong keys fail auth when verification exists and are never claimed verified otherwise. Later stages skip correctly. Test shared timeout and zero inference unless requested.

### F15 — P2 — FakeProvider is not a sufficient contract oracle

**Evidence:** Inspected. `testing/FakeWireApi.encode`/`encoded`, `FakeServer.requests`/`reply`, existing tests.

The fake associates requests through an unbounded body-keyed map. Equal concurrent bodies overwrite associated request/options; preview also fills the map. Encoded bodies omit several settings/content details, including tool schemas/descriptions and image bytes. Requests are deduplicated by object identity, so `requests()` cannot establish real send counts. Stream scripts generate happy terminal paths and cannot reproduce the major lifecycle failures without custom fixtures.

**Fix:** Retain the fake for consumer scenarios, and add small deterministic transport/codec fixtures for exact attempts, headers/bodies, stalled phases, partial output, malformed data, late errors, and premature EOF. Remove/bound hidden request retention and make unsupported fake encoding explicit. No real vendor protocol is needed.

**Acceptance:** Tests observe actual attempts/effective requests and cover F03–F05. Concurrent identical requests do not misattribute metadata. Preview retains no requests indefinitely. Cache identity tests use payload differences present on the wire.

### F16 — P2 — Build and consumer verification are below the declared baseline

**Evidence:** Executed dependency report; inspected build/tests/docs. `llm/build.gradle.kts`, `llm/gradle.properties`, `ArchitectureTest`, `KotlinUsageTest`, `llm/README.md`.

The Kotlin plugin adds a main runtime dependency despite Kotlin being test-only. There is no complete SPI contract kit, external named-module consumer fixture, compatibility baseline, or compilation coverage of all applicable usage examples. README leads with a real vendor operation that throws a stub exception. Some test names exceed their assertions: the virtual-thread async test checks text, and the redacted-description test supplies no secret.

**Fix:** Restrict Kotlin dependencies to consumer tests and verify the graph. Add classpath/module-path callers, discovery checks, and external providers using only exports. Translate applicable architecture examples to offline fixtures and list deferred examples. Strengthen hierarchy/internal-export/JSON implementation rules. Establish an API baseline after the intentional package break; compare previous binaries only when a previous artifact exists. Lead README with a runnable fake example and precise supported behavior.

**Acceptance:** `build` includes contract/consumer checks; Kotlin tests add no external main runtime dependency. Both discovery mechanisms work; examples run offline; named tests assert their names. Document JVM bootstrap requirements separately from compile-toolchain provisioning. Keep CI changes aligned with repository conventions.

## 5. Phase-one completeness matrix

| Requirement / area | Current state | Exit action |
|---|---|---|
| Java 26, one module, required coordinates | Present; build verified | Preserve; F16 dependency graph |
| Kotlin usability | One happy-path fixture | External consumer coverage, F16 |
| Hierarchy | Root/SPI overloaded | Section 3, no new artifacts |
| Configuration precedence and snapshots | Basic merge/snapshots exist | F06; local invalid-input checks |
| Portable history/options/config | Incomplete | F11/F13 |
| Sync/async execution | Happy paths pass | F03/F04/F15 |
| Streams, partials and cleanup | APIs present, incomplete guarantees | F03/F05/F15 |
| Retry/error behavior | Selected statuses/backoff tested | F04/F07; malformed retry hints, decoder failures, interruption, certainty |
| Credentials/stores/isolation | Basic status/scoping tests | F01/F02/F07/F08/F09 |
| Catalog freshness/availability | Basic merge/restart tests | F02/F10 |
| Response cache/offline replay | Basic memory replay | F01/F03/F05; directory/corrupt-entry fixtures |
| Handoff and origin | Selected transformations tested | F12 |
| JSON and records | Useful implementation, six tests | F13 |
| Preview/connection diagnostics | APIs present | F09/F14 |
| Events/JFR | Implemented, limited verification | One terminal outcome; actual attempts, partial metadata, tags |
| Extension boundaries | Five architecture rules | F15/F16 and section 3.6 |
| Honest status documentation | Explicit stubs, broad “implemented” claims | F16 and deferred ledger below |

### 5.1 Contract decisions to finish

These attach to existing findings; they do not require new product features.

- **Call context (F03/F04/F16):** architecture specifies `ScopedValue`; current code passes `Call` state directly and creates it after preparation. Add the promised bounded context or record a justified design amendment. Verify request ID/tags/deadline visibility without cross-call leakage. Avoid a new context manager just to match a sketch.
- **Failure facts (F03/F04/F05):** retain causes and codec partial facts. Stream/exception partial accessors should agree on marked terminal state. `Call.cancelled` currently defaults outcome certainty instead of distinguishing pre-send and post-send. Record possible remote execution independently of cancellation.
- **Validation (F06/F11):** include stale cache breakpoints after `Conversation.Builder.messages(...)`, nulls/blanks, duplicate IDs, URI host/user-info, and unsupported combinations. `@NullMarked` documents nullness; it does not enforce runtime validation.
- **Observability (F09/F16):** `ChatOptions.tags()` promises log/JFR tags, but the JFR event has no tags field. `describe()` stringifies a small subset of defaults and omits promised effective-policy details. Make output structured/truthful and keep secrets/content out of ordinary events.
- **Construction/discovery (F16):** `Core` instantiates all bundles and asks for catalog models even with preset discovery disabled; the bundled catalog reads its resource then. Define whether “build performs no I/O” means no network/credential resolution or also lazy resource loading. Enforce that meaning. Document local snapshot reads separately from network-free status checks.

### 5.2 Deferred implementation ledger

| Deferred work | Current boundary | Evidence still needed in this phase |
|---|---|---|
| OpenAI Chat Completions/Responses | `openai/internal/*Codec` | Factories/options compile; explicit unsupported calls; core tested with doubles |
| Anthropic Messages | `anthropic/internal/MessagesCodec` | Same; preserve intended mapping documentation |
| Google generateContent | `google/internal/GenerateContentCodec` | Same |
| OAuth HTTP flows, PKCE/device/refresh/revocation | `internal/auth/StandardOAuth`, `DefaultAuth.revoke` | Explicit stubs; generic orchestration tested with injected strategy |
| models.dev and catalog regeneration | `catalog/ModelsDevFeed`, future update pipeline | Clear stub; generic feeds/merge tested with synthetic data |
| Gemini cached contents | `google/internal/CachesClient` | Typed shape/lifetime contract and explicit unsupported behavior |
| Custom TLS/trust store/mTLS | `internal/http/JdkHttpTransport` | Validate config; fail explicitly at construction |
| Real prices, limits, endpoint/auth details | Bundled `models.json`, presets | Label illustrative data; no claim it is verified or suitable for billing |
| Coroutines/reactive/Jackson/framework/native SDK integrations | Future integrations | No dependency or empty scaffolding required now |

Provider defaults/compat serialization is generic configuration work (F11), although README groups it with later stubs. Its data representation does not require real protocols. Protocol fixtures, live tests, endpoint validation, and OAuth conformance belong to the later phase.

## 6. Ordered implementation work

Keep mechanical moves separate from behavior changes. Each completed step must leave a passing build. Regression tests may be developed failing first, but do not leave a committed step knowingly failing.

| Step | Work | Depends on | Completion evidence |
|---|---|---|---|
| T01 | Capture baseline and preserve local defect recipes as test specifications/fixtures | None | Current 59 tests accounted for; reproducible defect cases ready for fixes |
| T02 | Apply package/type/resource/service map; update imports/docs | T01 | Complete move inventory, no stale FQNs, classpath/module smoke checks |
| T03 | Repair cache/auth/catalog identity and isolation | T02 | F01/F02 regressions pass across stores/scopes/accounts/headers |
| T04 | Repair one call lifetime: cancel/deadline/body/exchange/close | T02 | F03/F04 pass on sync/async/stream/raw/listing/feed paths |
| T05 | Bound stream memory and fix state ownership | T04 | F05 size/race/partial/terminal checks |
| T06 | Fix refresh/store coordination and redaction | T03/T04 | F07/F08/F09 pass without real OAuth |
| T07 | Finish option presence, formats and catalog provenance | T02/T03 | F06/F10/F11/F13 merge/round-trip checks |
| T08 | Finish replay-origin and diagnostic contracts | T04/T07 | F12/F14 and section 5.1 decisions tested/documented |
| T09 | Finish contract kit, external callers, API baseline and docs | T03–T08 | F15/F16, architecture checks, final build/dependency report |

T02 is an intentional draft API break. T03/T04 both affect `Core`/`Engine`; coordinate edits. Do not rewrite the entire runtime or implement vendor codecs as a shortcut to testing generic contracts. Add a failing regression immediately before each behavioral fix and keep it in the resulting suite.

### 6.1 Required test families

Use existing JUnit conventions and small local fixtures; most cases need no new dependency.

- **Consumers:** Java/Kotlin, try-with-resources/`use`, typed output, provider extension, classpath/module path.
- **Configuration:** unset/set/clear across three scopes, case-insensitive headers, invalid input, snapshots, deterministic JSON.
- **Execution:** barriers around auth/send/headers/body/backoff; exact attempts, cause preservation, cancellation/timeout classification.
- **Streams:** split UTF-8, SSE/NDJSON, interleaving/replacements, late errors, EOF, all size limits, close races.
- **Auth/storage:** stores/scopes/accounts, concurrent refresh, rejected versions, fallback policy, file contention/platform permissions.
- **Cache/catalog:** identity matrix, effective headers, offline/directory replay, corruption, source age, restart, private availability.
- **Diagnostics:** synthetic secret sentinels, curl argv capture, truthful stages, no implicit inference.
- **Architecture:** actual dependency edges, hidden exports, service/resources, package counts, no vendor imports in generic execution.

Preserve meaningful existing tests during migration. Update assertions only for deliberate contract changes and explain why. Avoid tests that merely mirror renamed imports.

## 7. Phase-one exit criteria

- [ ] Section 3 hierarchy is implemented, all root types accounted for, vendors/OAuth grouped as requested.
- [ ] Every P1 finding has a regression and fix; generic failures are not relabeled vendor stubs.
- [ ] P2 tasks are complete, or an explicit revised requirement and practical limitation are recorded.
- [ ] Credentials/private availability do not cross stores/views/accounts via cache or catalog.
- [ ] Cancellation/deadlines cover every blocking phase, terminal events occur once, owned cleanup is bounded.
- [ ] Streams/cache recording have enforced memory limits; failure preserves usable partial output.
- [ ] Configuration/history round trips preserve semantics and reject unsupported input without silent loss.
- [ ] Unknown capabilities/usage/prices remain unknown; illustrative metadata is clearly labeled.
- [ ] Consumer/SPI fixtures run offline on Java/Kotlin and verify classpath/module-path boundaries.
- [ ] Main dependency graph matches the Java/JDK-only design, apart from explicitly accepted dependencies.
- [ ] Build, Javadoc, architecture, service/resource checks, and revised suite pass reproducibly.
- [ ] README/progress distinguish implemented contracts, deliberate stubs, and unverified behavior.

**Verdict:** proceed with focused core refactoring and contract repairs. Start the vendor implementation phase after this baseline meets its exit criteria.

## Appendix — Original request (historical)

source: '\review_goals.md'
