# Universal Java LLM Transport Layer & Connection SDK
## Functional & Technical Baseline Requirements Specification

**Document Status:** Baseline / Draft Standard  
**Target Language:** Java 17+ (Loom Virtual Threads / Reactive Streams compatible)  
**Primary Artifact:** `transport_requirements_and_summary_info.md`

---

## Executive Summary & Design Vision

The goal of this project is to build a unified, high-performance, modular Java transport and connection SDK that bridges applications (coding agents, node-flow workflow engines, proxy gateways, cost-tracking tools) with remote public LLM APIs, cloud gateways, and local inference runtimes.

The SDK operates strictly at the **Transport and Communication Layer (OSI Layer 5–7 abstraction)**, decoupling lower-level protocol nuances, provider quirks, authentication schemes, and pay-load schemas from higher-level application logic.

```
+-------------------------------------------------------------------------------+
|                      Application Layer / Agent Harness                        |
|  (Coding Agents [Codex, OpenClaw, Pi], Flow Engines, Proxy/Gateways, UI)      |
+-------------------------------------------------------------------------------+
                                       |
                                       v
+-------------------------------------------------------------------------------+
|                 SDK Abstraction Layer / Facade API                            |
|    (Unified Prompt/Response Model, Capability Schema, Parameter Mapping)     |
+-------------------------------------------------------------------------------+
                                       |
                                       v
+-------------------------------------------------------------------------------+
|                       Universal LLM Transport Layer                           |
|  [ Auth Engine ]  [ Connection Pool ]  [ Stream Parser ]  [ Retry/Circuit ]   |
+-------------------------------------------------------------------------------+
           |                   |                   |                   |
           v                   v                   v                   v
+-------------------+ +-----------------+ +-----------------+ +-----------------+
| Public Cloud APIs | | Gateways/Router | | Local Runtimes  | | Custom Gateways |
| (OpenAI, Claude,  | | (OpenRouter,    | | (Ollama,        | | (LiteLLM,       |
|  Gemini, Grok)    | |  Portkey)       | |  llama.cpp)     | |  Internal)      |
+-------------------+ +-----------------+ +-----------------+ +-----------------+
```

---

## 1. Core Architectural Requirements & SDK Principles

### 1.1 Non-Blocking & Modern Threading Models
* **Dual Execution Modes:**
  * **Asynchronous / Reactive:** Support Non-blocking I/O via `CompletableFuture<T>` and Reactive Streams (`Publisher<T>` / Flow API).
  * **Virtual Threads (Java 21+ Project Loom):** Lightweight blocking-style API that scales seamlessly without thread-pool exhaustion.
* **Zero-Copy / Memory Efficient Parsing:** Streaming SSE responses must be parsed on-the-fly without buffer bloating, crucial for large contexts (128k–2M+ tokens) and image payloads.

### 1.2 Pluggable Engine Architecture
* Abstract network backends so users can switch transport engines based on environment needs:
  * Default: `java.net.http.HttpClient` (Java 11+, zero external dependencies).
  * Optional: OkHttp, Netty, or Apache HTTP Client 5.
* Custom middleware pipeline (Interceptor Pattern) for intercepting Requests/Responses (useful for logging, proxying, prompt transformation, header injection).

---

## 2. Authentication & Credential Management Matrix

The SDK must handle complex auth flows dynamically, across multi-tenant or multi-provider setups.

| Auth Mechanism | Target Providers / Services | Technical Requirements & Considerations |
| :--- | :--- | :--- |
| **API Keys (Header)** | OpenAI, Anthropic, OpenRouter, Grok, DeepSeek, Qwen | Support standard formats: `Authorization: Bearer <key>`, `x-api-key: <key>`. |
| **OAuth 2.0 (PKCE / Client Credentials)** | Enterprise Gateways, Vertex AI, Custom Proxies | Support automatic access token refresh, token caching, PKCE for desktop/agent authorization, expiry detection. |
| **AWS SigV4** | Amazon Bedrock | Support signing requests with Access Key ID, Secret Key, Session Token, Region, and Service (`bedrock-runtime`). |
| **GCP Service Account (JWT)** | Google Cloud Vertex AI | IAM Service Account key parsing (JSON), OAuth2 scope generation, automated token renewal. |
| **Dynamic Key Rotation** | Multi-account gateways, High-throughput clients | Key-pool provider interface (`KeyProvider`) allowing round-robin, failover, or rate-limit-aware key selection. |
| **Secret Masking** | All | Zero accidental exposure of credentials in logs, stack traces, standard output, or OpenTelemetry spans. |

---

## 3. Network Protocol & Transport Layer Capabilities

### 3.1 Supported Protocols
* **HTTP/1.1 & HTTP/2:** Full multiplexing support over HTTP/2 for concurrent tool calls and fast SSE transport.
* **HTTP/3 (QUIC):** Optional support where supported by endpoints.
* **Server-Sent Events (SSE):** Robust parsing of `data: [DONE]` and event streams with auto-reconnection and event id tracking.
* **WebSockets / gRPC:** Required for low-latency audio/video bidirectional streaming (e.g., Gemini Multimodal Live API).

### 3.2 Resilience & Reliability
* **Connection Pooling:** Configurable per-host connection limits, keep-alive durations, and idle connection harvesting.
* **Timeouts Management:** Fine-grained timeout configurations:
  * `connectTimeout`
  * `readTimeout` / `socketTimeout`
  * `writeTimeout`
  * `timeToFirstByteTimeout` (TTFB)
  * `streamIdleTimeout` (for long pauses during reasoning phases).
* **Smart Retry Mechanism:**
  * Exponential backoff with full jitter.
  * Retry-After header parsing (`Retry-After: 30`, `x-ratelimit-reset-requests`).
  * Non-retryable error short-circuiting (e.g., 401 Unauthorized, 400 Invalid Schema).
* **Circuit Breaker:** Transient outage detection preventing cascade failures in agent loops or workflow networks.
* **Proxy Support:** SOCKS4, SOCKS5, HTTP/HTTPS proxies, System proxy auto-detection, and Custom CA / SSL certificate pinning (mTLS support).

---

## 4. Unified Provider Ecosystem Matrix

The SDK must establish direct or adapter-based connections across three primary tiers:

### 4.1 Public Cloud Providers & APIs
* **OpenAI:** Chat Completions API, Assistants API v2, and the modern **Responses API** (`/v1/responses`).
* **Anthropic:** Messages API (`/v1/messages`), Batch API.
* **Google Gemini:** REST & gRPC endpoints (`generateContent`, `streamGenerateContent`).
* **xAI (Grok):** OpenAI-compatible spec with reasoning tokens.
* **DeepSeek:** Native Reasoning API (`reasoning_content`) and standard Chat API.
* **Alibaba Cloud Qwen (DashScope):** OpenAI-compatible mode & Native DashScope API.

### 4.2 Gateway & Aggregator Ecosystem
* **OpenRouter:** Dynamic models endpoint, routing preferences, fallback providers, provider-specific data policy headers (`HTTP-Referer`, `X-Title`).
* **LiteLLM / Portkey / Cloudflare AI Gateway:** Custom headers (`x-portkey-api-key`, dynamic routing keys, caching controls).

### 4.3 Local & Edge Runtimes
* **Ollama:** Native `/api/generate`, `/api/chat`, and `/api/tags` support.
* **llama.cpp (llama-server):** `/completion`, `/v1/chat/completions`, native slot allocation management, GBNF grammar constraints.
* **vLLM / Text Generation Inference (TGI):** High-throughput OpenAI-compatible endpoints with continuous batching metadata.

---

## 5. Dynamic Metadata, Model Catalog & Capability Discovery

To support UI node-flow displays and intelligent agent routing, the SDK must query and normalize metadata across providers.

```
                       Normalized Model Metadata Object
+-------------------------------------------------------------------------------+
| Model ID: "claude-3-7-sonnet-20250219"                                         |
| Provider: "Anthropic"                                                          |
| Context Limits: { Max Context: 200000, Max Output: 64000 }                     |
| Modalities: [ TEXT, IMAGE, PDF ]                                              |
| Capabilities: { Reasoning: TRUE, Structured Output: TRUE, Tool Calling: TRUE } |
| Pricing: { Input: $3.00/1M, Output: $15.00/1M, CacheRead: $0.30/1M }           |
+-------------------------------------------------------------------------------+
```

### 5.1 Metadata Taxonomy To Normalize
1. **Model Identifiers:** Canonical ID, Provider Name, Friendly Display Name, Model Version, Release/Deprecation Date.
2. **Context Window Limits:** Total Context Size, Max Completion/Output Tokens, Max Thinking/Reasoning Tokens.
3. **Capabilities Matrix:**
   * Text Generation / Chat
   * Vision / Image Input (Resolution capabilities, detail mode: `low`, `high`, `auto`)
   * Audio Input / Output
   * Video Input
   * Structured Outputs (Strict JSON Schema support level)
   * Tool Calling (Single, Parallel, Computer Use)
   * Prompt Caching Support (Explicit vs Implicit)
   * System Instructions support
4. **Reasoning Profile:**
   * Supports Reasoning/Thinking toggles (`effort`: `low`, `medium`, `high` or token budget).
   * Exposes raw thought logs vs hidden thought logs.
5. **Pricing Models (Normalized to per 1,000,000 tokens):**
   * Prompt Tokens Price
   * Completion Tokens Price
   * Cached Prompt Read Price
   * Cached Prompt Write Price
   * Reasoning Tokens Price
6. **Rate Limits & Live Header Extraction:**
   * Remaining Requests (`x-ratelimit-remaining-requests`)
   * Remaining Tokens (`x-ratelimit-remaining-tokens`)
   * Reset Duration (`x-ratelimit-reset-tokens`)

---

## 6. Core Inference Payload & Parameter Architecture

The SDK must provide a unified fluent Request/Response builder that normalizes differences across underlying APIs.

### 6.1 Generation Parameters Standard

```java
// Conceptual Usage Target for SDK Facade
LLMRequest request = LLMRequest.builder()
    .model("gpt-4o")
    .systemPrompt("You are an expert Java architect.")
    .addMessage(ChatMessage.user("Explain virtual threads"))
    .temperature(0.7)
    .topP(0.9)
    .minP(0.05)
    .frequencyPenalty(0.0)
    .presencePenalty(0.0)
    .repetitionPenalty(1.1)
    .maxTokens(4096)
    .reasoningEffort(ReasoningEffort.MEDIUM)
    .stopSequences(List.of("```\n"))
    .seed(42)
    .build();
```

### 6.2 Fine-Grained Parameter Matrix
* **Temperature:** `[0.0 - 2.0]` scale (auto-mapped for Anthropic/Gemini scales).
* **Sampling Parameters:** `top_p`, `top_k`, `min_p` (essential for local models/OpenRouter), `typical_p`.
* **Penalties:** `frequency_penalty`, `presence_penalty`, `repetition_penalty`.
* **Output Restrictions:** `max_tokens` / `max_completion_tokens`, `stop` sequences (string or array).
* **Determinism:** `seed` parameter (OpenAI, llama.cpp).
* **Logit Bias:** Map of token IDs to bias weights `[-100, 100]`.

### 6.3 Reasoning & Thought Process Handling
The SDK must handle varying vendor implementations of "Reasoning/Thinking":
* **OpenAI (o1, o3-mini):** `reasoning_effort` (`low`, `medium`, `high`) mapping, parsing `completion_tokens_details.reasoning_tokens`.
* **Anthropic (Claude 3.7 Sonnet):** Explicit thinking budget (`thinking: { type: "enabled", budget_tokens: 2048 }`), parsing stream blocks of type `thinking_delta`.
* **DeepSeek (R1):** Extraction of reasoning output via dedicated API parameters (`reasoning_content`) OR fallback extraction from `<think>...</think>` tags in raw text streams.
* **Unified Output Event:** Abstracted event `ReasoningChunkEvent` delivered prior to standard text generation chunks during streaming.

---

## 7. Multimodal, File & Media Attachments

The transport layer must abstract media ingestion, encoding, and vendor-specific wire formats.

### 7.1 Media Modalities & Abstraction

```
               Unified Media Ingestion Abstraction
+-----------------------------------------------------------------+
| Input Types: Java File, InputStream, byte[], URI / URL           |
+-----------------------------------------------------------------+
                                |
                                v
+-----------------------------------------------------------------+
| Auto-Detection: MIME Type, Compression, Base64 Encoding        |
+-----------------------------------------------------------------+
       |                        |                        |
       v                        v                        v
+--------------+         +--------------+         +--------------+
| Image (PNG,  |         | Audio (MP3,  |         | Documents    |
|  JPEG, WEBP) |         |  WAV, OGG)   |         | (PDF, TXT)   |
+--------------+         +--------------+         +--------------+
```

### 7.2 Media Ingestion Mechanics
1. **Images:**
   * Support inline Base64 payload delivery or remote URL reference.
   * Fine-grained detail mode settings: `low`, `high`, `auto`.
2. **Audio:**
   * Text-to-Speech (TTS) & Speech-to-Text (STT) request formats.
   * Native multi-modal audio input (e.g. Gemini 1.5, OpenAI Realtime/Audio).
3. **Documents & PDFs:**
   * Native Anthropic PDF attachment API (`document` block with base64/pdf type).
   * OpenAI Vector Store / File Search attachment references.
4. **Automatic Content Type Detection:** Built-in MIME-type resolver for files and stream inputs.

---

## 8. Structured Outputs, Tool Calling & Agentic Capabilities

This layer is critical for building autonomous AI coding harnesses (like OpenCode, OpenClaw, Codex, Pi) and workflow nodes.

### 8.1 Tool / Function Calling Framework
* **Unified Tool Specification:** Accepts Java Records / Classes, JSON Schema, or OpenAPI specifications and converts them to vendor-specific tool formats.
* **Tool Choice Control:** `auto`, `required`, `none`, or forcing a specific function by name.
* **Parallel Tool Calling:** Stream parsing must seamlessly collect parallel tool invocation chunks (e.g., standardizing index-based tool call chunks across OpenAI, Anthropic, and Grok).
* **Computer Use / OS Agent Extensions:** Support specialized tool specs (e.g., Anthropic Computer Use API parameters: `computer_20241022`, `bash_20241022`, `str_replace_editor_20241022`).

### 8.2 Response Format & Structured Outputs
* **Strict JSON Schema Enforcement:** Convert JSON Schemas into OpenAI `json_schema` strict mode, Anthropic tool-enforced JSON, or llama.cpp GBNF grammars.
* **JSON Mode:** Simple `type: "json_object"` forcing across supporting providers.
* **Regex & Grammar Constraints:** Direct passing of BNF/GBNF grammars to local runtimes (llama.cpp, Ollama).

---

## 9. Prompt Caching & Cost Optimization

Prompt caching drastically reduces latency and cost for large coding agent contexts.

### 9.1 Cache Control Mechanics
* **Explicit Cache Marking (Anthropic style):** Allow attaching explicit `cache_control` markers (`{"type": "ephemeral"}`) at specific points in message lists, system prompts, or tool declarations.
* **Implicit Cache Monitoring (OpenAI/Gemini style):** Track cache utilization headers (`cached_tokens` in usage response objects).
* **Unified Usage Statistics Object:**
  ```java
  public record TokenUsage(
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long reasoningTokens,
      long cachedPromptTokens,
      long cacheWriteTokens
  ) {}
  ```

---

## 10. Gateway, Proxy Readiness & Observability Pipeline

The SDK must be designed to serve both as a **client** and as a **building block for custom LLM Proxies/Gateways**.

```
                        SDK Middleware Pipeline Architecture
+-----------------------------------------------------------------------------------+
|  Outgoing Request -> [ Logging ] -> [ Auth Inject ] -> [ Compression ] -> Wire   |
+-----------------------------------------------------------------------------------+
|  Incoming Response <- [ OpenTelemetry ] <- [ Decompress ] <- [ Rate Limit ] <- Wire|
+-----------------------------------------------------------------------------------+
```

### 10.1 Telemetry & OpenTelemetry (OTel)
* **Tracing Standards:** Native OpenTelemetry spans adhering to `gen_ai.*` semantic conventions:
  * `gen_ai.system` (e.g., "openai", "anthropic")
  * `gen_ai.request.model`
  * `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`
  * `gen_ai.response.finish_reasons`
* **Metrics:** Export metrics for Request Latency, TTFB (Time to First Byte), Active Stream Counts, Error Rates, and Cost calculations.

### 10.2 Wire Traffic Analysis & Manipulation
* **Raw Wire Logging:** Configurable logging with built-in PII/API key sanitization.
* **Compression:** Request/Response header negotiation (`gzip`, `brotli`, `deflate`).
* **Header Interceptors:** Ability to inject custom tracing headers (`traceparent`, `x-request-id`, custom router metadata).
* **Payload Transformation Pipeline:** Low-level access to manipulate JSON trees before bytes hit the wire (crucial for proxy building).

---

## 11. Error Handling, Unified Exceptions & Robustness Matrix

The SDK must transform varied standard and non-standard HTTP status codes and JSON error responses into a clean, hierarchical Exception tree.

```
                         SDK Unified Exception Hierarchy
                               +-------------------+
                               |  LLMException     |
                               +-------------------+
                                         |
         +-------------------------------+-------------------------------+
         |                               |                               |
+-------------------+         +-------------------+         +-------------------+
| LLMTransportExcept|         | LLMApiException   |         | LLMValidationExcept|
+-------------------+         +-------------------+         +-------------------+
         |                               |
   +-----+-----+                   +-----+-----+
   |           |                   |           |
Timeout    Connection         RateLimit   Authentication
Except       Except             Except        Except
```

### 11.1 Exception Taxonomy
1. `LLMTransportException`: Base class for network failure, DNS resolution failure, SSL handshake errors, socket timeouts.
2. `LLMAuthenticationException` (401, 403): Invalid API keys, expired OAuth tokens, insufficient permissions.
3. `LLMRateLimitException` (429): Rate limits reached; exposes `retryAfterDuration` and limit stats.
4. `LLMContextWindowExceededException` (400/413): Input prompt exceeds max context length.
5. `LLMProviderUnavailableException` (500, 502, 503, 504): Server-side outages or overload.
6. `LLMInvalidRequestException` (400): Malformed JSON, unsupported parameters, invalid schemas.
7. `LLMStreamException`: Unexpected client/server disconnect during active SSE stream.

---

## 12. Provider Nuances, Quirks & Potential Pitfalls

Building a universal transport layer requires overcoming dozens of provider-specific inconsistencies.

| Area | Inconsistency / Pitfall | SDK Resolution / Handling Strategy |
| :--- | :--- | :--- |
| **System Messages** | OpenAI uses `system` role in array; Anthropic uses a top-level `"system"` field; Gemini uses `systemInstruction`. | Transport normalizer automatically lifts/shifts system messages into the target provider's preferred structure. |
| **Tool Calls Output Format** | OpenAI returns tool calls in `message.tool_calls`; Anthropic returns tool calls as message content blocks of type `tool_use`. | Abstract content into unified `ContentBlock` structures containing standard text or tool calls. |
| **Reasoning Tokens** | DeepSeek outputs reasoning in `<think>` text tags or `reasoning_content`; OpenAI uses token counts without text; Anthropic uses `thinking` content blocks. | Standardize via dedicated `ReasoningChunkEvent` during streaming and unified usage statistics upon completion. |
| **Max Tokens Parameter** | OpenAI uses `max_tokens` or `max_completion_tokens`; Anthropic requires `max_tokens`; Gemini uses `maxOutputTokens`. | Map unified `maxTokens` parameter dynamically based on target model specs. |
| **SSE Data Formats** | OpenAI streams `data: [DONE]`; Ollama streams raw JSON objects without `data:` prefix; Anthropic uses typed SSE event lines (`event: content_block_delta`). | Pluggable stream parser interface with specialized SSE decoders per provider type. |
| **Strict JSON Schemas** | OpenAI requires schema under `response_format.json_schema`; Local runtimes require GBNF grammars or native schema params. | Include dynamic schema-to-grammar transformer utility. |

---

## 13. Summary Implementation Checklist for SDK Facade Design

When designing the API Facade and Configuration Layer based on this baseline document, ensure the following checklist is completed:

- [ ] **Core Client Facade:** Implement `LLMClient` with synchronous, async (`CompletableFuture`), and reactive streaming (`Publisher`) capabilities.
- [ ] **Unified Provider Registry:** Dynamic provider discovery and capabilities lookup.
- [ ] **Configuration Model:** Clean Builder pattern allowing global default overrides (timeouts, auth keys, headers) per individual call.
- [ ] **Streaming Engine:** Low-memory SSE parser with automatic reconnect and event deduplication.
- [ ] **Tooling Engine:** Jackson-backed JSON schema generator from Java POJOs/Records.
- [ ] **Observability Engine:** Out-of-the-box OpenTelemetry tracing and token cost monitoring interceptors.
- [ ] **Proxy Middleware Toolkit:** Request/Response mutation hooks to facilitate proxy and gateway construction.

---
*End of Specifications Document (`transport_requirements_and_summary_info.md`).*