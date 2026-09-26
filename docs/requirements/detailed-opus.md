# LLM Transport & Connectivity Layer — Requirements Baseline (Java SDK)

Sep 26, 2026 · @Alex

## 0. Meta and conventions

This baseline lists what a universal Java LLM transport SDK must support, with implementation notes and pitfalls, as input for API, facade and configuration design.

- Basis: public provider docs, specs and open-source behavior known as of mid-2026, written from memory without re-checking sources. Figures are approximate.
- Volatile details are marked (verify). Confirm them at implementation time and keep them in data, not code.
- Use: cite requirement IDs in design docs, tickets and LLM prompts; treat mapping tables as test fixtures to validate.

| Marker | Meaning |
| --- | --- |
| `AREA-NN` | Stable requirement ID, e.g. `AUTH-03` |
| \[M\] | Must: MVP (P0) |
| \[S\] | Should: P1 |
| \[C\] | Could: P2 or optional module |
| PITFALL: | Known failure mode or surprising behavior |
| → | Design implication |
| (verify) | Volatile detail; check current docs |

Core terms: Provider (vendor serving models), Endpoint (base URL + dialect + auth + network), Dialect (wire protocol family), Preset (data describing a known endpoint), Quirk (known deviation from a dialect), ModelRef / ModelProfile (model address / address + defaults + overrides), Capability (tri-state support flag). Full glossary in §30.

| § | Topic |
| --- | --- |
| 1 | Goals, use cases, scope, principles |
| 2 | Landscape: dialects, endpoints, standards, prior art |
| 3 | Architecture and core abstractions |
| 4 | Connection and transport |
| 5 | Authentication and credentials |
| 6 | Discovery and metadata |
| 7 | Unified request/response model |
| 8 | Generation parameters |
| 9 | Reasoning |
| 10 | Tools and MCP |
| 11 | Structured output |
| 12 | Streaming |
| 13 | Files and media |
| 14 | Caching |
| 15 | Usage, cost, statistics |
| 16 | Reliability |
| 17–19 | Jobs; server state and tokens; realtime |
| 20 | Gateway support |
| 21–22 | Observability; configuration |
| 23–24 | Security; Java engineering |
| 25–26 | Node-flow UI; agent harness |
| 27–29 | Pitfalls; phasing; open decisions |
| 30–31 | Glossary; quick reference |

## 1. Goals, use cases, scope, principles

Goal: one SDK that reaches any LLM endpoint (cloud, gateway, local) through a simple facade, while exposing every provider feature through typed extensions and raw escape hatches.

### 1.1 Use cases that drive requirements

| ID | Use case | Drives |
| --- | --- | --- |
| UC1 | Agent harness (OpenCode, Codex, Pi, OpenClaw-like) | Streaming tool loops, instant cancel, prompt caching, reasoning continuity, session portability, many providers, OAuth logins |
| UC2 | Node-flow workflow app (screenshots) | Introspectable endpoint/model info, parameter descriptors, attachments, fan-out, cost and failure outputs |
| UC3 | Gateway / proxy | Server-side codecs, dialect translation, passthrough, interceptors (compression, redaction), metering, routing |
| UC4 | Catalog analytics | Model/provider metadata, prices, uptime, latency, snapshots and diffs |
| UC5 | Batch / offline processing | Batch APIs, jobs, budgets, retries |
| UC6 | Local / edge inference | Ollama, llama.cpp, vLLM, LM Studio; discovery, cold starts, effective context |
| UC7 | App features | One-shot calls, structured extraction, image generation, STT/TTS, embeddings |

### 1.2 Scope

- In: connection, auth, discovery and metadata, unified and native request/response models, streaming, tool transport and MCP bridge, structured output, files and media, cache controls, usage and cost, reliability, batch/async, observability, configuration, gateway primitives.
- Out (hooks only): agent planning and orchestration, RAG and vector stores, prompt templating beyond variable substitution, memory and summarization algorithms, UI rendering.
- → Each out-of-scope item that touches transport gets an SPI: tool executor callback, context-overflow strategy, template hook, compaction hook.

### 1.3 Design principles

- P1 Layered with escape hatches: facade → unified → native → raw HTTP; every lower layer reachable from above.
- P2 No lowest common denominator: the unified model covers the common \~90%; provider features via typed options and passthrough; nothing dropped silently.
- P3 Data-driven variability: capabilities, quirks, prices, limits and parameter mappings live in versioned data, updatable without a code release.
- P4 Forward compatible: unknown fields, enum values, event types and content blocks are preserved and surfaced, never fatal.
- P5 Explicit transformations: every rename, drop, clamp, conversion and fallback is recorded in a per-request TransformationReport.
- P6 Secure by default: TLS verification on, secrets redacted, no content logging, SSRF guards.
- P7 Observable: request ids, timings, usage and cost on every result and error.
- P8 Deterministic serialization: stable JSON key and tool order for cache-friendly prefixes and reproducible tests.
- P9 Minimal core dependencies; heavy integrations (AWS, GCP, Azure, Netty, OTel) in optional modules.
- P10 Same DTOs on client and server side: codecs are bidirectional, which gateways need.
- P11 Never send what the user did not set: provider defaults differ, and sending "defaults" changes behavior or triggers 400 errors.

## 2. Landscape: dialects, endpoints, standards, prior art

Support is organized by wire dialect, not by vendor: about eight dialects cover nearly every endpoint, and vendors differ mainly in preset data and quirks.

### 2.1 Wire dialects

| Dialect | Core endpoints | Stream framing | Spoken by | Notes |
| --- | --- | --- | --- | --- |
| openai-chat | `POST /v1/chat/completions`, `GET /v1/models` | SSE `data:` chunks + `data: [DONE]` | OpenAI, Azure, OpenRouter, xAI, DeepSeek, Qwen (DashScope compatible-mode), Mistral (near), Groq, Together, Fireworks, Cerebras, DeepInfra, Perplexity, Gemini OpenAI-compat, HF router, LiteLLM, vLLM, SGLang, llama.cpp, Ollama `/v1`, LM Studio, LocalAI | Lingua franca; "compatible" varies widely → quirk flags (§22.4) |
| openai-responses | `POST /v1/responses`; `GET`/`DELETE /v1/responses/{id}`; `/cancel`; `/input_items`; input-token count and compact endpoints (verify); `/v1/conversations` | SSE typed `response.*` events with `sequence_number` | OpenAI, Azure (v1), xAI, OpenRouter (beta), vLLM, LM Studio, Ollama (verify); Open Responses adopters (verify) | Items-based; reasoning items; hosted tools; stateful chaining; background mode; some OpenAI models are Responses-only |
| anthropic-messages | `POST /v1/messages`, `/v1/messages/count_tokens`, `/v1/messages/batches`, `GET /v1/models`, `/v1/files` | SSE named events | Anthropic; Bedrock and Vertex (body variants); Anthropic-compatible: DeepSeek, Z.ai, Moonshot, MiniMax, Ollama, llama.cpp, vLLM, LiteLLM, OpenRouter (verify) | Second de-facto standard (Claude Code ecosystem) |
| gemini | `models/{m}:generateContent`, `:streamGenerateContent?alt=sse`, `:countTokens`, `:embedContent`, `:batchGenerateContent`; `/files`; `/cachedContents`; `GET /models`; Interactions API (beta, verify) | SSE with `alt=sse`, else a streamed JSON array | Gemini API (AI Studio); Vertex AI (other base URL, paths, auth) | Parts-based; thought signatures; safety settings |
| bedrock-converse | `Converse`, `ConverseStream`, `InvokeModel[WithResponseStream]` | AWS event-stream (binary) | AWS Bedrock | SigV4 or Bedrock API key; inference profiles |
| ollama-native | `/api/chat`, `/api/generate`, `/api/embed`, `/api/tags`, `/api/show`, `/api/ps`, `/api/pull`, `/api/version` | NDJSON (not SSE) | Ollama local, Ollama Cloud | `options{}`, `keep_alive`, `think`, `format` |
| llamacpp-native | `/completion`, `/infill`, `/tokenize`, `/detokenize`, `/apply-template`, `/embedding`, `/reranking`, `/props`, `/slots`, `/health`, `/metrics` | SSE | llama.cpp `llama-server` | GBNF grammar, samplers, slots |
| openai-completions (legacy) | `POST /v1/completions` (`prompt`, `suffix` for FIM) | SSE | vLLM, llama.cpp, Ollama, DeepSeek beta FIM, hosts | Base models, code fill-in-the-middle |
| others \[C\] | Cohere v2 (`/v2/chat`, `/v2/rerank`); Mistral extras (`/v1/fim/completions`, agents, OCR); realtime (OpenAI Realtime, Gemini Live) | SSE / WebSocket | — | Separate modules |

- Adjacent operation APIs, mostly OpenAI-shaped: `/v1/embeddings`, `/v1/images/*`, `/v1/audio/*`, `/v1/moderations`, `/v1/files`, `/v1/uploads`, `/v1/batches`, `/v1/videos`; rerank endpoints (Cohere, Jina, vLLM, llama.cpp).
- PITFALL: "OpenAI-compatible" and "Anthropic-compatible" are claims, not contracts: subsets, renamed fields, missing streaming usage, different error bodies.
- → `DialectCodec` (encode/decode request, response and stream; client and server side) is independent of `EndpointSpec`.
- Deprecated: OpenAI Assistants API (sunset 2026) — do not support; legacy `function_call` fields — decode only.

### 2.2 Endpoint categories

| Category | Examples | Specifics |
| --- | --- | --- |
| First-party model APIs | OpenAI, Anthropic, Google Gemini API, xAI, DeepSeek, Alibaba Qwen (DashScope intl/cn), Mistral, Cohere, Moonshot, Z.ai, MiniMax, Perplexity | Richest features; own quirks |
| Hyperscaler platforms | AWS Bedrock, Google Vertex AI, Azure OpenAI / AI Foundry | IAM auth, regions, deployments, own model id formats |
| Aggregators / routers | OpenRouter, HF Inference Providers, Vercel AI Gateway, Cloudflare AI Gateway | Per-model provider lists, routing preferences, credits, key info |
| Self-hosted gateways | LiteLLM Proxy, Portkey, Kong / Envoy AI Gateway, Bifrost, corporate gateways | Virtual keys, OAuth client credentials, mTLS, custom headers |
| Inference hosts | Groq, Together, Fireworks, Cerebras, DeepInfra, SambaNova, Nebius, Novita, Baseten | OpenAI-compatible with deviations |
| Local engines (default port) | Ollama 11434, llama.cpp 8080, LM Studio 1234, vLLM 8000, SGLang 30000, LocalAI 8080, Jan 1337, KoboldCpp 5001, TabbyAPI 5000, MLX server 8080 | No or optional auth, cold starts, effective context limits |

### 2.3 Standards and specs to align with

- HTTP: RFC 9110 (semantics, `Retry-After`), RFC 9112 (HTTP/1.1), RFC 9113 (HTTP/2); IETF RateLimit header draft.
- Streaming: WHATWG Server-Sent Events; NDJSON / JSON Lines; AWS event-stream; WebSocket (RFC 6455).
- Data: JSON (RFC 8259); JSON Schema 2020-12 plus draft-07 legacy; OpenAPI 3.0 schema subset (Gemini); base64 (RFC 4648); data URIs (RFC 2397); multipart (RFC 7578); RFC 3339 timestamps; ISO 4217 currency; BCP 47 language tags.
- Auth: OAuth 2.0 (RFC 6749/6750), PKCE (RFC 7636), native apps (RFC 8252), device grant (RFC 8628), AS metadata (RFC 8414), OIDC Discovery, DCR (RFC 7591), token exchange (RFC 8693), JWT bearer (RFC 7523), resource indicators (RFC 8707), protected resource metadata (RFC 9728), DPoP (RFC 9449), mTLS (RFC 8705), revocation (RFC 7009), OAuth 2.1 draft; AWS SigV4; Google ADC; Azure Entra ID.
- Agent protocols: MCP (JSON-RPC 2.0; stdio and Streamable HTTP; OAuth 2.1-based authorization; dated spec revisions). A2A, AG-UI and ACP are adjacent, outside the core.
- Interop: Open Responses spec (verify maturity); Standard Webhooks (used by OpenAI webhooks).
- Observability: OpenTelemetry GenAI semantic conventions (`gen_ai.*`, still evolving); W3C Trace Context; OpenMetrics.
- Model formats: tiktoken encodings (`o200k_base`, `cl100k_base`), HF `tokenizer.json`, GGUF metadata, Jinja chat templates, Harmony (gpt-oss).

### 2.4 Prior art to study

| Project | Take | Avoid |
| --- | --- | --- |
| LangChain4j, Spring AI (Java) | Builder DX, Spring/Quarkus integration, chat-model abstractions | Lowest-common-denominator types, feature lag, heavy transitive deps |
| Official Java SDKs (openai-java, anthropic-sdk-java, google-genai, AWS Bedrock Runtime) | Full-fidelity DTOs, raw response access, retry defaults | One vendor each; generated verbosity |
| Vercel AI SDK (TypeScript) | Provider spec: content parts, stream parts, providerOptions / providerMetadata | JS-specific patterns |
| pi-ai in pi-mono (TypeScript) | Unified API over OpenAI Completions/Responses, Anthropic, Google; cross-provider context handoff; thinking levels; abort; usage and cost; generated model registry; OAuth logins | — |
| LiteLLM (Python) | Per-provider parameter mapping, cost map JSON, proxy features | Implicit magic |
| models.dev | Open model database (limits, costs, modalities) used by OpenCode | Coverage gaps |
| OpenRouter API | Rich catalog, per-provider endpoints, routing, reasoning normalization | Aggregator-only semantics |
| Gateways (LiteLLM Proxy, Portkey, Envoy AI Gateway, Bifrost) | Virtual keys, routing configs, metering | — |

## 3. Architecture: layers, abstractions, extension points

Five layers, each usable alone, with explicit mappers between the unified and native models and raw access at every level.

### 3.1 Layers

```text
L4 Facade      Llm.chat(...), stream(...), models(), endpointInfo()        one-liners, safe defaults
L3 Unified     ChatRequest / ChatResponse / StreamEvent / ModelInfo / Usage / Cost / Error
L2 Services    auth + tokens, catalog + pricing, retry / rate limit / routing, cache hints, files, jobs, interceptors
L1 Native      per-dialect typed clients + full-fidelity DTOs (OpenAIChat, OpenAIResponses, AnthropicMessages, Gemini, Ollama ...)
L0 Transport   HttpTransport SPI, SSE / NDJSON / event-stream / WebSocket decoders, TLS + proxy, JsonCodec
```

- **ARCH-01** \[M\] Each layer usable standalone; higher layers expose `.native()` and `.raw()`.
- **ARCH-02** \[M\] Unified ↔ native mapping is explicit, lossless where possible, and reports losses.
- **ARCH-03** \[M\] Dialect codecs are decoupled from endpoints and presets (one dialect, many endpoints).
- **ARCH-04** \[M\] Codecs are bidirectional (client and server side) for gateways.
- **ARCH-05** \[S\] Presets and quirks are data resources loaded by a registry; users can add or override them.
- **ARCH-06** \[M\] Clients are stateless and thread-safe; state (sessions, caches, catalogs, tokens) lives in explicit objects.
- **ARCH-07** \[M\] The internal unified model is items-centric — a superset of chat messages, Responses items, Anthropic blocks and Gemini parts — with a simple message-list view (open decision, §29).

### 3.2 Core domain objects

| Object | Purpose |
| --- | --- |
| EndpointSpec | id, baseUrl, dialects + path overrides, credential ref, headers/query, apiVersion, region, org/project, network, policies, quirks, catalog sources |
| AuthProvider / Credential | Produces auth material per request (header, query, signature); refreshable |
| ModelRef | Endpoint + model id (+ variant, routing prefs); string form `endpoint:model`, split on the first colon (Ollama tags contain colons) |
| ModelInfo | Merged metadata with per-field provenance (§6.3) |
| ModelProfile | ModelRef + default params + capability/limit/price overrides + dialect choice |
| ChatRequest / ChatResponse / StreamEvent | Unified call model (§7, §12) |
| Usage / Cost / FinishReason | Normalized accounting and outcomes (§15, §7.5) |
| Attachment, FileRef, ToolSpec, ToolCall, ToolResult, ResponseFormat, ReasoningConfig, CacheHint | Request building blocks |
| Job | Batch, background response, long-running operation, media job (§17) |
| Conversation | Portable, serializable history (§7.7) |
| EndpointInfo / CatalogSnapshot | Account, key and credit info; immutable model catalog (§6) |
| TransformationReport | Renamed/dropped/clamped params, converted content, cache breakpoints, routes (§21) |

### 3.3 Extension points (SPI via ServiceLoader and programmatic registration)

HttpTransport, JsonCodec, DialectCodec, ProviderPreset/Adapter, AuthProvider, CredentialStore, SecretResolver, TokenCounter, ModelCatalogSource, PriceSource, RetryPolicy, RateLimiter, CircuitBreaker, Router/FallbackPolicy, Interceptor (request, response, stream), EventListener, CacheStore, AttachmentConverter, SchemaGenerator, ToolExecutor, ContextOverflowStrategy, IdGenerator, Clock.

### 3.4 Escape hatches

- **ESC-01** \[M\] `extraBody` (deep merge at any JSON path), `extraHeaders`, `extraQuery` at endpoint, model-profile and request level.
- **ESC-02** \[M\] Pre-send hook that can mutate the final wire JSON.
- **ESC-03** \[M\] Raw response JSON, headers and raw stream events on every result, event and error.
- **ESC-04** \[M\] Unknown fields kept (`extras: Map<String, JsonNode>`); unknown enum values as `Unknown(raw)`.
- **ESC-05** \[M\] Passthrough: send native JSON, receive native JSON (the lossless gateway path).

### 3.5 Target developer experience (pseudocode; model ids illustrative)

```text
llm  = Llm.fromEnv()                                        // presets + standard env vars
text = llm.chat("openai:gpt-5.1", "Summarize: ...").text()

llm = Llm.builder()
  .endpoint("or", Presets.OPENROUTER.apiKey(Secret.env("OPENROUTER_API_KEY")))
  .endpoint("local", Presets.OLLAMA.baseUrl("http://127.0.0.1:11434"))
  .defaults(Params.temperature(0.2))
  .build()

resp = llm.request("or:deepseek/deepseek-v4-flash")
  .system("You are ...").user("Explain the story", attach(Path.of("story.txt")))
  .reasoning(Effort.MEDIUM).responseFormat(schemaOf(Summary.class))
  .cache(CachePolicy.AUTO).maxOutputTokens(2000)
  .stream(ev -> switch (ev) {
      case TextDelta d    -> ui.append(d.text());
      case ToolCallDone t -> tools.run(t);
      case Finish f       -> ui.done(f.reason());
      default             -> {}
  });

info  = llm.endpointInfo("or")                               // credits, usage, limits, key expiry
model = llm.models("or").get("deepseek/deepseek-v4-flash")   // limits, prices, capabilities, providers
cost  = resp.cost()                                          // BigDecimal + currency + breakdown
```

## 4. Connection and transport

The transport must survive minutes-long streams, flaky local servers and corporate proxies; most field failures here come from timeouts, proxies and HTTP version negotiation.

### 4.1 Endpoint addressing

- **CONN-01** \[M\] Base URL + per-operation path templates (Azure deployments + `api-version`, Vertex `projects/{p}/locations/{l}/publishers/{pub}/models/{m}:{method}`, Gemini `models/{m}:{method}`).
- **CONN-02** \[M\] Normalize base URL variants (trailing slash, with or without `/v1`) per preset. PITFALL: a doubled `/v1/v1` is the most common misconfiguration.
- **CONN-03** \[M\] Regional and data-residency endpoints (DashScope intl vs cn, Vertex regional vs `global`, Azure resource, Bedrock region, provider residency domains (verify)).
- **CONN-04** \[S\] Several EndpointSpecs per provider (keys, projects, regions), groupable into pools.
- **CONN-05** \[C\] Unix domain socket transport for local servers and sidecars.

### 4.2 HTTP behavior

- **HTTP-01** \[M\] HTTP/1.1 and HTTP/2 via TLS ALPN; HTTP/1.1 by default for cleartext `http://`. PITFALL: JDK HttpClient in HTTP/2 mode attempts an h2c upgrade on cleartext, which confuses some local servers and proxies.
- **HTTP-02** \[M\] Connection pooling, keep-alive, per-host limits, idle eviction. PITFALL: stale pooled connections fail on first write; a retry is safe only if the request was not sent.
- **HTTP-03** \[M\] Accept gzip/br for JSON; avoid compression on streams. PITFALL: compressed SSE is often buffered by proxies.
- **HTTP-04** \[M\] Follow redirects only for safe methods; never forward auth headers cross-origin.
- **HTTP-05** \[M\] `User-Agent` = `<sdk>/<version> java/<version>`; app attribution headers (OpenRouter `HTTP-Referer`, `X-Title`).
- **HTTP-06** \[M\] Pre-flight body size check against provider limits (e.g. Anthropic Messages 32 MB); switch to a Files API or fail early.
- **HTTP-07** \[M\] Streamed request bodies for uploads; no full in-memory buffering of large files or base64.

### 4.3 Timeouts

- **TIME-01** \[M\] Separate timeouts: connect, TLS, response headers, first token (TTFT), inter-chunk idle (stall), total deadline, write.
- **TIME-02** \[M\] Stall watchdog for streams. PITFALL: JDK `HttpRequest.timeout()` only covers the wait for response headers.
- **TIME-03** \[M\] Long-running defaults: reasoning models may think for minutes before the first token, and load balancers drop idle connections after roughly 5–10 min. → Prefer streaming, TCP keepalive or background mode; warn on non-streaming calls with large `max_tokens` (Anthropic SDKs refuse calls expected to exceed \~10 min).
- **TIME-04** \[M\] Local cold start: the first request may load a model for 10–120 s → separate warm-up timeout and an optional preload call.
- **TIME-05** \[S\] Deadline propagation (gateway client deadline → upstream cancel).

### 4.4 Proxy, TLS, certificates

- **NET-01** \[M\] HTTP(S) proxy with auth; honor `HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY` and Java system properties; `NO_PROXY` defaults include `localhost`, `127.0.0.1`, `::1`.
- **NET-02** \[M\] Custom trust store or extra CA (corporate TLS inspection); mTLS client certificates; hostname verification on; "insecure" mode only explicit and logged.
- **NET-03** \[M\] TCP keepalive on long streams.
- **NET-04** \[C\] Certificate pinning, custom DNS resolver, IPv6-only networks.
- PITFALL: JDK HttpClient has no SOCKS proxy support, and Basic auth for HTTPS tunnels is disabled by default (`jdk.http.auth.tunneling.disabledSchemes`) → offer OkHttp or Netty transports.

### 4.5 Response framing decoders

- **FRAME-01** \[M\] SSE per WHATWG: `event`, `data` (multi-line joined with `\n`), `id`, `retry`; comment lines (keepalives such as OpenRouter `: OPENROUTER PROCESSING`); CR, LF and CRLF; BOM; dispatch on blank line; `data: [DONE]`.
- **FRAME-02** \[M\] Robustness: UTF-8 multi-byte characters split across network chunks; multi-MB events (base64 images) without quadratic buffering; a max event size.
- **FRAME-03** \[M\] NDJSON decoder (Ollama native, JSONL batch results).
- **FRAME-04** \[S\] Streamed JSON array decoder (Gemini without `alt=sse`).
- **FRAME-05** \[S\] AWS event-stream binary decoder with CRC checks (Bedrock).
- **FRAME-06** \[C\] WebSocket client (Realtime, Live).
- **FRAME-07** \[M\] Binary bodies (TTS audio, images, file content) streamed to a sink.

### 4.6 Cancellation

- **CANC-01** \[M\] Cancel any in-flight call: sync (handle or interrupt), async (future cancel), stream (`close()`); the socket closes immediately.
- **CANC-02** \[M\] Keep partial results on cancel (text, tool calls, usage if known) with `FinishReason.CANCELLED`.
- **CANC-03** \[S\] Provider-side cancel where offered (OpenAI background responses: `POST /v1/responses/{id}/cancel`).
- PITFALL: some providers keep generating and billing after a client disconnect; behavior varies and is rarely documented.

### 4.7 Health, reachability, auto-detection

- **HLTH-01** \[M\] `ping()` → reachable, latency, TLS ok, auth ok (cheapest authenticated call), server version.
- **HLTH-02** \[S\] Auto-detect the server type from a bare URL: `/api/version` (Ollama), `/props` or `/health` (llama.cpp), `/v1/models` shape (vLLM `max_model_len`, `owned_by`), `/api/v0/models` (LM Studio), `/api/v1/key` (OpenRouter), `server` header; cache results.
- **HLTH-03** \[S\] Dialect probing (`/v1/chat/completions`, `/v1/responses`, `/v1/messages`) via minimal requests; opt-in, because probes can cost tokens.
- **HLTH-04** \[S\] Local discovery by scanning well-known localhost ports (opt-in).
- **HLTH-05** \[C\] Provider status pages (Statuspage `/api/v2/status.json`) for dashboards.

## 5. Authentication and credentials

Auth is a pluggable per-request provider: static keys are the common case, but OAuth, cloud IAM signing, per-tenant keys and key pools must fit the same interface.

### 5.1 Credential placement

| Endpoint | Mechanism |
| --- | --- |
| OpenAI and most OpenAI-compatible | `Authorization: Bearer <key>`; optional `OpenAI-Organization`, `OpenAI-Project` |
| Anthropic | `x-api-key` + `anthropic-version: 2023-06-01`; optional `anthropic-beta: a,b` |
| Gemini API | `x-goog-api-key` (or `?key=`, which leaks into logs) |
| Vertex AI | `Authorization: Bearer <OAuth2 access token>` (ADC, service account, workload identity federation); express-mode API keys (verify) |
| Azure OpenAI | `api-key`, or Entra ID Bearer (scope `https://cognitiveservices.azure.com/.default`) |
| AWS Bedrock | SigV4 (access key, secret, session token; profiles, SSO, IRSA, IMDS), or a Bedrock API key as Bearer |
| OpenRouter | Bearer key; OAuth PKCE issues user keys; management (provisioning) keys for key CRUD |
| Local engines | Usually none; optional Bearer (`--api-key`) or reverse-proxy auth |
| Corporate gateways | OAuth2 client credentials, mTLS, custom headers (tenant, cost center) |
| Remote MCP servers | OAuth 2.1 + PKCE with RFC 9728 discovery, or static Bearer |

### 5.2 API keys

- **AUTH-01** \[M\] Key sources: env var, file, OS keychain, secret manager via `SecretResolver` (Vault, AWS Secrets Manager, GCP Secret Manager, Azure Key Vault); literals allowed but discouraged; lazy resolution.
- **AUTH-02** \[M\] `Secret` type: redacted `toString`, never serialized, display fingerprint (prefix + last 4) and optional label.
- **AUTH-03** \[M\] Per-request credential override (multi-tenant gateway, BYOK).
- **AUTH-04** \[M\] Hot rotation without rebuilding clients (the provider re-reads the source).
- **AUTH-05** \[S\] Key validation via the cheapest authenticated call (OpenRouter `/api/v1/key`, `/v1/models` elsewhere).
- **AUTH-06** \[S\] Key metadata: label, limits, usage, expiry, scopes (from key-info APIs where offered).
- **AUTH-07** \[S\] Key pools: round-robin, least-used, rotate-on-429; per-key rate-limit state and cooldown. PITFALL: may breach provider ToS and destroys cache affinity.

### 5.3 OAuth 2.x

- **OAUTH-01** \[S\] Flows: Authorization Code + PKCE (S256) with loopback redirect (RFC 8252) and a paste-the-code fallback for headless hosts; Device Authorization Grant (RFC 8628: poll at `interval`, handle `authorization_pending`, `slow_down`, `expired_token`); Client Credentials; Refresh Token; JWT bearer assertion (RFC 7523); token exchange (RFC 8693) \[C\].
- **OAUTH-02** \[S\] Discovery: RFC 8414 or OIDC well-known documents; for MCP, RFC 9728 → AS metadata; dynamic client registration (RFC 7591); resource indicators (RFC 8707).
- **OAUTH-03** \[S\] Token manager: cache, proactive refresh (expiry minus a skew margin), single-flight refresh, one forced refresh and retry on 401, revocation on logout, clock-skew tolerance.
- **OAUTH-04** \[S\] Token store SPI: memory, encrypted file, OS keychain (macOS Keychain, Windows Credential Manager / DPAPI, Linux Secret Service); multiple accounts and profiles.
- **OAUTH-05** \[S\] Host-app callbacks: open browser, show device code and URL, cancel; `state` CSRF check; custom success page; timeouts.
- **OAUTH-06** \[C\] DPoP (RFC 9449) and certificate-bound tokens (RFC 8705).
- PITFALL: rotating refresh tokens are single-use → persist atomically, or the user is silently logged out. Scopes and audiences differ per provider.

### 5.4 Cloud IAM (optional modules)

- **IAM-01** \[S\] AWS: SigV4 signer (service `bedrock`), default credential chain, STS temporary credentials, region; Bedrock API keys.
- **IAM-02** \[S\] GCP: ADC chain (`GOOGLE_APPLICATION_CREDENTIALS`, gcloud user credentials, metadata server), service-account JWT, workload identity federation, impersonation, quota project header `x-goog-user-project`.
- **IAM-03** \[S\] Azure: api-key or Entra ID (client secret or certificate, managed identity, workload identity, Azure CLI).
- PITFALL: cloud access tokens last about 1 h; refresh through the token manager, never per request.

### 5.5 Gateway-specific auth

- **GWA-01** \[S\] OpenRouter OAuth PKCE key issuance: redirect with `callback_url` and `code_challenge`, then exchange `code` at `POST /api/v1/auth/keys` for a user-owned key.
- **GWA-02** \[S\] Management keys: create, list, update and delete keys; set credit limits (`/api/v1/keys`).
- **GWA-03** \[S\] BYOK awareness: provider keys stored at the gateway; usage reported separately (`byok_usage`).
- **GWA-04** \[S\] Virtual keys and metadata headers for LiteLLM, Portkey and similar gateways.

### 5.6 Subscription and consumer-account auth (high risk)

- Examples: ChatGPT sign-in for Codex (PKCE), GitHub Copilot (device flow → short-lived token + editor headers), Qwen Code OAuth (device flow), Gemini CLI Google login, Claude Pro/Max OAuth.
- **SUB-01** \[C\] Only as isolated, opt-in AuthProvider modules labeled unofficial.
- PITFALL: several vendors restrict subscription tokens to their own clients, and Anthropic has enforced this against third-party harnesses. Endpoints and headers are undocumented and change without notice. Make ToS responsibility explicit to integrators.

### 5.7 Secret hygiene

- **SEC-01** \[M\] Redact everywhere (logs, errors, metrics, traces, curl exports): `Authorization`, `x-api-key`, `api-key`, `x-goog-api-key`, `?key=`, cookies, OAuth codes and tokens, signed URLs.
- **SEC-02** \[M\] Never write secrets into saved configs or workflows; store references (`${env:...}`, `${secret:...}`).
- **SEC-03** \[S\] Minimize secret lifetime in memory (`char[]` or `byte[]` where feasible; JVM strings are immutable).

## 6. Discovery and metadata

First-party `/models` endpoints return little beyond ids, so rich metadata (limits, prices, capabilities) is merged from several sources with per-field provenance and staleness.

### 6.1 Endpoint and account info

Unified `EndpointInfo`: every field optional, each with unit, source and `fetchedAt`.

| Group | Fields | Typical sources |
| --- | --- | --- |
| Identity | Gateway/provider, base URL, server type + version, dialects, reachable, latency, models served | ping, probes, `/models` |
| Key | Label, fingerprint, scopes, created, expires, free-tier flag, rate-limit tier | OpenRouter `/api/v1/key` (`label`, `is_free_tier`, `expires_at`); OpenAI project key settings (admin) |
| Usage | Total, today, week, month; BYOK usage and whether it counts toward the limit; free-model requests used/quota | OpenRouter `/api/v1/key` (`usage`, `usage_daily/weekly/monthly`, `byok_usage`, `include_byok_in_limit`) |
| Limits | Credit limit, remaining, reset period; RPM / TPM / RPD | Key APIs (`limit`, `limit_remaining`, `limit_reset`), rate-limit headers |
| Balance | Credits purchased, used, remaining (with currency) | OpenRouter `/api/v1/credits` (`total_credits`, `total_usage`); DeepSeek `/user/balance` (CNY or USD); Moonshot balance API |
| Catalogue stats | Counts by capability (vision, reasoning, tools, structured output, files); modalities seen | Computed from the catalog |
| Local server | Loaded models, VRAM/RAM, context per slot, parallel slots, build | Ollama `/api/ps`; llama.cpp `/props`, `/slots`; LM Studio `/api/v0/models` |

- **META-01** \[M\] Unified `EndpointInfo` with optional fields, an `extras` map and raw payloads.
- **META-02** \[M\] Per-field provenance: `api`, `header`, `catalog`, `override` or `computed`, plus `fetchedAt` and TTL.
- **META-03** \[S\] Refresh modes: on demand, TTL, background, stale-while-revalidate; change events.
- PITFALL: OpenAI and Anthropic expose usage and cost only through Admin APIs with admin keys; standard keys have no balance endpoint.
- PITFALL: counters mix units (USD credits vs request counts) and reset clocks (UTC; verify).

### 6.2 Catalog operations

- **CAT-01** \[M\] List with pagination (Anthropic `after_id`/`before_id`/`limit`/`has_more`; Gemini `pageToken`/`pageSize`), get by id, filter (capability, modality, price, context, provider, tags), sort.
- **CAT-02** \[M\] Per-model provider endpoints on aggregators: OpenRouter `GET /api/v1/models/{author}/{slug}/endpoints` → provider, quantization, context, max output, prices, supported params, status, uptime.
- **CAT-03** \[M\] Immutable, diffable `CatalogSnapshot` (added, removed, changed models and prices).
- **CAT-04** \[S\] Offline mode: bundled snapshot in a data jar plus user overrides.
- **CAT-05** \[S\] Aggregates for UIs: counts by capability, modality and provider.

### 6.3 Unified ModelInfo fields

| Group | Fields |
| --- | --- |
| Identity | id (endpoint-specific), canonicalId, name, author/provider, family, version/snapshot, aliases, hfId, description, createdAt, releaseDate, knowledgeCutoff, deprecatedAt, retiresAt, replacement, openWeights, license |
| Limits | contextWindow (define: input + output unless stated), maxInputTokens, maxOutputTokens, reasoning budget min/max, maxImages, maxFileBytes, maxPdfPages, maxTools, maxStopSequences, per-request limits |
| Modalities | Input: text, image, audio, video, file/pdf. Output: text, image, audio, video, embedding |
| Capabilities | Tri-state set (§6.4) |
| Pricing | Components and tiers (§6.5) |
| Parameters | supportedParameters, defaultParameters, ranges (e.g. temperature max 1 vs 2) |
| Reasoning | Supported, mandatory, control type (effort levels, budget range, level, toggle), visibility (raw, summary, encrypted, none) |
| Tokenizer | Encoding/tokenizer id, chat template id |
| Serving | providers\[\]: name, quantization, context, max output, price, uptime, latency, throughput, ZDR/data policy. Local: parameter size, quantization, format, file size, digest, loaded state |
| Dialect | Preferred or required dialect (e.g. Responses-only), required headers and betas |
| Policy | Training/retention terms, moderation, region availability |
| Provenance | Per-field source + fetchedAt |

### 6.4 Capability taxonomy

- Core: text, streaming, systemPrompt, developerRole, multiTurn, stopSequences, seed, n>1, logprobs (top N), assistantPrefill.
- Tools: tools, parallelToolCalls, toolChoice (auto, none, required, specific, allowed subset), strictTools, customGrammarTools, toolArgStreaming.
- Output control: jsonMode, jsonSchema (strict), grammar (GBNF, regex, Lark, choice), citations.
- Reasoning: reasoning, effortLevels, budgetRange, interleavedThinking, reasoningSummary, encryptedReasoning.
- Caching: automatic, explicitBreakpoints, cacheResource, ttlOptions.
- Media: vision, imageDetail, pdfInput, fileIds, audioIn, audioOut, videoIn, imageOut, imageEdit, embeddings (dimensions), rerank.
- Hosted tools: webSearch, webFetch, codeExecution, fileSearch, computerUse, shell/applyPatch, textEditor, memory, toolSearch, remoteMcp.
- Platform: batch, backgroundMode, statefulResponses, compaction, predictedOutputs, serviceTiers, realtime, fineTuned/LoRA.
- **CAPS-01** \[M\] Tri-state values: SUPPORTED, UNSUPPORTED, UNKNOWN, each with a source; missing data means UNKNOWN, never UNSUPPORTED.
- **CAPS-02** \[M\] Pre-send capability check with a policy: fail fast, warn, or try anyway.

### 6.5 Pricing model

- Components: input, output, reasoning (usually the output rate), cache read, cache write per TTL, audio in/out, image in (per image or token), image out (per image by size/quality, or per token), per-request fee, web search per call, code-execution time, cache storage per token-hour, video per second, embeddings, fine-tuned uplift.
- Modifiers: long-context tiers (a price step above a threshold such as 200K input tokens, usually applied to the whole request), batch discount (\~50%), service tiers (OpenAI flex/priority; others verify), regional uplift, free variants, BYOK fees, variable-price router models.
- **PRICE-01** \[M\] `BigDecimal` + ISO 4217 currency; store per token, display per 1M tokens.
- **PRICE-02** \[M\] Source precedence: override > live API (OpenRouter, xAI) > bundled catalog > community data (models.dev, LiteLLM cost map).
- **PRICE-03** \[M\] A versioned, dated price snapshot attached to every cost computation.
- PITFALL: OpenRouter prices are strings in USD per token; router models use sentinel values (verify); prices change without notice.
- PITFALL: an aggregator's model-level price is not the price of the provider that served the call (screenshot: model $0.049 / $0.098 per M vs providers from $0.03 / $1.28) → compute cost from the actual route.

### 6.6 Limits

- **LIM-01** \[M\] Expose limits with provenance; allow overrides; use them for pre-flight validation and clamping.
- Relationships: on Anthropic, input + `max_tokens` must fit the window, and thinking counts inside `max_tokens`; on OpenAI, reasoning tokens count against `max_output_tokens`; on Gemini, thinking has its own budget but bills as output.
- Rate limits: RPM, RPD, TPM, input/output TPM split (Anthropic), concurrency; scoped per model, org, key and tier.
- PITFALL: the same model on an aggregator has different context, max output and quantization per provider (screenshot: 1,048,576 vs 1,024,000 context; 943,718 vs 131,072 max output) → effective limits depend on the route.

### 6.7 Metadata sources and merging

| Source | Strength | Weakness |
| --- | --- | --- |
| Live endpoint APIs | Authoritative availability | Sparse fields, except OpenRouter, Gemini, Mistral, xAI |
| Bundled catalog (data jar) | Curated, offline | Stale between releases |
| models.dev, LiteLLM cost map | Broad coverage of prices and limits | Community-maintained; id mismatches |
| Capability probes (opt-in) | Ground truth for unknown servers | Cost, rate limits, false negatives |
| Name heuristics | Always available | Low confidence; must be flagged |
| User overrides | Highest precedence | Manual upkeep |

- **MERGE-01** \[M\] Field-level merge with precedence, provenance and confidence; conflicts logged.
- **MERGE-02** \[M\] Cross-source identity map, e.g. `claude-sonnet-4-5-20250929` ↔ Bedrock `anthropic.claude-sonnet-4-5-20250929-v1:0` (+ `us.`/`eu.`/`global.` inference profiles) ↔ Vertex `claude-sonnet-4-5@20250929` ↔ OpenRouter `anthropic/claude-sonnet-4.5`.
- **MERGE-03** \[S\] Disk cache with TTL; conditional requests (ETag) where supported.

### 6.8 Model identity and lifecycle

- **ID-01** \[M\] Aliases vs pinned snapshots; record the actual model from each response. PITFALL: the response `model` can differ from the requested one (alias resolution, aggregator fallback or auto-routing).
- **ID-02** \[S\] Deprecation and retirement tracking, warnings, replacement hints.
- **ID-03** \[M\] Variant syntax: OpenRouter suffixes (`:free`, `:nitro`, `:floor`, `:online`, `:thinking`, `:exacto`; verify) and `@preset/...`; Ollama tags `name:size-quant`; llama.cpp HF `repo:quant`; vLLM LoRA adapter names; fine-tune ids `ft:...`.

### 6.9 Local model metadata

- Ollama: `/api/tags` (size, digest, family, `parameter_size`, `quantization_level`), `/api/show` (capabilities: completion, tools, vision, thinking, embedding, insert; `model_info` incl. context length; template; license), `/api/ps` (loaded, `size_vram`, expiry, context).
- llama.cpp: `/props` (default generation settings, `n_ctx`, total slots, chat template, modalities, build), `/v1/models` meta (`n_ctx_train`, `n_params`, size), `/slots`, `/metrics`.
- LM Studio `/api/v0/models`: type (llm, vlm, embeddings), architecture, quantization, loaded state, max context.
- vLLM `/v1/models`: `max_model_len`, `root`, `parent` (LoRA).
- PITFALL: effective context ≠ trained context. Ollama's default `num_ctx` is small and version/hardware-dependent, so prompts are silently truncated; llama.cpp splits `n_ctx` across parallel slots. Surface both effective and maximum values.

## 7. Unified request and response model

One items-centric model represents messages, content blocks, reasoning, tool calls and hosted-tool outputs from every dialect, without losing opaque provider data.

### 7.1 Operations

| Group | Operations |
| --- | --- |
| Generation | chat, chatStream, completion (legacy text + FIM), countTokens |
| Vectors | embed, rerank |
| Safety | moderate |
| Media | imageGenerate / Edit / Variation, transcribe / translate (STT), speech (TTS), videoGenerate \[C\], realtimeSession \[C\] |
| Files | upload, list, get, content, delete |
| Jobs | batch create / get / list / cancel / results; background response get / cancel / stream-resume; LRO poll |
| Server state | response get / delete / inputItems / compact; conversations \[S\]; cachedContents create / get / list / updateTtl / delete |
| Local management \[C\] | tokenize / detokenize; model pull / load / unload / delete (Ollama, LM Studio) |

### 7.2 Messages and roles

- Canonical roles: system, developer, user, assistant, tool.
- Mapping:
  - Anthropic: top-level `system` (text blocks, cacheable); tool results are `tool_result` blocks in the next user message.
  - Gemini: `systemInstruction`; roles `user` and `model`; function responses as user parts.
  - OpenAI Responses: `instructions` or system/developer input items; tool results as `function_call_output` items.
  - OpenAI chat: `developer` for reasoning models (system accepted or converted; verify per model); `tool` role with `tool_call_id`.
- **MSG-01** \[M\] Normalize per target: merge consecutive same-role turns where required; enforce first-turn rules; handle mid-conversation system messages by policy (convert to marked user text, hoist, or error).
- **MSG-02** \[M\] `name` field support governed by a quirk flag.
- **MSG-03** \[S\] Assistant prefill (a partial final assistant message) where supported. PITFALL: not allowed with thinking, and dropped on some newer models.
- **MSG-04** \[M\] Local-only message metadata (id, timestamps, origin model) is never sent on the wire.

### 7.3 Content parts (sealed hierarchy)

| Part | Fields and notes |
| --- | --- |
| Text | text, cacheHint, citations |
| Image | bytes/base64, URL or fileId; MIME; detail/resolution |
| Audio | bytes or fileId; format |
| Video | fileUri, URL or bytes; fps and clip offsets (Gemini) |
| Document | PDF, text or other via bytes, URL or fileId; title, context, citations flag |
| ToolCall | id, name, args (raw string + parsed), kind: function, custom or hosted |
| ToolResult | callId, content (text or parts incl. images/docs), isError |
| Reasoning | Text, summary list, encrypted blob or redacted blob; signature; origin provider + model |
| Refusal | text |
| Citation | URL, title, spans, file id, search-result reference |
| HostedToolCall / Result | Web search queries and results, code output, file search results, image-generation results; typed where common, else opaque |
| GeneratedMedia | Image, audio or video bytes, or URL + expiry |
| ProviderItem | Raw JSON + provider tag for anything unknown (compaction items, MCP listings, new types) |

### 7.4 Response

- Ids: provider response id, request-id header, gateway generation id (OpenRouter `gen-...`), client correlation id.
- Actual model, actual upstream provider (aggregators), system fingerprint, actual service tier.
- Ordered output items plus helpers: `text()`, `toolCalls()`, `reasoning()`, `parsed(Class<T>)`.
- Finish reason (normalized + raw), usage (normalized + raw), cost, timings (queue, TTFT, total, tokens/s).
- TransformationReport warnings; typed provider metadata (e.g. Gemini safety ratings and grounding, Anthropic container id); raw JSON and headers.

### 7.5 Finish-reason normalization

| Normalized | OpenAI chat | OpenAI Responses | Anthropic | Gemini | Notes |
| --- | --- | --- | --- | --- | --- |
| STOP | `stop` | `completed` | `end_turn` | `STOP` |  |
| STOP\_SEQUENCE | `stop` | `completed` | `stop_sequence` (+ which one) | `STOP` | OpenAI does not say which sequence matched |
| LENGTH | `length` | `incomplete`, reason `max_output_tokens` | `max_tokens` | `MAX_TOKENS` | Can mean reasoning exhausted the budget, leaving empty text |
| CONTEXT\_OVERFLOW | 400 error | error or incomplete | `model_context_window_exceeded` | error |  |
| TOOL\_CALLS | `tool_calls` (legacy `function_call`) | function\_call items present | `tool_use` | `STOP` + functionCall parts | Gemini signals through parts, not the reason |
| CONTENT\_FILTER | `content_filter` | `incomplete`, reason `content_filter` | — | `SAFETY`, `RECITATION`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `SPII`, `IMAGE_SAFETY` | Also Gemini `promptFeedback.blockReason` |
| REFUSAL | `message.refusal` set | refusal content | `refusal` | — |  |
| PAUSED | — | — | `pause_turn` | — | Resend to continue (long hosted tools) |
| MALFORMED\_TOOL\_CALL | — | — | — | `MALFORMED_FUNCTION_CALL`, `UNEXPECTED_TOOL_CALL` |  |
| ERROR | stream error | `failed` | error event | `OTHER` | OpenRouter streams finish `error` |
| CANCELLED | client | `cancelled` | client | client |  |
| UNKNOWN(raw) | new values | new values | new values | `LANGUAGE`, new values | Forward compatibility |

Local servers: Ollama `done_reason` (`stop`, `length`); llama.cpp and vLLM follow OpenAI chat.

### 7.6 Provider options and metadata

- **OPT-01** \[M\] Typed option objects per provider, attached to the request; other endpoints ignore or reject them by policy.
- Examples:
  - OpenAI: `store`, `previous_response_id`, `service_tier`, `prompt_cache_key`, `prompt_cache_retention`, `safety_identifier`, `metadata`, `truncation`, `include[]`, `text.verbosity`, `prediction`, `logit_bias`, `background`.
  - Anthropic: betas, `top_k`, `metadata.user_id`, `container`, context management, MCP servers, `service_tier`.
  - Gemini: `safetySettings`, `cachedContent`, `responseModalities`, `speechConfig`, `mediaResolution`, `labels` (Vertex).
  - OpenRouter: `provider{}` routing, `models[]` fallbacks, `transforms`, `plugins`, usage accounting.
  - Local: Ollama `options{num_ctx, ...}`, `keep_alive`, `raw`; llama.cpp `grammar`, `samplers`, `cache_prompt`, `id_slot`, DRY/XTC; vLLM `structured_outputs`, `chat_template_kwargs`, `priority`.

### 7.7 Conversation portability

- **PORT-01** \[M\] Versioned, neutral JSON serialization of conversations: items, tool calls and results, per-turn model and usage, provider opaque blobs.
- **PORT-02** \[M\] Handoff rules: same provider + model → return opaque reasoning verbatim; different model → drop encrypted/signed blocks, optionally convert visible reasoning to tagged text; remap tool-call ids and names; convert unsupported parts (audio → transcript placeholder) with a report entry.
- **PORT-03** \[S\] Import and export native histories (OpenAI messages, Anthropic messages, Gemini contents).

## 8. Generation parameters

Parameters need a machine-readable registry — one canonical name, a wire mapping per dialect, and per-model support, ranges and defaults — which drives validation, UI generation and gateway translation.

### 8.1 Registry and policy

- **PARAM-01** \[M\] Descriptor per canonical parameter: key, type, unit, range or enum, default (per model when known), description, category (sampling, length, penalties, output, reasoning, tools, safety, advanced), wire mapping per dialect (path, name, transform), per-model support (tri-state), conflicts.
- **PARAM-02** \[M\] Unsupported-parameter policy: `STRICT` (error), `WARN_DROP`, `SILENT_DROP`, `PASSTHROUGH`; configurable per endpoint and per request.
- **PARAM-03** \[M\] Validate and clamp ranges per model (temperature 0–1 on Anthropic, 0–2 on OpenAI and Gemini), with report entries.
- **PARAM-04** \[M\] Layering: endpoint < model profile < preset < request. Unset means not sent; the SDK has no hidden defaults except for mandatory fields (Anthropic `max_tokens`).
- **PARAM-05** \[S\] `explain()`: effective values with their source; a "modified only" diff against defaults.

### 8.2 Canonical parameters and wire names (indicative; verify)

| Canonical | OpenAI chat | OpenAI Responses | Anthropic | Gemini `generationConfig` | Ollama `options` | llama.cpp / vLLM | OpenRouter |
| --- | --- | --- | --- | --- | --- | --- | --- |
| maxOutputTokens | `max_completion_tokens` (legacy `max_tokens`) | `max_output_tokens` | `max_tokens` (required) | `maxOutputTokens` | `num_predict` | `n_predict` / `max_tokens` | `max_tokens` |
| temperature | 0–2 | same | 0–1 | 0–2 | `temperature` | `temperature` | `temperature` |
| topP | `top_p` | `top_p` | `top_p` | `topP` | `top_p` | `top_p` | `top_p` |
| topK | — | — | `top_k` | `topK` | `top_k` | `top_k` | `top_k` |
| minP | — | — | — | — | `min_p` | `min_p` | `min_p` |
| topA | — | — | — | — | — | — | `top_a` |
| presencePenalty | `presence_penalty` (−2..2) | — | — | `presencePenalty` | `presence_penalty` | `presence_penalty` | `presence_penalty` |
| frequencyPenalty | `frequency_penalty` | — | — | `frequencyPenalty` | `frequency_penalty` | `frequency_penalty` | `frequency_penalty` |
| repetitionPenalty | — | — | — | — | `repeat_penalty` (+ `repeat_last_n`) | `repeat_penalty` / `repetition_penalty` | `repetition_penalty` |
| seed | `seed` | — | — | `seed` | `seed` | `seed` | `seed` |
| stop | `stop` (≤ 4) | — | `stop_sequences` | `stopSequences` (≤ 5) | `stop` | `stop` | `stop` |
| n | `n` | — | — | `candidateCount` | — | `n` | — |
| logprobs | `logprobs`, `top_logprobs` (≤ 20) | `top_logprobs` + `include` | — | `responseLogprobs`, `logprobs` | `logprobs` (newer; verify) | `n_probs` / `logprobs` | `logprobs`, `top_logprobs` |
| logitBias | `logit_bias` | — | — | — | — | `logit_bias` | `logit_bias` |
| responseFormat | `response_format` | `text.format` | structured-output config (verify) | `responseMimeType` + `responseSchema` / `responseJsonSchema` | `format` | `response_format`, `json_schema`, `grammar`, `structured_outputs` | `response_format` |
| reasoning | `reasoning_effort` | `reasoning{effort, summary}` | `thinking{...}` + effort | `thinkingConfig` | `think` | `chat_template_kwargs`, reasoning budget | `reasoning{effort or max_tokens, exclude, enabled}` |
| verbosity | `verbosity` | `text.verbosity` | — | — | — | — | `verbosity` |
| toolChoice / parallel | `tool_choice`, `parallel_tool_calls` | same | `tool_choice{type, disable_parallel_tool_use}` | `toolConfig.functionCallingConfig` | — | `tool_choice` | as OpenAI |
| userId | `safety_identifier` / `user` | `safety_identifier` | `metadata.user_id` | — | — | — | `user` |
| serviceTier | `service_tier` | `service_tier` | `service_tier` | (verify) | — | vLLM `priority` | — |
| streamUsage | `stream_options.include_usage` | always | always | always | final chunk | `stream_options` | final chunk |

- Local sampler extras (registry entries with per-server support): `typical_p`; mirostat (`mirostat`, `mirostat_tau`, `mirostat_eta`); DRY (`dry_multiplier`, `dry_base`, `dry_allowed_length`, `dry_penalty_last_n`); XTC (`xtc_probability`, `xtc_threshold`); dynamic temperature; sampler order; `num_keep`.
- Load-time options (Ollama): `num_ctx`, `num_batch`, `num_gpu`, `num_thread` — they change memory use and may reload the model.

### 8.3 Restrictions and interactions

- Reasoning models (OpenAI o-series; GPT-5 family with reasoning on) reject or ignore temperature, top\_p, penalties and logprobs (verify per model) and need `max_completion_tokens`.
- Anthropic thinking: no temperature or `top_k` changes; `top_p` only 0.95–1; no forced tool choice; no prefill; `budget_tokens` ≥ 1024 and < `max_tokens` (except interleaved thinking). Some newer models reject temperature and top\_p together.
- Gemini: some models cannot disable thinking; `thinkingLevel` and `thinkingBudget` are mutually exclusive (Gemini 3).
- Penalty semantics differ: OpenAI penalties are additive (−2..2), `repeat_penalty` is multiplicative (\~1.0–1.5) → never map one onto the other silently.
- Determinism: `seed` is best-effort; temperature 0 is not deterministic; server batching adds noise (see OpenAI `system_fingerprint`).
- `n>1` multiplies cost and is widely unsupported.
- Mandatory `max_tokens` (Anthropic): choose a default by policy (open decision). PITFALL: huge defaults count against output-token rate limits and inflate cost bounds.
- PITFALL: locale-formatted numbers ("0,06") must never reach wire JSON, query strings or config parsing.

### 8.4 Presets and profiles

- **PRESET-01** \[S\] Named presets (precise, balanced, creative, code, extraction) plus model-recommended defaults (OpenRouter `default_parameters`; vendor guidance such as Qwen's thinking vs non-thinking settings).
- **PRESET-02** \[S\] Per-model support shown next to each parameter (UI chips of supported parameters).

## 9. Reasoning and thinking

Reasoning needs one control model mapped onto many incompatible dialects, plus strict round-tripping of opaque reasoning data during tool loops.

### 9.1 Unified control

```text
ReasoningConfig {
  mode:         OFF | ON | ADAPTIVE | DEFAULT
  effort:       NONE | MINIMAL | LOW | MEDIUM | HIGH | XHIGH | MAX     // abstract levels
  budgetTokens: Int?                                               // wins where the dialect supports budgets
  visibility:   HIDDEN | SUMMARY(auto | concise | detailed) | FULL
  preserve:     Boolean                                            // round-trip opaque reasoning (default true)
}
```

### 9.2 Dialect mapping (verify per model)

| Target | Request controls | Response representation |
| --- | --- | --- |
| OpenAI Responses / chat | `reasoning.effort` / `reasoning_effort`: none, minimal, low, medium, high, xhigh (model subsets); `reasoning.summary` | Reasoning items with summaries; `encrypted_content` via `include: ["reasoning.encrypted_content"]` when `store=false` |
| Anthropic | `thinking: {type: "enabled", budget_tokens}`; newer models `{type: "adaptive"}` + effort low…max (placement verify); interleaved thinking (beta on Claude 4) | `thinking` blocks (summarized on Claude 4+) with `signature`; `redacted_thinking` blocks |
| Gemini | 2.5: `thinkingBudget` (−1 dynamic, 0 off where allowed), `includeThoughts`; 3: `thinkingLevel` | Parts with `thought: true`; `thoughtSignature` on parts |
| OpenRouter | `reasoning: {effort or max_tokens, exclude, enabled}`, normalized upstream | `reasoning` text + `reasoning_details[]` (summary, encrypted, text + signature) |
| DeepSeek | Reasoner model or thinking toggle (verify) | `reasoning_content` |
| Qwen (DashScope) | `enable_thinking`, `thinking_budget` | `reasoning_content` |
| xAI | `reasoning_effort` on some models; others always reason | Reasoning often hidden or encrypted |
| Ollama | `think`: boolean, or low/medium/high for gpt-oss | `message.thinking` |
| vLLM / SGLang | `chat_template_kwargs: {enable_thinking}`; server reasoning parser | `reasoning_content` or `reasoning` (renamed in newer vLLM; verify) |
| llama.cpp | `--reasoning-format`, `--reasoning-budget`; per-request control (verify) | `reasoning_content` or raw `<think>` tags |
| gpt-oss (raw) | Harmony format | Channels: analysis, commentary, final |

### 9.3 Requirements

- **REAS-01** \[M\] Effort ↔ budget conversion policy (configurable ratios of max output, min/max clamps), recorded in the report.
- **REAS-02** \[M\] Mapping driven by capability metadata; OFF on a mandatory-reasoning model → warning, not silent failure.
- **REAS-03** \[M\] Parse reasoning from every known location: `reasoning_content`, `reasoning`, `reasoning_details`, thinking blocks, thought parts, `<think>` tags in text (configurable tag names, streaming-safe splitter).
- **REAS-04** \[M\] Round-trip opaque reasoning unchanged for same-model continuation, especially inside tool loops: Anthropic thinking + signature; OpenAI reasoning items or encrypted content; Gemini thought signatures (required for function calling on Gemini 3); OpenRouter `reasoning_details`; DeepSeek `reasoning_content` within a tool-calling turn (verify rules).
- **REAS-05** \[M\] Reasoning tokens reported separately where available; visible vs hidden reasoning distinguished.
- **REAS-06** \[S\] Budget sanity check: auto-raise max output or warn when the thinking budget reaches max output.
- PITFALL: reasoning consumes the output budget → empty answer with finish reason LENGTH.
- PITFALL: edited or reordered thinking blocks fail signature checks; switching models invalidates them.
- PITFALL: providers treat reasoning from earlier turns differently (some ignore or strip it) → store it always, let the codec decide what to send.
- PITFALL: summaries are not raw thoughts; UIs should label them as summaries.

## 10. Tools, hosted tools and MCP

The SDK transports tool definitions, calls and results faithfully across dialects; executing tools and deciding loop policy stay with the caller, helped by a thin optional loop helper.

### 10.1 Definitions and schemas

- **TOOL-01** \[M\] `ToolSpec`: name, description, input JSON Schema, strict flag, output schema (MCP), examples (Anthropic `input_examples`; verify), cache hint, deferred loading (tool search), provider options.
- **TOOL-02** \[M\] Schema generation from Java records/POJOs (annotations for descriptions, enums, required fields) plus raw schema input.
- **TOOL-03** \[M\] Per-target schema adaptation with a loss report: OpenAI strict (all properties required, `additionalProperties: false`, keyword subset, optional fields as nullable unions); Gemini (OpenAPI-subset `parameters` vs JSON Schema `parametersJsonSchema`; verify); Anthropic (JSON Schema; strict tools via structured outputs; verify); local chat templates (complex schemas degrade).
- **TOOL-04** \[M\] Name and id constraints: sanitize names (`^[a-zA-Z0-9_-]{1,64}$` for OpenAI and Anthropic; Gemini also allows `.` and `:`); remap call ids (Mistral requires exactly 9 alphanumeric characters); keep a bidirectional map per conversation.

### 10.2 Tool choice and parallelism

| Canonical | OpenAI | Anthropic | Gemini |
| --- | --- | --- | --- |
| AUTO | `"auto"` | `{type: "auto"}` | `mode: AUTO` |
| NONE | `"none"` | `{type: "none"}` | `mode: NONE` |
| REQUIRED | `"required"` | `{type: "any"}` | `mode: ANY` |
| SPECIFIC(name) | `{type: "function", name}` | `{type: "tool", name}` | `ANY` + `allowedFunctionNames: [name]` |
| ALLOWED(subset) | `{type: "allowed_tools", mode, tools}` | Filter the tool list | `allowedFunctionNames` |
| Parallel off | `parallel_tool_calls: false` | `disable_parallel_tool_use: true` | (verify) |

- PITFALL: Anthropic thinking allows only auto and none; some local servers ignore `tool_choice` entirely.

### 10.3 Tool-loop transport

- **TOOL-05** \[M\] Surface complete calls (raw and parsed args), accept results by call id, and place them correctly:
  - OpenAI chat: assistant message with `tool_calls` → one `tool` message per call.
  - OpenAI Responses: `function_call` → `function_call_output` items (or `previous_response_id` + outputs only).
  - Anthropic: assistant `tool_use` → next user message starting with `tool_result` blocks.
  - Gemini: model `functionCall` parts → user `functionResponse` parts in the same order, with ids when present.
- **TOOL-06** \[S\] Optional `runToolLoop(request, executor, maxSteps, stopWhen)` helper; overridable; parallel execution with ordered results.
- **TOOL-07** \[M\] Result content: text, JSON, images and documents where supported (Anthropic, Responses), otherwise stringified with a note; `isError` mapping.
- PITFALL: every call needs a result before the next turn (OpenAI rejects orphans), and results must directly follow their calls.

### 10.4 Streaming tool calls

- **TOOL-08** \[M\] Assemble argument deltas: OpenAI chat fragments keyed by `index` (id and name only in the first delta); Responses `function_call_arguments.delta` / `.done`; Anthropic `input_json_delta`; Gemini and Ollama mostly send whole calls.
- **TOOL-09** \[S\] Partial-JSON parser for progressive UIs.
- PITFALL: `""` vs `"{}"` for empty arguments; invalid JSON from fine-grained streaming or local models → repair policy plus a surfaced error; missing or duplicate ids from some compatible servers → synthesize ids.

### 10.5 Hosted (server-side) tools

- **TOOL-10** \[S\] Typed configs for common hosted tools:
  - Web search: OpenAI `web_search`, Anthropic web search (max uses, domain filters, user location), Gemini `googleSearch`, xAI web and X search, OpenRouter web plugin or `:online`, Perplexity (built in).
  - Web fetch / URL context: Anthropic web fetch, Gemini `urlContext`.
  - Code execution: OpenAI code interpreter (containers), Anthropic code execution, Gemini `codeExecution`.
  - File search: OpenAI vector stores, Gemini file search stores.
  - Computer use: OpenAI, Anthropic, Gemini.
  - Image generation: Responses `image_generation`.
  - Coding tools: OpenAI shell and `apply_patch`; Anthropic text editor, bash and memory (client-executed, provider-defined schemas).
  - Tool search and deferred loading (Anthropic); remote MCP (OpenAI `mcp` tool with approvals, Anthropic MCP connector).
- **TOOL-11** \[M\] Versioned tool type ids (e.g. `web_search_20250305`) are data, not code constants.
- **TOOL-12** \[M\] Hosted outputs surfaced as typed or opaque parts; citations extracted; per-call fees counted in cost.
- PITFALL: hosted tools may need beta headers, specific models or containers, and can end with `pause_turn` (resend to continue).

### 10.6 Custom and grammar tools

- **TOOL-13** \[S\] OpenAI custom tools (free-text input with optional Lark or regex grammar), used by coding models for patches.
- **TOOL-14** \[C\] Grammar-constrained tool calls on local servers (GBNF).

### 10.7 MCP bridge

- **MCP-01** \[S\] Client transports: stdio (spawn, env, cwd, lifecycle, stderr capture) and Streamable HTTP (`Mcp-Session-Id` and `MCP-Protocol-Version` headers, SSE responses); legacy HTTP+SSE \[C\].
- **MCP-02** \[S\] `tools/list` → ToolSpec (input/output schema, annotations); `tools/call` → ToolResult (text, image, audio, resource links, `structuredContent`, `isError`); `list_changed` notifications → refresh.
- **MCP-03** \[S\] Auth per spec: RFC 9728 discovery, PKCE, dynamic or metadata-document client registration; static tokens.
- **MCP-04** \[C\] Resources, prompts, sampling (routed back through the SDK), elicitation.
- **MCP-05** \[S\] Policy: execute MCP tools locally vs delegate to provider-hosted MCP connectors.
- PITFALL: protocol-version negotiation across dated spec revisions; tool-name collisions across servers (use namespace prefixes); large tool lists bloat context and break caching (use tool search or deferred loading).

### 10.8 Tool calls on open models

- Tool calling depends on chat templates and server parsers (vLLM `--tool-call-parser`, llama.cpp Jinja templates, Ollama templates).
- **TOOL-15** \[C\] Client-side fallback parsers for calls embedded in text: `<tool_call>` JSON (Hermes, Qwen), `[TOOL_CALLS]` (Mistral), Llama JSON, Harmony.
- PITFALL: streaming and parallel tool calls vary by server and template version.

## 11. Structured output

Structured output needs automatic strategy selection per model — native strict schema first, emulation last — with validation and typed parsing on every path.

- **SO-01** \[M\] `ResponseFormat`: `TEXT`, `JSON_OBJECT`, `JSON_SCHEMA(schema, name, strict)`, `GRAMMAR(gbnf, lark or regex)`, `CHOICE(values)`, `TOOL_FORCED(schema)`.
- **SO-02** \[M\] Strategy chain by capability: native strict schema → native JSON mode + validation → forced tool call → prompt-only + validation.
- **SO-03** \[M\] Validate against the schema, deserialize to a Java type, detect refusals (OpenAI `refusal`), apply bounded repair (strip code fences, trailing commas) and optionally retry with the validation error as feedback.
- **SO-04** \[S\] Streaming partial JSON → incremental object snapshots.
- **SO-05** \[M\] Schema generation from Java types, shared with tools (TOOL-02).

| Target | Wire form |
| --- | --- |
| OpenAI chat | `response_format: {type: "json_schema", json_schema: {name, schema, strict}}` or `{type: "json_object"}` |
| OpenAI Responses | `text.format: {type: "json_schema", name, schema, strict}` |
| Anthropic | Structured outputs (beta `output_format`, later `output_config.format`; verify) or a forced tool |
| Gemini | `responseMimeType: "application/json"` + `responseSchema` (OpenAPI subset) or `responseJsonSchema`; `text/x.enum` for choices |
| Ollama | `format: "json"` or a JSON Schema object |
| llama.cpp | `json_schema`, `grammar` (GBNF), `response_format` |
| vLLM / SGLang | `response_format`; `structured_outputs` (json, regex, choice, grammar); legacy `guided_*` fields |
| OpenRouter | `response_format` / `structured_outputs`; `provider.require_parameters` routes only to supporting providers |

- PITFALL: strict-mode schema subsets differ (all fields required, limited keywords, nesting and size limits, object root); unsupported keywords are silently ignored elsewhere.
- PITFALL: Gemini output order follows `propertyOrdering`; first use of a schema adds compilation latency on several providers.
- PITFALL: a LENGTH finish leaves truncated JSON; thinking + JSON interplay varies; some compatible servers ignore `response_format` entirely → always validate.

## 12. Streaming

Every dialect's stream maps to one sealed event type, and one accumulator folds events into the same `ChatResponse` a non-streaming call returns.

### 12.1 Unified events

```text
StreamEvent =
  ResponseStart(id, model, provider)
  TextDelta(itemIndex, text)          | TextDone(itemIndex)
  ReasoningDelta(text | summaryText)  | ReasoningSignature(sig) | ReasoningDone(opaque)
  ToolCallStart(id, name, kind)       | ToolCallArgsDelta(id, fragment) | ToolCallDone(id, argsJson)
  HostedToolEvent(kind, status, payload)      // web search progress, code output ...
  CitationAdded(citation)
  MediaDelta(kind, bytes) | MediaDone(media)  // partial images, audio chunks
  Usage(partial | final)
  Finish(reason, raw)
  Error(error, partialResponse)
  RetryStarted(attempt)                       // consumer must discard partial output
  KeepAlive | Raw(event)                      // optional
```

### 12.2 Requirements

- **STRM-01** \[M\] Sealed event types as above; exhaustive `switch` on Java 21.
- **STRM-02** \[M\] Accumulator: events → final response identical to the non-streaming result.
- **STRM-03** \[M\] Consumption styles: blocking iterator (virtual-thread friendly), `Flow.Publisher` with backpressure, callback listener, `CompletableFuture` of the final response; Reactor, RxJava and Kotlin adapters in optional modules.
- **STRM-04** \[M\] Mid-stream errors surface as `Error` with the partial response and a retryability class; never silently truncated.
- **STRM-05** \[M\] EOF without a terminal event → `Error(INCOMPLETE_STREAM)`.
- **STRM-06** \[S\] Resume where supported (OpenAI background responses: stream from a `starting_after` sequence number).
- **STRM-07** \[M\] Timings: TTFT (first text or reasoning token), inter-token latency, tokens/s, total.

### 12.3 Per-dialect decoding notes

| Dialect | Shape | Gotchas |
| --- | --- | --- |
| OpenAI chat | `choices[i].delta` with `content`, `tool_calls[]`, reasoning fields; `finish_reason`; a usage chunk with empty `choices` when `stream_options.include_usage` is set | Usage absent unless requested; `n>1` interleaves by index; `[DONE]` sentinel |
| OpenAI Responses | Typed events: `response.created`, `output_item.added`, `output_text.delta`, `reasoning_summary_text.delta`, `function_call_arguments.delta`, `completed` / `failed` / `incomplete`, `error`; each with `sequence_number` | Many event types, incl. hosted-tool progress |
| Anthropic | `message_start` → per block: `content_block_start`, deltas (`text_delta`, `input_json_delta`, `thinking_delta`, `signature_delta`, `citations_delta`), `content_block_stop` → `message_delta` (stop reason, cumulative usage) → `message_stop`; `ping` | `error` events mid-stream (e.g. overloaded) after HTTP 200 |
| Gemini | Each chunk is a partial `GenerateContentResponse`: parts with text, thought or functionCall; cumulative `usageMetadata` | Block reasons arrive with empty candidates |
| Ollama native | NDJSON objects with `message.content`, `thinking`, `tool_calls`; final `done: true` with counts and durations (ns) | Not SSE |
| OpenRouter | OpenAI chat format + SSE comment keepalives; `reasoning` / `reasoning_details` deltas; final usage with cost | Mid-stream error chunk with `error` and finish reason `error` |

### 12.4 Pitfalls

- PITFALL: proxies buffer SSE (nginx `proxy_buffering`; send `X-Accel-Buffering: no` when serving streams); HTTP/2 flow-control stalls; gzip buffering.
- PITFALL: usage often arrives only after the finish chunk; tool-call ids and names appear only once, in the first delta.
- PITFALL: retries after partial output duplicate text unless the consumer handles `RetryStarted`.

## 13. Files, attachments and media

Attachments pass through a delivery-strategy resolver that picks inline, URL, upload or text extraction per model capability and size, so callers simply attach files.

### 13.1 Sources

Path, bytes, InputStream (+ length, MIME), http(s) URL (fetched by the provider or by the SDK), provider file id, cloud URIs (`gs://`, `s3://` where supported), YouTube URL (Gemini), data URI, text with a filename.

### 13.2 Delivery strategies

- **FILE-01** \[M\] Strategy per attachment: `INLINE_BASE64`, `URL_REFERENCE`, `UPLOAD_THEN_REFERENCE`, `TEXT_EXTRACT`, `CONVERT`, `REJECT`.
- **FILE-02** \[M\] Auto-selection by model capabilities, provider limits, size thresholds and user policy; the decision goes into the report.
- **FILE-03** \[M\] "Send text files as text": text-like types (txt, md, csv, json, source code) become text parts wrapped in filename delimiters (configurable template).
- **FILE-04** \[S\] Documents: native PDF input (Anthropic document blocks, OpenAI `input_file`, Gemini inline or file) vs pluggable text extraction; OpenRouter file-parser engines (text, OCR, native; verify names).
- **FILE-05** \[C\] Office documents (docx, xlsx, pptx) via pluggable converters.

### 13.3 MIME and preprocessing

- **FILE-06** \[M\] MIME from magic bytes + extension + declared type; per-model allowlists (JPEG, PNG, GIF, WebP; HEIC on Gemini; PDF; wav, mp3, flac, ogg, m4a, webm; mp4, mov, webm video).
- **FILE-07** \[S\] Image preprocessing: EXIF orientation, metadata stripping (privacy), downscaling to the provider's optimal size (Anthropic \~1568 px long edge), format conversion.
- **FILE-08** \[S\] Image token estimates: Anthropic ≈ width × height / 750; OpenAI tile or patch formulas per model family; Gemini per tile or media resolution. Estimates only.

### 13.4 Files API lifecycle

- **FILE-09** \[S\] Unified files client: upload (multipart; Gemini resumable protocol; OpenAI Uploads API for multi-GB parts), list, metadata, download (where allowed), delete, TTL, purpose mapping (OpenAI `user_data`, `batch`, `vision`, `assistants`, `fine-tune`, `evals`).
- **FILE-10** \[S\] Upload cache: content hash → file id per endpoint and account, expiry-aware, with garbage collection.
- PITFALL: file ids are account-scoped and not portable; Gemini files expire after 48 h and need `PROCESSING` → `ACTIVE` polling (video); the Anthropic Files API is beta-gated (verify).

### 13.5 Limits (approximate; keep as data)

| Provider | Request size | Images | PDFs | Files API |
| --- | --- | --- | --- | --- |
| Anthropic | 32 MB (Messages); 256 MB (batch) | ≤ 5 MB each, ≤ 100 per request, ≤ 8000 px (≤ 2000 px when > 20 images) | ≤ 32 MB, \~100 pages with visual analysis | ≤ 500 MB per file |
| OpenAI | \~50 MB image payload, up to \~500 images (verify) | detail low / high / auto | `input_file` (verify limits) | ≤ 512 MB per file; Uploads up to 8 GB |
| Gemini | Inline data 20 MB historically, raised later (verify) | up to \~3,600 per request | up to \~1,000 pages | 2 GB per file, 20 GB per project, 48 h retention |

### 13.6 Fan-out and aggregation (workflow)

- **FAN-01** \[S\] Attach modes: `ALL_IN_ONE`, `ONE_PER_FILE`, `BATCHED(n)`, `PER_DATA_ITEM` (with variables).
- **FAN-02** \[S\] Concurrency-limited execution, per-item retries (attempts), ordered results; aggregates: results\[\], texts\[\], total cost, failures\[\] with error class.
- **FAN-03** \[C\] Route large fan-outs to provider batch APIs for the discount (§17).

### 13.7 Image generation and editing

- **IMG-01** \[S\] Unified request: prompt, n, size or aspect ratio, quality, background (transparent), output format + compression, seed and negative prompt where supported, reference images, mask (edits), moderation level, streamed partial images.
- APIs: OpenAI Images (`/v1/images/generations`, `/edits`); Responses `image_generation` tool (multi-turn edits); Gemini native image output (`responseModalities: ["TEXT", "IMAGE"]`) and Imagen `:predict`; OpenRouter image models via chat (`modalities: ["image", "text"]`, data URLs in the message); xAI image models; async vendors (submit → poll).
- **IMG-02** \[S\] Output: bytes or URL + expiry, revised prompt, usage and cost, safety rejection reason; preserve bytes unchanged (C2PA provenance).
- PITFALL: returned image URLs expire → download immediately.

### 13.8 Audio

- **AUD-01** \[S\] STT: `/v1/audio/transcriptions` and `/translations` (multipart; `json`, `text`, `srt`, `vtt`, `verbose_json`; timestamp granularity; language; prompt); Gemini audio understanding; local Whisper-compatible servers.
- **AUD-02** \[S\] TTS: `/v1/audio/speech` (voice, instructions, formats mp3/opus/aac/flac/wav/pcm, speed; streamed bytes); Gemini TTS via `speechConfig`.
- **AUD-03** \[S\] Audio in chat (`input_audio` parts) and audio out (`modalities: ["text", "audio"]`, voice, format; streamed audio deltas).

### 13.9 Video \[C\]

- Understanding: Gemini (file, URL, YouTube; fps, offsets).
- Generation: async jobs (OpenAI videos API, Google Veo via long-running operations) → Job model; cost per second.

### 13.10 Adjacent operations

- **EMB-01** \[S\] Embeddings: single or batch input, `dimensions` (Matryoshka truncation), float or base64 encoding, task type (Gemini `taskType`, Cohere `input_type`), batch limits, normalization, usage.
- **EMB-02** \[S\] Rerank: query + documents → scores (Cohere, Jina, Voyage, vLLM, llama.cpp formats).
- **EMB-03** \[C\] Moderation (`/v1/moderations`, text and images).
- PITFALL: base64 inflates payloads by \~33% → stream the encoding; SDK-side URL fetching is an SSRF vector; EXIF leaks location; providers may fail to fetch URLs (auth, robots, geo); large inline payloads hurt latency and caching.

## 14. Caching

Prompt caching is the biggest cost lever for agents; model it as hints that map to explicit breakpoints, automatic caching or cache resources per provider, with normalized accounting.

### 14.1 Provider caching matrix (approximate; verify)

| Provider | Mode | Controls | TTL | Usage fields |
| --- | --- | --- | --- | --- |
| Anthropic | Explicit breakpoints (≤ 4) on tools, system, messages | `cache_control: {type: "ephemeral", ttl: "5m" or "1h"}`; minimum \~1024–4096 tokens by model | 5 min (refreshed on hit) or 1 h | `cache_creation_input_tokens` (+ per-TTL split), `cache_read_input_tokens`; `input_tokens` excludes both |
| OpenAI | Automatic prefix caching (≥ 1024 tokens, 128-token steps) | `prompt_cache_key` (routing affinity), `prompt_cache_retention` (extended; verify) | Minutes; extended up to 24 h | `cached_tokens` in prompt/input details (included in the input total) |
| Gemini | Implicit (2.5+) and explicit `cachedContents` | `cachedContent` name; created with a TTL; storage billed per hour | Implicit: managed; explicit: user TTL | `cachedContentTokenCount` (included in the prompt total) |
| DeepSeek | Automatic disk cache | None | Hours (best effort) | `prompt_cache_hit_tokens`, `prompt_cache_miss_tokens` |
| xAI | Automatic | Conversation-id header for affinity (verify) | — | `cached_tokens` |
| OpenRouter | Passes `cache_control` upstream; sticky provider routing for hits | As upstream | As upstream | `cached_tokens`; cache discount in cost (verify) |
| Qwen (DashScope) | Implicit, plus explicit on some models (verify) | `cache_control` | — | `cached_tokens` |
| llama.cpp | KV cache per slot | `cache_prompt`, `id_slot`, slot save/restore | Until evicted | Timings |
| vLLM / SGLang | Automatic prefix caching | Server flags | Until evicted | `cached_tokens` (optional) |
| Ollama | Model stays loaded; prefix reuse | `keep_alive` (default 5 min) | — | `prompt_eval_count` |

### 14.2 Unified cache API

- **CACHE-01** \[M\] `CacheHint.ephemeral(ttl)` on system, tools, messages and parts → breakpoints where caching is explicit, no-op where automatic; decisions go into the report.
- **CACHE-02** \[M\] `CachePolicy.AUTO`: breakpoints on the tools + system prefix, on stable history, and a rolling breakpoint on the latest turn, within provider maximums and minimum lengths.
- **CACHE-03** \[S\] Session affinity key → `prompt_cache_key`, xAI conversation header, OpenRouter sticky routing, gateway or key-pool stickiness.
- **CACHE-04** \[S\] Explicit cache resources: create, list, update TTL, delete (Gemini); llama.cpp slot save/restore \[C\].
- **CACHE-05** \[M\] Normalized cache usage, hit ratio and savings per response and per session.
- **CACHE-06** \[M\] Deterministic serialization (stable key and tool order, no volatile values in prefixes) plus a prefix-hash "cache-bust detector" that warns when the prefix changes between turns.

### 14.3 Client-side caches

- **CCACHE-01** \[M\] Metadata caches (catalog, endpoint info) with TTL, staleness and disk persistence.
- **CCACHE-02** \[S\] Token-count cache (content hash + tokenizer → count); upload cache (FILE-10).
- **CCACHE-03** \[S\] Response cache for gateways and tests: opt-in exact match on normalized request + route; tenant isolation; skipped when temperature > 0 unless forced; stream replay from recorded events. Semantic cache via SPI \[C\].

### 14.4 Pitfalls

- PITFALL: cache writes cost more than normal input on Anthropic (≈1.25× for 5 min, 2× for 1 h; reads ≈0.1×) → caching one-off prompts wastes money.
- PITFALL: changing tools, tool order, system prompt, images or thinking settings invalidates the downstream cache (Anthropic order: tools → system → messages).
- PITFALL: timestamps or ids in system prompts kill hits; breakpoints below the minimum length are silently ignored.
- PITFALL: load balancing across keys or providers destroys hit rates.
- PITFALL: accounting differs (cached tokens included vs excluded from input) → wrong cost unless normalized.

## 15. Usage, cost, budgets and statistics

Usage is normalized to one definition per field, and cost is computed from dated price snapshots, preferring provider-reported cost when available.

### 15.1 Normalized usage

```text
Usage {
  inputTokens          // total prompt tokens incl. cached (normalized definition)
  inputUncachedTokens
  cacheReadTokens
  cacheWriteTokens     // + byTtl {5m, 1h}
  outputTokens         // all generated tokens incl. reasoning
  reasoningTokens      // subset of output, if known
  audio / image in/out tokens (optional)
  toolUsePromptTokens  // Gemini
  hostedToolCalls { webSearch, codeExec, ... }
  totalTokens, raw
}
```

| Normalized | OpenAI chat | OpenAI Responses | Anthropic | Gemini | Ollama | DeepSeek |
| --- | --- | --- | --- | --- | --- | --- |
| inputTokens | `prompt_tokens` | `input_tokens` | `input_tokens` + cache creation + cache read | `promptTokenCount` (+ `toolUsePromptTokenCount`) | `prompt_eval_count` | `prompt_tokens` |
| cacheRead | `prompt_tokens_details.cached_tokens` | `input_tokens_details.cached_tokens` | `cache_read_input_tokens` | `cachedContentTokenCount` | — | `prompt_cache_hit_tokens` |
| cacheWrite | — | — | `cache_creation_input_tokens` | — | — | — |
| outputTokens | `completion_tokens` | `output_tokens` | `output_tokens` | `candidatesTokenCount` + `thoughtsTokenCount` | `eval_count` | `completion_tokens` |
| reasoningTokens | `completion_tokens_details.reasoning_tokens` | `output_tokens_details.reasoning_tokens` | Not split (verify) | `thoughtsTokenCount` | — | `completion_tokens_details.reasoning_tokens` |

- PITFALL: Gemini `candidatesTokenCount` excludes thoughts (billed as output); Anthropic `input_tokens` excludes cache tokens while OpenAI includes them.
- PITFALL: gateways may report normalized and native token counts differently (OpenRouter generation stats carry native counts; verify).

### 15.2 Cost computation

```text
tier  = contextTier(model, inputTokens)            // a long-context step applies to the whole request
cost  = sum over components: tokens(component) * price(component, tier, serviceTier, batch, region)
      + perRequestFee + hostedToolFees + mediaFees
final = providerReportedCost ?: cost               // keep both for reconciliation
record priceSnapshotId, currency, breakdown, flag ESTIMATED | REPORTED | RECONCILED
```

- **COST-01** \[M\] Cost on every response when prices are known; `UNKNOWN` otherwise, never a silent 0.
- **COST-02** \[M\] Pre-flight estimate and upper bound: estimated input × input price + (max output + thinking budget) × output price.
- **COST-03** \[S\] Reconciliation hooks: OpenRouter `GET /api/v1/generation?id=` (native tokens, cost, latency, provider); OpenAI and Anthropic admin usage and cost reports.
- **COST-04** \[S\] BYOK split: upstream provider cost vs gateway fee (verify field names).
- **COST-05** \[M\] Display formatting (currency-aware rounding) separate from full-precision storage. PITFALL: raw values like "$0.908213" shown next to "$24.18".

### 15.3 Budgets and guards

- **BUD-01** \[S\] Budgets per request, session, endpoint, tenant and period: max cost, tokens, requests; modes: reject pre-flight, stop the stream at a threshold, warn.
- **BUD-02** \[S\] Free-tier awareness: daily free-model request quotas and stricter rate limits on free variants.

### 15.4 Statistics (analytics use case)

- **STAT-01** \[S\] Per endpoint × provider × model × dialect: counts, errors by class, latency p50/p90/p99, TTFT, tokens/s, token and cost totals, cache hit ratio, retries, fallbacks; time buckets; export as JSON, CSV, Micrometer or OTel.
- **STAT-02** \[S\] Catalog analytics: counts by capability, modality and provider; price distributions; snapshot diffs over time.
- **STAT-03** \[C\] Scheduled probe and benchmark jobs, opt-in and cost-capped.

## 16. Reliability: errors, retries, rate limits, routing

Errors map to one sealed taxonomy with a retryability flag; retries, throttling and fallbacks are driven by that class plus provider rate-limit signals.

### 16.1 Error taxonomy

| Class | Retry | Signals |
| --- | --- | --- |
| NetworkError (DNS, connect, TLS, reset) | Yes if not sent, else per idempotency policy | IOException |
| Timeout (connect, headers, idle, total) | Policy | — |
| Authentication | No (one forced token refresh first) | 401 |
| PermissionDenied / RegionBlocked | No | 403 |
| InsufficientCredits / QuotaExhausted | No | 402 (OpenRouter), 429 `insufficient_quota` (OpenAI), quota failures |
| NotFound (model, endpoint, file) | No | 404 |
| InvalidRequest (ContextLengthExceeded, UnsupportedParameter, InvalidSchema, InvalidMedia, ToolSequenceError) | No; optional auto-fix (drop param, clamp) | 400, 422 |
| RequestTooLarge | No; switch strategy (upload) | 413 |
| RateLimited | Yes, honoring retry-after | 429 |
| Overloaded | Yes, with backoff | 529 (Anthropic), 503 |
| ServerError / UpstreamError | Yes, limited | 500, 502, 504 |
| NoProviderAvailable | Maybe (relax routing, fall back) | OpenRouter 503 |
| ContentBlocked / Moderation | No | 400/403, content-filter finish, Gemini block reason |
| StreamError / IncompleteStream | Before first token yes; after, by policy | Mid-stream error events, EOF |
| Cancelled | No | Client |
| Unknown(raw) | Policy | — |

- **ERR-01** \[M\] Error payload: class, HTTP status, provider type/code/message, request id, endpoint and model, retry-after, raw body, partial response, attempt history.
- **ERR-02** \[M\] Per-dialect body parsing: OpenAI `{error: {message, type, param, code}}`; Anthropic `{type: "error", error: {type, message}}`; Gemini `{error: {code, message, status, details[]}}` (RetryInfo, QuotaFailure, ErrorInfo); OpenRouter `{error: {code, message, metadata}}`; Ollama `{error: "..."}`; non-JSON bodies from proxies (HTML, plain text).
- **ERR-03** \[M\] Context-overflow detection across providers (codes + message patterns) → typed error with the limit and requested size when parseable.

### 16.2 Retry policy

```text
for attempt in 1..maxAttempts:
  try: return send()
  catch e:
    if !e.retryable or retryBudgetExhausted or deadlineNear: throw e
    if streaming and firstTokenEmitted and !policy.midStreamRetry: throw e
    delay = e.retryAfter ?: min(cap, base * 2^(attempt-1)) * random(0..1)   // full jitter
    if e is RateLimited and delay > policy.maxWait: fallback() or throw e
    emit RetryStarted(attempt); sleep(delay)
```

- **RETRY-01** \[M\] Defaults: 2–3 retries, base \~0.5–1 s, cap \~30–60 s, full jitter.
- **RETRY-02** \[M\] Honor `Retry-After` (seconds or HTTP-date), `retry-after-ms`, `x-ms-retry-after-ms`, Gemini `RetryInfo.retryDelay`, and reset headers.
- **RETRY-03** \[M\] Global retry budget and per-endpoint concurrency caps (no retry storms).
- PITFALL: retrying generation can double-bill; a retry after partial output duplicates text; `insufficient_quota` looks like a 429 but is permanent.

### 16.3 Rate-limit awareness

- **RL-01** \[M\] Parse and expose rate-limit state per endpoint, key and model (requests and tokens; input/output split on Anthropic).
- **RL-02** \[S\] Client-side limiter: token buckets for RPM and TPM (pre-flight estimate, corrected after the response), concurrency semaphore, priority queue with timeout, adaptive to headers.
- **RL-03** \[S\] Local servers: concurrency = server slots (Ollama parallel setting, llama.cpp `--parallel`) to avoid queue timeouts.
- Header formats: OpenAI `x-ratelimit-*` with duration strings ("6m0s"); Anthropic `anthropic-ratelimit-*` with RFC 3339 resets; Azure `x-ratelimit-*` + `retry-after-ms`; Gemini via error details; IETF `RateLimit` draft.

### 16.4 Circuit breaking and routing

- **CB-01** \[S\] Breaker per endpoint + model (error rate or consecutive failures, half-open probes); a health score feeds routing.
- **ROUTE-01** \[S\] Fallback chains with triggers (error classes, latency SLA, cost cap) and compatibility checks (tools, vision, schema, context, max output) that skip unfit routes.
- **ROUTE-02** \[S\] Strategies: priority, weighted, least-latency, least-cost, least-busy, sticky (cache affinity); per-request override.
- **ROUTE-03** \[S\] Aggregator routing passthrough: OpenRouter `provider{order, only, ignore, allow_fallbacks, require_parameters, data_collection, zdr, quantizations, sort, max_price}` and `models[]` (verify fields); report the provider actually used.
- **ROUTE-04** \[C\] Hedged requests (duplicate after p95 latency, cancel the loser), off by default.
- PITFALL: falling back to another model changes behavior, schema support and reasoning validity → convert history (PORT-02) and report it.

### 16.5 Long-running requests

- **LONG-01** \[S\] Background mode (OpenAI `background: true`, requires `store`): poll, resume the stream, cancel.
- **LONG-02** \[M\] Deadline-aware retries and streaming keepalive for multi-minute generations.

## 17. Batch, async and jobs

All asynchronous work — batch jobs, background responses, long-running operations, media generation — shares one `Job` model with normalized states.

- **JOB-01** \[S\] `Job`: id, kind (`BATCH`, `BACKGROUND_RESPONSE`, `LRO`, `MEDIA`, `FILE_PROCESSING`), status (`QUEUED`, `VALIDATING`, `RUNNING`, `FINALIZING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `EXPIRED`, `CANCELLING`, `CANCELLED`), progress counts, created/expires, results, errors, cost.
- **JOB-02** \[S\] Batch builder from unified `ChatRequest`s; results mapped back by `custom_id` to responses or errors.
- **JOB-03** \[S\] Completion via polling (backoff + jitter) or webhooks (OpenAI webhooks signed per Standard Webhooks; verification helper).
- **JOB-04** \[S\] Google long-running operations (`operations/{name}`) for batch, video and file imports.
- **JOB-05** \[S\] Batch emulation for endpoints without batch APIs: concurrency-limited fan-out.
- **JOB-06** \[S\] Persist job ids so jobs survive process restarts.

| Provider | Submit | Results | Notes |
| --- | --- | --- | --- |
| OpenAI | JSONL lines (`custom_id`, `method`, `url`, `body`) uploaded with purpose `batch` → `POST /v1/batches` | Output and error JSONL files | 24 h window; \~50% discount |
| Anthropic | `POST /v1/messages/batches` with `requests[{custom_id, params}]` | `results_url` JSONL: succeeded, errored, canceled, expired | Up to 100k requests or 256 MB; results kept 29 days |
| Gemini | `batchGenerateContent` (inline or file) | LRO results | \~50% discount |
| Vertex, Bedrock, Mistral \[C\] | GCS/BigQuery, S3, files | Storage outputs | Cloud-specific IAM |

- PITFALL: results come back unordered (match on `custom_id`); per-line errors; jobs expire; batch and cache discounts may or may not stack.

## 18. Server-side state, context and tokens

Server-side conversation state is opt-in per session, because it ties the session to one provider account and has retention implications.

### 18.1 Stateful APIs

- OpenAI Responses: `store` (default true), `previous_response_id`, Conversations API, retrieve/delete, input items.
- Others: xAI stored responses (time-limited; verify), Gemini Interactions API (beta; verify), Mistral Conversations.
- **STATE-01** \[S\] Opt-in chaining per session, never mixed with sending full history; cleanup helpers (delete responses, conversations, files, caches).
- PITFALL: zero-data-retention organizations cannot use `store`; chains expire; stored data raises privacy questions → policy option to default `store=false`.

### 18.2 Compaction and context editing

- Provider features: OpenAI compaction endpoint and items (verify), Anthropic context management (clearing tool uses and thinking) and compaction (verify), OpenRouter `middle-out` transform.
- **CTX-01** \[S\] `ContextOverflowStrategy` SPI: `FAIL`, `TRUNCATE_OLDEST`, `MIDDLE_OUT`, `PROVIDER_COMPACTION`, `CUSTOM(summarizer)`; the transport supplies token math and usage-percentage signals.

### 18.3 Token counting

- **TOK-01** \[M\] `countTokens(request)` returning a value plus `EXACT` or `ESTIMATED`: remote (Anthropic `/v1/messages/count_tokens`, Gemini `:countTokens`, OpenAI input-token endpoint (verify), llama.cpp and vLLM `/tokenize`); local (jtokkit for OpenAI encodings; HF tokenizers optional); heuristic (\~3.5–4 characters per token plus a margin).
- **TOK-02** \[M\] Include overheads: system prompt, tool schemas, images, message framing.
- **TOK-03** \[S\] Budget helper: remaining = window − input − reserved output − thinking budget; optional auto-clamp of max output.
- PITFALL: tokenizers differ per model family; counting endpoints are rate-limited; hosts of open models use each model's own tokenizer.

## 19. Realtime and bidirectional \[C, separate module\]

- Targets: OpenAI Realtime (WebSocket, WebRTC with ephemeral client secrets, SIP), Gemini Live (WebSocket), others (verify).
- **RT-01** \[C\] Session config (voice, modalities, turn detection / VAD, tools, instructions, transcription), audio append/commit, interruption and truncation, function calls, usage events, ephemeral token minting for browser clients.
- PITFALL: event schemas differ per vendor; audio formats are strict (e.g. PCM16 at fixed sample rates); reconnection needs session-resumption handling.

## 20. Gateway and proxy-building support

Gateways need the same codecs in reverse: accept any dialect, forward losslessly when source and target match, translate when they differ, and meter everything.

- **GW-01** \[M\] Server-side codecs: parse inbound OpenAI chat, Responses, Anthropic, Gemini and Ollama requests into native DTOs and the unified model; encode responses and streams back byte-compatibly (event names, `[DONE]`, keepalives).
- **GW-02** \[M\] Fidelity levels: `PASSTHROUGH` (same dialect, minimal mutation, unknown fields and opaque blobs preserved), `MAPPED` (via the unified model), `LOSSY` (with warnings in logs and headers).
- **GW-03** \[M\] Streaming transcoder: upstream events → unified → downstream dialect, incremental and backpressured; keepalive injection; force upstream usage reporting for metering and strip it if the client did not ask.
- **GW-04** \[M\] Async interceptor pipeline: `onInbound`, `onRoute`, `onUpstreamRequest`, `onUpstreamResponse`, `onStreamEvent`, `onDownstream`, `onError`. Uses: auth swap, PII redaction, prompt compression (tool-result pruning, dedupe, whitespace, summarizer hook), cache-breakpoint injection, parameter policy, guardrails, analytics, metering.
- **GW-05** \[S\] Virtual keys and tenancy: inbound key → tenant → allowed routes, budgets, rate limits, upstream credentials (BYOK).
- **GW-06** \[S\] Model alias and routing tables (inbound model name → route or fallback chain); synthesized `/v1/models`.
- **GW-07** \[S\] Traffic capture: JSONL logs of requests, responses and events with sampling, redaction and size caps; replay tool; HAR export \[C\].
- **GW-08** \[M\] Header policy: strip hop-by-hop headers (`Connection`, `Keep-Alive`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, `Proxy-*`); never forward client auth upstream by default; propagate request ids and `traceparent`.
- **GW-09** \[M\] A client disconnect cancels the upstream call; timeouts are aligned; body, decompression and JSON limits apply.
- **GW-10** \[S\] Compatibility shims: Anthropic-dialect clients (e.g. Claude Code) on any backend; Responses-dialect clients (e.g. Codex) on chat backends; gaps documented.
- **GW-11** \[S\] Server-agnostic core with adapters (Netty, Vert.x, Jetty/servlet, Spring WebFlux).
- PITFALL: JSON re-serialization changes key order and number formatting, which breaks upstream cache prefixes → preserve order or forward raw bytes.
- PITFALL: mutating bodies requires handling `Content-Encoding` and recomputing `Content-Length`; HTTP/2 ↔ HTTP/1.1 translation; SSE comment lines must pass through.
- PITFALL: tool-call id and name maps must stay consistent across a whole translated conversation.
- PITFALL: gateway-side fetching of user-supplied URLs is an SSRF vector; use a mature HTTP server to avoid request smuggling.

## 21. Observability and diagnostics

Every call must be explainable after the fact: what was sent, what was changed, which route served it, and what it cost.

- **OBS-01** \[M\] SLF4J logging (no binding): one summary line per call (endpoint, model, status, latency, tokens, cost, request id); opt-in wire logging with redaction and truncation of base64 and large bodies.
- **OBS-02** \[S\] Export any request as curl or HTTPie (redacted by default).
- **OBS-03** \[S\] Micrometer metrics (optional module): `llm.requests` (endpoint, provider, model, dialect, operation, outcome, error class), `llm.latency`, `llm.ttft`, `llm.tokens` (input, output, reasoning, cache\_read, cache\_write), `llm.cost`, `llm.retries`, `llm.fallbacks`, `llm.inflight`, `llm.ratelimit.remaining`, `llm.cache.hit_ratio`, `llm.stream.stalls`. PITFALL: never tag with user ids or prompts (cardinality, PII).
- **OBS-04** \[S\] OpenTelemetry tracing per GenAI semantic conventions (`gen_ai.operation.name`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.request.max_tokens`, `gen_ai.request.temperature`, `gen_ai.response.finish_reasons`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.response.id`); content capture opt-in; W3C `traceparent` propagation. PITFALL: the conventions still evolve (e.g. `gen_ai.system` became `gen_ai.provider.name`).
- **OBS-05** \[M\] Listener API: `onRequestStart`, `onRequestSent`, `onResponseHeaders`, `onFirstToken`, `onEvent`, `onRetry`, `onFallback`, `onTransformation`, `onComplete`, `onError`.
- **OBS-06** \[M\] Correlation: a generated client request id (sent where supported, e.g. OpenAI `X-Client-Request-Id`; verify) + provider request id (`x-request-id`, Anthropic `request-id`) + gateway generation id + response id, on results, errors and logs.
- **OBS-07** \[S\] Record/replay (VCR-style) of HTTP exchanges, incl. SSE timing, for tests and demos.
- **OBS-08** \[M\] TransformationReport on every response: params renamed, dropped or clamped; content conversions; cache breakpoints; route and fallbacks; auth refreshes; schema losses.

## 22. Configuration layer

Configuration is layered, immutable once resolved, fully usable from code, and explainable field by field.

### 22.1 Example (YAML; JSON and TOML equivalent)

```yaml
version: 1
credentials:
  or-key:     { type: api_key, value: "${env:OPENROUTER_API_KEY}" }
  corp-oauth: { type: oauth2_client_credentials, tokenUrl: "https://idp.corp.example/oauth2/token",
                clientId: llm-app, clientSecret: "${secret:vault:kv/llm#secret}", scopes: [llm.invoke] }
endpoints:
  openrouter:
    preset: openrouter                  # baseUrl, dialects, quirks, catalog source
    credential: or-key
    headers: { HTTP-Referer: "https://myapp.example", X-Title: "MyApp" }
    retry: { maxAttempts: 3 }
    rateLimit: { rpm: 120 }
  local:
    preset: ollama
    baseUrl: "http://127.0.0.1:11434"
    timeouts: { connect: 2s, firstToken: 120s, idle: 60s }
  corp:
    preset: openai-compatible
    baseUrl: "https://llm-gw.corp.example/v1"
    credential: corp-oauth
    tls: { trustStore: /etc/pki/corp.p12 }
    quirks: { maxTokensField: max_tokens, reasoningRequest: reasoning_effort, streamUsage: never }
models:
  fast:  { ref: "openrouter:deepseek/deepseek-v4-flash", params: { temperature: 0.3 } }
  local: { ref: "local:qwen3:8b", params: { num_ctx: 32768 },
           overrides: { contextWindow: 32768, capabilities: { tools: true, vision: false }, price: { input: 0, output: 0 } } }
routes:
  default: { chain: [fast, local], on: [RateLimited, Overloaded, ServerError] }
catalog:  { sources: [live, bundled, models.dev], ttl: 24h }
policies: { unsupportedParams: warn_drop, store: false, logContent: false }
```

### 22.2 Sources and precedence

Built-in defaults < provider presets < user file (`~/.config/<sdk>/config.yaml`) < project file < environment < programmatic builder < per-request overrides.

- **CFG-01** \[M\] Immutable resolved snapshot; `explain()` lists each effective value with its source; a "modified only" view.
- **CFG-02** \[M\] Interpolation: `${env:X}`, `${env:X:-default}`, `${file:path}`, `${secret:<resolver>:<ref>}`; lazy secret resolution, never written back.
- **CFG-03** \[M\] JSON Schema for config files (IDE completion) and path-precise validation errors.
- **CFG-04** \[S\] Hot reload with atomic swap; in-flight calls keep their snapshot.
- **CFG-05** \[M\] Programmatic-only use is first-class (no files needed).
- **CFG-06** \[S\] Serializable endpoint and model profiles (secrets as references) for workflow persistence.

### 22.3 Standard environment variables (read by presets; verify)

- `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_ORG_ID`, `OPENAI_PROJECT_ID`; `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`.
- `GEMINI_API_KEY` / `GOOGLE_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `GOOGLE_GENAI_USE_VERTEXAI`.
- `OPENROUTER_API_KEY`, `XAI_API_KEY`, `DEEPSEEK_API_KEY`, `DASHSCOPE_API_KEY`, `MISTRAL_API_KEY`, `GROQ_API_KEY`, `TOGETHER_API_KEY`, `FIREWORKS_API_KEY`, `CEREBRAS_API_KEY`, `HF_TOKEN`.
- `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT`, `OPENAI_API_VERSION`; `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_REGION`, `AWS_PROFILE`, `AWS_BEARER_TOKEN_BEDROCK`.
- `OLLAMA_HOST`; `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`; `SSL_CERT_FILE`.
- PITFALL: overlapping names (`GOOGLE_API_KEY` vs `GEMINI_API_KEY`) need a documented precedence.

### 22.4 Presets and quirk flags

- **PRESET-03** \[M\] A preset is data: base URLs, dialects and paths, auth scheme, required headers, default betas, catalog sources + field mapping, env var names, quirks, known limits, docs links.
- **PRESET-04** \[S\] Probe-and-learn for unknown servers, persisted as a quirk profile.
- Initial quirk flags for OpenAI-compatible servers:
  - `maxTokensField`: max\_tokens, max\_completion\_tokens or none
  - `systemRole`: system, developer, user-prefixed or unsupported
  - `contentFormat`: string-only or parts; `imageInput`: URL + base64, base64-only or none
  - `streamUsage`: always, on request or never
  - `toolChoiceValues`, `parallelToolCallsParam`, `toolCallIdRule`, `synthesizeToolCallIds`, `toolStreaming` (native, buffered or none)
  - `strictSchema`: supported, ignored or error; `responseFormatTypes`
  - `reasoningRequest`: none, reasoning\_effort, reasoning object, thinking object, enable\_thinking, chat\_template\_kwargs or think
  - `reasoningResponse`: reasoning\_content, reasoning, reasoning\_details, think tags or none; `requiresReasoningEcho`
  - `supportsDeveloperRole`, `supportsNameField`, `supportsN`, `supportsLogprobs`, `supportsSeed`, `maxStopSequences`
  - `assistantContentWithToolCalls`: null, empty string or omitted
  - `cachedTokensPath`, `errorFormat`, `extraBodyDefaults`, `dropParams`

### 22.5 Manual overrides

- **OVR-01** \[M\] Override any ModelInfo field per model profile (context window, max output, output-limit field name, tri-state capabilities, modalities, prices incl. cache, reasoning dialect and levels, tokenizer); provenance = override; "N changed" counts for UIs.

## 23. Security and compliance

Secure defaults ship on: TLS verification, redaction, no content logging, bounded parsing, SSRF guards.

- **SEC-04** \[M\] Defaults: TLS verification on; no prompt or response content in logs; redaction on; bounded parsers; optional `store=false` policy for OpenAI.
- **SEC-05** \[M\] Input hardening: JSON limits (depth, string length, document size via Jackson `StreamReadConstraints`), SSE event size limit, response size limit, decompression limits, no polymorphic default typing.
- **SEC-06** \[M\] SSRF guard for SDK-side URL fetching and redirects: block private, link-local and metadata addresses (e.g. 169.254.169.254) by default; allowlist option; resolve-then-connect pinning against DNS rebinding.
- **SEC-07** \[M\] Path traversal and symlink checks for attachments in server contexts.
- **SEC-08** \[S\] Data governance: provider opt-outs (OpenRouter `data_collection: deny`, ZDR routing), regional routing, retention cleanup, PII redaction hooks, hashed safety identifiers.
- **SEC-09** \[S\] Webhook verification: Standard Webhooks HMAC, timestamp tolerance, replay protection.
- **SEC-10** \[M\] Supply chain: minimal dependencies, SBOM, signed artifacts, reproducible builds; signed catalog data if downloaded at runtime.
- **SEC-11** \[S\] Audit trail: which route, provider and model served each request.
- ToS notes: subscription tokens (§5.6), attribution requirements, per-provider output usage policies.

## 24. Java engineering requirements

Target Java 21 LTS with virtual-thread-friendly blocking APIs plus async and reactive variants, sealed types for exhaustive handling, and a small core with optional modules.

### 24.1 Platform

- **JAVA-01** \[M\] Java 21 baseline (records, sealed types, pattern matching, virtual threads); CI on 21 and 25 LTS; Java 17 only if consumers require it (open decision).
- **JAVA-02** \[M\] JPMS `module-info` per module; classpath use works too.
- **JAVA-03** \[S\] GraalVM native-image friendly (reachability metadata, no hidden reflection).
- **JAVA-04** \[C\] Android via an OkHttp transport (`java.net.http` is unavailable there).

### 24.2 Modules (proposal)

| Module | Contents |
| --- | --- |
| core-api | Types, facade interfaces, SPIs; no third-party dependencies |
| core | Config, retry, rate limiting, routing, catalog merge, pricing, accumulators, reports |
| transport-jdk, transport-okhttp, transport-netty \[C\] | HttpTransport implementations |
| json-jackson | Default JsonCodec |
| dialect-openai, -anthropic, -gemini, -ollama, -llamacpp, -bedrock, -cohere \[C\] | Codecs + native clients |
| presets | OpenRouter, xAI, DeepSeek, Qwen, Mistral, Groq, Together, Azure, Vertex, vLLM, LM Studio and more (data) |
| auth-oauth, auth-aws, auth-gcp, auth-azure, auth-keychain | Optional auth |
| catalog-bundled (versioned separately), catalog-modelsdev, catalog-litellm | Metadata sources |
| mcp-client, tokenizers | Bridges and counters |
| micrometer, otel, reactor / rxjava / kotlin adapters, spring-boot-starter, quarkus \[C\] | Integrations |
| gateway | Server codecs, interceptors, routing; server adapters |
| testing | Mock servers, fixtures, record/replay, conformance suite |

### 24.3 HTTP transport SPI and JDK caveats

- **HTTPSPI-01** \[M\] SPI: send request → status, headers, body stream; streamed request bodies; cancellation; per-request timeouts; proxy and TLS settings; HTTP version.
- JDK `HttpClient` caveats: h2c upgrade attempts on cleartext; no SOCKS; Basic auth for tunnels disabled by default; restricted headers (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`) unless `jdk.httpclient.allowRestrictedHeaders` is set; `timeout()` ends at response headers; reuse one client (each owns selector threads; `close()` since Java 21); always close body streams to release connections.

### 24.4 JSON

- Jackson by default with `StreamReadConstraints`; streaming parser for SSE payloads; tree model for extras; stable write order; `BigDecimal` for money; `Instant` and `Duration` for time; lenient enums mapping unknown values to `UNKNOWN`.

### 24.5 API style

- Immutable records + builders; fluent facade; sealed interfaces for parts, events and errors.
- Sync (`call()`), async (`CompletableFuture`), streaming (`stream()` → AutoCloseable handle that is Iterable and exposes a `Flow.Publisher`).
- Unchecked exception hierarchy mirroring §16.1.
- JSpecify nullness annotations; `Optional` for optional metadata.
- Thread-safe clients; cheap request objects; clients are `AutoCloseable`.
- Canonical units: tokens as `int`/`long`, `Duration`, `BigDecimal` + `Currency`.
- Evolution without breaking changes: builders, typed options, extras maps.

### 24.6 Concurrency

- Virtual-thread-first blocking APIs; no `synchronized` around I/O (pinning before JDK 24); bounded executors for callbacks; backpressure in publishers; structured concurrency optional.

### 24.7 Testing and quality

- **TEST-01** \[M\] Golden JSON fixtures per dialect (encode, decode, stream), incl. unknown fields, mid-stream errors, UTF-8 splits.
- **TEST-02** \[M\] Mock-server integration tests; SSE fuzzing with random chunk boundaries.
- **TEST-03** \[S\] Opt-in live contract tests (cheap models, cost cap) run nightly to detect provider drift.
- **TEST-04** \[S\] User-runnable conformance suite for OpenAI-compatible servers that outputs a quirk profile.
- **TEST-05** \[S\] Translation round-trips (A → unified → A lossless; A → B with a loss report).
- **TEST-06** \[M\] API compatibility checks (japicmp or revapi); SemVer; catalog data versioned separately and refreshable.
- **DOC-01** \[S\] Generated docs: capability matrix, parameter mapping, quirks per preset, error mapping, cookbook (agent loop, workflow fan-out, gateway), security guide.

## 25. Node-flow and UI integration

The workflow screenshots imply a generic property-panel contract: every info and settings object is introspectable, typed, grouped, unit-labeled and marked readout or settable.

- **UI-01** \[M\] Descriptors on EndpointInfo, ModelInfo, provider endpoints, Usage and parameters: key, label, group (Usage, Balance, Catalogue, Providers, Settings, Capabilities), unit (USD, tok, %), format hints, help text, readonly vs settable, source, `fetchedAt`.
- **UI-02** \[M\] Staleness and refresh: relative age ("Fetched: yesterday"), TTL, async `refresh()`, change events.
- **UI-03** \[M\] Parameter descriptors drive controls: per-model support, defaults, limits ("model's limit" placeholder), validation, "N changed" counts and a "modified only" filter.
- **UI-04** \[M\] Capability chips from tri-state capabilities; supported and default parameter lists; provider rows (name, quantization, context, max output, price in/out, uptime) with "show all".
- **UI-05** \[M\] Request-node contract. Inputs: model, system prompt, user prompt, data, attachments, variables, params, response format (text, JSON, schema), attempts. Outputs: text, results, texts\[\], cost (USD), failures\[\].
- **UI-06** \[S\] Attach modes (all files in one request vs one per file) and "send text files as text" as first-class options (FAN-01, FILE-03).
- **UI-07** \[S\] Catalog aggregates for endpoint panels (counts by capability, input modalities seen).
- **UI-08** \[S\] JSON-serializable configs and profiles for workflow save/load, with secrets by reference and stable ids.
- **UI-09** \[S\] Cancellation of running nodes; progress events (streamed text preview, per-item fan-out progress).
- **UI-10** \[M\] Locale-safe numbers: the UI may display "0,06", but wire data and storage never contain it.

## 26. Agent-harness requirements

Coding agents stress three things above all: streaming tool loops with instant cancel, stable prompt-cache prefixes, and lossless session state across model switches.

- **AGT-01** \[M\] Streaming-first tool loops; instant cancel (Esc) with partial output persisted.
- **AGT-02** \[M\] Cache-friendly request construction: stable prefix, AUTO breakpoints, affinity keys (§14).
- **AGT-03** \[M\] Session serialization and resume; model switching mid-session (PORT-01, PORT-02).
- **AGT-04** \[M\] Per-turn meters: context used vs window, session cost, cache hit ratio.
- **AGT-05** \[M\] Reasoning preserved across tool loops (REAS-04); interleaved thinking.
- **AGT-06** \[S\] Native coding tools: OpenAI custom tools, `apply_patch` and shell; Anthropic text editor and bash; automatic Responses dialect for Responses-only models.
- **AGT-07** \[S\] Many providers at once (models.dev-scale catalogs); new providers added through preset data only.
- **AGT-08** \[S\] OAuth and device-flow logins through pluggable modules with host UI callbacks (§5.3; mind §5.6).
- **AGT-09** \[S\] Screenshots and pasted images auto-resized; file mentions turned into attachments.
- **AGT-10** \[S\] MCP bridge (§10.7) and a delegation policy for provider-hosted MCP.
- **AGT-11** \[S\] Compaction hooks and provider compaction (§18.2).
- **AGT-12** \[S\] Parallel tool calls with ordered results, per-call timeouts and tool-output size caps.

## 27. Top pitfalls checklist

These 28 failure modes cause most real-world breakage; each maps to requirements above.

1. "OpenAI-compatible" servers differ → quirk flags, probes, tests.
2. Sending unset parameters → rejections or changed behavior.
3. Reasoning models reject sampling params; thinking eats the output budget → empty answers.
4. Opaque reasoning (signatures, encrypted content, thought signatures, `reasoning_details`) must round-trip unchanged within a model and be stripped across models.
5. Tool names and call ids have different constraints → remap; every call needs a result.
6. Streams fail after HTTP 200, end without terminal events, carry comments, split UTF-8 and omit usage unless asked.
7. Usage semantics differ (cached included vs excluded; Gemini thoughts separate) → wrong cost.
8. Prices: decimals, tiers, cache-write premiums, batch and tier multipliers, route- and time-dependent → snapshots.
9. Unstable prefixes, key rotation and provider hopping destroy cache hits; cache writes cost extra.
10. Ollama's small default context silently truncates, and its OpenAI-compatible endpoint cannot set it per request (verify).
11. Local cold starts exceed timeouts; llama.cpp splits context across slots.
12. JDK HttpClient: h2c upgrade, no SOCKS, tunnel auth, header-only timeout.
13. Retries double-bill; `insufficient_quota` is not a rate limit; retry-after comes in several formats.
14. Long non-streaming calls die on idle timeouts → stream or use background mode.
15. Stateful APIs store data by default; never combine chaining with full history.
16. File ids are not portable; Gemini files expire; generated media URLs expire.
17. Base64 bloat and request size limits → stream encoding, auto-upload.
18. Schema subsets differ (strict rules, keywords, ordering) → adapt, report, validate.
19. Subscription OAuth tokens carry ToS and blocking risk.
20. Secrets in URLs (`?key=`) leak → redact everywhere.
21. Unknown enums, events and blocks crash naive clients → forward compatibility.
22. Response model ≠ requested model → record actual model and provider.
23. `/models` data is sparse → merged sources, provenance, tri-state capabilities.
24. Same model, different providers → different limits, quantization, price and params.
25. Gateway re-serialization breaks cache prefixes and drops unknown fields → passthrough mode.
26. Clock skew breaks token expiry and reset math.
27. Locale-formatted numbers leak into wire data.
28. Provider APIs drift monthly → data-driven presets and nightly contract tests.

## 28. Phasing (proposal)

| Phase | Scope |
| --- | --- |
| P0 (MVP) | JDK transport, JSON, SSE/NDJSON; dialects openai-chat, anthropic-messages, openai-responses (core), gemini (core), ollama native; presets OpenAI, Anthropic, Gemini, OpenRouter, Ollama, llama.cpp, vLLM, LM Studio, DeepSeek, xAI, Qwen, Mistral, Groq, generic compatible; API keys; unified request/response/stream; tools; structured output (native + validation); reasoning mapping; attachments (inline, text, URL); usage normalization; cost from catalog; catalog (live + bundled + overrides); endpoint info (OpenRouter key and credits); retries and rate-limit parsing; error taxonomy; timeouts and cancel; config (builder + YAML + env); redaction; logging; transformation report; parameter registry |
| P1 | OAuth flows + token store; Files APIs; cache hints, AUTO policy, affinity; batch and background jobs; fallbacks, routing, breakers; client rate limiter; token counting; models.dev and LiteLLM sources; Micrometer and OTel; MCP client; image generation, STT/TTS, embeddings; Bedrock/Vertex/Azure auth; gateway codecs + passthrough; record/replay; conformance probes |
| P2 | Realtime, video, Cohere, hedging, semantic cache, compaction integrations, reactive and Kotlin adapters, Spring and Quarkus, native-image polish, isolated subscription-auth modules, benchmark jobs |

## 29. Open design decisions

- [ ] Java baseline: 21 (recommended) vs 17.
- [ ] Jackson as a hard dependency vs a JSON SPI with Jackson as default.
- [ ] Unified model: items-centric (recommended) vs message-centric with extensions.
- [ ] Default unsupported-parameter policy: `WARN_DROP` vs `STRICT`.
- [ ] Default OpenAI `store` value: SDK privacy default vs provider default.
- [ ] Default `max_tokens` where mandatory: model max, fixed value, or policy.
- [ ] Scope of the tool-loop helper: thin helper vs none.
- [ ] Catalog updates: jar releases vs signed runtime download vs both.
- [ ] Canonical streaming style: iterator vs `Flow.Publisher` vs callbacks (all three offered).
- [ ] Errors in streams: exceptions vs `Error` events vs both.
- [ ] Money type: `BigDecimal` + `Currency` vs JSR 354.
- [ ] ModelRef string grammar: first-colon split, escaping, URI form.
- [ ] Gateway server technology (keep the core server-agnostic).

## 30. Glossary

| Term | Meaning |
| --- | --- |
| Dialect | Wire protocol family (openai-chat, anthropic-messages, ...) |
| Endpoint | Concrete base URL + dialect(s) + auth + network settings |
| Preset | Data describing a known provider or server (URLs, auth, quirks, catalog) |
| Quirk | Known deviation of an endpoint from its dialect |
| ModelRef / ModelProfile | Model address / address + defaults + overrides |
| Capability (tri-state) | Supported, unsupported or unknown feature, with its source |
| Hosted tool | Tool executed by the provider (web search, code execution) |
| Breakpoint | Explicit cache boundary in a prompt (Anthropic) |
| TTFT / TPS | Time to first token / output tokens per second |
| BYOK | Bring your own key: a provider key used through a gateway |
| ZDR | Zero data retention |
| PKCE / device flow | OAuth flows for native apps / input-constrained clients |
| ADC / SigV4 | Google Application Default Credentials / AWS request signing |
| LRO | Long-running operation, polled until done |
| SSE / NDJSON | Server-sent events / newline-delimited JSON |
| Prefill | Partial assistant message the model continues |
| Compaction | Server- or client-side summarization of context |
| Thought signature / encrypted reasoning | Opaque reasoning data that must be returned unchanged |
| Fan-out | One logical request expanded into many calls (per file, per item) |
| Fallback chain | Ordered alternative routes tried on failure |
| Virtual key | Gateway-issued key mapped to tenant policies and upstream credentials |

## 31. Quick reference (approximate; verify before use)

### 31.1 Base URLs and auth

| Endpoint | Base URL | Auth |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | Bearer |
| Anthropic | `https://api.anthropic.com/v1` | `x-api-key` + `anthropic-version` |
| Gemini API | `https://generativelanguage.googleapis.com/v1beta` (OpenAI-compatible: `/v1beta/openai/`) | `x-goog-api-key` |
| Vertex AI | `https://{location}-aiplatform.googleapis.com/v1/projects/{p}/locations/{l}/publishers/{pub}/models/{m}:{method}` | OAuth2 Bearer |
| Azure OpenAI | `https://{resource}.openai.azure.com/openai/v1/` (legacy: deployments + `api-version`) | `api-key` or Entra Bearer |
| AWS Bedrock | `https://bedrock-runtime.{region}.amazonaws.com/model/{id}/converse` (and `/converse-stream`) | SigV4 or Bearer API key |
| OpenRouter | `https://openrouter.ai/api/v1` (`/models`, `/models/{a}/{s}/endpoints`, `/key`, `/credits`, `/generation`, `/providers`, `/auth/keys`, `/keys`) | Bearer |
| xAI | `https://api.x.ai/v1` (`/chat/completions`, `/responses`, model listing with prices) | Bearer |
| DeepSeek | `https://api.deepseek.com` (OpenAI), `/anthropic`, `/beta` (FIM, prefix), `/user/balance` | Bearer |
| Qwen DashScope | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (intl); `https://dashscope.aliyuncs.com/compatible-mode/v1` (cn) | Bearer |
| Mistral | `https://api.mistral.ai/v1` | Bearer |
| Groq, Together, Fireworks, Cerebras | `https://api.groq.com/openai/v1`; `https://api.together.xyz/v1`; `https://api.fireworks.ai/inference/v1`; `https://api.cerebras.ai/v1` | Bearer |
| HF router, Perplexity | `https://router.huggingface.co/v1`; `https://api.perplexity.ai` | Bearer |
| Local | Ollama `http://localhost:11434` (`/api/*`, `/v1/*`); llama.cpp `:8080`; LM Studio `:1234/v1`; vLLM `:8000/v1`; SGLang `:30000/v1` | None or optional Bearer |

### 31.2 Rate-limit and request-id headers

| Provider | Rate-limit signals | Request id |
| --- | --- | --- |
| OpenAI | `x-ratelimit-{limit,remaining,reset}-{requests,tokens}` (reset as a duration) | `x-request-id` |
| Anthropic | `anthropic-ratelimit-{requests,tokens,input-tokens,output-tokens}-{limit,remaining,reset}` (RFC 3339), `retry-after` | `request-id` |
| Azure OpenAI | `x-ratelimit-remaining-{requests,tokens}`, `retry-after-ms`, `x-ms-retry-after-ms` | `apim-request-id` (verify) |
| Gemini | 429 `RESOURCE_EXHAUSTED` + `RetryInfo.retryDelay` in error details | Not standardized (verify) |
| OpenRouter | 429 and 402; free-model quotas via key info | Response `id` (generation id) |
| Generic | `Retry-After`; IETF `RateLimit` and `RateLimit-Policy` | — |

### 31.3 Useful metadata endpoints

| Need | Endpoint |
| --- | --- |
| Model catalog with prices and params | OpenRouter `GET /api/v1/models`; xAI model listing; models.dev `api.json` |
| Providers serving a model | OpenRouter `GET /api/v1/models/{author}/{slug}/endpoints` |
| Key usage, limits, expiry | OpenRouter `GET /api/v1/key` |
| Credits and balance | OpenRouter `GET /api/v1/credits`; DeepSeek `GET /user/balance` |
| Per-generation cost and native tokens | OpenRouter `GET /api/v1/generation?id=` |
| Model limits (Google) | Gemini `GET /v1beta/models` (`inputTokenLimit`, `outputTokenLimit`, `thinking`) |
| Local model details | Ollama `/api/show`, `/api/ps`; llama.cpp `/props`, `/slots`; LM Studio `/api/v0/models`; vLLM `/v1/models` |
| Token counts | Anthropic `/v1/messages/count_tokens`; Gemini `:countTokens`; llama.cpp and vLLM `/tokenize` |
