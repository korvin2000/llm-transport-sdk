# LLM Transport & Connection Layer — Baseline Requirements and Summary Information

**Purpose of this document:** a design baseline for a universal Java SDK/library that provides the *connection, authentication, discovery and transport layer* for talking to LLMs — public providers (OpenAI, Anthropic, Google, xAI, DeepSeek, Qwen/DashScope, Mistral, …), gateways/routers (OpenRouter, LiteLLM, Vercel AI Gateway, Azure OpenAI, AWS Bedrock, Google Vertex, GitHub Models/Copilot, …) and local inference servers (llama.cpp, Ollama, vLLM, LM Studio, …). It is meant to be read by humans and by LLMs that will later help design the public API, facade and configuration layer.

**Snapshot date:** 2026-09-26. Provider APIs change monthly. Items marked `[VERIFY]` were true at the time of writing (or reported by secondary sources) and must be re-checked against the provider's live documentation before implementation. Items marked `[POLICY]` are legal/ToS constraints rather than technical ones.

---

## 0. How to read this document

### 0.1 Conventions

- Requirement IDs: `PREFIX-n` (e.g. `AUTH-3`). Prefixes: `GEN` general, `ARCH` architecture, `CFG` configuration, `AUTH` authentication, `NET` connection/transport, `STRM` streaming, `RETRY` resilience, `DISC` discovery/metadata, `REQ` request model, `RSN` reasoning, `TOOL` tools, `SO` structured output, `MEDIA` files/media, `CACHE` prompt caching, `STATE` conversation state, `RSP` response model, `ERR` errors, `OBS` observability, `GW` gateway/proxy building, `TOK` token counting, `JAVA` Java-specific.
- Priority tags: `[MUST]` core for v1, `[SHOULD]` expected in a complete v1, `[MAY]` later/optional.
- "Dialect" = a concrete wire protocol (request/response JSON shapes + streaming framing + endpoint paths), e.g. *OpenAI Chat Completions*, *OpenAI Responses*, *Anthropic Messages*, *Gemini generateContent*, *Ollama native*.
- "Provider" = the organization/service behind an endpoint (OpenAI, DeepSeek, a self-hosted llama.cpp). One provider can expose several dialects; one dialect is spoken by many providers.
- "Endpoint" = a configured, reachable base URL + dialect + credentials + defaults (what the screenshot's *LLM Endpoint* node represents).

### 0.2 Document map

| Section | Content |
|---|---|
| 1 | Purpose, use cases, goals/non-goals |
| 2 | Domain model / glossary |
| 3 | Architecture & layering, extension points |
| 4 | Provider, gateway, local-server and standards landscape |
| 5 | Configuration layer |
| 6 | Authentication & credentials |
| 7 | Connection, transport, streaming, resilience, routing |
| 8 | Discovery, metadata, catalog, pricing, limits |
| 9 | Unified request model (messages, params, reasoning, tools, structured output, media, caching, state) |
| 10 | Unified response model, streaming events, usage/cost, errors |
| 11 | Per-dialect reference notes and quirks |
| 12 | Building proxies/gateways on top of the SDK |
| 13 | Observability, monitoring, statistics, recording |
| 14 | Token counting and context budgeting |
| 15 | Java SDK design considerations and facade sketch |
| 16 | Consolidated pitfalls checklist |
| 17 | Feature tiers / roadmap suggestion |
| 18 | Open design questions |
| A–D | Appendices: parameter mapping, endpoint tables, env vars, sources |

---

## 1. Purpose, scope, goals and non-goals

### 1.1 Vision

One Java library that lets any application **connect to any LLM endpoint** with a single mental model: *configure an endpoint → discover what it offers → send a unified request → receive a unified (streaming or complete) response with usage, cost and diagnostics* — while never hiding provider-specific power (raw passthrough, native parameters, native response fields) from users who need it.

### 1.2 Target use cases (drive the requirements)

1. **AI coding agent harnesses** (OpenCode, OpenClaw, Codex, Pi/pi-mono, Claude-Code-like tools): long agentic loops, tool calling, streaming, reasoning persistence across turns, prompt caching, cost tracking, provider switching mid-session, OAuth/subscription auth, resilience over hours-long runs.
2. **Node/flow-based workflow engines** (as in the screenshots: *LLM Endpoint*, *LLM Model*, *Endpoint Info*, *Model Info*, *LLM Request*, *Attach Files*, *Generation Params*, *Select Files* nodes): needs rich, inspectable metadata (models served, capabilities, prices, limits, providers serving a model, usage/balance/limits of the key), per-node parameter overrides, attachments, structured output, batch of files → one request, cost and failure outputs per run.
3. **LLM proxies / gateways** built on the library: parse incoming requests in one dialect, translate to another, forward, re-emit streams, enforce budgets/keys, analyze and compress traffic, collect statistics. Requires *bidirectional* codecs (parse requests + serialize responses in every supported dialect).
4. **Traffic analysis, compression, deduplication, replay**: raw request/response capture, token counting, cost attribution, cache-hit analysis.
5. **Statistics/catalog UIs**: list models with descriptions, modalities, capabilities, prices, context windows, provider variants (quantization, uptime, latency), key usage & balances.
6. **Batch/offline jobs, evaluation harnesses, tests**: deterministic recording/replay, mock endpoints, batch APIs.

### 1.3 Goals

- `GEN-1 [MUST]` Universal: any provider/gateway/local server via a small set of dialect implementations + a compatibility-profile mechanism for "almost OpenAI-compatible" servers.
- `GEN-2 [MUST]` Feature-rich but layered: a one-line facade for the 80% case; full control (every native parameter, every native response field, raw JSON) for the 20%.
- `GEN-3 [MUST]` Lossless where possible, explicit where lossy: every translation between dialects documents what is dropped/approximated; unsupported parameters are handled by a configurable policy (error / warn+drop / passthrough).
- `GEN-4 [MUST]` Transport concerns (timeouts, retries, rate limits, streaming framing, auth refresh, proxies) are solved once, centrally, and are observable.
- `GEN-5 [MUST]` Metadata-first: models, capabilities, prices and limits are first-class objects with provenance (where the fact came from, when, how confident).
- `GEN-6 [MUST]` Gateway-grade: usable as the core of a proxy (server-side parsing, translation, stream re-emission, accounting).
- `GEN-7 [SHOULD]` Standards-aligned: OpenAI Chat/Responses shapes, Open Responses, Anthropic Messages, Gemini, JSON Schema, MCP tool definitions, OpenTelemetry GenAI semantic conventions, models.dev catalog format.
- `GEN-8 [SHOULD]` Minimal, well-chosen dependencies; embeddable in CLIs, servers, desktop apps; virtual-thread friendly.

### 1.4 Non-goals (keep the library focused)

- Not an agent framework (no tool execution loop policy, no planning) — but provide the primitives an agent loop needs (tool call/result modeling, reasoning persistence, event streams). A thin optional "tool runner" helper is acceptable.
- Not a prompt-templating, RAG, vector-store or memory library.
- Not a full MCP client/server implementation (provide MCP ↔ tool-definition conversion and pass-through of provider "remote MCP" tool types; a separate module may add an MCP client later).
- Not a UI. But everything a UI needs (metadata, events, stats) must be exposed.

### 1.5 Success criteria for the future API design

- A new provider that is "OpenAI-compatible with quirks" can be added with configuration only (no code).
- A new dialect can be added by implementing one SPI (codec + streaming decoder + capability descriptor) without touching the core.
- Switching a running conversation from Anthropic to OpenAI (or to a local llama.cpp) requires changing one model reference; the SDK converts history best-effort and reports what could not be carried over.
- A proxy author can parse an inbound Anthropic `/v1/messages` request, forward it to OpenRouter as Chat Completions, and stream back Anthropic-shaped SSE events using only SDK building blocks.
- Every response answers: *which endpoint/provider/model actually served this, how many tokens (by class), what did it cost (and is that cost exact or estimated), how long did it take (TTFT/total), what rate-limit headroom remains*.

---

## 2. Domain model and glossary

Recommended core nouns (candidate Java types). Names are suggestions; the design phase decides.

| Term | Meaning | Notes |
|---|---|---|
| **Endpoint** | A configured connection target: base URL, dialect, auth, default headers, transport policies, defaults, metadata cache. | Screenshot: *LLM Endpoint / Endpoint Info*. Many models per endpoint. |
| **Provider** | Identity of the organization/service (`openai`, `anthropic`, `openrouter`, `ollama`, `llamacpp`, `custom`). Carries a *provider profile*: known base URLs, env var names, auth scheme, dialects, quirks, catalog sources. | Provider ≠ dialect. |
| **Dialect / WireApi** | Concrete protocol implementation: `openai-chat`, `openai-responses`, `anthropic-messages`, `gemini-generate-content`, `gemini-interactions`, `ollama-native`, `llamacpp-native`, `bedrock-converse`, `mistral-chat`, `cohere-v2`, … | Pi-ai and OpenCode use the same idea (`api` field). |
| **Compatibility profile** | Declarative set of quirks for an "OpenAI-compatible" server (which params are accepted, how reasoning is expressed, whether `stream_options` works, max tokens field name, role support). | Screenshot's *LLM Model → Advanced* (Output Limit Field, Reasoning Dialect). |
| **ModelRef** | Endpoint + model id (+ optional variant/tags/deployment). | e.g. `openrouter:deepseek/deepseek-v4-flash:free`, `azure:my-deployment`. |
| **ModelInfo** | Unified metadata: ids/aliases, names, description, family, release/deprecation dates, modalities, capabilities, limits, pricing, supported params, provider variants, provenance. | Screenshot: *Model Info*. |
| **Capability** | Tri-state facts (`supported`, `unsupported`, `unknown`) with source: tools, structured output, reasoning (+ effort levels), vision, audio in/out, video, files/PDF, prompt caching, batch, citations, code execution, web search, computer use, streaming, logprobs, seeds, `n`, temperature… | Never assume; allow overrides. |
| **Credential / CredentialProvider** | Anything that yields auth material at request time: static key, env var, file, keychain, command, OAuth token store, cloud signer. | Supports refresh and rotation. |
| **Request (ChatRequest / GenerateRequest)** | Unified, dialect-neutral request: messages, tools, params, reasoning config, output format, cache hints, state hints, attachments, per-request options, native extras. | |
| **Message / ContentPart** | Role + ordered parts: text, image, audio, video, document, tool call, tool result, reasoning (+signature), redacted reasoning, citation, refusal, provider-opaque part. | |
| **Response** | Unified result: content parts, tool calls, finish reason, usage, cost, model/provider served, ids, headers, timings, raw. | |
| **StreamEvent** | Semantic event (not raw chunk): `start`, `text.delta`, `reasoning.delta`, `toolCall.start/delta/end`, `part.start/end`, `usage`, `finish`, `error`, `ping`, `raw`. | Accumulator rebuilds a Response. |
| **Usage / Cost** | Token classes (input, output, cached-read, cached-write, reasoning, audio, image, tool/server-side), request counts, provider-reported cost, computed cost, `exact|estimated` flag. | |
| **Catalog** | Merged model metadata from endpoint APIs, external sources (models.dev, OpenRouter), bundled snapshot, user overrides. | With TTL/refresh and provenance. |
| **Session / ConversationHandle** | Optional stateful handle for providers with server-side state (`previous_response_id`, Gemini interactions, Mistral conversations). | Stateless by default. |
| **Policy** | Retry, timeout, rate-limit, routing/fallback, unsupported-param, redaction policies; layered defaults (global → endpoint → model → request). | |

---

## 3. Architecture and layering

### 3.1 Layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│ L5  Facade / Client API      one-liners, fluent builders, sessions,      │
│                              tool-runner helper, catalog queries         │
├──────────────────────────────────────────────────────────────────────────┤
│ L4  Policies & Routing       retries, timeouts, rate limiting, circuit   │
│                              breaker, fallback chains, load balancing,   │
│                              budget guards, unsupported-param policy     │
├──────────────────────────────────────────────────────────────────────────┤
│ L3  Unified Model            Request/Response/StreamEvent/ModelInfo/     │
│                              Usage/Cost/Error + translation between      │
│                              unified model and dialects (both directions)│
├──────────────────────────────────────────────────────────────────────────┤
│ L2  Dialect Codecs           per-dialect request encoder, response       │
│                              decoder, stream decoder/encoder, error      │
│                              decoder, metadata/list-models client,       │
│                              capability descriptor, quirk profile        │
├──────────────────────────────────────────────────────────────────────────┤
│ L1  HTTP / Streaming Transport  pluggable HttpTransport SPI (JDK 11+     │
│                              HttpClient default; OkHttp/Apache adapters),│
│                              SSE / NDJSON / WebSocket / AWS event-stream │
│                              decoders, compression, proxies, TLS,        │
│                              timeouts incl. idle watchdog                │
├──────────────────────────────────────────────────────────────────────────┤
│ L0  Cross-cutting            Config, Credentials/Auth (incl. OAuth),     │
│                              Catalog/Metadata cache, Observability       │
│                              (metrics/tracing/logging/recording), SPI    │
└──────────────────────────────────────────────────────────────────────────┘
```

- `ARCH-1 [MUST]` Every layer usable independently: e.g. a gateway may use only L1+L2 (raw dialect passthrough), a UI only L0 catalog.
- `ARCH-2 [MUST]` Codecs are **bidirectional**: `encodeRequest(unified) → dialect JSON`, `decodeRequest(dialect JSON) → unified`, `encodeResponse(unified) → dialect JSON/SSE`, `decodeResponse(...)`. Needed for the proxy use case and for testing symmetry.
- `ARCH-3 [MUST]` Unknown/native fields are preserved end-to-end (`extras` maps / raw JSON nodes on request, response and events).
- `ARCH-4 [MUST]` Two escape hatches at every level: (a) *native extras* merged into the outgoing JSON (`extra_body`, `extra_headers`, `extra_query`), (b) *raw call*: send an arbitrary JSON body to an endpoint path and get raw response/stream back, still benefiting from auth, retries, telemetry.
- `ARCH-5 [SHOULD]` Interceptor/middleware chain at transport level (request/response/stream) and at unified level (before encode / after decode): logging, redaction, header injection, request rewriting, caching, recording, budget checks.
- `ARCH-6 [SHOULD]` Dialect and provider implementations discoverable via `ServiceLoader` (SPI) so third parties can add dialects/providers/credential sources/catalog sources without forking.
- `ARCH-7 [SHOULD]` Pure-Java core with no framework (Spring/Micronaut) dependency; optional integration modules.
- `ARCH-8 [MAY]` Kotlin-friendly API shapes (nullable-annotated, builder DSL friendly) and a small Kotlin extension module.

### 3.2 Suggested module layout

| Module | Responsibility |
|---|---|
| `core` | Unified model, SPI interfaces, config model, policies, catalog model, error model, event model, JSON abstraction |
| `transport-jdk` (default), `transport-okhttp`, `transport-apache` | `HttpTransport` implementations + SSE/NDJSON/WebSocket decoders |
| `dialect-openai` (chat + responses + realtime-lite), `dialect-anthropic`, `dialect-gemini` (generateContent + interactions + Vertex), `dialect-ollama`, `dialect-llamacpp`, `dialect-bedrock`, `dialect-mistral`, `dialect-cohere` | Codecs, metadata clients, quirk profiles |
| `providers` | Provider profiles (base URLs, env vars, auth schemes, dialect choice, known quirks) for public providers, gateways, local servers |
| `auth-oauth` | OAuth 2.0/2.1 flows (auth-code+PKCE, device, client-credentials), token stores, loopback callback server, provider presets |
| `auth-cloud` | AWS SigV4, GCP ADC/service account, Azure Entra (thin wrappers or optional adapters over vendor SDKs) |
| `catalog` | models.dev/OpenRouter/LiteLLM importers, bundled snapshot, merge/override logic, pricing calculator |
| `observability` | OpenTelemetry GenAI conventions, Micrometer metrics, structured logging, redaction, recorder/replayer |
| `gateway` | Server-side helpers: inbound dialect parsers, translation matrix, SSE writers, accounting hooks (framework-agnostic; adapters for Servlet/Netty/Vert.x optional) |
| `tokenizers` | Token estimators (tiktoken-compatible via JTokkit, SentencePiece/HF via optional adapters), provider count-tokens clients |
| `testing` | Mock endpoint server, fixtures for each dialect, recorded cassettes, contract tests |

### 3.3 Extension points (SPI)

- `Dialect` (codec bundle), `ProviderProfile`, `HttpTransport`, `CredentialProvider`, `OAuthFlow`/`TokenStore`, `CatalogSource`, `PricingRule`, `Tokenizer`, `Interceptor`, `RetryClassifier`, `Router`/`EndpointSelector`, `MetricsSink`/`TraceExporter`, `Redactor`, `Recorder`.

### 3.4 Threading and async model

- `ARCH-9 [MUST]` Blocking (synchronous) API as the primary, simplest surface (fits Java 21 virtual threads). Plus `CompletableFuture` variants, plus streaming as `java.util.concurrent.Flow.Publisher<StreamEvent>` and as a blocking `Iterator/Stream<StreamEvent>`; optional callback listener style. Optional adapters: Reactor `Flux`, RxJava, Kotlin `Flow`.
- `ARCH-10 [MUST]` Cancellation propagates to the socket (closing the stream cancels generation server-side for most providers and stops billing of further output; note providers may still bill what was generated).
- `ARCH-11 [MUST]` Backpressure: the consumer's pace controls the read from the socket (no unbounded buffering of stream events by default; configurable buffer).
- `ARCH-12 [MUST]` Thread-safe, immutable request/response objects; client objects reusable and `AutoCloseable` (release pools, schedulers, token refreshers).

---

## 4. Landscape: dialects, providers, gateways, local servers, standards

### 4.1 Wire dialects to support (core set)

| Dialect id | Origin | Endpoint(s) | Streaming | Who speaks it | Priority |
|---|---|---|---|---|---|
| `openai-chat` | OpenAI Chat Completions | `POST /v1/chat/completions`, `GET /v1/models`, `/v1/embeddings` | SSE, `data: [DONE]` terminator | Nearly everyone (the de-facto standard): OpenAI, OpenRouter, DeepSeek, xAI, Qwen/DashScope compatible-mode, Mistral, Groq, Together, Fireworks, Cerebras, Perplexity, Ollama `/v1`, llama.cpp `/v1`, vLLM, LM Studio, Azure, GitHub Models, Copilot… | MUST |
| `openai-responses` | OpenAI Responses API (2025→), basis of **Open Responses** spec | `POST /v1/responses`, `GET/DELETE /v1/responses/{id}`, `POST .../cancel`, `GET .../input_items`, `/v1/conversations`, `POST /v1/responses/compact` `[VERIFY]` | SSE with typed `event:` names + `sequence_number`; ends with `response.completed/incomplete/failed` (no `[DONE]` in pure spec; some impls still send it); WebSocket transport exists `[VERIFY]` | OpenAI, Azure OpenAI v1, OpenRouter (beta), Vercel AI Gateway, LiteLLM, Portkey, DeepSeek (own docs list it), xAI, Qwen (reported), LM Studio, vLLM, Codex backend (`chatgpt.com/backend-api/codex/responses`) | MUST |
| `anthropic-messages` | Anthropic Messages API | `POST /v1/messages`, `/v1/messages/count_tokens`, `/v1/messages/batches`, `GET /v1/models`, `/v1/files` (beta) | SSE with named events (`message_start`, `content_block_delta`, …) | Anthropic; Bedrock/Vertex (body-compatible); many "Anthropic-compatible" endpoints: DeepSeek `/anthropic`, Qwen `/apps/anthropic`, Moonshot/Kimi, Zhipu GLM, MiniMax, Xiaomi MiMo, xAI `[VERIFY]`, Vercel AI Gateway, llama.cpp `[VERIFY]`, LiteLLM | MUST |
| `gemini-generate-content` | Google Gemini API / Vertex AI | `POST /v1beta/models/{m}:generateContent`, `:streamGenerateContent?alt=sse`, `:countTokens`, `GET /v1beta/models`, `/v1beta/files`, `/v1beta/cachedContents`, batches | SSE only with `alt=sse` (otherwise a JSON array) | Google AI Studio API, Vertex AI (different base URL/auth), Gemini via OpenRouter (translated) | MUST |
| `gemini-interactions` | Google Interactions API (2026, stateful, `previous_interaction_id`, `Api-Revision` header) `[VERIFY]` | `POST /v1beta/interactions` | SSE with typed deltas | Google | SHOULD |
| `ollama-native` | Ollama | `/api/chat`, `/api/generate`, `/api/embed`, `/api/tags`, `/api/show`, `/api/ps`, `/api/pull`, `/api/version`, … | NDJSON (one JSON object per line), `done:true` terminator | Ollama local, Ollama Cloud (`https://ollama.com`, Bearer key), llama.cpp Ollama-shim `[VERIFY]` | MUST |
| `llamacpp-native` | llama.cpp `llama-server` | `/completion`, `/tokenize`, `/detokenize`, `/apply-template`, `/embedding`, `/reranking`, `/infill`, `/props`, `/slots`, `/metrics`, `/health`, `/models`, `/lora-adapters` (+ `/v1/*` OpenAI compat) | SSE | llama.cpp and derivatives (KoboldCpp partially) | SHOULD |
| `bedrock-converse` | AWS Bedrock Converse/ConverseStream (+ InvokeModel with native bodies) | `POST /model/{id}/converse`, `/converse-stream`, `/invoke`, `/invoke-with-response-stream` | AWS binary event-stream (not SSE) | Bedrock | SHOULD (via AWS SDK adapter) |
| `mistral-chat` / `mistral-conversations` | Mistral | `/v1/chat/completions` (OpenAI-like + extras), `/v1/conversations` (stateful agents), `/v1/fim/completions`, `/v1/ocr` | SSE | Mistral | MAY |
| `cohere-v2` | Cohere Chat v2 | `/v2/chat`, `/v2/embed`, `/v2/rerank` | SSE | Cohere | MAY |
| `openai-completions` (legacy) | Text completions / FIM | `/v1/completions` | SSE | llama.cpp, vLLM, Codestral/DeepSeek FIM, Ollama `/v1` | MAY |
| `openai-realtime` | WebSocket/WebRTC realtime audio | `wss://.../v1/realtime` | WS events | OpenAI, xAI `[VERIFY]` | MAY (separate module) |

Rule of thumb: implement `openai-chat`, `openai-responses`, `anthropic-messages`, `gemini-generate-content`, `ollama-native` fully; everything else via compatibility profiles or thin adapters.

### 4.2 Public providers (first-party APIs) and how they map

| Provider | Base URL(s) | Dialects | Auth | Notable extras / quirks |
|---|---|---|---|---|
| **OpenAI** | `https://api.openai.com/v1` | responses (primary), chat, embeddings, images, audio, files, batches, realtime | `Authorization: Bearer`, optional `OpenAI-Organization`, `OpenAI-Project` | reasoning models reject `temperature/top_p/logprobs`; `max_completion_tokens`; `developer` role; `store` default true on Responses; `prompt_cache_key` + `prompt_cache_options` (ttl, explicit breakpoints on GPT‑5.6+ `[VERIFY]`); `reasoning.encrypted_content`; `service_tier` (auto/default/flex/priority); `safety_identifier` replaces `user`; Codex models want assistant `phase` (`commentary`/`final_answer`) on follow-ups `[VERIFY]`; strict JSON-schema subset |
| **Anthropic** | `https://api.anthropic.com` | messages, count_tokens, batches, models, files (beta), admin usage/cost reports | `x-api-key` (+ `Authorization: Bearer` for OAuth tokens), `anthropic-version: 2023-06-01` required, `anthropic-beta` for betas | `max_tokens` required; `temperature` 0–1; on Claude 4.x you cannot send both `temperature` and `top_p` `[VERIFY]`; `system` is top-level; `thinking` `{type: enabled\|adaptive\|disabled, budget_tokens, display}`; `output_config.effort` (`low\|medium\|high\|xhigh\|max`); structured outputs via `output_format` (+ beta header on older models); `context_management` edits (`clear_tool_uses`, `clear_thinking`, `compact`); prompt caching via `cache_control` breakpoints (`ttl: 5m\|1h`); thinking blocks must be replayed verbatim with signature and are bound to the prompt prefix on newest models `[VERIFY]`; 529 `overloaded_error`; `/v1/models` returns a `capabilities` object (effort levels, thinking types, structured outputs, pdf/image input, batch, citations, context management) `[VERIFY]` |
| **Google Gemini (AI Studio)** | `https://generativelanguage.googleapis.com/v1beta` (+ `/v1beta/openai/` OpenAI-compatible shim) | generateContent, interactions, files, cachedContents, batches, embeddings; OpenAI-compatible shim | `x-goog-api-key` header (or `?key=` — avoid; leaks in logs) | roles `user`/`model`; `systemInstruction`; `thinkingConfig.thinkingLevel` (`low\|medium\|high`; legacy `thinkingBudget` mutually exclusive); `thoughtSignature` must be echoed back (esp. function calls; in parallel calls only the first carries it) `[VERIFY]`; `responseSchema` is an OpenAPI-3-style subset (`responseJsonSchema` for JSON Schema `[VERIFY]`); safety settings/blocks; `finishReason` enum; `usageMetadata` incl. `thoughtsTokenCount`, `cachedContentTokenCount`; `GET /models` gives `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods` |
| **Google Vertex AI** | `https://{region}-aiplatform.googleapis.com/v1/projects/{p}/locations/{l}/publishers/{pub}/models/{m}:generateContent` (also `global`) | generateContent; Anthropic-on-Vertex via `:rawPredict`/`:streamRawPredict` with Messages body (`anthropic_version: vertex-2023-10-16`) | OAuth2 bearer from ADC / service account (`https://www.googleapis.com/auth/cloud-platform`) | project/region in path; model IDs with `@version`; Model Garden partner models; quotas per region |
| **xAI** | `https://api.x.ai/v1` | chat, responses, images, (messages compat `[VERIFY]`) | Bearer | `reasoning_effort` on mini reasoning models; `search_parameters` (live search); `/v1/language-models` returns pricing & modalities `[VERIFY]`; `/v1/api-key` key info; deferred completions |
| **DeepSeek** | `https://api.deepseek.com` (`/v1` alias), `/beta` (FIM, prefix completion), `/anthropic` (Messages compat), `/responses` `[VERIFY]` | chat (+ `reasoning_content`), responses, messages | Bearer | automatic context caching with `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`; reasoning content returned in `reasoning_content` and should be replayed per their docs `[VERIFY]`; effort levels `high`/`xhigh` on V4 |
| **Qwen / Alibaba Model Studio (DashScope)** | `https://dashscope.aliyuncs.com/compatible-mode/v1` (CN), `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (intl), Anthropic compat `/apps/anthropic`, native `/api/v1/services/aigc/...` | chat (compat), responses `[VERIFY]`, messages, native DashScope | Bearer (`DASHSCOPE_API_KEY`) | `enable_thinking`, `preserve_thinking`, `reasoning_effort` (`low\|medium\|xhigh`); **Anthropic-compat endpoint defaults thinking ON** and can consume the entire `max_tokens` (empty text, `stop_reason: max_tokens`) `[VERIFY]`; region-specific hosts |
| **Mistral** | `https://api.mistral.ai/v1` | chat (OpenAI-like + `random_seed`, `safe_prompt`, `prediction`), conversations (stateful agents), fim, ocr, embeddings, moderation | Bearer | `Magistral` reasoning content; strict JSON via `json_schema` |
| **Moonshot (Kimi)** | `https://api.moonshot.ai/v1` (+ `/anthropic`), region CN host | chat, messages | Bearer | reasoning_content; "Kimi Code" subscription endpoints |
| **Zhipu / Z.ai (GLM)** | `https://api.z.ai/api/paas/v4`, coding plan `/api/coding/paas/v4`, `/api/anthropic` | chat, messages | Bearer | `thinking: {type: enabled}` extension |
| **MiniMax, Xiaomi MiMo, Baidu, StepFun, ByteDance Volcengine (Doubao), Tencent Hunyuan, Kimi, 01.AI, Baichuan** | vendor hosts | mostly OpenAI-compatible; several add Anthropic-compatible | Bearer | region/host variants; token-plan vs pay-per-token endpoints |
| **Cohere** | `https://api.cohere.com/v2` | cohere-v2 (+ OpenAI-compat `/compatibility/v1`) | Bearer | rerank, embeddings |
| **Groq, Cerebras, SambaNova, Together, Fireworks, DeepInfra, Nebius, Novita, Baseten, Hyperbolic, Lambda, Perplexity, AI21, Upstage, NVIDIA NIM (`integrate.api.nvidia.com/v1`)** | vendor `/v1` | openai-chat (+ some responses) | Bearer | provider-specific extras (`reasoning_format` (Groq), Perplexity `search_domain_filter`/citations, Fireworks grammar/`response_format` extensions, Together `repetition_penalty`/`min_p`) |
| **Hugging Face Inference** | `https://router.huggingface.co/v1` (+ per-provider routing) | openai-chat | Bearer (HF token) | model ids `org/model`, provider suffix routing |

### 4.3 Gateways / routers / cloud platforms

| Gateway | Base URL | Inbound dialects | Extras the SDK should surface |
|---|---|---|---|
| **OpenRouter** | `https://openrouter.ai/api/v1` | chat, completions, responses (beta), embeddings, images, videos, audio (speech/transcriptions), rerank; also `GET /models`, `/models/{author}/{slug}/endpoints`, `/models/user`, `/providers`, `/auth/key`, `/credits`, `/generation?id=`, `/activity` (provisioning key), `/keys` (management), OAuth PKCE (`/auth`, `/auth/keys`) | `supported_parameters`, `default_parameters`, `expiration_date`, `knowledge_cutoff`, `per_request_limits`, `top_provider`, pricing incl. `image`, `request`, `input_cache_read/write`, `web_search`, `internal_reasoning`, `audio`; provider routing object (`order`, `only`, `ignore`, `allow_fallbacks`, `require_parameters`, `data_collection`, `quantizations`, `sort`, `max_price`, `zdr`); `models[]` fallback list; `transforms: ["middle-out"]`; unified `reasoning {effort, max_tokens, exclude, enabled}` and `reasoning_details[]` in responses; `usage: {include: true}` → `usage.cost`; response `provider` field; model variant suffixes `:free`, `:nitro`, `:floor`, `:online`, `:thinking`, `:extended`, `:exacto`; `openrouter/auto`; plugins (`web`, `file-parser` PDF engines, `response-healing`); `HTTP-Referer`/`X-Title` attribution headers; SSE keepalive comments `: OPENROUTER PROCESSING`; 402 insufficient credits; mid-stream error objects with `finish_reason: "error"`; key info: usage, limit, limit_remaining, `is_free_tier`, `rate_limit`, daily/weekly/monthly usage, BYOK usage, expiry `[VERIFY]` (matches screenshot *Endpoint Info → Usage/Balance*) |
| **LiteLLM Proxy** | user host `/v1` | chat, responses, messages (`/v1/messages`), embeddings, images, audio, batches, `/model/info`, `/models`, `/key/info`, `/spend/*` | virtual keys, budgets, tags; `model_prices_and_context_window.json` as a catalog source; passes `thinking`, `reasoning_effort`, forwards `context_management` |
| **Vercel AI Gateway** | `https://ai-gateway.vercel.sh/v1` | chat, responses, messages; `GET /v1/models` with reasoning/thinking metadata | `provider/model` ids; maps `reasoning.effort`/`thinking` across providers; BYOK |
| **Portkey, Cloudflare AI Gateway, Helicone, Kong AI Gateway, TrueFoundry, Bifrost, Apigee, Traefik AI** | vendor hosts | usually chat + responses + provider-native passthrough with extra headers (`x-portkey-*`, `Helicone-*`) | header-based config, virtual keys, caching headers, retries/fallback config in headers |
| **Azure OpenAI / Microsoft Foundry** | `https://{resource}.openai.azure.com/openai/v1` (new v1 route, no `api-version`) or legacy `/openai/deployments/{deployment}/chat/completions?api-version=…`; Foundry Models: `https://{resource}.services.ai.azure.com/models` | responses, chat, embeddings, images, files, batches | auth `api-key` header **or** Entra ID Bearer; deployment names ≠ model names; content-filter annotations in responses; different error envelope; regional quotas; Anthropic models on Foundry via Messages-shaped endpoint `[VERIFY]` |
| **AWS Bedrock** | `https://bedrock-runtime.{region}.amazonaws.com` (+ `bedrock.{region}` control plane: `ListFoundationModels`, `GetFoundationModel`, inference profiles) | converse/converse-stream (unified), invoke (native body per vendor), `CountTokens` `[VERIFY]` | SigV4 or Bedrock API keys (bearer) `[VERIFY]`; model ids / cross-region inference profiles (`us.anthropic.claude-…`); binary event stream; pricing not exposed by API; guardrails; prompt caching via `cachePoint` blocks |
| **GitHub Models** | `https://models.github.ai/inference` | chat (`/chat/completions`), `/catalog/models` | GitHub PAT (`models:read`) | org/model ids; rate limits by plan |
| **GitHub Copilot** | `https://api.githubcopilot.com` | chat (+ responses `[VERIFY]`), `/models` | OAuth device flow (GitHub app client id) → exchange at `api.github.com/copilot_internal/v2/token` → short-lived token; requires editor/integration headers `[VERIFY][POLICY]` | premium-request accounting; model list depends on plan |
| **Ollama Cloud** | `https://ollama.com` | ollama-native, `/v1` chat | Bearer | same API as local |

### 4.4 Local inference servers

| Server | Default host | Dialects | Discovery & health | Quirks |
|---|---|---|---|---|
| **llama.cpp `llama-server`** | `http://127.0.0.1:8080` | `/v1/chat/completions`, `/v1/completions`, `/v1/embeddings`, `/v1/rerank`, `/v1/models`; native `/completion`, `/infill`, `/tokenize`, `/detokenize`, `/apply-template`, `/embedding`, `/reranking`; Anthropic `/v1/messages` `[VERIFY]`; Ollama-shim (`/api/tags`, `/api/chat`) `[VERIFY]` | `/health`, `/props` (default sampling params, `model_alias`, `total_slots`, `modalities`, chat template, build info, `n_ctx`), `/slots` (per-slot state, can be disabled), `/metrics` (Prometheus, opt-in), `/models` (router mode: cached models + load status, `?model=` query on GET endpoints, autoload) | optional `--api-key`; one model per process unless router mode; `n_parallel` slots share `n_ctx`; `cache_prompt`; GBNF `grammar` and `json_schema`; nonstandard `response_format {type: json_object, schema}`; `chat_template_kwargs` (e.g. `enable_thinking`); `reasoning_format`; `reasoning_effort` passed to template; `timings` in every response (prompt/predicted tokens, ms, tokens/s); `n_probs`; `id_slot`; LoRA hot-swap; model id may be a file path unless alias set |
| **Ollama** | `http://127.0.0.1:11434` (`OLLAMA_HOST`) | native (`/api/*`) + `/v1/*` OpenAI compat | `/api/version`, `/api/tags`, `/api/ps` (loaded models, VRAM, expiry), `/api/show` (capabilities: `completion`, `vision`, `tools`, `thinking`, `embedding`; `model_info` with `*.context_length`; parameters; template; quantization) | `options` nest sampling params (`num_ctx`, `num_predict`, `top_k`, `top_p`, `min_p`, `repeat_penalty`, `seed`, `stop`, `mirostat`…); **default `num_ctx` (4096) silently truncates**; `keep_alive` (duration/seconds/-1/0) only honored on native API; `think: true|false|"low"|"medium"|"high"|"max"`; `format: "json" | <schema>`; images as base64 array; NDJSON streaming; `done_reason` (`stop`, `length`, `load`, `unload`); first request loads model (cold-start latency → separate timeout); 503 when queue full (`OLLAMA_MAX_QUEUE`); no auth locally; may bind only to 127.0.0.1 (IPv6 `localhost`→`::1` resolution pitfall) |
| **LM Studio** | `http://localhost:1234/v1` (+ `/api/v0/models` richer) | chat, responses `[VERIFY]`, completions, embeddings; MCP tool support in responses | `/api/v0/models` (loaded state, quantization, context) | JIT model loading; TTL unload |
| **vLLM** | `http://localhost:8000/v1` | chat, responses `[VERIFY]`, completions, embeddings, rerank, `/tokenize`, `/detokenize`, `/metrics` | `/health`, `/version`, `/v1/models` (max_model_len) | `extra_body`: `guided_json`/`structured_outputs`, `min_p`, `repetition_penalty`, `top_k`, `chat_template_kwargs`, `return_tokens_as_token_ids`; reasoning parser flag determines `reasoning_content`; optional `--api-key` |
| **SGLang** | `:30000/v1` | chat, completions | `/health`, `/get_model_info`, `/get_server_info` | similar to vLLM extras |
| **TGI (Hugging Face)** | `:8080` | `/v1/chat/completions`, native `/generate`, `/generate_stream` | `/info`, `/health`, `/metrics` | grammar via `grammar` param |
| **LocalAI, Jan, KoboldCpp, TabbyAPI (ExLlama), text-generation-webui, MLX-based servers (`mlx_lm.server`, mlx-server), Aphrodite, Xinference, GPUStack, Docker Model Runner** | various | openai-chat (+ some native) | mostly `/v1/models` only | treat via compatibility profiles; probe capabilities at runtime |

- `DISC-0 [SHOULD]` **Auto-detection** of local servers: probe `/health`, `/props` (llama.cpp), `/api/version`+`/api/tags` (Ollama), `/api/v0/models` (LM Studio), `/version` (vLLM), `/v1/models` (generic) to classify the server and pick the best dialect + profile.

### 4.5 "OpenAI-compatible" is a spectrum — compatibility profile

Servers differ in: accepted parameter set (some 400 on unknown params, some ignore silently), the max-output field name (`max_tokens` vs `max_completion_tokens`), support of `developer` vs `system` role, `stream_options.include_usage`, tool-call streaming granularity, `response_format` variants, `n`, `logprobs`, `seed`, reasoning expression (`reasoning_effort` vs `reasoning: {effort}` vs `thinking` vs `chat_template_kwargs.enable_thinking` vs `think`), reasoning output field (`reasoning_content` vs `reasoning` vs `reasoning_details` vs `<think>` tags inline in `content`), usage detail fields, error envelope, `[DONE]` presence, keep-alive comments, `/models` richness.

- `CFG-P1 [MUST]` A **declarative compatibility profile** per endpoint/model with fields such as: `maxOutputTokensField`, `supportsDeveloperRole`, `supportsStreamOptions`, `unknownParamPolicy`, `reasoningDialect` (`none | openai-reasoning-effort | openai-reasoning-object | anthropic-thinking | gemini-thinking | ollama-think | chat-template-kwargs | qwen-enable-thinking | deepseek | openrouter-reasoning`), `reasoningOutputField`, `inlineThinkTags` (parse `<think>…</think>`), `toolCallStreaming` (`delta | whole | none`), `responseFormatStyle`, `supportsN`, `supportsSeed`, `supportsLogprobs`, `usageInStream`, `terminator` (`done-marker | event | none`), `contextWindowOverride`, `maxOutputOverride`, `pricingOverride`. (Mirrors the screenshot's *LLM Model → Advanced*: *Output Limit Field*, *Context Window*, *Reasoning Dialect*, *Reasoning On*, *Input/Output Price*.)
- `CFG-P2 [SHOULD]` Ship built-in profiles for known servers/providers; allow user profiles in config; allow runtime probing to fill `unknown` facts (e.g. send a tiny request with `stream_options` and see if it 400s) — opt-in, never automatic against paid endpoints without consent.

### 4.6 Standards and shared vocabularies to align with

- **OpenAI Chat Completions** shape — the de-facto lingua franca; keep an exact, versioned model of it.
- **OpenAI Responses API / Open Responses** (openresponses.org): items as atomic units (message, function_call, function_call_output, reasoning, compaction…), item state machines, semantic streaming events, `/responses/compact`, assistant message `phase`. Good template for the SDK's own unified event model. `[VERIFY]` spec version at implementation time.
- **Anthropic Messages** content-block model — good template for content parts and cache breakpoints.
- **JSON Schema** (draft 2020-12) for tool parameters and structured outputs; plus provider subsets (OpenAI strict subset; Gemini OpenAPI-3-style `Schema`).
- **MCP (Model Context Protocol)** tool definitions (`name`, `description`, `inputSchema`, annotations like `readOnlyHint`) and providers' server-side "remote MCP" tool types (OpenAI `mcp` tool: `server_label`, `server_url`, `require_approval`, `allowed_tools`, headers; Anthropic `mcp_servers`).
- **OpenTelemetry GenAI semantic conventions** (`gen_ai.*`; moved to a dedicated repo mid‑2026, status *Development*; core attributes stable in shape). See §13.
- **models.dev** (`api.json` provider-keyed, `models.json` provider-agnostic, `catalog.json` both) — the catalog format used by OpenCode; a natural import/export format. See §8.
- **OAuth 2.0/2.1** (RFC 6749, 7636 PKCE, 8628 Device Authorization, 8414 metadata, 9728 protected resource metadata used by MCP), plus cloud IAM (AWS SigV4, GCP OAuth2/ADC, Azure Entra).
- **SSE (WHATWG EventSource)** framing; **NDJSON**; **AWS event-stream**; **WebSocket**.
- **HTTP semantics**: `Retry-After`, RFC 9457 problem details (rare in this space), rate-limit headers (`x-ratelimit-*`, `anthropic-ratelimit-*`, IETF RateLimit header draft `[VERIFY]`).

---

## 5. Configuration layer

### 5.1 Configuration objects

**EndpointConfig** (`CFG-1 [MUST]`)

| Field | Notes |
|---|---|
| `id` / `name` | stable identifier used by ModelRefs (`MyEndpoint` in screenshot) |
| `provider` | provider profile id (`openai`, `anthropic`, `openrouter`, `ollama`, `llamacpp`, `azure-openai`, `bedrock`, `vertex`, `custom`) — supplies defaults for everything below |
| `baseUrl` | full base incl. version segment when applicable (`/v1`); support path templates for Azure/Vertex |
| `dialect` | primary dialect; optional per-operation overrides (e.g. chat via responses, embeddings via chat-compat) |
| `compatibilityProfile` | see §4.5 |
| `auth` | credential reference(s): scheme + source (see §6) |
| `headers`, `envHeaders`, `queryParams` | static extra headers; headers whose values come from env vars (Codex-style `env_http_headers`); static query params (Azure `api-version`) |
| `apiVersion` / `betaFlags` | e.g. `anthropic-version`, `anthropic-beta[]`, Gemini `Api-Revision`, Azure `api-version` |
| `transport` | timeouts (connect, headers, read/idle, total, stream idle), proxy, TLS, HTTP version, pool size, max concurrency |
| `resilience` | retry policy, rate-limit policy, circuit breaker, hedging (off by default) |
| `defaults` | default generation params, default model, default reasoning config, default cache policy |
| `catalog` | which catalog sources apply, refresh TTL, snapshot path, model include/exclude filters, aliases |
| `metadata` | free-form tags (team, cost center, region, data-residency) used for routing and reporting |
| `limits` | client-side budgets: max tokens per request, max cost per request/day, max concurrent requests |
| `privacy` | data-collection preference (OpenRouter `data_collection: deny`, ZDR flags, log content on/off) |

**ModelConfig / ModelOverride** (`CFG-2 [MUST]`): per model id (or glob) under an endpoint: display name, aliases, `contextWindow`, `maxOutput`, `maxInput`, pricing (input/output/cache read/write/reasoning/image/request), capabilities overrides (tri-state), reasoning dialect/defaults, default params, param clamps, `deprecatedAt`, `tokenizerHint`, `knowledgeCutoff`, `family`, `tags`.

**Profiles** (`CFG-3 [SHOULD]`): named bundles selecting endpoint + model + defaults (like Codex `profiles`, OpenClaw auth profiles) for quick switching (`--profile fast`, `--profile cheap`).

**Global config** (`CFG-4 [SHOULD]`): default endpoint, catalog cache dir, secret store choice, telemetry settings, log redaction, user agent string, locale/currency for cost display.

### 5.2 Sources and precedence

`CFG-5 [MUST]` Deterministic layering, highest first:
1. Explicit per-request options (code)
2. Client/builder settings (code)
3. Process environment variables (provider-conventional names, plus SDK-prefixed generic ones)
4. Config file(s): project-local → user (`~/.config/<sdk>/…`, `%APPDATA%`) → system
5. Provider profile defaults (built-in)
6. Catalog facts (models.dev/OpenRouter/endpoint `/models`) for metadata only

Expose an "effective configuration" view with provenance per field (which layer set it) — invaluable for debugging and for the node-flow UI.

### 5.3 Environment variable conventions (`CFG-6 [MUST]` recognize)

| Provider | Key | Base URL / other |
|---|---|---|
| OpenAI | `OPENAI_API_KEY` | `OPENAI_BASE_URL`, `OPENAI_ORG_ID`/`OPENAI_ORGANIZATION`, `OPENAI_PROJECT_ID` |
| Anthropic | `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` (bearer, used by Claude Code for gateways), `CLAUDE_CODE_OAUTH_TOKEN` `[POLICY]` | `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL`, `ANTHROPIC_CUSTOM_HEADERS` |
| Google | `GEMINI_API_KEY` (also `GOOGLE_API_KEY`), `GOOGLE_APPLICATION_CREDENTIALS` (ADC), `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `GOOGLE_GENAI_USE_VERTEXAI` | |
| Azure | `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_VERSION`, Entra via `AZURE_CLIENT_ID/SECRET/TENANT_ID` | |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_REGION`/`AWS_DEFAULT_REGION`, `AWS_PROFILE`, `AWS_BEARER_TOKEN_BEDROCK` `[VERIFY]` | |
| OpenRouter | `OPENROUTER_API_KEY` | `OPENROUTER_BASE_URL` |
| xAI | `XAI_API_KEY` | |
| DeepSeek | `DEEPSEEK_API_KEY` | |
| Qwen | `DASHSCOPE_API_KEY` (also `QWEN_API_KEY`) | region host |
| Mistral | `MISTRAL_API_KEY` | |
| Groq / Cerebras / Together / Fireworks / Perplexity / Cohere / HF | `GROQ_API_KEY`, `CEREBRAS_API_KEY`, `TOGETHER_API_KEY`, `FIREWORKS_API_KEY`, `PERPLEXITY_API_KEY`, `COHERE_API_KEY`, `HF_TOKEN` | |
| GitHub | `GITHUB_TOKEN` (Models), Copilot OAuth store | |
| Ollama | none (or `OLLAMA_API_KEY` for cloud) | `OLLAMA_HOST` |
| llama.cpp | `LLAMA_API_KEY` (convention, `[VERIFY]`) | `LLAMA_SERVER_URL` (convention) |
| Generic | `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_PROVIDER` (common in agent tools) | |
| Proxy | `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (+ lowercase) | JVM `-Dhttp.proxyHost` etc. |

### 5.4 File formats and interop (`CFG-7 [SHOULD]`)

- Native config: TOML or YAML/JSON (pick one primary; JSON Schema published for editor validation).
- **Importers** (read-only, best-effort): models.dev `api.json`; OpenCode `opencode.json` (`provider.<id>.npm/options.baseURL/apiKey/models`); pi `models.json` (`baseUrl`, `api`, `apiKey`, `models[{id,name,input,contextWindow,maxTokens,reasoning,cost}]`); Codex `~/.codex/config.toml` (`model_providers.<id>`: `base_url`, `wire_api` (only `responses` since Feb‑2026 `[VERIFY]`), `env_key`, `query_params`, `http_headers`, `env_http_headers`, `request_max_retries`, `stream_max_retries`, `stream_idle_timeout_ms`, `requires_openai_auth`, `auth {command,args}`); LiteLLM `config.yaml` (`model_list[].litellm_params`); Claude Code env (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`); Continue/aichat/Cline-style provider lists. Import is for onboarding; the SDK keeps its own canonical format.
- **Exporter**: emit models.dev-compatible JSON for the catalog and a Codex/OpenCode-style snippet for an endpoint (helps users wire the same endpoint into other tools).

### 5.5 Secrets handling (`CFG-8 [MUST]`)

- Never store raw secrets in the main config file by default; store *references*: `env:NAME`, `file:/path`, `keychain:service/account`, `cmd:<command>` (Codex-style), `oauth:<profile>`, `vault:...` (SPI).
- Redact secrets in `toString`, logs, exceptions, telemetry, effective-config dumps (show last 4 chars at most).
- Config file permission checks on POSIX (warn on world-readable secrets).

### 5.6 Validation, reload, versioning (`CFG-9 [SHOULD]`)

- Validate at load (schema + semantic: unknown dialect, missing auth for non-local host, unreachable base URL is a warning not error).
- Hot reload support (watch file or explicit `reload()`); in-flight requests keep old config.
- Config schema version field; migrations for breaking changes.

---

## 6. Authentication and credentials

### 6.1 Mechanisms to support (`AUTH-1 [MUST]` unless noted)

| Scheme | How it is sent | Used by |
|---|---|---|
| Bearer API key | `Authorization: Bearer <key>` | OpenAI, OpenRouter, DeepSeek, xAI, Mistral, Groq, Together, Ollama Cloud, LiteLLM, most compat servers, llama.cpp `--api-key` |
| Custom header key | `x-api-key: <key>` | Anthropic (also `Authorization: Bearer` for OAuth tokens), some gateways |
| Google key header | `x-goog-api-key: <key>` (avoid `?key=`) | Gemini API |
| Azure key header | `api-key: <key>` | Azure OpenAI (legacy and v1) |
| OAuth 2 bearer (short-lived, refreshable) | `Authorization: Bearer <access_token>` (+ provider-specific headers, e.g. Anthropic `anthropic-beta: oauth-2025-04-20` `[VERIFY][POLICY]`, ChatGPT account id header for Codex `[VERIFY]`) | Codex/ChatGPT, Gemini CLI/Code Assist, Copilot, OpenRouter (PKCE-issued key), Vertex, Azure Entra |
| Cloud request signing | AWS SigV4 (headers `Authorization`, `x-amz-date`, `x-amz-security-token`) | Bedrock |
| Google ADC / service account | JWT → access token; metadata server on GCE/Cloud Run; `gcloud` user creds | Vertex AI |
| Azure Entra ID | client credentials / managed identity / `az` CLI → bearer with scope `https://cognitiveservices.azure.com/.default` | Azure OpenAI |
| Basic auth | `Authorization: Basic` | some self-hosted reverse proxies `[MAY]` |
| mTLS client certs | TLS layer | enterprise gateways `[SHOULD]` |
| None | — | local servers |
| Multi-header composite | e.g. gateway key + upstream key (`X-Coder-AI-Governance-Token` + `OPENAI_API_KEY`, Portkey `x-portkey-api-key` + `x-portkey-virtual-key`, Helicone) | gateways `[SHOULD]` |
| Query-param key | `?key=` / `?api_key=` | legacy/edge cases; must be opt-in and excluded from logs `[MAY]` |
| Command-provided secret | run a command to obtain the key/token (Codex `auth { command, args }`, 1Password/`op`, `gcloud auth print-access-token`) | `[SHOULD]` |

### 6.2 OAuth 2.0 / 2.1 support (`AUTH-2 [SHOULD]`, `auth-oauth` module)

- Flows: **Authorization Code + PKCE** (S256; loopback redirect `http://127.0.0.1:<port>/callback` with a tiny embedded HTTP listener, or manual paste of the redirect URL/code for headless/remote), **Device Authorization** (RFC 8628, polling with `interval`, `slow_down` handling), **Client Credentials**, **Refresh Token** (with rotation: always persist the newest refresh token immediately; some providers invalidate the previous one).
- Token store SPI: in-memory, encrypted file, OS keychain (Windows Credential Manager, macOS Keychain, Linux Secret Service), pluggable vault. Store `{access, refresh, expiresAt, scopes, accountId/email, providerProfileId}`; multi-account profiles per provider; "token sink" behaviour to avoid two tools logging each other out (OpenClaw pattern).
- Refresh policy: refresh proactively before expiry (skew, e.g. 60–120 s), single-flight refresh under concurrency, retry once on 401 with forced refresh, surface `AuthenticationRequired` errors with an actionable "how to login" hint.
- Discovery: RFC 8414 authorization server metadata; RFC 9728 protected resource metadata (needed if the SDK later adds an MCP client).
- Clock skew tolerance; `expires_in` vs JWT `exp`; extract account ids from ID tokens/JWT claims when providers require them as headers.

### 6.3 Known provider OAuth/subscription surfaces (`[VERIFY]` all; `[POLICY]` where flagged)

| Provider | Flow | Notes |
|---|---|---|
| OpenAI Codex (ChatGPT Plus/Pro/Team) | Auth code + PKCE at `https://auth.openai.com/oauth/authorize` → `/oauth/token`; loopback `127.0.0.1:1455/auth/callback`; device-code flow for headless | Access tokens ~hours; account id extracted from token; requests go to `https://chatgpt.com/backend-api/codex/responses` with Codex-specific headers/instructions; **intended for Codex CLI**, third-party use is a community pattern and may be restricted `[POLICY]`. Also `~/.codex/auth.json` import. |
| Anthropic (Claude Pro/Max) | Claude Code's PKCE flow / `claude setup-token` (`sk-ant-oat01-…`) | **Feb‑2026 ToS prohibits subscription OAuth tokens in third-party tools; Apr‑2026 billing enforcement** `[POLICY]`. The SDK should support the mechanism generically (bearer + beta header) for first-party/authorized clients only, and default to API keys. |
| Google (Gemini CLI / Code Assist / "Google AI Pro") | Auth code + PKCE with Google OAuth (Cloud Code Assist scopes), `~/.gemini/oauth_creds.json` import | Requests go to Code Assist endpoints, not the public Gemini API `[VERIFY][POLICY]`. |
| GitHub Copilot | Device flow with GitHub OAuth app → `copilot_internal/v2/token` short-lived token → `api.githubcopilot.com` | Requires editor identification headers; plan-dependent model list `[POLICY]`. |
| OpenRouter | PKCE flow (`https://openrouter.ai/auth?callback_url=…`, exchange at `/api/v1/auth/keys`) issues a *user-scoped API key* | Simple and officially supported for third-party apps. |
| Cloud IAM | GCP ADC, AWS SigV4/STS/SSO, Azure Entra | Prefer delegating to vendor SDK credential providers via adapters. |

- `AUTH-3 [MUST]` Auth is a **strategy object per endpoint** that can add headers, query params, sign bodies, and react to 401/403 (refresh/rotate); it must never be baked into dialect code.
- `AUTH-4 [SHOULD]` Key pools: multiple keys per endpoint with strategies (round-robin, least-loaded, failover on 429/402/401), per-key rate-limit state, per-key usage accounting. Useful for gateways and for free-tier juggling.
- `AUTH-5 [SHOULD]` BYOK pass-through: forward an end-user's key per request (gateway use case) with strict no-persist/no-log guarantees.
- `AUTH-6 [MUST]` Required protocol headers are provider-profile driven: `anthropic-version` (required), `anthropic-beta` (comma list; SDK adds needed betas automatically per feature and lets users add more), `OpenAI-Beta` (e.g. assistants/realtime), `Api-Revision` (Gemini interactions), `User-Agent` (identify the SDK + app; Ollama/llama.cpp don't care, OpenRouter shows `X-Title`), `HTTP-Referer`/`X-Title` (OpenRouter attribution, optional), `X-Request-Id`/idempotency headers where supported.
- `AUTH-7 [MUST]` Security: secrets never in URLs by default, never in logs/traces/exceptions; zeroize where practical; TLS verification on by default with explicit opt-out per endpoint for local self-signed setups; support custom trust stores and system proxies with proxy auth.

### 6.4 Pitfalls (auth)

- Two credential env vars for one provider with precedence rules (Claude Code: non-empty `ANTHROPIC_API_KEY` wins over `ANTHROPIC_AUTH_TOKEN`); document and mirror.
- Codex config `env_http_headers` "Bearer Bearer" bugs: don't double-prefix when the source already includes the scheme.
- Refresh-token rotation races between two processes sharing a token file → file locking + re-read-before-write.
- Loopback callback port conflicts; provide port ranges and the manual-paste fallback.
- OAuth tokens that authenticate only against product-specific endpoints (Claude Code, Codex, Code Assist) must not be sent to the public API paths; encode this in provider profiles.
- Gateways that require an empty upstream key (`ANTHROPIC_API_KEY=""`) — support "explicitly empty" credentials.

---

## 7. Connection and transport

### 7.1 HTTP client abstraction (`NET-1 [MUST]`)

- `HttpTransport` SPI: `send(request) → response` (buffered) and `stream(request) → byte/line stream` (unbuffered), with header access before body, cancellation, per-request timeouts, and a `RawEvent` hook for interceptors.
- Default implementation on `java.net.http.HttpClient` (JDK 11+, HTTP/2 negotiation, `BodyHandlers.ofInputStream`/`ofLines` for streaming). Adapters for OkHttp (Android), Apache HttpClient 5, Reactor Netty / Vert.x (reactive servers).
- Known JDK HttpClient gaps to compensate in the SDK: **no read/idle timeout** (only connect timeout + a per-request timeout that covers headers, not the body) → implement a stream-idle watchdog; HTTP/2 GOAWAY/`IOException: EOF` on stale pooled connections → one transparent retry when no bytes were sent to the consumer; no built-in `Retry-After` handling; body compression must be handled manually (`Accept-Encoding: gzip` + decompressing wrapper).

### 7.2 Protocol/TLS/proxy (`NET-2 [MUST]`)

- HTTP/1.1 and HTTP/2 (allow forcing 1.1 per endpoint — some proxies/SSE paths break on h2); TLS 1.2/1.3; custom `SSLContext`/trust store; hostname verification toggle (local only); optional mTLS; SNI.
- Proxies: HTTP/HTTPS CONNECT and SOCKS5, per-endpoint override, system/env proxy discovery (`HTTPS_PROXY`, `NO_PROXY` incl. CIDR and suffix rules), proxy auth. Warn that buffering proxies (and some corporate TLS inspectors) delay or break SSE.
- IPv4/IPv6: `localhost` may resolve to `::1` while Ollama/llama.cpp bind `127.0.0.1` — prefer explicit `127.0.0.1` in local presets and fall back across address families.
- DNS: JVM caches positive DNS forever under a security manager default (`networkaddress.cache.ttl`) — document; gateways with rotating IPs need a sane TTL.

### 7.3 Timeouts taxonomy (`NET-3 [MUST]`)

| Timeout | Meaning | Suggested default |
|---|---|---|
| connect | TCP+TLS establish | 10 s |
| response headers (TTFB) | until status/headers arrive | 60 s non-stream; 120–300 s stream (reasoning models can think for minutes before first token; TTFT for a 1M-token prompt on a local box can be many minutes) |
| stream idle | max gap between two stream chunks (keep-alive comments/pings reset it) | 60–120 s (Codex uses 300 s; long tool-heavy turns behind gateways may need 2 h) |
| total request | wall clock for one call | 10 min non-stream; unbounded-but-idle-guarded for streams |
| model load (local) | first request after cold start | 5–15 min on Ollama/llama.cpp with large models; make configurable and observable |
| per-operation | metadata calls short (10 s); batch/file uploads long | |

- Timeouts configurable at endpoint, model and request level; expose which timeout fired in the error.

### 7.4 Pools and concurrency (`NET-4 [SHOULD]`)

- Connection pool per endpoint/host; keep-alive; max concurrent requests per endpoint and per key (semaphores) with queueing and queue timeout; priority lanes (interactive vs batch); fair-share across tenants (gateway use).
- Local servers: concurrency limited by slots (`llama.cpp` `n_parallel`, Ollama `OLLAMA_NUM_PARALLEL`); the SDK should read `/props.total_slots` and size its limiter automatically when possible.

### 7.5 Retries (`RETRY-1 [MUST]`)

- Classification: retryable = network errors before any response byte, 408, 409 (conflict, some providers), 425, 429, 500 (provider-dependent), 502, 503, 504, 529 (Anthropic overloaded), OpenRouter 502/503 provider errors; **not retryable** = 400/401/403/404/413/422, content-filter, insufficient credits (402), context-length exceeded, invalid schema, malformed tool call replay errors.
- Policy: exponential backoff with full jitter, cap, max attempts, total retry budget; honor `Retry-After` (seconds or HTTP-date) and provider reset headers (Anthropic RFC 3339 timestamps; OpenAI Go-style durations like `6m0s`; OpenRouter `X-RateLimit-Reset` epoch ms `[VERIFY]`), with a sanity cap.
- Streaming rules: retry only if **no event has been delivered** to the consumer (or the consumer opted into idempotent restart); on mid-stream failure surface a `StreamInterrupted` error with the partial accumulation so callers (agents) can decide.
- Distinguish 429 (your quota) from 529/503 (their capacity): the latter should trigger failover to another endpoint sooner (RETRY policy hook `onOverloaded → failover`).
- Idempotency: send an idempotency key header where supported (`Idempotency-Key`/`X-Request-Id`; provider support is sparse `[VERIFY]`); never retry a request that may have been billed unless the caller allows it.
- Hedged/duplicate requests are **off by default** (they double cost).
- Retry counters and reasons exposed in response metadata and metrics.

### 7.6 Rate limiting (`RETRY-2 [SHOULD]`)

- Client-side limiters per endpoint/key/model: requests-per-minute, tokens-per-minute (input/output/combined), requests-per-day (free tiers), concurrency; token-bucket with burst; adaptive tightening on 429; pre-flight estimation of input tokens to reserve TPM.
- Parse and expose rate-limit headers: OpenAI `x-ratelimit-limit-requests`, `-remaining-requests`, `-reset-requests`, `-limit-tokens`, `-remaining-tokens`, `-reset-tokens` (+ project-scoped variants); Anthropic `anthropic-ratelimit-{requests,tokens,input-tokens,output-tokens}-{limit,remaining,reset}` (tokens rounded to nearest thousand), `retry-after`; OpenRouter `X-RateLimit-Limit/Remaining/Reset`; Gemini: none documented (backoff only); Azure `x-ratelimit-*` variants; missing headers must degrade gracefully.
- Rate-limit state is part of *Endpoint Info* (screenshot: *Limit Resets*, *Free-Model Requests 0 of 1,000 used*).

### 7.7 Health, reachability, circuit breaking (`NET-5 [SHOULD]`)

- `probe(endpoint)`: DNS/TCP/TLS/auth/`/models` (or `/health`) check with latency; results feed *Endpoint Info → Reachable ✓*.
- Circuit breaker per endpoint (open on consecutive failures/overloads, half-open probes), with events for UIs.
- Background health polling optional (never for paid endpoints without opt-in).

### 7.8 Routing, fallback, load balancing (`NET-6 [SHOULD]`)

- Endpoint groups: ordered fallback chains (`primary → secondary`), weighted round-robin, least-latency, cheapest-first (uses pricing catalog), capability-aware selection (only endpoints whose model supports required features: tools, vision, JSON schema, reasoning), data-residency constraints via tags.
- Model fallback lists (OpenRouter `models[]`, `route: fallback`) and provider preferences (`provider.order/only/ignore/sort/max_price/quantizations`) exposed as first-class request options when the endpoint is OpenRouter; emulated client-side otherwise.
- Sticky routing for prompt-cache affinity: keep a conversation on the same endpoint/key/provider to preserve cache hits (OpenAI `prompt_cache_key` routing, Anthropic cache is per-org, OpenRouter caching depends on the upstream provider chosen — pin `provider.order` when relying on caches).
- Routing decisions recorded in response metadata (`servedBy`, `attempts[]`).

### 7.9 Streaming protocols (`STRM-1 [MUST]`)

| Framing | Details the decoder must handle |
|---|---|
| **SSE** (OpenAI, Anthropic, Gemini `alt=sse`, OpenRouter, llama.cpp, vLLM, most) | WHATWG rules: events separated by blank line; `data:` lines concatenated with `\n`; optional `event:`, `id:`, `retry:`; lines starting with `:` are comments/keep-alives (OpenRouter `: OPENROUTER PROCESSING`, Anthropic `ping` is a real event); CRLF/LF/CR line endings; **split UTF‑8 multibyte sequences across TCP chunks** → decode after byte-level line splitting; `data: [DONE]` terminator (OpenAI chat, OpenRouter, many compat servers — not in Anthropic/Gemini/Responses-spec); error JSON delivered as a normal `data:` event with HTTP 200 (OpenRouter, some gateways) or as `event: error` (Anthropic, Responses); non-200 status with JSON body before any event; `Content-Type: text/event-stream` check; very large single events (base64 images in stream) |
| **NDJSON** (Ollama native) | one JSON object per line; final object has `done: true` plus stats; partial lines across chunks |
| **JSON array streaming** (Gemini without `alt=sse`) | avoid; always request `alt=sse` |
| **WebSocket** (OpenAI Realtime; Responses over WS `[VERIFY]`; some local UIs) | JSON text frames, ping/pong, reconnect with state |
| **AWS event-stream** (Bedrock) | binary prelude/headers/CRC framing; use AWS SDK or implement decoder |
| **gRPC** (Vertex optional) | out of scope; REST is sufficient |
| **HTTP chunked plain text** (rare legacy servers) | treat as raw byte stream |

- `STRM-2 [MUST]` Decoder emits **raw frames** (for passthrough/gateways) and **semantic unified events** (for apps). Both must be available simultaneously (tee).
- `STRM-3 [MUST]` Accumulator: rebuilds a complete `Response` from events (text concatenation per part index, tool-call argument JSON assembly by index/id, reasoning blocks with signatures, usage merge, finish reason), and exposes partial state during the stream (for UIs showing "so far").
- `STRM-4 [MUST]` Tool-call arguments arrive as partial JSON deltas: provide an incremental/partial JSON parser (lenient) for early previews, but only mark a tool call `complete` when the provider signals done.
- `STRM-5 [MUST]` Cancellation closes the connection promptly; consumers can request "stop but keep partial".
- `STRM-6 [SHOULD]` Keep-alive/heartbeat events exposed (for idle-timeout resets and UIs), plus `sequence_number`/`index` preservation for Responses.
- `STRM-7 [SHOULD]` Stream to non-stream adapter (collect) and non-stream to stream adapter (emit one synthetic sequence) so callers and gateways can mix modes.

### 7.10 Compression and size (`NET-7 [SHOULD]`)

- Request gzip where accepted (few providers; test), response gzip/br decoding; body size guards (base64 images/PDFs inflate ~33%; providers cap request bodies: e.g. Anthropic 32 MB, OpenAI ~ tens of MB `[VERIFY]`); large-file paths should prefer Files APIs.

### 7.11 Pitfalls (transport)

- Proxies/load balancers buffering SSE → configure `X-Accel-Buffering: no` on your own gateways; detect "all events arrive at once" and warn.
- HTTP/2 + SSE + some CDNs → intermittent `RST_STREAM`; allow forcing HTTP/1.1.
- Long-lived idle sockets closed by NAT → keep-alives; retry once on connection reset before body was read.
- Providers occasionally return `200` with an error JSON, or `text/html` error pages from CDNs (Cloudflare 5xx HTML) → content-type aware error decoding.
- Windows: `localhost` resolution, console encoding for logs, path/env quoting for command-based secrets.
- Clock skew affects OAuth expiry checks and SigV4 (15‑minute window).

---

## 8. Discovery, metadata and catalog

### 8.1 Endpoint info (`DISC-1 [MUST]`, screenshot *Endpoint Info*)

| Fact | Sources |
|---|---|
| identity: name, provider, gateway type, base URL, dialect(s), version/build | config + probes (`/props.build_info`, `/api/version`, `/version`) |
| reachability, latency, TLS info, last checked | probe |
| models served (count, ids, load state) | `/v1/models`, `/api/tags`+`/api/ps`, `/models`, `/v1beta/models`, Anthropic `/v1/models` |
| key info: label, created/expires, scopes, is free tier, spending limit, remaining credit | OpenRouter `/auth/key` + `/credits`; xAI `/v1/api-key`; Anthropic/OpenAI admin APIs (org-level, separate admin keys) `[VERIFY]`; otherwise `unknown` |
| usage: total / today / week / month; BYOK usage; requests; tokens by class; cost | OpenRouter `/auth/key` (daily/weekly/monthly) and `/activity`; LiteLLM `/spend`; OpenAI/Anthropic admin usage & cost reports `[VERIFY]`; **plus the SDK's own local accounting** (always available) |
| rate limits & resets, free-model request counters | headers + provider docs + local counters |
| account balance / credits | OpenRouter `/credits` (`total_credits - total_usage`) |
| data policies (ZDR, training, logging), region | provider profile + OpenRouter `/providers` |
| catalogue stats: #models with vision/reasoning/tools/structured output/files; modalities seen | computed from the merged catalog |

### 8.2 Model listing endpoints per dialect (`DISC-2 [MUST]`)

| Dialect / provider | Endpoint | Richness |
|---|---|---|
| OpenAI `/v1/models` | `id, created, owned_by` only | poor → needs external catalog |
| OpenRouter `/api/v1/models` (+ `?category=`, `?supported_parameters=`, `?input_modalities=`, `?output_modalities=`; opt-in pagination `offset/limit`) and `/models/{author}/{slug}/endpoints` | id, `canonical_slug`, name, description, created, `context_length`, `architecture {input_modalities, output_modalities, tokenizer, instruct_type}`, `pricing` (strings, USD **per token**), `top_provider {context_length, max_completion_tokens, is_moderated}`, `per_request_limits`, `supported_parameters[]`, `default_parameters`, `expiration_date`, `knowledge_cutoff`, `supported_voices`; per-endpoint: provider name, quantization, context, max output, pricing, uptime, latency/throughput stats, status | rich (screenshot *Model Info → Providers serving this model*) |
| Anthropic `/v1/models`, `/v1/models/{id}` | id, display_name, created_at, type, `capabilities{…}` incl. effort levels, thinking types, structured outputs, image/pdf input, batch, citations, code execution, context management; `max_input_tokens`? `[VERIFY]` | good for capabilities; no pricing |
| Gemini `/v1beta/models`, `/v1beta/models/{m}` | name, displayName, description, version, `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods`, default `temperature/topP/topK/maxTemperature`, thinking flag `[VERIFY]` | good limits; no pricing |
| Vertex | Model Garden / publisher models API | partial |
| xAI `/v1/language-models`, `/v1/models` | pricing per token/image, modalities, aliases `[VERIFY]` | good |
| Mistral `/v1/models` | capabilities (`completion_chat`, `function_calling`, `vision`, `fine_tuning`), `max_context_length`, deprecation | good |
| DeepSeek, Qwen, Groq, Together, Fireworks `/v1/models` | ids (+ some context lengths/pricing on Together/Fireworks) | mixed |
| Ollama `/api/tags`, `/api/show`, `/api/ps` | size, digest, family, parameter size, quantization, `capabilities[]`, `model_info.*.context_length`, template, license, loaded VRAM/expiry | good (local) |
| llama.cpp `/v1/models`, `/models`, `/props` | model id/alias/path, `n_ctx`, modalities (vision/audio), slots, chat template, default sampler params, build | good (local) |
| LM Studio `/api/v0/models` | type, arch, quantization, state, `max_context_length` | good |
| vLLM `/v1/models` | `max_model_len` | limits only |
| Azure | deployments API (control plane, separate auth) | partial |
| Bedrock `ListFoundationModels` | modalities, streaming support, customization; no prices | partial |
| LiteLLM `/model/info` | merged `model_prices_and_context_window` data | good |

### 8.3 Unified `ModelInfo` schema (`DISC-3 [MUST]`)

- Identity: `id` (as used on this endpoint), `canonicalId` (provider-agnostic, models.dev/OpenRouter style `vendor/model`), `aliases[]`, `displayName`, `family`, `vendor` (who trained it), `provider` (who serves it), `variant` (`:free`, quantization, deployment).
- Descriptive: `description`, `releaseDate`, `lastUpdated`, `knowledgeCutoff`, `deprecation {announced, shutdown, replacement}`, `status` (`alpha|beta|preview|ga|deprecated|retired`), `openWeights`, `license`, `parameterCount`, `architecture`, `tokenizer`, `instructType`.
- Modalities: `inputModalities[]` (text, image, audio, video, file/pdf), `outputModalities[]` (text, image, audio, embedding).
- Limits (§8.6), Pricing (§8.5), Capabilities (§8.4), `supportedParameters[]` + ranges/defaults, `defaultParameters`, `reasoningOptions` (levels, budgets, adaptive, summaries), `providerEndpoints[]` (§8.7), `rateLimits` (if known), `dataPolicy`.
- Provenance: per-field `source` (`endpoint-api | models.dev | openrouter | litellm | bundled | user-override | probe`), `fetchedAt`, `confidence`.
- Raw: the untouched provider JSON.

### 8.4 Capability model (`DISC-4 [MUST]`)

Tri-state (`SUPPORTED | UNSUPPORTED | UNKNOWN`) + source, for at least: `chat`, `completion/fim`, `streaming`, `tools` (function calling), `parallelTools`, `toolChoiceRequired`, `strictTools`, `structuredOutput.jsonMode`, `structuredOutput.jsonSchema`, `structuredOutput.grammar`, `reasoning` (+ `effortLevels[]`, `budgetTokens`, `adaptive`, `summaries`, `hiddenReasoning`), `vision.image`, `vision.pdf/document`, `audioIn`, `audioOut`, `video`, `imageGeneration`, `embeddings`, `rerank`, `promptCaching` (+ style: explicit/automatic), `batch`, `citations`, `webSearch`, `codeExecution`, `computerUse`, `mcpRemote`, `logprobs`, `seed`, `n`, `temperature`, `topK`, `minP`, `penalties`, `stopSequences`, `systemRole`/`developerRole`, `assistantPrefill`, `logitBias`, `prediction`, `serviceTiers`, `contextManagement`, `fileUpload`, `fineTuned`.
Capability facts drive: parameter validation (§9.4 policy), routing (§7.8), UI toggles (screenshot capability chips), and cost estimation.

### 8.5 Pricing model (`DISC-5 [MUST]`)

- Units: USD per **1M tokens** canonical (store as `BigDecimal`; OpenRouter gives per-token strings; models.dev per‑1M; LiteLLM per-token floats) + per-request, per-image (or per image-token/tile), per-second (audio/video), per-character (some TTS), per-search/tool call (web search), per-GB-hour (batch storage — ignore).
- Token classes: `input`, `output`, `cacheRead`, `cacheWrite` (Anthropic 5m vs 1h TTL different prices; OpenAI cache write price on newer models `[VERIFY]`), `reasoning` (usually billed as output; OpenRouter lists `internal_reasoning`), `audioInput/Output`, `imageInput`, `imageOutput`, `videoInput`, `serverToolCalls` (e.g. web search), `batchDiscount` (50%), `priorityMultiplier`/`flexDiscount` (service tiers), long-context tiers (Gemini/Claude >200k tokens priced higher `[VERIFY]`), free tier flags.
- Effective dates and currency; pricing provenance; user overrides (screenshot: *Input Price 0.15 / Output Price 0.6*).
- Calculator: `cost(usage, pricing) → Money {amount, currency, exact|estimated, breakdown}`; prefer provider-reported cost when present (OpenRouter `usage.cost`, LiteLLM headers `x-litellm-response-cost` `[VERIFY]`), else compute; flag when pricing unknown.

### 8.6 Limits (`DISC-6 [MUST]`)

- `contextWindow` (total tokens; Anthropic counts input+output within 200k/1M; OpenAI context includes output; Gemini has separate `inputTokenLimit` and `outputTokenLimit`) — store both `maxInput` and `maxOutput` and `maxTotal` explicitly, with semantics flag.
- `maxOutputTokens` (hard cap) vs `defaultMaxOutput` (what the provider uses if omitted; Anthropic requires explicit `max_tokens`, gateways like LiteLLM inject 4096).
- Per-request limits: max images, max image bytes/dimensions, max PDF pages/bytes, max tools, max tool-name length (64), max stop sequences (4 on OpenAI), max system prompt, max `n`, max logprobs (20), max metadata keys.
- Rate limits (RPM/TPM/RPD/ITPM/OTPM) per tier when known; local: slots/parallelism, `n_ctx` per slot.
- Streaming/time limits: max request duration (some gateways cap at 10 min), batch turnaround (24 h).

### 8.7 Provider variants per model (`DISC-7 [SHOULD]`, screenshot *Providers serving this model*)

For gateways: list of concrete endpoints for a model with `providerName`, `quantization` (fp4/fp8/bf16…), `contextLength`, `maxCompletionTokens`, pricing, `uptime`, `latency p50/p95`, `throughput tok/s`, `status`, `supportedParameters`, data policy; allow pinning/ignoring providers per request (OpenRouter `provider` object).

### 8.8 External catalog sources and merge strategy (`DISC-8 [SHOULD]`)

| Source | Format | Strengths | Caveats |
|---|---|---|---|
| **models.dev** `api.json` / `models.json` / `catalog.json` | provider-keyed JSON; per model: `name, family, attachment, reasoning, tool_call, structured_output, temperature, knowledge, release_date, last_updated, open_weights, status, cost {input, output, cache_read, cache_write, reasoning?}, limit {context, output}, modalities {input[], output[]}, reasoning_options, provider {api, npm, env[]}, base_model` | community-maintained, broad, used by OpenCode; provider `env`/`api` hints | lag on new models; per‑1M USD; capability booleans are coarse |
| **OpenRouter** `/models` + `/endpoints` | see §8.2 | live, rich, `supported_parameters`, provider variants | OpenRouter ids/pricing, not first-party ids |
| **LiteLLM** `model_prices_and_context_window.json` | flat JSON keyed by `provider/model` with `max_input_tokens`, `max_output_tokens`, `input_cost_per_token`, cache costs, `supports_*` booleans, `mode` | very broad, includes Bedrock/Vertex/Azure prices | key naming quirks; per-token floats |
| **Endpoint-native** lists | §8.2 | authoritative ids/limits | rarely pricing |
| **Bundled snapshot** | shipped with SDK release | offline default | stale |
| **User overrides** | config | final say | — |

Merge: field-level precedence `user-override > endpoint-native (ids/limits/capabilities) > OpenRouter (pricing on OpenRouter endpoints) > models.dev > LiteLLM > bundled`, with id normalization (strip `:free`, date suffixes, `-latest`, vendor prefixes; keep alias table). Record conflicts.

### 8.9 Caching, refresh, offline (`DISC-9 [MUST]`)

- Catalog cache with TTL (e.g. 24 h), ETag/If-Modified-Since where supported, manual `refresh()`, background refresh optional; persisted to disk (JSON) for offline start; "fetched yesterday" style provenance (screenshot *Fetched: yesterday*).
- Never block a chat call on catalog fetch; use `unknown` capabilities and estimate.

### 8.10 Model id conventions (`DISC-10 [MUST]`)

- Forms: `gpt-5.6`, `claude-opus-5-20260724` (dated) vs `claude-opus-5` (alias), `gemini-3.8-flash`, OpenRouter `vendor/model[:variant]`, Ollama `name:tag` (`qwen3:8b-q4_K_M`), HF `org/repo`, llama.cpp alias or file path, Azure deployment names, Bedrock `region.vendor.model-vX:Y` inference profiles, Vertex `publishers/anthropic/models/claude-...@date`.
- `ModelRef` grammar: `[endpoint:]modelId[@version][#variant]` (design choice) with escaping for ids containing `:`; alias resolution table; record `response.model` (served snapshot) separately from requested id.

### 8.11 Pitfalls (metadata)

- Capabilities differ per **provider endpoint** of the same model (quantized endpoints may lack tools or JSON mode) — `supported_parameters` on the model is a union; check the chosen endpoint.
- Context window advertised ≠ usable (Ollama `num_ctx`; llama.cpp `n_ctx / n_parallel`; gateway caps; provider "1M context" behind a beta header or tier).
- Prices change; free variants have separate limits; "$0" may mean *unknown* on some sources — distinguish `ZERO` from `UNKNOWN`.
- OpenAI `/models` lists models the key cannot use (project restrictions) → treat as hints.
- Model list size (OpenRouter 400+; Ollama library) → paginate and cache.

---

## 9. Unified request model

### 9.1 Messages and roles (`REQ-1 [MUST]`)

- Roles: `system` (a.k.a. `developer` on OpenAI; top-level `system` on Anthropic; `systemInstruction` on Gemini; `instructions` on Responses; Ollama `system` message), `user`, `assistant`, `tool` (OpenAI tool result message; Anthropic `tool_result` block inside a `user` message; Gemini `functionResponse` part in a `user` role; Responses `function_call_output` item).
- Rules the encoder must enforce/adapt per dialect: system placement; role alternation (Anthropic historically strict, now merges consecutive same-role messages `[VERIFY]`; Gemini requires alternation and merges); tool results must immediately follow the assistant tool call (Anthropic) and all results for parallel calls go in one message; `name` field on messages (OpenAI) has no equivalent elsewhere; assistant **prefill** (Anthropic: last assistant message continues; not allowed with extended thinking; OpenAI has no prefill — emulate with `prediction`? no — document as unsupported); empty content rules (Anthropic rejects empty text blocks; OpenAI allows `content: null` with tool calls).
- Multi-system messages: concatenate or convert extra ones to user messages per dialect policy; keep cache breakpoints attached to the right block.
- Mid-conversation system messages (Anthropic turn-scoped `role: system` with `clear_at` beta `[VERIFY]`; OpenAI developer messages anywhere) — model as a message with a `scope` hint.

### 9.2 Content parts (`REQ-2 [MUST]`)

`text`, `image` (url | base64 | fileId | local path → SDK reads & encodes; `detail`/`mediaResolution` hint), `audio` (data+format; `input_audio`), `video` (Gemini inline/file, Qwen-VL), `document` (PDF/text/markdown/csv…: Anthropic `document` block with `title`, `context`, `citations`; OpenAI `input_file` (`file_id` / `file_data` / `file_url`); Gemini `fileData`/`inlineData`; OpenRouter `file` + `file-parser` plugin), `toolCall {id, name, argumentsJson, providerMeta}`, `toolResult {toolCallId, name, parts[] (text/image/document), isError}`, `reasoning {text, signature, redacted?, providerMeta (Anthropic signature, OpenAI encrypted_content/item id, Gemini thoughtSignature, OpenRouter reasoning_details)}`, `citation`/`annotation` (url citations, file citations, Anthropic citations with locations), `refusal` (OpenAI), `serverToolUse`/`serverToolResult` (web search, code execution outputs, container files), `opaque` (provider-specific block passed through untouched).
- "Send text files as text" option (screenshot *Attach Files → Send Text Files As Text*): decide per MIME/size whether a file becomes a `text` part (fenced, with filename header) or a `document` part; configurable size cap and encoding detection.
- Attachment strategy for multiple files (screenshot *Attach: All files in one request*): all-in-one message vs one-per-message vs one-request-per-file (fan-out) — SDK exposes helpers, the app decides.

### 9.3 Media and file handling (`MEDIA-1 [MUST]`)

- Sources: `URL` (only if provider fetches URLs: OpenAI, Anthropic (url source), Gemini (`fileUri` only for its File API / GCS on Vertex), OpenRouter; Ollama/llama.cpp need base64), `base64`, `fileId` (provider Files API), local `Path`/`InputStream` (SDK loads, sniffs MIME, validates size, resizes/downscales images optionally, converts HEIC/TIFF if needed `[MAY]`).
- Files APIs: OpenAI `/v1/files` (+ `purpose`), Anthropic Files API (beta header `files-api-2025-04-14`), Gemini File API (resumable upload, 48 h TTL, `state: PROCESSING`), OpenRouter (inline only + PDF engine choice), Mistral files/OCR; expose `upload/list/get/delete` per endpoint with capability gating.
- Limits: image formats (png/jpeg/gif/webp, sometimes bmp/tiff), max size (OpenAI 50 MB per image `[VERIFY]`; Anthropic 5 MB/image, 100 images `[VERIFY]`; Gemini inline 20 MB request), max dimensions, PDF page limits (Anthropic 100 pages/32 MB `[VERIFY]`), audio formats (wav/mp3/…), video via File API.
- Output media: image generation (OpenAI Images API `gpt-image-*`, Responses `image_generation` tool, Gemini `responseModalities: [IMAGE]`, xAI `/images/generations`, OpenRouter `modalities: ["image","text"]`) → unified `image` output parts with `b64`/`url`, size, mime, revised prompt, C2PA/SynthID flags where reported; audio output (TTS endpoints, `modalities: ["audio"]` in chat, Gemini speech config); STT (`/audio/transcriptions`).

### 9.4 Generation parameters (`REQ-3 [MUST]`)

Unified fields (with per-dialect mapping in Appendix A): `maxOutputTokens`, `temperature`, `topP`, `topK`, `minP`, `typicalP`, `topA`, `frequencyPenalty`, `presencePenalty`, `repetitionPenalty`, `repeatLastN`, `stopSequences[]`, `seed`, `n` (candidates), `logprobs`/`topLogprobs`, `logitBias`, `responseFormat`, `toolChoice`, `parallelToolCalls`, `reasoning` (§9.5), `verbosity` (OpenAI), `serviceTier`, `safety` (Gemini settings), `userId`/`safetyIdentifier`, `metadata` (kv), `store`, `prediction` (OpenAI predicted outputs), `truncation` (Responses `auto|disabled`), `maxToolCalls`, `keepAlive` (Ollama), `numCtx` (Ollama), `mirostat*`, `dry*`, `grammar` (llama.cpp GBNF), `samplersOrder`, `cachePrompt` (llama.cpp), `idSlot`, `timingsPerToken`, `extras` (native passthrough map), `extraHeaders`, `extraQuery`.

- Ranges/clamping: temperature OpenAI 0–2, Anthropic 0–1, Gemini 0–2 (model-dependent `maxTemperature`), Ollama unbounded; `topK` unsupported on OpenAI; `minP` only on open-model servers/OpenRouter; penalties differ (`presence/frequency` vs `repeat_penalty`).
- **Unsupported-parameter policy** (`REQ-4 [MUST]`): per endpoint: `ERROR | WARN_AND_DROP | DROP_SILENTLY | PASSTHROUGH`; default `WARN_AND_DROP` for known-unsupported (from catalog/profile), `PASSTHROUGH` for unknown on compat servers, `ERROR` in strict mode. Special cases: OpenAI reasoning models reject `temperature/top_p/logprobs/logit_bias` (drop with warning); Anthropic rejects `temperature`+`top_p` together `[VERIFY]` and forces `temperature=1` when thinking is enabled; Gemini rejects both `thinkingBudget`+`thinkingLevel`.
- Parameter defaults layering: SDK defaults are *none* (send nothing → provider default) except `maxOutputTokens` where required (Anthropic) — resolved from catalog `maxOutput` (or a conservative fallback) with a warning if guessed.
- Field name choice for max tokens per profile: `max_tokens` (Anthropic, legacy chat, OpenRouter), `max_completion_tokens` (OpenAI chat), `max_output_tokens` (Responses), `maxOutputTokens` (Gemini), `num_predict` (Ollama), `n_predict` (llama.cpp).

### 9.5 Reasoning / thinking controls (`RSN-1 [MUST]`)

Unified `ReasoningConfig`: `mode` (`OFF | ON | ADAPTIVE | DEFAULT`), `effort` (`NONE | MINIMAL | LOW | MEDIUM | HIGH | XHIGH | MAX`), `budgetTokens` (int), `display` (`HIDDEN | SUMMARY | FULL | UPDATES`), `includeInOutput` (bool), `interleaved` (thinking between tool calls), `persist` (`AUTO | REPLAY_SIGNED | STRIP`).

| Dialect / provider | Mapping |
|---|---|
| OpenAI chat | `reasoning_effort: none? \| minimal \| low \| medium \| high \| xhigh` (model-dependent set); output not returned (only `reasoning_tokens` in usage) |
| OpenAI Responses | `reasoning: {effort, summary: auto\|concise\|detailed}`; `include: ["reasoning.encrypted_content"]` for stateless replay; reasoning items must be passed back between tool calls |
| Anthropic | `thinking: {type: enabled, budget_tokens}` (legacy on 4.6+, rejected on Opus 4.7+ `[VERIFY]`) or `{type: adaptive}` + `output_config: {effort}`; `thinking.display: summarized \| omitted \| updates` (beta); `thinking`/`redacted_thinking` blocks with `signature` **must be replayed verbatim** (prefix-bound on newest models; betas control drop-vs-reject); per-message effort via mid-conversation system message beta; interleaved thinking auto with adaptive; `thinking_tokens` in usage `[VERIFY]` |
| Gemini generateContent | `thinkingConfig: {thinkingLevel: low\|medium\|high}` (or legacy `thinkingBudget: -1 dynamic / 0 off / n`), `includeThoughts: true` → parts with `thought: true`; `thoughtSignature` on parts must be echoed (function-call flows); Interactions API: `thinking_summaries`, thought steps carry signatures `[VERIFY]` |
| OpenRouter | `reasoning: {effort \| max_tokens, exclude, enabled}`; response `reasoning` + `reasoning_details[{type: reasoning.text\|reasoning.summary\|reasoning.encrypted, text, signature, id, format, index}]` → replay `reasoning_details` in assistant messages for Anthropic/OpenAI-backed models `[VERIFY]` |
| DeepSeek | `reasoning_content` in message (chat); thinking toggle via model choice / `thinking` param `[VERIFY]`; efforts `high`/`xhigh`; replay guidance per docs |
| Qwen/DashScope | `enable_thinking`, `thinking_budget`, `reasoning_effort`, `preserve_thinking`; Anthropic-compat defaults ON |
| xAI | `reasoning_effort: low\|high` (mini models); `reasoning_content` / encrypted reasoning `[VERIFY]` |
| Ollama | `think: true\|false\|"low"\|"medium"\|"high"\|"max"`; response `thinking` field |
| llama.cpp | `reasoning_format` (server flag), `chat_template_kwargs: {enable_thinking}`, `reasoning_effort` fed to template, `--reasoning-budget`; output `reasoning_content` |
| vLLM/SGLang | reasoning parser flags; `chat_template_kwargs`; `reasoning_content` |
| Mistral Magistral, Groq, Perplexity | `reasoning_content` or `reasoning_format` (Groq `parsed\|raw\|hidden`) |

- `RSN-2 [MUST]` Effort level translation table with rounding rules (e.g. `XHIGH` on a provider with only low/medium/high → `HIGH` + warning), and per-model allowed sets from catalog (`Anthropic /models capabilities.effort`, OpenRouter `supported_parameters`).
- `RSN-3 [MUST]` Reasoning persistence: signed/encrypted blobs are kept as opaque provider metadata on `reasoning` parts and replayed **only to the same provider/model family**; when switching providers, convert to plain text (`<thinking>` delimited) or strip, and report the transformation (pi-ai's documented approach).
- `RSN-4 [SHOULD]` Inline `<think>…</think>` tags in `content` (misconfigured servers, some open models) → optional parser into reasoning parts.
- `RSN-5 [MUST]` Budget interplay: reasoning tokens count toward `max_tokens` on Anthropic/Gemini/Qwen → the SDK should warn when `budgetTokens ≥ maxOutputTokens` and detect "thinking consumed all output" (`stop_reason: max_tokens` with empty text) as a typed condition.

### 9.6 Tools / function calling (`TOOL-1 [MUST]`)

- `ToolDefinition {name, description, parametersJsonSchema, strict?, annotations (readOnly, destructive…), cacheControl?, defer/search hints}`; name constraints (OpenAI `^[a-zA-Z0-9_-]{1,64}$`; Gemini similar; Anthropic `^[a-zA-Z0-9_-]{1,128}$` `[VERIFY]`); description length caps.
- `toolChoice`: `AUTO | NONE | REQUIRED(any) | SPECIFIC(name) | ALLOWED_SUBSET(names)` ↔ OpenAI `none|auto|required|{function}`/`allowed_tools`; Anthropic `{type: auto|any|tool|none, disable_parallel_tool_use}`; Gemini `functionCallingConfig {mode: AUTO|ANY|NONE|VALIDATED, allowedFunctionNames}`; Ollama none (only by presence).
- `parallelToolCalls` (OpenAI default true; Anthropic `disable_parallel_tool_use`; Gemini supports parallel; many compat servers emit one).
- Tool call ids: OpenAI `call_…`, Anthropic `toolu_…`, Gemini has **no ids** (SDK synthesizes stable ids and maps back by name+order), Responses has `call_id` + item `id`; ids must survive translation for proxies.
- Tool results: text, JSON (stringify), images/documents (Anthropic/OpenAI support image tool results), `isError`; multiple results per turn; ordering; empty results.
- Streaming tool calls: OpenAI deltas by `index`; Anthropic `input_json_delta` per block; Gemini whole `functionCall` parts; Responses `function_call_arguments.delta/done`.
- Provider/server-side built-in tools (typed passthrough, not executed by SDK): OpenAI `web_search`, `file_search`, `code_interpreter`, `computer_use_preview`, `image_generation`, `mcp` (remote MCP with `server_url`/tunnel, `require_approval`, `allowed_tools`, headers; approval request/response items), `local_shell`, `shell`, `apply_patch`, custom tools with grammars `[VERIFY]`; Anthropic `web_search_20250305`, `web_fetch`, `code_execution`, `text_editor`, `bash`, `computer`, `memory`, tool search / deferred tools, `mcp_servers`; Gemini `googleSearch`, `codeExecution`, `urlContext`, `fileSearch`, `googleMaps`, `computerUse`; xAI live search; OpenRouter `web` plugin; Perplexity built-in search. Model them as `BuiltInTool(type, config)` + typed result parts + usage of server tool calls (billing).
- MCP integration (`TOOL-2 [SHOULD]`): converters `McpTool → ToolDefinition` and `ToolCall → MCP tools/call` argument shape; passthrough of remote MCP server declarations to providers that host MCP calls.
- Strict mode/schema normalization (`TOOL-3 [SHOULD]`): a schema normalizer producing provider-compatible variants (OpenAI strict: all properties `required`, `additionalProperties:false`, limited keywords, no `$ref` cycles beyond limits; Gemini: OpenAPI subset — no `$ref`/`anyOf` historically, `nullable`, `propertyOrdering`, enum only for strings `[VERIFY]`; Anthropic: JSON Schema draft with `strict` beta `[VERIFY]`; Ollama/llama.cpp: JSON Schema → grammar). Optional client-side validation of tool arguments when server strictness is unavailable.
- Optional helper `ToolRunner` (`TOOL-4 [MAY]`): executes registered Java functions for tool calls and loops until finish, with hooks — kept out of core.

### 9.7 Structured output (`SO-1 [MUST]`)

- Unified `OutputFormat`: `TEXT`, `JSON_OBJECT` (free-form JSON), `JSON_SCHEMA {name, schema, strict, description}`, `GRAMMAR {gbnf}` (local), `REGEX` (some local servers), plus `mimeType` (Gemini `responseMimeType`, e.g. `text/x.enum`).
- Mapping: OpenAI chat `response_format {type: json_object | json_schema {json_schema:{name, schema, strict}}}`; Responses `text.format {type: json_schema, name, schema, strict}`; Anthropic `output_format {type: json_schema, schema}` (+ beta header on older models) or tool-forcing emulation; Gemini `responseMimeType: application/json` + `responseSchema` (subset) / `responseJsonSchema` `[VERIFY]`; Ollama `format: "json" | schema`; llama.cpp `response_format {type: json_object, schema}` or `json_schema`/`grammar`; vLLM `structured_outputs`/`guided_json`; Mistral `json_schema`; OpenRouter `response_format` + `require_parameters` provider routing + `response-healing` plugin.
- Emulation ladder when unsupported: (1) tool-forcing with a single tool whose schema is the desired output; (2) system-prompt instruction + JSON extraction + validation + optional retry; the SDK reports which strategy was used.
- Client-side JSON Schema validation (optional dependency) and typed binding (`ResponseParser<T>` via Jackson) — helpful for the facade.
- Pitfall: strict schema + streaming yields partial JSON → provide partial-JSON preview; refusals (`refusal` part) can replace JSON on OpenAI; Gemini + thinking + schema combinations had bugs (empty parts) — detect empty-output-with-STOP as an error condition.

### 9.8 Prompt caching / cache control (`CACHE-1 [MUST]`)

| Provider | Mechanism | Unified handling |
|---|---|---|
| Anthropic | Explicit `cache_control: {type: ephemeral, ttl: "5m"\|"1h"}` on system blocks, tools (last tool), messages content blocks; max 4 breakpoints; min cacheable length per model (1024/2048/4096 tokens `[VERIFY]`); cache keyed by exact prefix incl. tool definitions/order/system; switching thinking modes or changing tools invalidates; usage: `cache_creation_input_tokens`, `cache_read_input_tokens`, `cache_creation {ephemeral_5m_input_tokens, ephemeral_1h_input_tokens}` | `CacheHint.BREAKPOINT(ttl)` on parts/tools/system; auto-placement strategies (`NONE`, `SYSTEM_AND_TOOLS`, `LAST_N_USER_TURNS`, `AGENT_LOOP` = system+tools+second-to-last user turn) |
| OpenAI | Automatic prefix caching ≥1024 tokens (128-token granularity); `prompt_cache_key` (routing affinity; ≤ 15 req/min per key `[VERIFY]`); `prompt_cache_options {ttl, mode: explicit}` and explicit breakpoints on GPT‑5.6+ `[VERIFY]`; `prompt_cache_retention: 24h` (older field); usage `prompt_tokens_details.cached_tokens` (+ `cache_write_tokens` new `[VERIFY]`); diagnostics endpoint via `comparison_response_id` | `CacheHint.KEY(sessionId)`; breakpoints mapped when supported |
| Gemini | Implicit caching (automatic, `cachedContentTokenCount`) + explicit `cachedContents` resource (create with TTL, reference via `cachedContent`; minimum token counts) | `CacheHint.EXPLICIT_RESOURCE` managed by SDK helper (create/refresh/delete), plus implicit stats |
| DeepSeek | automatic; `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` | stats only |
| OpenRouter | forwards provider caching; `cache_control` for Anthropic/Gemini/…; usage `prompt_tokens_details.cached_tokens`, cost details | pass-through + stats |
| Bedrock | `cachePoint` blocks (Converse) / `cache_control` (InvokeModel) | same as Anthropic |
| llama.cpp | `cache_prompt: true` (KV reuse), slot save/restore | local KV reuse flag |
| Ollama | implicit KV reuse; `keep_alive` | — |

- `CACHE-2 [MUST]` Report cache effectiveness per response (`cachedInput`, `cacheWrite`, hit ratio) and aggregate (§13).
- `CACHE-3 [SHOULD]` Stable-prefix discipline helpers: keep tool/system ordering deterministic, warn when a request changes the prefix mid-conversation (prompt cache diagnostics), pin routing for affinity (§7.8).

### 9.9 Conversation state (`STATE-1 [MUST]`)

- Default: **stateless** — the caller (or the SDK's `Conversation` helper) resends full history in the unified model; the SDK guarantees correct replay of tool calls/results and signed reasoning per dialect.
- Stateful providers: OpenAI Responses (`store`, `previous_response_id`, `conversation` objects, item ids, `/responses/{id}`, compaction endpoint `[VERIFY]`), Gemini Interactions (`store`, `previous_interaction_id`), Mistral Conversations, Ollama legacy `context` array. Expose as `ServerStateHandle` with explicit opt-in; the SDK still keeps a local mirror so switching to a stateless endpoint works.
- Context management delegated to provider: Anthropic `context_management.edits` (`clear_tool_uses`, `clear_thinking`, `compact`) and OpenAI `truncation: auto`/compaction; unified `ContextPolicy` with provider mapping, plus client-side hooks (token-budget trimming callbacks) — the SDK does not decide summarization content.
- ZDR/no-store mode: `store:false` + `include: reasoning.encrypted_content` on OpenAI; Anthropic is stateless anyway.

### 9.10 Other request-level options (`REQ-5 [MUST]`)

`endpointOverride`, `modelOverride`, `timeouts`, `retryPolicy`, `routingHints` (provider order, fallbacks), `cacheAffinityKey`, `tags` (for metrics/cost attribution: tenant, feature, session), `idempotencyKey`, `recordingLabel`, `dryRun` (encode and return the wire JSON without sending — great for the node-flow UI and for tests), `estimateOnly` (tokens/cost estimate), `rawResponseCapture: NONE|HEADERS|BODY|STREAM`, `attempts` (screenshot *Attempts: 1*), `abortSignal`.

### 9.11 Non-chat operations (scope tiers)

| Operation | Tier | Notes |
|---|---|---|
| Chat/generate (stream & non-stream) | MUST | core |
| Embeddings (`/v1/embeddings`, Gemini `embedContent`, Ollama `/api/embed`, Cohere v2, llama.cpp `/embedding`) | SHOULD | dimensions, input types, batching, pricing per token |
| Count tokens (Anthropic, Gemini, Bedrock, llama.cpp `/tokenize`, vLLM `/tokenize`) | SHOULD | §14 |
| Image generation/edit/variation (OpenAI Images, Gemini, xAI, OpenRouter) | SHOULD | sizes, quality, `n`, background, output format, moderation, streaming partial images |
| Rerank (Cohere, Jina, OpenRouter, llama.cpp, vLLM) | MAY | |
| TTS / STT / audio (OpenAI, OpenRouter, Gemini, ElevenLabs-style) | MAY | streaming audio |
| Moderation (OpenAI `/moderations`, Mistral) | MAY | |
| Batch APIs (OpenAI `/batches` JSONL, Anthropic `/messages/batches`, Gemini batches, Vertex) | MAY | 50% discount; polling; result retrieval |
| Files APIs | SHOULD | §9.3 |
| Realtime (WebSocket audio) | MAY | separate module |
| Fine-tuning, assistants (legacy), vector stores | out of scope | |

### 9.12 Pitfalls (request model)

- `max_tokens` semantics: OpenAI legacy `max_tokens` excludes reasoning on o-series (rejected) → use `max_completion_tokens`; Anthropic includes thinking; Gemini `maxOutputTokens` includes thoughts.
- Stop sequences: OpenAI ≤4; Anthropic `stop_sequences` returned in `stop_sequence`; Gemini `stopSequences` ≤5 `[VERIFY]`; matched text excluded/included differs.
- `n>1` multiplies cost; unsupported on Anthropic; Gemini `candidateCount`.
- Seeds are best-effort everywhere; `system_fingerprint` (OpenAI) indicates backend changes.
- Base64 images in history explode token/byte counts on every turn → prefer file ids or downscale; cache breakpoints help.
- Sending both `tools` and `response_format` json_schema is allowed on OpenAI but tool-forced JSON is the norm on Anthropic; document interplay.
- `developer` role sent to servers that only know `system` → translate per profile (Codex issue class).
- Anthropic requires `tool_result` blocks *before* any text in the user message; Gemini requires `functionResponse.name` to match; Responses requires `call_id` matching.
- Tool argument JSON from models can be invalid (truncated at `length`, single quotes) → expose raw string and a lenient parse result with `valid:false`.

---

## 10. Unified response model

### 10.1 Complete (non-streaming) response (`RSP-1 [MUST]`)

`Response { id, requestedModel, servedModel, servedProvider (e.g. OpenRouter `provider`), endpointId, createdAt, message (role assistant, parts[]), toolCalls[] (convenience view), finishReason (unified) + rawFinishReason, usage, cost, rateLimit (parsed headers), timings {ttfbMs, ttftMs, totalMs, tokensPerSecond, llama.cpp timings}, requestId (provider `x-request-id` / `request-id` / OpenRouter `id` / Anthropic `request-id`), systemFingerprint, serviceTier, warnings[] (dropped params, emulations, transformations), attempts[] (retries/failovers with reasons), raw {headers, body JSON, wire request JSON if captured}, providerExtras (map) }`.

- Convenience accessors: `text()`, `reasoningText()`, `json<T>()`, `images()`, `citations()`, `hasToolCalls()`.
- Multi-candidate responses (`n>1`, Gemini candidates) → `candidates[]` with the first as `message`.

### 10.2 Finish/stop reason mapping (`RSP-2 [MUST]`)

| Unified | OpenAI chat `finish_reason` | Responses `status`/`incomplete_details.reason` | Anthropic `stop_reason` | Gemini `finishReason` | Ollama `done_reason` | Others |
|---|---|---|---|---|---|---|
| `STOP` | `stop` | `completed` | `end_turn`, `stop_sequence` | `STOP` | `stop` | |
| `LENGTH` | `length` | `incomplete/max_output_tokens` | `max_tokens`, `model_context_window_exceeded` `[VERIFY]` | `MAX_TOKENS` | `length` | llama.cpp `truncated` |
| `TOOL_CALLS` | `tool_calls` (`function_call` legacy) | output contains `function_call` items, status `completed` | `tool_use` | parts contain `functionCall` (reason often `STOP`) | tool_calls present | |
| `CONTENT_FILTER` | `content_filter` | `incomplete/content_filter` | `refusal` | `SAFETY`, `RECITATION`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `SPII`, `IMAGE_SAFETY`, `promptFeedback.blockReason` | — | Azure content filter |
| `PAUSE` (server tool loop needs continuation) | — | — | `pause_turn` | — | — | |
| `ERROR` | OpenRouter `error` | `failed` | — | `MALFORMED_FUNCTION_CALL`, `OTHER`, `UNEXPECTED_TOOL_CALL` | — | mid-stream error |
| `CANCELLED` | — | `cancelled` | — | — | — | client abort |
| `UNKNOWN` | anything else | | | `LANGUAGE`, `FINISH_REASON_UNSPECIFIED` | `load`/`unload` | keep raw |

### 10.3 Usage and cost (`RSP-3 [MUST]`)

- `Usage { inputTokens (total incl. cached), outputTokens (incl. reasoning unless separated), cachedInputTokens (read), cacheWriteTokens (+ by TTL), reasoningTokens, audioInputTokens, audioOutputTokens, imageInputTokens, imageOutputTokens (or images count), serverToolCalls {webSearch: n, …}, requests, iterations, acceptedPredictionTokens, rejectedPredictionTokens, providerReportedCostUsd?, raw }`.
- Mapping: OpenAI chat `usage.prompt_tokens/completion_tokens/total_tokens + prompt_tokens_details.{cached_tokens,audio_tokens} + completion_tokens_details.{reasoning_tokens,audio_tokens,accepted_prediction_tokens,rejected_prediction_tokens}`; Responses `usage.input_tokens/input_tokens_details.cached_tokens/output_tokens/output_tokens_details.reasoning_tokens/total_tokens`; Anthropic `input_tokens (uncached!), cache_creation_input_tokens, cache_read_input_tokens, output_tokens, thinking_tokens?, server_tool_use.web_search_requests, service_tier, iterations`; Gemini `usageMetadata.promptTokenCount/candidatesTokenCount/thoughtsTokenCount/cachedContentTokenCount/toolUsePromptTokenCount/totalTokenCount + promptTokensDetails[modality]`; Ollama `prompt_eval_count/eval_count (+durations)`; llama.cpp `usage` + `timings`; OpenRouter `usage` (normalized, + `cost`, `cost_details.upstream_inference_cost`, `is_byok`, native token counts via `/generation`); DeepSeek `prompt_cache_hit/miss_tokens`.
- Semantics flags: `inputTokensIncludeCached` (OpenAI yes, Anthropic no — normalize to *total input* and keep raw), `outputIncludesReasoning`, `countsAreEstimated` (when provider omits usage in stream, e.g. missing `stream_options`).
- Cost: provider-reported when available, else computed from pricing; always attach `exact|estimated|unknown` and the pricing snapshot used.
- Streaming: usage typically arrives in the final event (OpenAI chunk with `stream_options.include_usage`; Anthropic `message_start` has input usage, `message_delta` has output; Gemini every chunk carries cumulative usage; Ollama final object; Responses `response.completed`).

### 10.4 Streaming event model (`RSP-4 [MUST]`)

Unified events (design after Open Responses / Anthropic granularity):

```
StreamStart {responseId?, model?, provider?}
PartStart {index, type: text|reasoning|toolCall|image|audio|citation|opaque, toolCall{id,name}?}
TextDelta {index, text}
ReasoningDelta {index, text | signatureDelta | encryptedChunk}
ToolCallArgsDelta {index, id?, jsonFragment}
PartEnd {index, finalPart}
Annotation {index, citation/urlSource/fileCitation}
UsageUpdate {usage (cumulative or delta, flagged)}
RateLimitUpdate {headers parsed}   -- emitted once headers are available
Ping/KeepAlive
Warning {code, message}
Finish {finishReason, usage, cost, response (fully accumulated)}
Error {error, partialResponse}
Raw {frame}   -- optional tee of the wire frame
```

- Ordering guarantees: parts complete in index order per provider; tool calls may interleave with text (Anthropic: text block then tool_use blocks; Responses: items); events carry `sequence` numbers from the wire when present.
- Encoders for the reverse direction (unified events → OpenAI chat chunks / Responses events / Anthropic events / Ollama NDJSON / Gemini chunks) for gateways (§12).

### 10.5 Errors (`ERR-1 [MUST]`)

- Exception hierarchy: `LlmException` → `TransportException` (connect, TLS, timeout{which}, proxy, DNS), `ProtocolException` (malformed SSE/JSON, unexpected content type), `AuthenticationException` (401; includes `needsReauth`), `PermissionException` (403; incl. moderation/ToS/region), `NotFoundException` (404 model/endpoint/deployment), `InvalidRequestException` (400/422; with `param`, `code`; subtypes `ContextLengthExceeded`, `InvalidSchema`, `UnsupportedParameter`, `InvalidToolReplay`, `InvalidImage`), `PayloadTooLargeException` (413), `RateLimitException` (429; `retryAfter`, headers), `InsufficientCreditsException` (402 OpenRouter/`billing` errors), `ContentFilteredException` (soft — may be a finish reason instead), `OverloadedException` (529/503 capacity), `ServerException` (5xx), `StreamInterruptedException` (with partial), `CancelledException`, `ConfigurationException`.
- Fields on every error: HTTP status, provider error `type/code/message/param`, `requestId`, `endpointId`, `model`, `retryable` (bool + reason), `attempts`, raw body (redacted), `providerName` (OpenRouter `error.metadata.provider_name`, `raw`), `retryAfter`.
- Provider error envelopes to parse: OpenAI `{error:{message,type,param,code}}`; Anthropic `{type:"error", error:{type,message}, request_id}`; Gemini `{error:{code,message,status,details[]}}`; OpenRouter `{error:{code,message,metadata}}` (also inside SSE with `finish_reason: error`); Ollama `{error: "…"}`; llama.cpp `{error:{code,message,type}}`; Azure `{error:{code,message,innererror{content_filter_result}}}`; Bedrock exception types; Mistral `{object:"error", message, type, code}`; plain HTML/text bodies.
- Stream-specific: HTTP 200 followed by error event (Anthropic `error` event types `overloaded_error`, `api_error`; Responses `response.failed`/`error` events; OpenRouter error object; Gemini `promptFeedback.blockReason` with no candidates) — must map to the same hierarchy and carry partial output.

### 10.6 Response metadata (`RSP-5 [MUST]`)

Parsed headers: request ids, rate limits, `openai-processing-ms`, `x-litellm-*` cost/model headers, OpenRouter provider, Cloudflare ray ids; `servedModel` vs requested; `serviceTier`; `systemFingerprint`; content-filter results (Azure/Gemini safety ratings); `citations`; `groundingMetadata`; `container` ids (Anthropic code execution); Gemini `modelVersion`/`responseId`; llama.cpp `timings`, `id_slot`; Ollama `total_duration`, `load_duration`, `prompt_eval_duration`, `eval_duration` (ns).

### 10.7 Pitfalls (response)

- `finish_reason: length` **with** a partial tool call → treat as `LENGTH` and mark tool call invalid.
- Anthropic `input_tokens` excludes cached tokens → wrong cost if summed naively.
- Gemini can return zero candidates (blocked) with 200.
- OpenRouter may return `provider` different from requested `provider.order` when fallbacks allowed.
- Usage missing in streams (no `stream_options`) → estimate and flag.
- Some servers send `usage` with `null` fields or `0` for reasoning even when reasoning happened.
- `servedModel` may be a dated snapshot (`gpt-5.4-2026-03-05`) — keep both.

---

## 11. Dialect reference notes (concise, implementation-oriented)

### 11.1 OpenAI Chat Completions (`openai-chat`)

- Request keys: `model, messages[{role: system|developer|user|assistant|tool, content: string|parts[], name?, tool_calls?, tool_call_id?, refusal?, audio?}], tools[{type:function, function:{name,description,parameters,strict}}], tool_choice, parallel_tool_calls, temperature, top_p, n, stream, stream_options{include_usage}, stop, max_tokens (legacy) | max_completion_tokens, presence_penalty, frequency_penalty, logit_bias, logprobs, top_logprobs, seed, response_format, reasoning_effort, verbosity, service_tier, store, metadata, modalities, audio{voice,format}, prediction, web_search_options, prompt_cache_key, prompt_cache_retention (older) / prompt_cache_options `[VERIFY]`, safety_identifier, user (deprecated)`.
- Content parts: `text`, `image_url{url,detail}`, `input_audio{data,format}`, `file{file_id | file_data + filename}`.
- Response: `choices[{index, message{role,content,refusal,tool_calls[{id,type,function{name,arguments}}],annotations[],audio}, finish_reason, logprobs}], usage, system_fingerprint, service_tier, model, id, created`.
- Streaming: `chat.completion.chunk` with `choices[].delta` (`content`, `tool_calls[{index,id,function{name,arguments}}]`, `refusal`, `reasoning_content` on many compat servers), `finish_reason` on last chunk, optional final usage-only chunk, `data: [DONE]`.
- Compat variance hot-spots: `max_tokens` vs `max_completion_tokens`, `developer` role, `stream_options`, `reasoning_content` field, `[DONE]`, `n`, `logprobs`, tool-call streaming, `response_format` json_schema strictness, `min_p/top_k/repetition_penalty` extras, images as base64 only, `keep_alive` ignored (Ollama `/v1`), unknown-param 400s.

### 11.2 OpenAI Responses (`openai-responses`) and Open Responses

- Request: `model, input (string | items[]), instructions, previous_response_id, conversation, store, stream, background, tools[], tool_choice, parallel_tool_calls, text{format, verbosity}, reasoning{effort, summary}, include[], max_output_tokens, max_tool_calls, truncation, temperature, top_p, top_logprobs, metadata, safety_identifier, prompt_cache_key, prompt_cache_options, service_tier, prompt{id,version,variables}`.
- Items: `message` (roles user/assistant/system/developer; content `input_text`, `input_image{image_url|file_id, detail}`, `input_file{file_id|file_data|file_url, filename}`, `output_text{text, annotations}`, `refusal`), `function_call{call_id,name,arguments,id,status}`, `function_call_output{call_id, output (string|parts)}`, `reasoning{id, summary[], encrypted_content}`, `item_reference{id}`, `compaction`, plus built-in tool call/result items (`web_search_call`, `file_search_call`, `code_interpreter_call`, `computer_call`/`computer_call_output`, `image_generation_call`, `mcp_list_tools`, `mcp_call`, `mcp_approval_request`/`mcp_approval_response`, `local_shell_call`, `custom_tool_call`).
- Response: `id, object:"response", status (queued|in_progress|completed|incomplete|failed|cancelled), output[], output_text (SDK convenience), usage, incomplete_details{reason}, error, model, created_at, previous_response_id, reasoning, text, tools, metadata, service_tier`.
- Streaming events (typed `event:` + `type` field + `sequence_number`): `response.created`, `response.in_progress`, `response.queued`, `response.output_item.added/done`, `response.content_part.added/done`, `response.output_text.delta/done`, `response.output_text.annotation.added`, `response.refusal.delta/done`, `response.function_call_arguments.delta/done`, `response.reasoning_summary_part.added/done`, `response.reasoning_summary_text.delta/done`, `response.reasoning_text.delta/done` `[VERIFY]`, tool-specific (`response.web_search_call.*`, `response.mcp_call.*`, `response.image_generation_call.partial_image`, …), `response.completed`, `response.incomplete`, `response.failed`, `error`.
- Codex backend (`chatgpt.com/backend-api/codex/responses`): Responses-shaped with required Codex instructions/headers; `phase` on assistant messages; model set restricted per plan; treat as a distinct provider profile `[VERIFY][POLICY]`.
- Open Responses spec (openresponses.org): dated OpenAPI releases; `POST /v1/responses`, `/v1/responses/compact`; the SDK's unified model should be losslessly convertible to/from it — it is the most likely long-term interchange format.

### 11.3 Anthropic Messages (`anthropic-messages`)

- Request: `model, max_tokens (required), messages[{role: user|assistant, content: string|blocks[]}], system (string|blocks with cache_control), metadata{user_id}, stop_sequences, stream, temperature, top_p, top_k, tools[], tool_choice, thinking, output_config{effort, …}, output_format, context_management, service_tier (auto|standard_only), container, mcp_servers[]`; headers `x-api-key`, `anthropic-version`, `anthropic-beta`.
- Blocks: `text{text, cache_control, citations}`, `image{source: base64|url|file}`, `document{source: base64|url|text|content|file, title, context, citations{enabled}}`, `tool_use{id,name,input}`, `tool_result{tool_use_id, content: string|blocks, is_error}`, `thinking{thinking, signature}`, `redacted_thinking{data}`, `server_tool_use`, `web_search_tool_result`, `web_fetch_tool_result`, `code_execution_tool_result`, `mcp_tool_use`/`mcp_tool_result`, `search_result`, `container_upload`.
- Response: `id, type:"message", role, model, content[], stop_reason, stop_sequence, usage, container, context_management`.
- Streaming: `message_start{message with usage.input}`, `content_block_start{index, content_block}`, `content_block_delta{index, delta: text_delta|input_json_delta|thinking_delta|signature_delta|citations_delta}`, `content_block_stop`, `message_delta{delta{stop_reason, stop_sequence}, usage{output_tokens,…}}`, `message_stop`, `ping`, `error`.
- Other endpoints: `POST /v1/messages/count_tokens` (same body minus max_tokens), `/v1/messages/batches` (+ results JSONL), `/v1/models`, `/v1/files` (beta), admin `/v1/organizations/...` usage/cost reports (admin key).
- Quirks: `max_tokens` mandatory; tool results first in user message; thinking replay rules and prefix binding; adaptive vs enabled switching breaks cache; effort levels model-dependent (from `/models` capabilities); betas required for structured outputs on older models, files, context management, 1M context, interleaved thinking, per-message effort `[VERIFY]`; 529 overloaded; `request-id` header; `anthropic-ratelimit-*` headers; on Bedrock/Vertex body has `anthropic_version` and no `model` (in URL) and betas via `anthropic_beta` array in body.

### 11.4 Google Gemini (`gemini-generate-content`, `gemini-interactions`, Vertex)

- generateContent request: `contents[{role: user|model, parts[]}], systemInstruction{parts}, tools[{functionDeclarations[] | googleSearch | codeExecution | urlContext | fileSearch | googleMaps | computerUse}], toolConfig{functionCallingConfig}, safetySettings[], generationConfig{temperature, topP, topK, candidateCount, maxOutputTokens, stopSequences, responseMimeType, responseSchema | responseJsonSchema, seed, presencePenalty, frequencyPenalty, responseLogprobs, logprobs, responseModalities[], speechConfig, thinkingConfig{thinkingLevel | thinkingBudget, includeThoughts}, mediaResolution, imageConfig}, cachedContent, labels`.
- Parts: `text`, `inlineData{mimeType,data}`, `fileData{mimeType,fileUri}`, `functionCall{name,args,id?}`, `functionResponse{name,response,id?}`, `executableCode`, `codeExecutionResult`, `thought: true` parts, `thoughtSignature` (attribute on parts), `videoMetadata`.
- Response: `candidates[{content, finishReason, finishMessage, safetyRatings, citationMetadata, groundingMetadata, urlContextMetadata, avgLogprobs, logprobsResult, index}], promptFeedback{blockReason, safetyRatings}, usageMetadata, modelVersion, responseId`.
- Streaming: `:streamGenerateContent?alt=sse` — each `data:` is a full `GenerateContentResponse` chunk with cumulative `usageMetadata`.
- Interactions API (2026): `POST /v1beta/interactions` with `model, input, previous_interaction_id, store, generation_config{thinking_level, thinking_summaries}`, header `Api-Revision: YYYY-MM-DD`; steps/items model similar to Responses; signatures only on thought/built-in tool steps `[VERIFY]`.
- Vertex: same body; URL carries project/location/model; OAuth bearer; `global` location; Anthropic/Llama partner models via `rawPredict`/OpenAI-compat `openapi` endpoint `[VERIFY]`; quotas per region/model; Model Armor/safety differences.
- Quirks: role `model`; function responses in `user` role; no tool-call ids (SDK synthesizes; Gemini 3 adds optional `id` `[VERIFY]`); schema subset; `thoughtSignature` echo (400 on strict image-edit flows if missing); `alt=sse`; safety blocks → empty candidates; free tier data usage policy; `x-goog-api-key`; File API `PROCESSING` state polling; implicit caching stats; `countTokens` supports `generateContentRequest` body.

### 11.5 xAI (`openai-chat`/`openai-responses`/`anthropic-messages` `[VERIFY]`)

Base `https://api.x.ai/v1`; `reasoning_effort` on reasoning-mini models; `search_parameters {mode, sources, from_date, to_date, return_citations}` → citations in response; `/v1/language-models` with prices; `/v1/api-key` info; images `/v1/images/generations`; deferred completions (`POST` returns request id, `GET /v1/chat/deferred-completion/{id}`); Anthropic-compatible `/v1/messages` `[VERIFY]`.

### 11.6 DeepSeek

Base `https://api.deepseek.com` (`/v1` alias); models `deepseek-chat`/`deepseek-reasoner` (older) and V4 ids; `reasoning_content` in messages/deltas; FIM `/beta/completions` with `suffix`; prefix completion (`prefix: true` on last assistant message, beta); JSON mode; tool calls (with reasoning interplay rules); automatic context caching (`prompt_cache_hit_tokens`/`miss`); Anthropic-compat `/anthropic`; Responses API per their docs (event list matches OpenAI) `[VERIFY]`; off-peak pricing windows `[VERIFY]`.

### 11.7 Qwen / DashScope

Compatible-mode `/compatible-mode/v1` (chat/completions, embeddings, responses `[VERIFY]`); native DashScope JSON (`input.messages`, `parameters.*`, `X-DashScope-SSE: enable` header for SSE) — treat native as optional; Anthropic-compat `/apps/anthropic`; params `enable_thinking`, `thinking_budget`, `reasoning_effort`, `preserve_thinking`, `enable_search`, `vl_high_resolution_images`; region hosts (`dashscope.aliyuncs.com` CN vs `dashscope-intl.aliyuncs.com`); Qwen-VL video parts; "Token Plan" vs pay-per-token endpoints; Anthropic-compat thinking-on default pitfall.

### 11.8 Mistral, Cohere, Groq, Perplexity and other OpenAI-like APIs

- Mistral: `random_seed`, `safe_prompt`, `prediction`, `parallel_tool_calls`, `json_schema` response format, `reasoning_effort`/Magistral thinking parts, Conversations API (`/v1/conversations` with `agent_id`/`model`, `inputs`, `stream`, `completion_args`), OCR, FIM, moderation; error object shape differs.
- Cohere v2: `messages` with `tool_plan`, `documents` for RAG citations, `response_format`, `k`/`p`, `citation_options`; distinct streaming events (`content-delta`, `tool-call-delta`, `citation-start`); rerank/embed first-class.
- Groq: `reasoning_format`, `reasoning_effort`, `service_tier`, strict JSON, fast; `x-groq` request ids; per-model token limits low.
- Perplexity: `search_domain_filter`, `search_recency_filter`, `return_images`, `return_related_questions`, `web_search_options`, citations array; Responses-style gateway subset `[VERIFY]`.
- Together/Fireworks/DeepInfra/Nebius/Novita: open-model extras (`min_p`, `repetition_penalty`, `top_k`, `echo`, grammar), inconsistent tool streaming, `logprobs` variants.
- NVIDIA NIM: `integrate.api.nvidia.com/v1`; model ids `vendor/model`; `nvext` extras `[VERIFY]`.

### 11.9 OpenRouter specifics (as a provider profile over `openai-chat`/`openai-responses`)

Request extras: `models[]`, `route`, `provider{…}`, `transforms[]`, `reasoning{…}`, `usage{include}`, `plugins[]`, `user`, `session_id` `[VERIFY]`, `stream_options`, `response_format`, `min_p/top_k/top_a/repetition_penalty/seed` accepted per `supported_parameters`; per-endpoint `max_price`. Response extras: `provider`, `usage.cost`, `usage.cost_details`, `usage.prompt_tokens_details.cached_tokens`, `usage.completion_tokens_details.reasoning_tokens`, `reasoning`, `reasoning_details[]`, `citations`/`annotations` (web plugin), `id` (generation id → `/generation?id=` for exact native token counts and cost after completion). Model variants and `openrouter/auto`; rate limits for free models (RPM/day depend on purchased credits); 402/403/408/502/503 semantics; `X-RateLimit-*` headers on throttle; OAuth PKCE key issuance; management/provisioning keys for `/keys`, `/activity`; BYOK with fee; data policies and ZDR routing.

### 11.10 Ollama native

`/api/chat {model, messages[{role, content, images[], thinking?, tool_calls?, tool_name?}], tools[], format, options{}, stream, keep_alive, think, logprobs, top_logprobs}`; `/api/generate` (prompt/suffix/system/template/raw/context/images); `/api/embed {model, input, truncate, keep_alive, dimensions?}`; `/api/tags`, `/api/show {model, verbose}`, `/api/ps`, `/api/pull {model, stream, insecure}` (progress stream), `/api/push`, `/api/create`, `/api/copy`, `/api/delete`, `/api/blobs/:digest`, `/api/version`. Streaming NDJSON; final object with `done_reason`, durations and counts. Auth: none locally; `Authorization: Bearer` for Ollama Cloud (`https://ollama.com`) and remote instances behind proxies. Quirks: `num_ctx`, first-load latency, `OLLAMA_NUM_PARALLEL`, `OLLAMA_MAX_LOADED_MODELS`, model name tags, `format` schema support depends on model, tool calling depends on model template, `think` levels model-dependent, `/v1` ignores `keep_alive`/`options`, image inputs base64 only, no auth by default → warn when exposed on 0.0.0.0.

### 11.11 llama.cpp native

`/completion {prompt (string|tokens|mixed), n_predict, temperature, dynatemp_range/exponent, top_k, top_p, min_p, typical_p, repeat_penalty, repeat_last_n, presence_penalty, frequency_penalty, dry_*, xtc_*, mirostat*, grammar, json_schema, seed, ignore_eos, logit_bias, n_probs, min_keep, stop[], stream, cache_prompt, n_keep, t_max_predict_ms, image_data[], id_slot, lora[], samplers[], return_tokens, timings_per_token, post_sampling_probs, response_fields[]}`; `/v1/chat/completions` with extras (`chat_template_kwargs`, `reasoning_effort`, `response_format` variants, `n_probs`, `cache_prompt`); `/tokenize {content, add_special, with_pieces}`, `/detokenize`, `/apply-template`, `/embedding`/`/v1/embeddings` (pooling), `/reranking`/`/v1/rerank`, `/infill {input_prefix, input_suffix, input_extra[]}`; `/props` (GET/POST), `/slots` (+ save/restore/erase actions), `/metrics`, `/health`, `/lora-adapters`, `/models` (router mode: list/load/unload, `?model=`), optional Anthropic `/v1/messages` and Ollama-compatible shim `[VERIFY]`. Response includes `timings`, `tokens_predicted`, `tokens_evaluated`, `truncated`, `stop_type`, `generation_settings`. Quirks: single-model default, slots, `n_ctx` split, alias vs path ids, `--jinja` templates decide tool/reasoning support, api key optional, `cache_prompt` default true, `--reasoning-format`, `--reasoning-budget`.

### 11.12 Azure OpenAI, AWS Bedrock, Vertex AI specifics

- Azure: v1 route `/openai/v1/{responses|chat/completions|…}` without `api-version` `[VERIFY]`; legacy per-deployment routes with `api-version`; `api-key` or Entra; content filter results in `choices[].content_filter_results` and `prompt_filter_results`; error codes `content_filter`, `DeploymentNotFound`; regional data residency; Foundry Models endpoint for non-OpenAI models with `model` param; Anthropic on Foundry via Messages-shaped endpoint `[VERIFY]`.
- Bedrock: Converse request `{modelId, messages[{role, content[{text|image|document|video|toolUse|toolResult|reasoningContent|cachePoint}]}], system[], inferenceConfig{maxTokens,temperature,topP,stopSequences}, toolConfig{tools[{toolSpec{name,description,inputSchema{json}}}], toolChoice}, additionalModelRequestFields (e.g. anthropic thinking, top_k), guardrailConfig, performanceConfig}`; ConverseStream events (`messageStart`, `contentBlockDelta`, `metadata` with usage/metrics); InvokeModel with vendor-native body (Anthropic body incl. `anthropic_version: bedrock-2023-05-31`, `anthropic_beta[]`); SigV4 or bearer API key; inference profile ids; `CountTokens` API `[VERIFY]`; pricing from AWS price list (not in SDK) → bundled/LiteLLM tables.
- Vertex: see §11.4; service accounts/ADC/Workload Identity; `global` vs regional endpoints; provisioned throughput; Anthropic models: `:rawPredict`/`:streamRawPredict` with `anthropic_version: vertex-2023-10-16`.

---

## 12. Building proxies and gateways on the SDK (`GW-*`)

- `GW-1 [MUST]` **Inbound parsers** for each dialect (request → unified) with validation errors in the *inbound* dialect's error format (a proxy that speaks Anthropic must return Anthropic-shaped errors).
- `GW-2 [MUST]` **Outbound serializers** (unified → dialect) for responses and stream events, including terminators (`[DONE]`), keep-alive comments, `event:` names, `sequence_number`, and dialect-specific usage placement.
- `GW-3 [MUST]` **Translation matrix** documented per feature (text, images, files, tools, tool results, reasoning replay, structured output, caching hints, stop reasons, usage): `lossless | approximated | dropped`, surfaced as `warnings[]` and optionally as response headers (`x-llm-warnings`).
- `GW-4 [MUST]` **Raw passthrough mode**: forward body/headers verbatim (with header allow/deny lists, auth swap, path rewrite), optionally tee-parse for accounting without altering bytes; byte-exact SSE relay with backpressure.
- `GW-5 [SHOULD]` **Accounting hooks**: per-request token/cost attribution (tenant, virtual key, model), budgets and quotas (soft/hard), rate limits per virtual key, spend logs; header-based overrides (LiteLLM/Portkey style) as an optional convention.
- `GW-6 [SHOULD]` **Traffic analysis hooks**: prompt/response capture with redaction, dedup/fingerprinting (prefix hashes for cache analysis), token histograms, tool-call stats, latency/TTFT distributions; pluggable request **compression/rewriting** interceptors (context trimming, image downscaling, tool-result truncation) applied before encoding — the SDK provides the seams, not the algorithms.
- `GW-7 [SHOULD]` Virtual keys and multi-tenant credential isolation; BYOK pass-through; per-tenant endpoint routing.
- `GW-8 [SHOULD]` Serving helpers: SSE writer utilities, chunked responses, graceful shutdown/drain, idle-timeout propagation, cancellation propagation (client disconnect → upstream abort), request size limits, dialect auto-detection by path (`/v1/chat/completions`, `/v1/responses`, `/v1/messages`, `/v1beta/models/*:generateContent`, `/api/chat`).
- `GW-9 [MAY]` Reference gateway example (Vert.x or JDK `HttpServer`) demonstrating Anthropic→OpenRouter and OpenAI→Ollama translation with accounting.

---

## 13. Observability, monitoring, statistics, recording (`OBS-*`)

### 13.1 Metrics (`OBS-1 [SHOULD]`, Micrometer + OTel adapters)

Per endpoint/model/key/tenant/tags: request count, success/error by class, retries/failovers, latency histograms (total, TTFB, TTFT), tokens by class (input, output, cached, reasoning), tokens/sec (output), cost (USD), rate-limit headroom gauges, circuit-breaker state, pool/queue depth, stream durations, cancellations, cache hit ratio, provider-served distribution (OpenRouter), local-server slot utilization (`/metrics`, `/slots`, Ollama `/api/ps`).

### 13.2 Tracing (`OBS-2 [SHOULD]`, OpenTelemetry GenAI semantic conventions — track the dedicated semconv repo, status *Development*)

Span per operation named `{gen_ai.operation.name} {gen_ai.request.model}`; attributes (core, stable in shape): `gen_ai.operation.name` (`chat`, `generate_content`, `text_completion`, `embeddings`, `execute_tool`, `invoke_agent`), `gen_ai.provider.name` (`openai`, `anthropic`, `gcp.gemini`, `gcp.vertex_ai`, `aws.bedrock`, `azure.ai.openai`, `deepseek`, `groq`, `mistral_ai`, `perplexity`, `x_ai`, `cohere`, `openrouter`…), `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.request.{temperature, top_p, top_k, max_tokens, stop_sequences, frequency_penalty, presence_penalty, seed, choice.count}`, `gen_ai.response.id`, `gen_ai.response.finish_reasons`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, cache/reasoning token extensions (`gen_ai.usage.cache_read.input_tokens`, `gen_ai.usage.cache_creation.input_tokens`, reasoning tokens — vendor extensions until standardized `[VERIFY]`), `gen_ai.conversation.id`, `gen_ai.output.type`, tool spans (`gen_ai.tool.name`, `gen_ai.tool.call.id`), `server.address/port`, `error.type`; **content capture opt-in only** (`gen_ai.input.messages`, `gen_ai.output.messages`, `gen_ai.system_instructions`); metrics `gen_ai.client.token.usage`, `gen_ai.client.operation.duration`, server-side `gen_ai.server.time_to_first_token`, `time_per_output_token`. Support the env switch `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` semantics. Emit a cost attribute as an extension (`gen_ai.usage.cost_usd` is common but non-standard).

### 13.3 Logging and redaction (`OBS-3 [MUST]`)

SLF4J; structured key/value logging; levels: wire (headers+bodies, redacted secrets, truncated base64), request summary (model, tokens, cost, latency, request id), warnings (dropped params, emulations). Redaction rules: API keys/tokens/`Authorization`, `x-api-key`, `api-key`, `x-goog-api-key`, query `key=`, cookies, base64 media (replace with `<image 123KB>`), PII toggle. Never log full prompts at INFO.

### 13.4 Recording, replay, testing (`OBS-4 [SHOULD]`)

Cassette recorder (request + response/stream frames with timing) in a documented JSON format; deterministic replay transport for tests and for offline demos; mock endpoint server speaking each dialect (incl. streaming, errors, rate limits) for contract tests; golden-file tests per codec; fuzzing of SSE splitting; property tests for translation round-trips.

### 13.5 Local statistics store (`OBS-5 [SHOULD]`)

Optional embedded accounting (in-memory + pluggable persistence): per key/endpoint/model/day counters for requests, tokens by class, cost, errors; rollups for today/week/month (screenshot *Usage Total / This Week / This Month*); export as CSV/JSON; hooks to merge with provider-reported usage (OpenRouter `/auth/key`, LiteLLM `/spend`, admin APIs).

---

## 14. Token counting and context budgeting (`TOK-*`)

- `TOK-1 [SHOULD]` Provider count endpoints: Anthropic `count_tokens` (exact, includes tools/images/PDFs/system/thinking), Gemini `countTokens` (exact), Bedrock `CountTokens` `[VERIFY]`, llama.cpp `/tokenize` (exact for that model/template), vLLM `/tokenize`, Ollama (none; estimate). OpenAI/OpenRouter/xAI/DeepSeek: no endpoint → local estimators.
- `TOK-2 [SHOULD]` Local estimators: tiktoken-compatible BPE (`o200k_base`, `cl100k_base`) via JTokkit; SentencePiece/HF tokenizers via optional native/DJL adapters; heuristic fallback (chars/4 with language factor); per-model tokenizer hints in catalog (`architecture.tokenizer` on OpenRouter). Always label counts as `EXACT | ESTIMATED`.
- `TOK-3 [SHOULD]` Message overhead accounting (per-message/per-role framing tokens; tool definition tokens; image tokens formulas per provider — OpenAI tile-based, Anthropic `(w*h)/750`, Gemini fixed per image/tile `[VERIFY]`).
- `TOK-4 [SHOULD]` Budget helpers: `fitsInContext(request, model)`, `remainingOutputBudget`, `estimateCost(request, model)` (pre-flight; used by budget guards and the node-flow UI), and hooks for trimming (the caller supplies the strategy).

---

## 15. Java SDK design considerations (`JAVA-*`)

### 15.1 Platform and dependencies

- `JAVA-1 [MUST]` Baseline Java 17 (records, sealed interfaces, text blocks); optimize for 21+ (virtual threads, pattern matching for switch); no Java 8 support.
- `JAVA-2 [MUST]` Core with minimal deps: a JSON library (Jackson 2/3 — decide; consider a thin internal JSON facade so Gson/Moshi adapters are possible), SLF4J API, `jspecify` nullability annotations. Optional modules pull OkHttp, Micrometer, OpenTelemetry API, JTokkit, AWS/GCP/Azure auth SDKs, JSON-Schema validator.
- `JAVA-3 [SHOULD]` JPMS module descriptors (`module-info.java`) with clean exports; OSGi-friendly manifests optional; GraalVM native-image metadata (reflection config for JSON types) shipped in `META-INF/native-image`; Android compatibility for the OkHttp transport + core (avoid `java.net.http` in core).
- `JAVA-4 [MUST]` No static mutable global state (no global default client hidden in statics; a `Llm.defaultClient()` convenience may exist but must be explicit/overridable); everything injectable.

### 15.2 API style

- Immutable value types (records / final classes with builders); sealed interfaces for `ContentPart`, `StreamEvent`, `Credential`, `OutputFormat`, `ToolChoice`, `FinishReason`-like enums with `UNKNOWN(raw)` escape values (never throw on unknown provider enum values).
- Builders with sensible required-arg constructors; static factories (`Message.user("…")`, `Part.image(path)`, `Tool.function("name").schema(…)`).
- Three facade levels:
  1. **One-liners**: `String reply = Llm.chat("openrouter:deepseek/deepseek-v4-flash", "Summarize …");`
  2. **Fluent request**: `client.chat(model).system(…).user(…, Part.file(path)).tools(…).reasoning(Effort.HIGH).jsonSchema(Schema.of(Foo.class)).stream(events -> …)`
  3. **Full control**: `ChatRequest.builder()…extras(Map.of("provider", …)).build(); client.execute(req)` and `client.raw().post("/v1/responses", jsonNode)`.
- Typed provider extras via small option classes (`OpenRouterOptions`, `AnthropicOptions`, `OllamaOptions`) merged into `extras` — discoverable in IDEs, still serializable to plain JSON.
- Return types: `Response` (sync), `CompletableFuture<Response>`, `Flow.Publisher<StreamEvent>` / `Stream<StreamEvent>` / `StreamHandle` (with `cancel()`, `await()`, `partial()`).
- Exceptions unchecked (`RuntimeException` subclasses) with rich fields; also a `Result`-style non-throwing variant for batch/gateway code `[MAY]`.

### 15.3 JSON handling

- Preserve unknown fields on all wire types (`@JsonAnySetter`/`extras`), never fail on new enum values, `BigDecimal` for prices, `long` for tokens, ISO‑8601/`Instant` for times, lenient number/string coercion for fields providers send inconsistently (OpenRouter pricing strings, Ollama durations in ns).
- Keep both typed models and raw `JsonNode` for every wire object; stable `toJson()`/`fromJson()` for persistence (session files, cassettes) with schema version.
- Streaming JSON: incremental parser for partial tool-call arguments (lenient) — implement or vendor a small partial-JSON parser.

### 15.4 Concurrency and lifecycle

- Client is thread-safe; per-endpoint pools/limiters; `AutoCloseable` releases sockets, schedulers (token refresh, catalog refresh, circuit-breaker timers); daemon threads by default; virtual-thread executor when available (`Executors.newVirtualThreadPerTaskExecutor` via reflection guard on 17).
- Timeouts implemented with a shared scheduler; cancellation via `Future.cancel(true)`/`StreamHandle.cancel()` closes the underlying connection.
- Avoid blocking in JDK HttpClient callbacks; hand off to consumer executor.

### 15.5 Existing Java libraries to study (do not depend on them in core)

- Official: `openai-java` (com.openai), `anthropic-sdk-java` (com.anthropic), `google-genai` (com.google.genai), AWS SDK v2 `bedrockruntime`, Azure OpenAI Java SDK, Vertex AI Java.
- Community: LangChain4j (provider adapters, `ChatModel`/`StreamingChatModel`, tool specs), Spring AI (`ChatModel`, `ChatOptions`, observation conventions), `ollama4j`, `simple-openai`, `openai4j`, JTokkit (tiktoken), DJL tokenizers.
- Patterns worth copying: pi-ai's `api` type per model + serializable context + best-effort cross-provider handoff; Vercel AI SDK's `providerOptions`/`providerMetadata` split; LiteLLM's normalized `reasoning_content`/`thinking_blocks` and unsupported-param `drop_params`; OpenRouter's `supported_parameters` capability derivation; Open Responses' items/state machines; Spring AI's observation conventions.

### 15.6 Testing strategy

Unit tests per codec (encode/decode golden files for each dialect, including streaming frames and errors); contract tests against the mock server; optional live smoke tests gated by env keys (tiny prompts, cost-capped); SSE fuzz tests (chunk boundaries, CRLF, multi-line data, comments); translation round-trip property tests; catalog merge tests with fixture snapshots; concurrency/cancellation tests with virtual threads; GraalVM native smoke build.

### 15.7 Documentation & DX

Javadoc with examples; a "capability matrix" page generated from provider profiles; a "what is lossy" page generated from the translation matrix; runnable examples per use case (agent loop, node-flow style request with attachments, proxy, catalog dump); `dryRun` output shown in docs so users see the exact wire JSON.

---

## 16. Consolidated pitfalls checklist

**Transport**
- JDK HttpClient has no read/idle timeout → stream watchdog. Stale HTTP/2 connections → single transparent retry before any byte is consumed.
- SSE: multi-line `data:`, comments, CRLF, UTF‑8 split across chunks, `[DONE]` optional, errors as 200 + JSON, HTML error pages, proxies buffering, HTTP/2 RST.
- Idle NAT/firewall kills long streams → keep-alives, idle timeout ≥ provider ping interval.
- `localhost` vs `127.0.0.1` (IPv6), Windows quirks, corporate proxies/TLS interception, JVM DNS caching.

**Auth**
- Header name/scheme per provider; two env vars with precedence; refresh-token rotation races; loopback ports; product-scoped OAuth tokens; policy changes (Anthropic subscription tokens), account-id headers; empty-key gateways.

**Parameters**
- `max_tokens` naming/semantics; reasoning tokens consume output budget; OpenAI reasoning models reject sampling params; Anthropic temperature/top_p exclusivity and thinking constraints; Gemini `thinkingLevel` vs `thinkingBudget`; unknown params → 400 on some servers, ignored on others; stop-sequence limits; `n` cost.

**Messages/tools**
- System placement; role alternation/merging; tool results ordering and grouping; tool-call id synthesis (Gemini); reasoning signatures/encrypted content replay bound to same model & unchanged prefix; `phase` on Codex; `developer` role on compat servers; prefill rules; empty content blocks; base64 bloat; schema dialect differences; strict-mode constraints; invalid/partial tool-argument JSON at `length`.

**Streaming/state**
- Usage only at end; cumulative vs delta usage; Gemini empty candidates; OpenRouter mid-stream error objects; partial-JSON previews; cancellation still billed for generated tokens; stateful ids expiring (OpenAI 30 days `[VERIFY]`; Gemini files 48 h).

**Metadata/pricing**
- Prices per token vs per 1M; `"0"` vs unknown; cache read/write prices by TTL; long-context tiers; provider variant capabilities; context window semantics (total vs input/output); `num_ctx`/`n_ctx` local limits; deprecation dates; aliases → dated snapshots; catalog staleness; OpenRouter ids ≠ vendor ids.

**Local servers**
- Cold-start load time; single model; slots; `keep_alive`; NDJSON; no auth on LAN; template-dependent tool/thinking support; `<think>` tags inline; nonstandard `response_format`.

**Gateways**
- Inbound error shapes must match inbound dialect; byte-exact relay vs re-encoding; header allow-lists (never forward inbound `Authorization` upstream unless BYOK intended); client disconnect → upstream cancel; cost attribution when provider fails over.

**Compliance/privacy**
- Data-collection/training policies per provider/route (OpenRouter `data_collection`, ZDR), regional endpoints (EU), logging of prompts, secrets in telemetry, ToS constraints on OAuth reuse.

---

## 17. Feature tiers (suggested roadmap)

| Tier | Scope |
|---|---|
| **MVP (v0.x)** | Core unified model; `openai-chat`, `anthropic-messages`, `openai-responses`, `gemini-generate-content`, `ollama-native`; JDK transport with SSE/NDJSON + idle watchdog; API-key auth + env/config; retries/backoff/`Retry-After`; rate-limit header parsing; streaming events + accumulator; tools, structured output (native + tool-forcing emulation), images/PDF inputs, reasoning config with signature replay; prompt-cache hints (Anthropic breakpoints, OpenAI keys); usage/cost with pricing from models.dev + OpenRouter + overrides; `/models` listing per dialect; compat profiles; unsupported-param policy; `dryRun`; SLF4J logging with redaction; mock server tests. |
| **v1.0** | OpenRouter full profile (endpoints, key/credits/generation APIs, provider routing); llama.cpp native (+ `/props`, `/slots`, `/tokenize`); catalog cache/refresh/merge with provenance; endpoint probing/auto-detect; circuit breaker, fallback chains, key pools; OAuth module (PKCE, device, refresh, token stores, OpenRouter/Codex/Copilot presets, policy notes); Azure/Vertex/Bedrock adapters; embeddings; count-tokens + estimators; Micrometer/OTel GenAI; recorder/replay; gateway module with inbound parsers, outbound encoders, passthrough, accounting hooks; importers (models.dev, Codex, OpenCode, pi, LiteLLM). |
| **Later** | Gemini Interactions, OpenAI conversations/compaction & WebSocket Responses, Mistral conversations, Cohere v2, realtime audio, image generation/edit APIs, TTS/STT, batch APIs, files management UX, MCP client module, tool runner helper, Kotlin/Reactor adapters, GraalVM native metadata polish, reference gateway app. |

---

## 18. Open design questions to settle before API design

1. JSON library choice (Jackson 2 vs 3 vs internal facade) and whether wire types are generated from provider OpenAPI specs or hand-written.
2. Sync-first vs async-first core; how `Flow.Publisher` and blocking iterators share one implementation.
3. Exact `ModelRef` string grammar and escaping; alias resolution precedence.
4. Whether reasoning persistence is automatic (SDK stores opaque provider metadata on parts and replays) — recommended yes — and how to expose "history was transformed for provider X".
5. Cache hint API: explicit breakpoints only, or strategies (`AGENT_LOOP`) as first-class.
6. Unsupported-parameter default policy per endpoint class (strict for first-party, lenient for compat servers).
7. Cost/pricing source of truth precedence and how user overrides are stored/updated.
8. How much of OAuth ships in core vs a separate artifact; policy stance on subscription OAuth presets (`[POLICY]`).
9. Gateway module framework neutrality (pure `java.net.http` server vs Vert.x/Netty adapters).
10. Config file format (TOML vs YAML/JSON) and schema publication.
11. Telemetry defaults (off by default? summary metrics on, content capture off).
12. Naming: "Endpoint" vs "Connection", "Dialect" vs "WireApi", "Part" vs "Block"/"Item".

---

## Appendix A — Parameter mapping table (unified → dialects)

| Unified | openai-chat | openai-responses | anthropic-messages | gemini | ollama-native | llamacpp-native | openrouter (extras) |
|---|---|---|---|---|---|---|---|
| maxOutputTokens | `max_completion_tokens` (`max_tokens` legacy) | `max_output_tokens` | `max_tokens` (required) | `generationConfig.maxOutputTokens` | `options.num_predict` | `n_predict` | `max_tokens` |
| temperature | `temperature` 0–2 | `temperature` | `temperature` 0–1 | `generationConfig.temperature` | `options.temperature` | `temperature` | `temperature` |
| topP | `top_p` | `top_p` | `top_p` (exclusive with temperature on 4.x `[VERIFY]`) | `topP` | `options.top_p` | `top_p` | `top_p` |
| topK | — | — | `top_k` | `topK` | `options.top_k` | `top_k` | `top_k` |
| minP | — (compat servers) | — | — | — | `options.min_p` | `min_p` | `min_p` |
| frequency/presencePenalty | `frequency_penalty`/`presence_penalty` | — (`[VERIFY]`) | — | `frequencyPenalty`/`presencePenalty` | `options.frequency_penalty`/`presence_penalty` | same | same |
| repetitionPenalty | — | — | — | — | `options.repeat_penalty` | `repeat_penalty` | `repetition_penalty` |
| stopSequences | `stop` (≤4) | — (`[VERIFY]`) | `stop_sequences` | `stopSequences` | `options.stop` | `stop` | `stop` |
| seed | `seed` | — | — | `seed` | `options.seed` | `seed` | `seed` |
| n | `n` | — | — | `candidateCount` | — | — | — |
| logprobs | `logprobs`,`top_logprobs` | `top_logprobs` + `include` | — | `responseLogprobs`,`logprobs` | `logprobs`,`top_logprobs` | `n_probs` | `logprobs`,`top_logprobs` |
| logitBias | `logit_bias` | — | — | — | — | `logit_bias` | `logit_bias` |
| responseFormat | `response_format` | `text.format` | `output_format` / tool-forcing | `responseMimeType`+`responseSchema` | `format` | `response_format`/`json_schema`/`grammar` | `response_format` |
| tools / toolChoice / parallel | `tools`,`tool_choice`,`parallel_tool_calls` | same | `tools`,`tool_choice{type,name,disable_parallel_tool_use}` | `tools.functionDeclarations`,`toolConfig` | `tools` | `tools` (template) | same as chat |
| reasoning | `reasoning_effort` | `reasoning{effort,summary}`,`include` | `thinking{…}`,`output_config.effort` | `thinkingConfig{thinkingLevel|thinkingBudget,includeThoughts}` | `think` | `reasoning_effort`,`chat_template_kwargs` | `reasoning{effort,max_tokens,exclude}` |
| verbosity | `verbosity` | `text.verbosity` | — | — | — | — | `verbosity` |
| serviceTier | `service_tier` | `service_tier` | `service_tier` | — | — | — | `provider.sort` etc. |
| cache hints | `prompt_cache_key`,`prompt_cache_options` | same | `cache_control` on blocks | `cachedContent` | `keep_alive` | `cache_prompt`,`id_slot` | `cache_control` passthrough |
| state | — | `previous_response_id`,`conversation`,`store` | — | Interactions `previous_interaction_id` | `context` (legacy) | slot save/restore | — |
| user/safety id | `safety_identifier`/`user` | `safety_identifier` | `metadata.user_id` | `labels` (Vertex) | — | — | `user` |
| metadata | `metadata` (with `store`) | `metadata` | — | — | — | — | — |
| system prompt | `messages[role=system|developer]` | `instructions` / `developer` item | `system` (top-level) | `systemInstruction` | `messages[role=system]` | in prompt/template | as chat |

## Appendix B — Endpoint reference (metadata & auxiliary)

| Provider | List models | Model detail | Key/usage/credits | Count tokens | Files | Health |
|---|---|---|---|---|---|---|
| OpenAI | `GET /v1/models` | `GET /v1/models/{id}` | admin usage/cost APIs (admin key) `[VERIFY]` | — | `/v1/files` | — |
| Anthropic | `GET /v1/models` | `GET /v1/models/{id}` (capabilities) | admin org usage/cost reports `[VERIFY]` | `POST /v1/messages/count_tokens` | `/v1/files` (beta) | — |
| Gemini | `GET /v1beta/models` | `GET /v1beta/models/{m}` | — | `POST …:countTokens` | `/upload/v1beta/files`, `/v1beta/files` | — |
| OpenRouter | `GET /api/v1/models` | `GET /api/v1/models/{author}/{slug}/endpoints` | `GET /api/v1/auth/key`, `/api/v1/credits`, `/api/v1/generation?id=`, `/api/v1/activity`, `/api/v1/keys` | — | inline only | — |
| xAI | `GET /v1/models`, `/v1/language-models` | `/v1/language-models/{id}` | `GET /v1/api-key` | — | — | — |
| Mistral | `GET /v1/models` | `GET /v1/models/{id}` | — | — | `/v1/files` | — |
| Ollama | `GET /api/tags` | `POST /api/show` | — | — | `/api/blobs` | `GET /api/version`, `/api/ps` |
| llama.cpp | `GET /v1/models`, `/models` | `/props` | — | `POST /tokenize` | — | `/health`, `/metrics`, `/slots` |
| LM Studio | `GET /api/v0/models` | `/api/v0/models/{id}` | — | — | — | — |
| vLLM | `GET /v1/models` | — | — | `POST /tokenize` | — | `/health`, `/version`, `/metrics` |
| LiteLLM | `GET /v1/models`, `/model/info` | — | `/key/info`, `/spend/logs` | — | — | `/health` |
| Azure | control plane deployments | — | — | — | `/openai/files` | — |
| Bedrock | `ListFoundationModels`, inference profiles | `GetFoundationModel` | — | `CountTokens` `[VERIFY]` | — | — |

## Appendix C — Unified event → dialect stream encoding (for gateways)

| Unified event | openai-chat chunk | openai-responses event | anthropic event | ollama NDJSON | gemini chunk |
|---|---|---|---|---|---|
| StreamStart | first chunk with `role: assistant` | `response.created`, `response.in_progress` | `message_start` | first object | first chunk |
| PartStart(text) | — | `output_item.added` (message) + `content_part.added` | `content_block_start{text}` | — | — |
| TextDelta | `delta.content` | `response.output_text.delta` | `content_block_delta{text_delta}` | `message.content` | `parts[].text` |
| ReasoningDelta | `delta.reasoning_content` (compat) | `response.reasoning_summary_text.delta` / `reasoning_text.delta` | `content_block_delta{thinking_delta|signature_delta}` | `message.thinking` | `parts[]{thought:true}` |
| PartStart(toolCall) | `delta.tool_calls[{index,id,function.name}]` | `output_item.added{function_call}` | `content_block_start{tool_use}` | `message.tool_calls[]` (whole) | `parts[].functionCall` (whole) |
| ToolCallArgsDelta | `delta.tool_calls[].function.arguments` | `response.function_call_arguments.delta` | `content_block_delta{input_json_delta}` | — | — |
| PartEnd | — | `content_part.done`, `output_item.done` | `content_block_stop` | — | — |
| UsageUpdate | final chunk `usage` (with `stream_options`) | in `response.completed.response.usage` | `message_start.usage` + `message_delta.usage` | final object counts | `usageMetadata` (cumulative) |
| Finish | `finish_reason` + `data: [DONE]` | `response.completed` / `incomplete` | `message_delta{stop_reason}` + `message_stop` | `done:true, done_reason` | `finishReason` |
| Error | `error` object (OpenRouter style) | `response.failed` / `error` | `error` event | `{error}` | `error` JSON |
| Ping | `: keep-alive` comment | — | `ping` event | — | — |

## Appendix D — Sources consulted (September 2026)

- OpenAI API reference — Responses create (reasoning `encrypted_content`, MCP tool options, deprecations): https://developers.openai.com/api/reference/resources/responses/methods/create
- OpenAI prompt caching guide and diagnostics: https://developers.openai.com/api/docs/guides/prompt-caching , https://developers.openai.com/api/docs/guides/prompt-caching/diagnostics
- Anthropic Messages API reference: https://docs.anthropic.com/en/api/messages ; List Models (capabilities object): https://platform.claude.com/docs/en/api/models/list ; Adaptive thinking: https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking ; Rate limits: https://platform.claude.com/docs/api/rate-limits
- Anthropic developer platform update notes (thinking binding, per-message effort, `thinking.display`): https://releasebot.io/updates/anthropic/claude-developer-platform
- AWS Bedrock — Claude adaptive thinking / per-turn effort: https://docs.aws.amazon.com/bedrock/latest/userguide/claude-messages-adaptive-thinking.html ; Claude Platform on AWS rate limits: https://docs.aws.amazon.com/claude-platform/latest/userguide/rate-limits.html
- Gemini API — thinking (generateContent and Interactions): https://ai.google.dev/gemini-api/docs/thinking , https://ai.google.dev/gemini-api/docs/interactions/thinking ; Gemini 3 guide (thinking_level, thought signatures): https://ai.google.dev/gemini-api/docs/generate-content/gemini-3 ; Gemini Enterprise inference reference (request schema): https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/models/inference
- OpenRouter — models API docs: https://openrouter.ai/docs/guides/overview/models ; FAQ/support (usage include, provider fallback, key checks): https://openrouter.ai/docs/faq.md , https://openrouter.ai/support ; generations & cost-control skills: https://openrouter.ai/skills/openrouter-generations ; Codex + OpenRouter tutorial (wire_api change): https://openrouter.ai/blog/tutorials/codex-cli-openrouter/ ; modality overview: https://openrouter.ai/blog/insights/every-modality-one-api/
- models.dev (schema, api.json/models.json/catalog.json): https://github.com/anomalyco/models.dev
- llama.cpp server README (router mode, response_format, chat_template_kwargs, reasoning_effort): https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md ; endpoint tour: https://mvysny.github.io/llama-server-endpoints/
- Ollama API docs and FAQ (think levels, keep_alive, queue 503): https://github.com/ollama/ollama/blob/main/docs/api.md , https://github.com/ollama/ollama/blob/main/docs/faq.md ; Go API types: https://pkg.go.dev/github.com/ollama/ollama/api
- Open Responses specification: https://www.openresponses.org/ , https://github.com/openresponses/openresponses
- OpenTelemetry GenAI observability (2026): https://opentelemetry.io/blog/2026/genai-observability/ ; convention status analysis: https://www.truefoundry.com/blog/opentelemetry-genai-semantic-conventions ; attribute survival guide: https://dev.to/gabrielanhaia/opentelemetry-genai-semantic-conventions-your-llm-traces-should-look-like-this-in-2026-3ff6
- Codex CLI provider configuration (config.toml fields): https://github.com/openai/codex/issues/2760 , https://www.morphllm.com/codex-provider-configuration , https://docs.litellm.ai/docs/proxy/client_setup/codex_cli
- OAuth landscape and policy changes (Anthropic subscription tokens, Codex OAuth pattern): https://developer.puter.com/tutorials/claude-oauth/ , https://developer.puter.com/tutorials/openai-oauth/ ; OpenClaw OAuth docs (PKCE flow, token sink): https://docs.claw.so/engine/concepts/oauth ; Hermes provider notes: https://hermes-agent.nousresearch.com/docs/integrations/providers
- pi-ai (api types, OAuth flows, cross-provider handoff): https://www.npmjs.com/package/@mariozechner/pi-ai , https://mariozechner.at/posts/2025-11-30-pi-coding-agent/ , https://github.com/rcarmo/go-ai
- LiteLLM docs — Anthropic provider (effort mapping, structured outputs, context management), reasoning content, prompt caching: https://docs.litellm.ai/docs/providers/anthropic , https://litellm.vercel.app/docs/reasoning_content , https://docs.litellm.ai/docs/completion/prompt_caching
- Vercel AI Gateway — Responses, Anthropic Messages, reasoning: https://vercel.com/docs/ai-gateway/sdks-and-apis/responses , https://vercel.com/docs/ai-gateway/sdks-and-apis/anthropic-messages-api , https://vercel.com/docs/ai-gateway/models-and-providers/reasoning/anthropic
- Qwen 3.8 API notes (reasoning_effort, enable_thinking, Anthropic-compatible endpoint): https://dev.to/hassann/how-to-use-the-qwen-38-api-1cgm ; DashScope Anthropic-compat thinking pitfall: https://github.com/ChatLab/ChatLab/issues/483 ; Qwen Code reasoning profiles: https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/
- DeepSeek Responses API guide: https://api-docs.deepseek.com/guides/responses_api
- 429 handling across vendors (header formats): https://dev.to/owen_fox/429-too-many-requests-what-it-means-when-to-retry-2026-5e9j ; Anthropic 429/529 production notes: https://www.respan.ai/articles/anthropic-api-rate-limits

*End of document.*
