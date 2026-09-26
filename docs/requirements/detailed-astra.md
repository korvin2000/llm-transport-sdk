---
title: "Universal Java LLM Transport SDK — Requirements and Reference Baseline"
document_id: "llm-transport-requirements"
version: "1.0"
status: "Design baseline; not an implemented API or a provider conformance certification"
language: "en"
research_date: "2026-09-26"
primary_audience: ["SDK/API designers", "Java developers", "LLM-assisted implementation agents"]
---

# Universal Java LLM Transport SDK
## Requirements, supported-feature taxonomy, implementation constraints, and pitfalls

**Purpose.** Define the capabilities and behavioral contracts needed to design a compact, user-friendly Java library for communicating with local inference servers, direct model providers, cloud deployments, and LLM gateways. The same foundation should serve ordinary applications, node-based workflows, coding-agent harnesses, multimodal applications, and custom proxies. This is a requirements baseline, not a prescribed class hierarchy, a finished facade, or a requirement to ship every extension in the first release.

**Central design position:** provide a small portable interface over a **capability-aware, native-preserving model**, rather than disguising every service as Chat Completions. Hide transport mechanics; do not hide unsupported features, information loss, side effects, costs, or uncertainty.

**Evidence boundary.** Provider-specific observations below were checked against official documentation on **26 September 2026**. No authenticated provider requests, billing checks, or live compatibility tests were performed. Documentation describes intended behavior, not proof that every account, gateway route, deployment, or server build behaves identically. Prices, model rosters, limits, defaults, API status, and entitlements must remain refreshable data. Requirement statements are proposed design decisions; source-linked provider observations are external facts.

## Contents

1. [Scope, priorities, and non-negotiable principles](#1-scope-priorities-and-non-negotiable-principles)
2. [Terminology and identity boundaries](#2-terminology-and-identity-boundaries)
3. [Provider and protocol coverage](#3-provider-and-protocol-coverage)
4. [Facade and responsibility boundaries](#4-facade-and-responsibility-boundaries)
5. [Configuration and endpoint resolution](#5-configuration-and-endpoint-resolution)
6. [Authentication, authorization, and credentials](#6-authentication-authorization-and-credentials)
7. [Connections, HTTP, and transport infrastructure](#7-connections-http-and-transport-infrastructure)
8. [Discovery, metadata, capabilities, and provenance](#8-discovery-metadata-capabilities-and-provenance)
9. [Capability negotiation and request planning](#9-capability-negotiation-and-request-planning)
10. [Canonical requests, content, and native fidelity](#10-canonical-requests-content-and-native-fidelity)
11. [Generation parameters and reasoning controls](#11-generation-parameters-and-reasoning-controls)
12. [Invocation lifecycle and streaming](#12-invocation-lifecycle-and-streaming)
13. [Structured output, schemas, and validation](#13-structured-output-schemas-and-validation)
14. [Tools, hosted capabilities, and MCP](#14-tools-hosted-capabilities-and-mcp)
15. [Files, attachments, and multimodal content](#15-files-attachments-and-multimodal-content)
16. [Conversations, reasoning continuity, and compaction](#16-conversations-reasoning-continuity-and-compaction)
17. [Caching and request optimization](#17-caching-and-request-optimization)
18. [Background jobs, batches, webhooks, and realtime](#18-background-jobs-batches-webhooks-and-realtime)
19. [Usage, prices, limits, quotas, and accounting](#19-usage-prices-limits-quotas-and-accounting)
20. [Errors, retries, idempotency, routing, and resilience](#20-errors-retries-idempotency-routing-and-resilience)
21. [Security, privacy, and tenant isolation](#21-security-privacy-and-tenant-isolation)
22. [Observability and diagnostics](#22-observability-and-diagnostics)
23. [Proxy and gateway construction](#23-proxy-and-gateway-construction)
24. [Local inference and specialized operations](#24-local-inference-and-specialized-operations)
25. [Java API quality and implementation constraints](#25-java-api-quality-and-implementation-constraints)
26. [Node-based workflow and UI requirements](#26-node-based-workflow-and-ui-requirements)
27. [Standards and wire-format registry](#27-standards-and-wire-format-registry)
28. [Conformance, testing, and acceptance criteria](#28-conformance-testing-and-acceptance-criteria)
29. [Delivery slices, open decisions, and design checklist](#29-delivery-slices-open-decisions-and-design-checklist)
30. [Illustrative machine-readable contracts](#30-illustrative-machine-readable-contracts)
31. [High-impact pitfalls and request traceability](#31-high-impact-pitfalls-and-request-traceability)
32. [Official sources and refresh policy](#32-official-sources-and-refresh-policy)

## 1. Scope, priorities, and non-negotiable principles

### 1.1 Requirement notation

Each normative requirement has a stable identifier and a scope tag:

| Tag | Meaning | Obligation |
|---|---|---|
| **C — Core** | Cross-cutting behavior of the SDK | Required for a trustworthy baseline, unless explicitly marked SHOULD. |
| **P — Profile** | Behavior of an advertised provider, protocol, or operation profile | MUST hold when that profile/feature is implemented. Does not claim universal upstream availability. |
| **X — Extension** | Optional service, integration, or advanced feature | Optional to ship; its stated contracts become mandatory when enabled. |

**MUST** identifies a correctness, security, or advertised-functionality requirement. **SHOULD** identifies a strong default that may be changed with an explicit rationale. **MAY** identifies an optional capability. Unnumbered explanatory text, tables, examples, and formulas elaborate requirements; they do not silently make every listed feature mandatory in the first release.

Implementation priority is separate from scope: **P0** protects fundamental semantics and security; **P1** delivers broad provider usability; **P2** extends specialized coverage. A feature can be optional to ship but P0-correctness-critical once shipped, such as webhook verification.

### 1.2 Intended use cases

| Use case | What the SDK should make easy |
|---|---|
| Ordinary application | Configure credentials, select a model, send content, obtain typed output or a stream. |
| Coding-agent harness | Preserve tool/reasoning continuity; cancel, resume where supported, meter costs, and manage context explicitly. |
| Node workflow | Expose endpoint/model descriptors, parameter forms, attachment plans, execution events, and statistics without hidden execution. |
| LLM proxy/gateway | Decode and encode supported protocols, route under policy, inspect or transform authorized traffic, preserve native semantics. |
| Local inference | Connect without cloud assumptions; discover actual runtime limits and supported options. |
| Multimodal application | Use files, image/audio/video input and generation without reducing everything to strings. |
| Enterprise service | Use workload identity, scoped credentials, private endpoints, audit-safe telemetry, quotas, and tenant isolation. |

### 1.3 Core principles

- **CORE-001 [C, MUST] — Honest universality.** Express a portable subset plus discoverable optional/native capabilities. Return explicit unsupported, unknown, or lossy-conversion results instead of pretending that all models share one feature set.
- **CORE-002 [C, MUST] — No silent semantic changes.** Never silently remove tools, schemas, reasoning state, attachments, safety constraints, cache instructions, or explicitly requested generation parameters. Coercion, emulation, repair, fallback, and truncation require an explicit policy and a visible report.
- **CORE-003 [C, MUST] — Native fidelity.** Preserve provider identifiers, opaque continuation material, native errors, unknown fields, and documented extension data alongside useful normalized views. Semantic normalization must not destroy replay-critical information.
- **CORE-004 [C, MUST] — No surprising work.** Configuration, descriptor inspection, and request construction must not automatically generate tokens, execute tools, download models, upload files, or contact new destinations. Network discovery and billable probes must be distinguishable operations.
- **CORE-005 [C, MUST] — Explicit uncertainty.** Missing metadata is not false, zero, free, unlimited, or unsupported. A provider accepting a field is not proof that the model applied it.
- **CORE-006 [C, MUST] — Controlled side effects.** Make network calls, uploads, storage, hosted-tool use, retries, transformations, and delegated authorization observable and policy-controlled.
- **CORE-007 [C, SHOULD] — Small default footprint.** Keep one-call use straightforward, with optional integrations and uncommon features outside the minimal dependency path. A large requirements envelope must not produce an equally large mandatory framework.
- **CORE-008 [C, MUST] — Provider-neutral, not agent-specific.** Do not make an agent loop, planner, memory store, UI framework, vector database, prompt language, or tool executor a prerequisite for a model call.

### 1.4 Explicit non-goals for the transport core

The core is not responsible for training models, deciding an agent's goals, executing arbitrary generated code, automatically summarizing conversations, running retrieval pipelines, rendering a workflow UI, enforcing application business authorization, or implementing a production HTTP gateway server. It should expose the data and extension points those systems need. Managed-agent APIs, vector stores, fine-tuning, evaluation platforms, and deployment orchestration may be separate integrations; they must not distort the main inference API.

## 2. Terminology and identity boundaries

### 2.1 Distinctions the public model must preserve

| Concept | Meaning | Do not confuse with |
|---|---|---|
| Model publisher/family | Organization or family associated with model weights/capabilities | The service executing a request. |
| Provider/service | A service exposing inference or management APIs | A model name, account, or HTTP dialect. |
| Gateway | An intermediary that may route, transform, bill, or forward requests | The actual upstream provider. |
| Endpoint | Configured network destination plus service/path context | An authenticated session or a specific model. |
| Protocol family | Responses, Messages, Interactions, GenerateContent, Chat Completions, etc. | A guarantee of complete feature equivalence. |
| Compatibility profile | A tested subset and deviations for a service/version | “OpenAI-compatible” as a universal boolean. |
| Account scope | Tenant, organization, project, workspace, region, or subscription relevant to authorization | A credential string. |
| Model reference | Requested model ID, alias, version, local tag, or deployment reference | The model/revision actually selected. |
| Deployment | Provider-specific serving resource/configuration | A globally unique model ID. |
| Route | Gateway selection and, when known, actual serving provider/deployment | The public gateway hostname alone. |
| Credential source | Mechanism that supplies or refreshes credentials | The current access token or a persisted API key. |
| Invocation/run | One logical operation requested by an application | One network attempt. |
| Attempt | A particular dispatch, retry, or fallback | An independently authorized new task. |
| Response/output item | Provider result and its ordered constituent items | One plain assistant string. |
| Conversation/session | Client transcript or provider-owned stateful resource | An HTTP connection or a KV cache. |
| Artifact | File, image, audio, video, or other generated/uploaded resource | A permanently readable public URL. |
| Tool call | A request to use a tool, with arguments and correlation identity | Evidence that the tool has executed. |

- **ID-001 [C, MUST] — Composite identity.** Scope model, resource, usage, and cache identities by the relevant endpoint, protocol, account, region, and deployment. Preserve original IDs verbatim; avoid globally identifying everything with `modelName`.
- **ID-002 [C, MUST] — Requested versus resolved versus observed.** Record requested model/route/settings, locally resolved settings, and upstream-reported actual settings/model/provider separately. Never label an unreported value as observed.
- **ID-003 [C, MUST] — Separate principals.** Distinguish application tenant, provider account, credential owner, end user, delegated tool principal, and gateway billing identity. One must not be inferred from another.
- **ID-004 [C, SHOULD] — Durable correlation.** Provide SDK run/attempt IDs while preserving provider request, response, item, tool-call, batch, and session IDs. Treat each ID namespace as distinct.

## 3. Provider and protocol coverage

### 3.1 Initial coverage map

This is a target coverage map, not a claim that every capability is available in every cell. Each profile needs its own operations, authentication rules, version policy, discovery behavior, and conformance tests.

| Service/runtime | Protocols and operation families to accommodate | Important profile boundary |
|---|---|---|
| OpenAI | Native Responses; Chat Completions compatibility/continuity; files, models, embeddings, images/audio; background/batch; Realtime and Responses WebSocket extensions | Responses and Chat Completions are different contracts. OpenAI recommends Responses for new projects while continuing to support Chat Completions. [OA-RESP] |
| Anthropic / Claude | Native Messages, streaming, token counting, models, files, message batches; provider-native tool/thinking/context features | Version/beta headers, workspace scope, content-block lifecycle, and replay rules belong to this profile. [AN-API] |
| Google Gemini | Native **Interactions** and **GenerateContent/StreamGenerateContent**; models, files, caches, batch, image/audio/video, Live API; OpenAI-compatible profile where needed | Current docs describe Interactions as generally available and recommended for new projects; GenerateContent remains supported and still has capabilities absent from Interactions. [GO-INTERACTIONS] |
| xAI / Grok | Responses, documented Chat Completions compatibility, model metadata, tools, files, image/audio/video operations, documented realtime/WebSocket extensions | Do not infer feature support from a shared OpenAI-shaped schema. [XA-TEXT] [XA-MODELS] |
| DeepSeek | Documented OpenAI-format, Responses-format, and Anthropic-format entry points; native reasoning and tool-continuation behavior | Mode-dependent parameters and reasoning replay differ from generic chat assumptions. [DS-THINK] |
| Alibaba Model Studio / Qwen | DashScope native operations where selected; documented OpenAI-compatible operations; regional multimodal services | Qwen is a model family, not one deployment. Endpoint region, key scope, native options, and local serving are separate concerns. [QW-COMPAT] |
| OpenRouter | Chat Completions and Responses profiles; models/provider endpoints; routing, pricing, usage, credentials | Its documented Responses endpoint is stateless: stored conversations and non-null `previous_response_id` are not supported there. [OR-RESP] |
| Ollama | Native chat/generate/embed and model management; documented compatibility endpoints | Native streaming is NDJSON. The documented Responses compatibility is stateless. Local access and cloud-backed execution have different auth/egress implications. [OL-STREAM] [OL-COMPAT] [OL-AUTH] |
| llama.cpp server | Native completion/tokenization/runtime inspection; compatible endpoints actually supported by the server build | Capabilities depend on build, model, template, command-line configuration, and effective context allocation. [LC-SERVER] |
| Azure-hosted OpenAI models | Azure deployment profile over supported APIs, including Responses; Entra/API-key authentication | Deployment identity and resource/region availability differ from direct OpenAI. Current v1 examples do not justify globally requiring an `api-version` query parameter. [AZ-RESP] |
| AWS Bedrock | Converse/ConverseStream and model-native invocation profiles; model/control-plane discovery | AWS event-stream framing is distinct from SSE. Support documented IAM signing and supported bearer-key modes as separate auth profiles. [AWS-STREAM] [AWS-SIG] [AWS-KEYS] |
| Google Cloud deployments | Cloud project/location/deployment profiles over supported Gemini operations | Application Default Credentials and cloud authorization are not interchangeable with Developer API key configuration. [GO-ADC] [GO-CLOUD] |
| vLLM, SGLang, custom servers/gateways | Explicit compatibility profiles, native extensions, configurable discovery | A custom base URL alone cannot describe supported tools, templates, sampling, state, or management operations. [VL-SERVER] [SG-API] |
| Other providers | Additional profiles, for example Mistral, Cohere, Groq, Together, Fireworks, or private enterprise services | Future integration targets only; this document does not certify their current operations. |

### 3.2 Coverage requirements

- **PRO-001 [C, MUST] — Explicit protocol selection.** A provider profile must declare its API families; users must be able to choose a family when several are available. Automatic selection must be explainable and constrained by required capabilities.
- **PRO-002 [P, MUST] — Compatibility manifest.** Publish supported operations, unsupported fields, deviations, known ignored parameters, version/build constraints, and tested fixtures. “Compatible” must never imply every upstream feature exists.
- **PRO-003 [P, MUST] — Version ownership.** Keep API version, preview/beta flags, model revision, gateway compatibility version, and SDK adapter version separate. Pin explicitly where possible; report mutable aliases and unversioned runtime assumptions.
- **PRO-004 [P, SHOULD] — Native-first access.** Provide native operation access when it offers material functionality absent from a compatibility endpoint. Do not force a native model through Chat Completions merely because it is easier to normalize.
- **PRO-005 [C, MUST] — Extensible registration.** Allow additional profiles without modifying the common request model for every provider. Provider-native extensions must be namespaced and discoverable, not arbitrary undocumented global fields.

## 4. Facade and responsibility boundaries

### 4.1 Logical services, not a mandatory class hierarchy

The following are responsibility boundaries. They may be packages, small interfaces, or cohesive implementations; avoid one abstraction or module per noun.

| Boundary | Responsibilities |
|---|---|
| Configuration/identity | Endpoint definitions, profile selection, scoped settings, secret references. |
| Credentials | Credential acquisition, renewal, challenge handling, redaction. |
| Discovery/control plane | Model catalogs, capabilities, quotas, prices, resource metadata, optional management. |
| Invocation/data plane | Request preparation, dispatch, streaming, cancellation, typed results. |
| Protocol codecs | Provider-native encoding/decoding, normalized projections, extension preservation. |
| Transport | HTTP/WebSocket mechanics, pooling, TLS, proxying, byte streams. |
| Policy | Validation, budgets, routing, retries, network/resource access, transformations. |
| Observability | Structured events, usage, timings, sanitized diagnostics. |
| Optional integrations | Cloud identity SDKs, OpenTelemetry, schema validators, server adapters, media helpers. |

- **API-001 [C, MUST] — Progressive disclosure.** Offer a straightforward path for configure → select model → call/stream, while allowing advanced users to inspect descriptors, set native options, and obtain full result/event data.
- **API-002 [C, MUST] — No discovery prerequisite.** A caller with a known endpoint/model must be able to invoke without a successful model-list or pricing request. Validate what is known and represent remaining capability uncertainty.
- **API-003 [C, MUST] — Separate read and write surfaces.** Read-only metadata inspection must not double as mutation of credentials, deployment settings, models, caches, or files. Management operations require explicit calls and authorization.
- **API-004 [C, MUST] — One source of execution truth.** Streaming events, final results, usage, and diagnostics for one invocation must derive from one execution record. A convenience result must not dispatch a second request to reconstruct the first.
- **API-005 [C, SHOULD] — Inspectable preparation.** Provide a non-dispatching validation/preparation operation that explains protocol selection, known constraints, transformations, estimated work, unresolved facts, and likely side effects.
- **API-006 [C, MUST] — Safe escape hatch.** Native request/response access must retain credential, timeout, cancellation, tenant, size-limit, and logging protections. An escape hatch must not become an unrestricted networking bypass.
- **API-007 [C, SHOULD] — Reusable codecs.** Keep encoding/decoding sufficiently independent of HTTP dispatch to support testing and gateway integrations without forcing a server framework into ordinary clients.

## 5. Configuration and endpoint resolution

### 5.1 Configuration categories

| Category | Representative values |
|---|---|
| Service identity | Provider profile, protocol, base URI, endpoint label, account/project/workspace, region, deployment. |
| Transport | TLS/trust settings, proxy, HTTP version preference, timeout budgets, connection/executor ownership, maximum payloads. |
| Authentication | Credential-source reference, approved auth mode, OAuth client/scopes/audience, identity/token source. |
| Model/request defaults | Model reference, supported generation defaults, output mode, reasoning, permitted hosted tools. |
| Execution policy | Retry/fallback limits, concurrency, deadlines, budgets, capability strictness, transformation policy. |
| Data policy | Storage preference, routing/residency restrictions, attachment access, logging, cache behavior. |
| Provider extensions | Version headers, documented extra fields, provider-specific routing or service tier. |

### 5.2 Resolution requirements

- **CFG-001 [C, MUST] — Explicit precedence.** Define and expose a deterministic precedence order. Suggested: library policy defaults → endpoint/profile configuration → named model/request preset → per-call overrides. Environment/system/file loading should be an explicit configuration-source layer, not a hidden override of supplied Java values.
- **CFG-002 [C, MUST] — Preserve absence.** Distinguish **unset/inherit**, **explicit null where meaningful**, and **explicit value**. `false`, `0`, an empty collection, and an empty string must not accidentally become “use default.” Reject meaningless nulls rather than interpreting them inconsistently.
- **CFG-003 [C, MUST] — Policy is not a normal override.** Per-call settings may narrow an administrator's allowed destinations, spend, or storage policy but must not silently weaken it. Define conflict resolution separately from ordinary value precedence.
- **CFG-004 [C, MUST] — No invented defaults.** Omit parameters whose provider default should apply; report unknown defaults as unknown. Do not globally inject temperature, token limits, reasoning settings, or `store` values that change provider semantics without documented SDK policy.
- **CFG-005 [C, MUST] — Endpoint-safe URI handling.** Resolve paths and query parameters structurally, preserving custom path prefixes and encoded identifiers. Prevent duplicate `/v1`, accidental path stripping, query-secret leakage, and user-controlled host replacement through relative-reference tricks.
- **CFG-006 [C, MUST] — Immutable execution snapshot.** Freeze resolved configuration for each invocation/attempt. Hot reload and credential rotation must not mutate in-flight semantics; subsequent attempts must record any permitted configuration changes.
- **CFG-007 [C, MUST] — Configuration diagnostics.** Return errors with a field path, reason, scope, and corrective action. Detect conflicting canonical/native fields and mutually exclusive settings before dispatch when possible.
- **CFG-008 [C, SHOULD] — Portable serialization.** Support a versioned, secret-free configuration representation suitable for JSON and optional YAML bindings. Export secret references rather than credentials; preserve extension fields during round trips.
- **CFG-009 [C, MUST] — Scoped headers.** Separate ordinary extension headers from credential, signature, host, content-length, version, and hop-by-hop headers. Define allowlists and precedence; never silently send two conflicting authorization mechanisms.
- **CFG-010 [C, SHOULD] — Explicit lifecycle policies.** Make eager connection checks, lazy discovery, metadata refresh, connection warming, and endpoint failover opt-in and independently configurable.

**Provider observation.** OpenRouter documents that omitted generation settings are omitted upstream rather than automatically replaced with its own values. This illustrates why omission and “explicitly use a default-looking number” are different. [OR-PARAMS]

## 6. Authentication, authorization, and credentials

### 6.1 Authentication is a workflow, not just a header

The credential boundary must support both static secrets and mechanisms that acquire short-lived credentials. It must know the destination, account scope, credential type, expiration, and renewal behavior without exposing secret values to callers or diagnostic systems.

| Mechanism | Required treatment when supported |
|---|---|
| No authentication | Explicit local/private profile; no fake-key requirement in the public API. |
| API key | Provider-selected header or documented query placement; account scope, revocation/expiration metadata if known. |
| Bearer access token | Separate from an API key even when the HTTP header is identical; expiry and acquisition source. |
| OAuth authorization code + PKCE | Browser authorization, transaction binding, code exchange, optional refresh, cancellation. |
| OAuth device authorization | User code/verification URI, polling interval, `authorization_pending`, `slow_down`, expiry, cancellation. |
| OAuth client credentials | Confidential service client, supported client authentication method, audience/scopes. |
| Workload identity/token exchange | External identity token/certificate, provider mapping, audience, short-lived result, re-exchange semantics. |
| Cloud SDK credential chain | Managed identity, service account, workload/instance roles, temporary session credentials; isolated optional integrations. |
| Request signing | Provider-specific signing, timestamp/region/service scope, payload-dependent signature. |
| mTLS / certificate-bound identity | Client key/certificate rotation and separate server trust verification. |
| Ephemeral media/session token | Narrow audience, lifetime, session/model restrictions; never equivalent to an administrative API key. |
| Custom enterprise authentication | Explicit trusted plugin with constrained destination and documented lifecycle. |

### 6.2 Credential handling

- **AUTH-001 [C, MUST] — Credential abstraction.** Represent credential acquisition/refresh separately from request serialization. Support asynchronous acquisition, expiration, scopes/audience, principal identity, redacted display, and acquisition failures.
- **AUTH-002 [C, MUST] — Destination binding.** Bind credentials to approved origin/service and account scope. A changed base URI, redirect, fallback route, upload host, or OAuth-discovered URL must not inherit a credential automatically.
- **AUTH-003 [C, MUST] — Secret-safe storage.** Accept caller-supplied credential providers, environment references, protected files, and optional OS/secret-manager integrations. Do not serialize secrets through configuration exports, `toString()`, exceptions, tracing, or node graphs.
- **AUTH-004 [C, MUST] — Bounded renewal.** Refresh before expiry with clock-skew allowance; coalesce simultaneous refreshes; bound retries; avoid replacing a newer token with an older completion. Persist rotated refresh credentials atomically only when persistence is explicitly enabled.
- **AUTH-005 [C, MUST] — Scope-aware caches.** Cache acquired credentials by all relevant issuer/client/principal/audience/scope/tenant/workspace attributes. Refreshing one tenant must never invalidate or disclose another tenant's credential.
- **AUTH-006 [C, MUST] — Challenge handling without loops.** Distinguish expired credentials, invalid audience, revoked authorization, missing scope, wrong workspace, forbidden model, and billing restrictions. A 401 may permit one controlled renewal; a 403 must not trigger endless refresh or a broader credential search.
- **AUTH-007 [C, MUST] — No implicit credential harvesting.** Do not scrape browser cookies, reuse consumer-product sessions, borrow another application's client ID, or assume a coding-agent login grants general API access. Integrate product-specific authorization only through explicitly documented and permitted flows.
- **AUTH-008 [C, MUST] — No surprise fallback identity.** If an explicitly configured credential source fails, do not silently try a developer's unrelated local cloud account or a different tenant's key. Any credential chain must be explicit and inspectable.

### 6.3 OAuth and delegated authorization

- **AUTH-009 [P, MUST] — Provider-specific OAuth contract.** Record supported grant types, endpoints, client registration, client authentication, scopes, resource/audience, redirect constraints, token response shape, renewal, and revocation behavior. Generic OAuth support does not mean every provider offers every grant.
- **AUTH-010 [P, MUST] — Secure interactive flow.** Prefer authorization code with PKCE S256 for public/native clients. Bind callback to the initiating transaction, validate issuer/redirect context, use an external browser, and avoid embedding a confidential client secret in a desktop application. [STD-OAUTH-BCP] [STD-PKCE] [STD-NATIVE]
- **AUTH-011 [P, MUST] — Noninteractive environment support.** Expose authorization instructions/events to the host application; do not assume a GUI, open a browser without consent, block a request thread indefinitely, or start an externally reachable callback listener by default.
- **AUTH-012 [P, MUST] — Device flow state machine.** Honor the provider's polling cadence and slowdown/denial/expiration responses; stop cleanly on cancellation and never log a live device code as ordinary telemetry. [STD-DEVICE]
- **AUTH-013 [P, MUST] — Correct renewal mechanism.** Distinguish refresh-token renewal from repeating workload token exchange, reacquiring an identity assertion, and issuing a new API key. Model returned credentials according to their real type. [STD-EXCHANGE]
- **AUTH-014 [P, MUST] — Trusted discovery.** Validate configured/discovered issuers and token endpoints; constrain network access and redirects. Decode JWTs for diagnostics only without implying verification; security decisions require appropriate signature/issuer/audience/time validation by the responsible component.
- **AUTH-015 [X, MUST] — Delegation separation.** Provider inference authorization, inbound proxy authorization, remote MCP authorization, and application-user login must have distinct policies and token stores. Never forward a provider token to an unrelated tool server.

### 6.4 Current provider distinctions worth preserving

**OpenRouter:** its documented PKCE flow exchanges an authorization code at `/api/v1/auth/keys` for a user-controlled **API key**, not a conventional access-token/refresh-token pair. The SDK should expose an authorization workflow with a provider-specific credential result. [OR-OAUTH]

**Anthropic:** current documentation supports API keys in `Authorization: Bearer`, short-lived workload-federation credentials, and the legacy `x-api-key` header. Multi-workspace credentials can require explicit workspace selection. These details belong to a versioned auth profile, not a permanent global assumption that Anthropic only uses `x-api-key`. [AN-AUTH]

**OpenAI:** documented workload federation can exchange an approved external identity for a short-lived API token. Its renewal path uses another exchange rather than a refresh token; API-platform and Codex identity mappings are distinct. [OA-WIF]

**Cloud profiles:** support Google ADC and Azure Entra through optional integrations; AWS signing and documented Bedrock bearer keys are separate modes. Do not replace all of them with a generic `apiKey` string. [GO-ADC] [AZ-RESP] [AWS-SIG] [AWS-KEYS]

- **AUTH-016 [P, MUST] — Single-use assertion handling.** Where a provider treats identity assertions or codes as single-use, a retry must acquire fresh input or follow that provider's recovery rule. Do not blindly replay a consumed assertion after an ambiguous exchange result. Anthropic documents replay-sensitive identity-token behavior. [AN-WIF]
- **AUTH-017 [X, SHOULD] — Administrative separation.** API-key creation/revocation, federation configuration, account administration, and billing administration should be optional privileged services, not permissions required for ordinary inference.

### 6.5 Optional enterprise OAuth mechanisms

- **AUTH-018 [X, MUST] — Sender-constrained credentials.** Where a profile supports DPoP or certificate-bound OAuth tokens, retain the key/certificate binding, generate request-specific proofs, and handle nonce challenges under bounded retry rules. Such credentials must not be downgraded to ordinary bearer tokens or moved across incompatible connections. [STD-DPOP] [STD-OAUTH-MTLS]
- **AUTH-019 [P, MUST] — Token-endpoint client authentication.** Implement the configured/discovered method, such as no client authentication for a public client, `client_secret_basic`, or an approved JWT/certificate method. Authorization-server metadata and dynamic registration are optional capabilities, not permission to trust arbitrary discovered services. [STD-AUTH-METADATA]
- **AUTH-020 [X, MUST] — Revocation and introspection.** Expose these operations only where documented and authorized; apply independent scopes, caching, and privacy rules. Local sign-out, remote token revocation, session termination, and deletion of a stored secret must be separate actions. [STD-REVOCATION] [STD-INTROSPECTION]
- **AUTH-021 [C, MUST] — Credential-type correctness.** Never send a refresh token to an inference endpoint, use an identity assertion as an access token without a documented exchange/profile, or treat a successfully decoded JWT as verified authorization. Preserve the actual token type and required presentation method.

## 7. Connections, HTTP, and transport infrastructure

### 7.1 Connection semantics and diagnostics

An endpoint object is a configuration handle. It need not represent a permanently open connection or authenticated server session. Ordinary request/response APIs can reuse HTTP connections while remaining stateless at the application level. Explicit WebSocket or realtime sessions have additional lifecycle semantics.

- **NET-001 [C, MUST] — Layered checks.** Distinguish configuration validation, DNS resolution, TCP/TLS reachability, HTTP service response, credential acceptance, model visibility, and successful inference. “Reachable” must not imply “authorized,” “model usable,” or “free credits available.”
- **NET-002 [C, MUST] — Non-billable default diagnostics.** A connection check should use a documented health or metadata operation where available. Where inference is the only meaningful test, require explicit authorization and label possible cost, storage, and side effects.
- **NET-003 [C, MUST] — Connection reuse and ownership.** Reuse suitable clients/pools; support caller-owned and SDK-owned transports with explicit close behavior. Do not create a client/executor per token, request, model lookup, or tenant unless isolation requires it.
- **NET-004 [C, MUST] — Structured deadlines.** Distinguish connection establishment, credential acquisition, upload, response headers, first meaningful event, read-idle, total invocation, and job-polling deadlines. State which timeouts the selected transport can enforce; never advertise precision it cannot provide.
- **NET-005 [C, MUST] — Cancellation propagation.** Cancel credential waits, queued work, uploads, transport reads, retry delays, and subscriptions. Separately report whether upstream cancellation was requested, acknowledged, unsupported, or unknown; closing a socket is not proof billing stopped.

### 7.2 Transport capabilities

- **NET-006 [C, MUST] — HTTP correctness.** Support HTTPS, configurable HTTP/1.1 or HTTP/2 preference, redirects under policy, status and multi-valued headers, content negotiation, request IDs, and streaming bodies. HTTP/3 may be a transport extension, not a core requirement. [STD-HTTP]
- **NET-007 [C, MUST] — TLS and proxy controls.** Support custom trust material and enterprise proxies without disabling hostname verification. Separate proxy authentication from provider credentials; scope client certificates and connection reuse appropriately.
- **NET-008 [C, SHOULD] — Environment portability.** Document IPv4/IPv6, loopback, private-network, proxy bypass, DNS, and container behavior. Unix-domain sockets or non-HTTP embedded runtimes may be optional transports with explicit support status.
- **NET-009 [C, MUST] — Bounded data processing.** Enforce limits on headers, compressed and decompressed bytes, JSON depth, event size, incomplete event buffers, queued events, uploaded files, and retained response data. A streaming API must not unconditionally buffer the entire body.
- **NET-010 [C, MUST] — Replayable-body contract.** Identify whether a request body can be resent. Streams consumed once must not be silently retried; optional spool-to-disk behavior needs size, permission, retention, and cleanup policies.
- **NET-011 [C, MUST] — Compression semantics.** Support HTTP content encodings only where documented and correctly implemented. Compressed bytes, decompressed bytes, and token counts are different measurements; wire compression does not reduce the model's semantic input tokens.
- **NET-012 [C, MUST] — Protocol-aware streaming.** Treat SSE, NDJSON, ordinary JSON, multipart responses, binary media, AWS event streams, and WebSocket frames as distinct decoders. Do not select SSE simply because the caller requested streaming.
- **NET-013 [C, MUST] — Safe resource release.** Close response bodies on success, error, cancellation, and abandoned consumers. Ensure decoder exceptions cannot leak sockets, buffers, executors, or temporary files.
- **NET-014 [C, SHOULD] — Transport substitution.** Keep the transport boundary small enough to permit a JDK HTTP implementation, specialized cloud transports, and deterministic test transports without reimplementing provider semantics.
- **NET-015 [C, MUST] — Secure transport defaults.** Use TLS for remote credentials and sensitive content. Cleartext local/private connections require an explicitly approved endpoint policy; never silently disable certificate verification or downgrade HTTPS after a TLS failure.

**Implementation reference.** Java's `HttpClient` offers synchronous/asynchronous requests and WebSocket construction. The exact resource and timeout contract still needs to be designed by the SDK rather than assumed from the existence of `sendAsync`. [JAVA-HTTP]

## 8. Discovery, metadata, capabilities, and provenance

### 8.1 Metadata is a sourced, scoped observation

No single discovery response should be presumed to provide a complete current catalog of every price, parameter constraint, quota, tool, and modality. A provider may publish some fields through APIs, others in documentation, and others only through account-specific responses. Current Anthropic and Gemini model APIs expose richer capability/limit information than a minimal `id` list; OpenRouter exposes another, gateway-oriented metadata shape. [AN-MODELS] [GO-MODELS] [OR-MODELS]

- **META-001 [C, MUST] — Per-field provenance.** Each material fact must be attributable to a source, retrieval time, scope, and optional effective/version interval. Keep live provider data, provider documentation, curated catalogs, user overrides, and probe observations distinguishable.
- **META-002 [C, MUST] — Explicit knowledge states.** Distinguish known value, unknown/not published, unsupported, not applicable, inaccessible, stale, and conflicting. A numeric limit may separately be known finite or explicitly unbounded; `null` alone must not encode all states.
- **META-003 [C, MUST] — Refreshable snapshots.** Provide stable immutable snapshots, cache age/expiry, explicit refresh, pagination, refresh errors, and optional stale-while-revalidate. A failed refresh must not overwrite previously known data with fictitious zeros or silently appear fresh.
- **META-004 [C, MUST] — Permission-aware visibility.** Distinguish a public catalog from models visible or usable by the current credential and deployment. A forbidden listing must not be interpreted as “no models exist.”
- **META-005 [C, MUST] — Scoped cache keys.** Key private metadata by credential/account visibility scope without storing raw secrets in keys. Public metadata may be shared only when it truly contains no account-specific facts.
- **META-006 [C, SHOULD] — Pluggable enrichment.** Allow optional curated catalogs and caller overrides. Record conflicts and precedence; a manual override may relax local validation but cannot assert an upstream capability has been verified.
- **META-007 [C, MUST] — Safe probing.** Separate passive discovery from active capability probes. Probes require explicit cost/network/storage budgets, record exact tested conditions, and cannot establish universal support from one successful response.
- **META-008 [C, MUST] — No brittle model-name logic.** Do not infer prices, context limits, modalities, reasoning support, or parameter ranges solely from name substrings. Versioned adapter rules may use explicit model mappings with sources and a fallback to unknown.

### 8.2 Endpoint and account descriptor inventory

| Group | Facts to expose when available |
|---|---|
| Identity | Provider/gateway, canonical endpoint, protocol families, server/build/version, region, environment. |
| Availability | Last check, check type, reachability, authentication result, degraded/loading status, latency observation. |
| Authorization | Redacted credential label/type, principal/account scope, scopes, expiration, model entitlements; never secret material. |
| Catalog | Model count with pagination/completeness status; available operations; metadata freshness. |
| Account economics | Balance, credits, spend, budget, currency, remaining quota, reset time, source and scope. |
| Operational limits | Request/byte/token/concurrency limits, supported uploads, batch limits, retention constraints. |
| Routing/privacy | Available routes, region constraints, storage/data-use controls, actual route visibility. |
| Native details | Sanitized raw metadata and extension fields, including unknown future fields. |

### 8.3 Model/deployment descriptor inventory

| Group | Facts to expose when available |
|---|---|
| Identity and lifecycle | Requested/normalized ID, aliases, deployment name, revision, publisher, display name, description, release/deprecation information, availability. |
| Context and output | Maximum input tokens, combined context capacity, maximum generated tokens, mode-dependent limits, local configured capacity, reservations and scope. |
| Modalities | Input/output text, image, audio, video, document, embedding; supported combinations and per-operation constraints. |
| Reasoning | Modes, effort labels, budgets, default behavior, summaries, opaque state/signatures, replay requirements. |
| Tooling | Client function calls, parallel calls, strict argument schemas, hosted tools, approval types, tool-result content types. |
| Output controls | Plain text, JSON mode, supported schema dialect/subset, grammar, log probabilities, citations, candidate counts. |
| Sampling | Supported fields, numeric ranges, discrete values, defaults, dependency/incompatibility conditions, documented ignored settings. |
| Execution | Streaming, stateful continuation, background/batch, caching, token counting, realtime, priority/service tiers. |
| Pricing | Rate-card reference and dimensions, not merely two hardcoded numbers. |
| Runtime-specific | Quantization, model/template/tokenizer identity, configured context/slots, loaded state, supported local options. |
| Legal/operational metadata | License/model-card links, residency and retention facts where published; labels are information, not compliance certification. |

- **META-009 [C, MUST] — Capability is more than a boolean.** Represent supported/unsupported/unknown/conditional, scope, constraints, source, and whether functionality is native, translated, or emulated. Separate “SDK implements it,” “endpoint exposes it,” “model supports it,” and “account may use it.”
- **META-010 [P, MUST] — Native discovery fidelity.** Handle provider-specific pagination, nullability, nested capability structures, endpoint-level versus model-level prices, and incomplete lists. Retain original units and field names alongside normalized ones.
- **META-011 [C, SHOULD] — Descriptor query utilities.** Support filtering by required capabilities, modality, context capacity, region, known price, and availability without triggering inference. An unknown price/capability must not automatically rank as cheapest/best.

### 8.4 Knowledge and availability are separate axes

A capability can be **documented supported** but currently **unavailable** because of entitlement, quota, outage, deployment settings, or route choice. Likewise, a model can be available while a required field remains unverified. The SDK should expose both axes instead of collapsing them into a single green checkmark.

## 9. Capability negotiation and request planning

### 9.1 Effective capabilities

Conceptually, a request is executable only when its requirements satisfy all relevant constraints:

```text
request requirements
    ∩ SDK operation/profile implementation
    ∩ protocol and endpoint behavior
    ∩ model/deployment capabilities
    ∩ account entitlements
    ∩ route and runtime configuration
    ∩ application security, data, and budget policy
```

This is a constraint check, not a literal set intersection of booleans. Conditional capabilities depend on values and combinations; unknown upstream routing prevents a claim of complete preflight certainty.

- **CAP-001 [C, MUST] — Requirement-aware validation.** Allow callers to declare required capabilities and constraints separately from preferences. Validate parameter ranges, supported combinations, output modality, tools, storage requirements, and known context/attachment limits before billable dispatch where possible.
- **CAP-002 [C, MUST] — Explicit unsupported policy.** Default to rejecting explicitly unsupported settings. Unknown support may be attempted under a documented policy, but uncertainty must remain visible; do not equate unknown with proven support.
- **CAP-003 [C, MUST] — Explain transformations.** Record mapped fields, changed defaults, dropped fields, emulation, schema rewrites, attachment preprocessing, and native-only values. A lossy transformation requires caller authorization and must remain inspectable afterward.
- **CAP-004 [P, MUST] — Route-level constraints.** Where a gateway can route among providers, apply required-parameter/privacy/region constraints through its documented controls or pin a route. Aggregate model capabilities must not be asserted for every possible route. [OR-ROUTING]
- **CAP-005 [C, MUST] — Prepared execution plan.** Preparation should identify the operation/protocol/model, configuration snapshot, known capability result, warnings, pending uploads/token counts/auth/network work, side effects, and cost estimate or uncertainty. It must not itself perform those effects unless the caller explicitly requested them.
- **CAP-006 [C, MUST] — Revalidation after change.** Revalidate after routing, retries that change deployment, transformations, or refreshed metadata that materially alter the request. Bind a plan to its input/configuration version so stale validation is detectable.
- **CAP-007 [C, MUST] — No false confirmation.** Expose parameters as requested, sent, documented-effective, and observed-effective where possible. HTTP success alone cannot mark all sent fields as applied.
- **CAP-008 [C, SHOULD] — Helpful failure output.** Return actionable alternatives: supported values, missing entitlement, conflicting settings, native-only operation, or a required explicit conversion. Do not automatically choose a more expensive or less private alternative.

### 9.2 Suggested preparation outcomes

Use outcomes equivalent to **ready**, **ready with declared uncertainty**, **requires explicit transformation approval**, **requires additional authorized preparation**, and **rejected**. Exact Java types are a later API decision. Do not encode all outcomes as a thrown generic exception or a boolean `isValid`.

## 10. Canonical requests, content, and native fidelity

### 10.1 Content and result vocabulary

The common model should support ordered messages/items and ordered content parts, with explicit provider-native extensions. It must not assume every API is a list of `{role, content: String}` objects.

| Element | Required representational capacity |
|---|---|
| Instructions | System/developer instructions, provider-specific instruction fields, role restrictions and precedence. |
| Text | Unicode text, optionally typed annotations; preserve exact content and ordering. |
| Media/document part | Typed inline data, URI, file reference, quality/detail/time/frame metadata where supported. |
| Function/tool call | Tool identity, call ID, argument bytes/text/value, status, execution location. |
| Tool result | Matching call ID, success/error, text/structured/multimodal content, native metadata. |
| Reasoning item | Provider-exposed text or summary, opaque encrypted content, signatures, or other replay material. |
| Hosted-tool event/result | Search, code execution, file search, computer/browser actions, tool-specific citations/artifacts. |
| Refusal/safety result | Refusal content, blocked input/output, safety annotations; not silently converted to an empty string. |
| Citation/annotation | Source reference, offsets/units, file/page/span information, tool provenance. |
| Candidate/output alternative | Candidate identity, ordering, completion status, usage semantics. |
| Native/unknown item | Original type and payload, with safe preservation and explicit unsupported projection. |

- **MSG-001 [C, MUST] — Ordered, typed structure.** Preserve message/item/part boundaries, role identity, candidate indices, and tool correlations. Do not merge adjacent content, rewrite roles, or flatten media unless an explicit adapter transformation permits it.
- **MSG-002 [C, MUST] — Transparent text convenience.** A simple `text` projection may be provided, but it must have documented selection rules and cannot replace the full result. Distinguish no text, empty text, refusal, tool-only output, incomplete output, and multiple candidates.
- **MSG-003 [C, MUST] — Extension preservation.** Preserve unrecognized response fields and event/item types in an extension/native representation. Unknown required behavior must produce a warning or failure, not fabricated semantics.
- **MSG-004 [C, MUST] — Native replay material.** Retain exact opaque values and their associations. Do not normalize, summarize, trim, decode/re-encode, or re-sign opaque provider fields on the assumption that they are ordinary text.
- **MSG-005 [C, MUST] — Input ownership.** Requests must be immutable or safely snapshotted at dispatch. Shared arrays/maps/streams require explicit ownership rules so another thread cannot alter a signed or already validated request.
- **MSG-006 [C, MUST] — No automatic prompt templating.** Plain strings are literal strings. Variable interpolation, role conversion, file-to-text extraction, and model chat-template application must be explicit higher-level actions, not hidden transport behavior.
- **MSG-007 [C, MUST] — Round-trip limits.** Distinguish semantic preservation, native JSON-value preservation, and exact wire-byte preservation. A normalized object cannot promise byte-identical replay; exact passthrough needs a separate bounded byte-stream path.
- **MSG-008 [C, SHOULD] — Versioned persistence.** Offer safe, versioned transcript/result serialization that preserves native extensions and resource provenance. Avoid Java native object serialization for untrusted persisted data.
- **MSG-009 [P, MUST] — Provider grammar validation.** Enforce required tool-result pairing, supported role ordering, item references, instruction placement, and native content constraints without inventing synthetic messages unless explicitly authorized.
- **MSG-010 [C, MUST] — Annotation correctness.** Preserve native offset units and source identity. Java UTF-16 positions, Unicode code points, byte offsets, and provider token offsets must not be silently treated as interchangeable.

## 11. Generation parameters and reasoning controls

### 11.1 Parameter descriptors, not a universal bag of numbers

Each exposed setting should have a canonical identifier where semantics align, a native field mapping, type, unit, allowed range/values, default knowledge, applicability, and incompatibilities. The table is a **coverage inventory**; not every provider/model supports every row.

| Group | Parameters/features to represent when supported | Important distinctions |
|---|---|---|
| Basic sampling | Temperature, top-p, top-k, min-p | Same name does not guarantee the same range, default, or applicability. |
| Advanced local sampling | Typical-p, tail-free sampling, Mirostat mode/tau/eta, XTC, DRY, sampler ordering, dynamic temperature | Native/local extensions; avoid forcing these into all cloud request builders. |
| Repetition controls | Frequency/presence penalties, repetition penalty/window, DRY settings, newline/EOS behavior | Additive frequency penalties and multiplicative repetition penalties are not equivalent. |
| Length/stopping | Maximum output/completion/prediction tokens, optional minimum tokens, stop strings/token IDs, EOS handling | Visible answer length may differ from total generated/reasoning tokens. |
| Reproducibility | Seed, model revision/fingerprint, deterministic/runtime options | A seed is not a cross-provider or cross-hardware reproducibility guarantee. |
| Candidate/scoring controls | Number of candidates, logprobs/top-logprobs, token IDs, logit bias, native scores | Token IDs and scores depend on tokenizer/model; multiple candidates affect usage. |
| Reasoning | Off/on/auto/adaptive modes, effort labels, explicit token budget, summaries/visibility, opaque state | Effort, budget, enabled state, and disclosed reasoning are different controls. |
| Structured generation | Output MIME type, JSON mode, JSON Schema, strictness, grammar/regex/native constraints | A grammar is not necessarily JSON Schema; validation is not generation enforcement. |
| Tools | Tool declarations, auto/none/required/named selection, parallelism, allowed tool subset, hosted-tool budgets | Native tool execution and client-side function calls have different side effects. |
| Service execution | Service tier/priority, latency policy, routing, batch/background, prediction/speculative input | Not sampling parameters; may change price, SLA, or routing. |
| Media generation | Image size/quality/count/background/format, audio voice/codec/speed, video duration/aspect/fps | Operation-specific descriptors, not text-generation fields. |
| Safety/output behavior | Documented safety settings, verbosity, citation controls, provider-native filtering metadata | Do not invent a universal safety-disable switch or assume cross-API equivalence. |
| Local runtime controls | Context allocation, keep-alive/model residency, threads/GPU/slots/template settings where exposed | Often server startup/admin settings, not ordinary per-request generation options. |

### 11.2 Parameter behavior requirements

- **PAR-001 [C, MUST] — Exact supported values.** Validate against the selected model/profile/mode rather than global ranges. Preserve unknown future enumerated values through native access while making unsupported normalized values explicit.
- **PAR-002 [C, MUST] — No blanket default injection.** Omitted values remain omitted unless a documented SDK policy intentionally supplies them. Expose provider defaults as metadata, not automatically copied request fields.
- **PAR-003 [C, MUST] — Reasoning separation.** Model thinking mode, effort, token budget, summary request, and replay material independently. Do not map every provider to one `reasoning=true` or a universal numeric effort scale.
- **PAR-004 [C, MUST] — Cross-field constraints.** Validate model/mode-dependent exclusions, minimum/maximum relationships, tool/schema combinations, candidate restrictions, and token accounting rules. Avoid globally enforcing a budget relationship that has documented provider-specific exceptions.
- **PAR-005 [C, MUST] — Numeric fidelity.** Use suitable integer/decimal representations; reject NaN/infinity and locale-formatted numeric strings unless explicitly parsed at the UI boundary. Accommodate provider values beyond signed 32-bit integers and, where needed, unsigned 64-bit seeds.
- **PAR-006 [C, MUST] — Native option conflicts.** If canonical and native options map to the same field, reject the conflict or use one explicitly documented precedence rule. Never let generic map merging silently overwrite a validated value.
- **PAR-007 [C, SHOULD] — Parameter applicability explanations.** Explain why a control is unavailable, ignored, coerced, or conditional, including the relevant mode and source. Keep requested and effective values distinct.
- **PAR-008 [X, MUST] — Runtime tuning isolation.** Keep server/process configuration, model residency, and administrative resource allocation outside ordinary generation presets unless the runtime explicitly offers safe per-request controls.

**Provider observations.** DeepSeek documents mode-dependent parameters that can be accepted without taking effect. Anthropic's effort control can affect output beyond thinking alone; thinking-mode and budget support are model-dependent. Ollama documents model-specific `think` types/values and runtime options. These are reasons to use descriptors and profile rules, not universal mappings. [DS-THINK] [AN-EFFORT] [AN-THINK] [OL-CHAT]

## 12. Invocation lifecycle and streaming

### 12.1 One execution, several consumption styles

- **RUN-001 [C, MUST] — Consistent execution contract.** Provide blocking, asynchronous, and streaming consumption with equivalent request semantics. A non-streaming facade may internally consume a provider stream where required, but must report that behavior and preserve terminal errors.
- **RUN-002 [C, MUST] — Explicit start/subscription semantics.** Document whether an invocation starts on creation, dispatch, or first subscription. A second subscriber or a later request for the final result must not accidentally launch another billable execution.
- **RUN-003 [C, MUST] — Stable lifecycle.** Represent preparation/queueing, dispatch, headers/acceptance, active output, terminal success, terminal incomplete, failure, and cancellation. Provider background jobs may introduce additional states without falsely marking them complete.
- **RUN-004 [C, MUST] — Partial-result access.** Preserve successfully received items/events, known usage, provider IDs, and outcome uncertainty when a stream fails. Partial output must not be returned as an ordinary successful complete response.
- **RUN-005 [C, MUST] — Completion evidence.** A clean socket close is not enough for success when the protocol requires a terminal event. Conversely, a terminal protocol result must not be lost because a subsequent connection cleanup operation fails.
- **RUN-006 [C, MUST] — Bounded retention.** Allow streaming-only consumption, bounded aggregation, and optional artifact spooling. A requested complete in-memory result may require output-proportional memory, but this must be explicit rather than imposed on all streams.

### 12.2 Stream event taxonomy

A normalized event envelope should carry SDK run/attempt identity; provider response ID; protocol/native event type; local sequence; optional upstream event sequence/cursor; candidate/item/content-block/tool-call identity; receive time; typed payload or unknown native payload; and usage/error metadata where applicable.

| Event family | Examples of distinctions to preserve |
|---|---|
| Lifecycle | Accepted/created, queued, in progress, complete, incomplete, failed, cancelled. |
| Item/block | Added, started, delta, snapshot/replacement, done. |
| Text | Text delta, completed text, refusal delta, annotations. |
| Reasoning | Exposed summary/text, signature/opaque-state fragments, completed native reasoning item. |
| Tools | Call started, argument fragment, completed arguments, hosted execution progress, result, approval required. |
| Media | Metadata, binary/base64 chunk, partial image, final artifact, audio transcript/timestamps. |
| Accounting | Usage update, final usage, cost/route report where supplied. |
| Transport/control | Heartbeat, reconnect hint/cursor, rate-limit notification, session update. |
| Unknown | Unrecognized native event preserved without fabricated interpretation. |

- **STR-001 [P, MUST] — Correct framing.** Incrementally parse arbitrary byte boundaries, split UTF-8 sequences, CR/LF variants, and multiple events per read. SSE supports comments and multiline `data`; NDJSON has different record boundaries. [STD-SSE] [OL-STREAM]
- **STR-002 [P, MUST] — Protocol-specific termination.** Handle each protocol's terminal grammar, including named events, final objects, and sentinel strings only where documented. Do not assume every provider uses `[DONE]`. A content finish reason can precede final usage and is not necessarily stream completion.
- **STR-003 [P, MUST] — Interleaving support.** Assemble text, tool arguments, reasoning, media, and candidates by their proper identities. Never append all deltas into one shared text buffer.
- **STR-004 [P, MUST] — Delta versus snapshot.** Distinguish additive deltas from cumulative snapshots and completed-item payloads. Reconcile final values without duplicating already streamed text or usage.
- **STR-005 [P, MUST] — Late errors and late usage.** Process provider errors received after HTTP 200, usage emitted after visible content, and incomplete terminal states. Preserve native error categories and finish reasons. [AN-STREAM] [OA-STREAM]
- **STR-006 [C, MUST] — Backpressure.** Provide bounded queues and documented demand/cancellation behavior. Slow consumers must cause controlled pausing, spooling, or cancellation—not unbounded memory growth or silent semantic-event loss.
- **STR-007 [C, MUST] — Observer isolation.** A faulty logging/UI observer must not corrupt protocol assembly. Define whether observer failures are isolated or intentionally fail the invocation; do not execute arbitrary callbacks on transport parsing threads without a documented policy.
- **STR-008 [P, MUST] — Resume only with evidence.** Expose resume cursors or sequence numbers only where the provider supports resumption. An SSE `id` or HTTP retry hint does not by itself authorize replaying an inference POST or reconstructing missing events.
- **STR-009 [C, MUST] — Tool arguments are provisional.** Expose partial arguments for display, but mark them incomplete/unvalidated. Do not execute a client tool from fragments before its call is complete and validated.
- **STR-010 [P, MUST] — Binary and multiplexed variants.** Dedicated decoders must support binary event streams and multiple session lanes only where documented. Lane/session identity must not be confused with conversation continuation identity. [AWS-STREAM] [OA-WS]

## 13. Structured output, schemas, and validation

### 13.1 Distinct output contracts

| Contract | What it requests or guarantees | What it does not imply |
|---|---|---|
| Text | Ordinary generated content | Valid JSON or any schema. |
| JSON mode | Provider-documented JSON generation behavior | Conformance to an arbitrary supplied schema. |
| Schema-constrained output | Generation according to a supported schema subset | Support for the entire JSON Schema standard or absence of refusals/interruption. |
| Strict tool arguments | Schema constraints on a tool's generated arguments | Validation of the tool's returned data or permission to execute it. |
| Grammar/native constraints | Provider/runtime-specific constrained generation | Portable JSON Schema semantics. |
| Local validation | Validation of received output | That the upstream model was constrained during generation. |
| Repair/retry | A transformation or another model call | Preservation of the original output, zero extra cost, or guaranteed correction. |

OpenAI, Anthropic, and Gemini expose different structured-output contracts and schema limitations. The SDK must validate against the selected contract rather than assuming a schema accepted by one API is transferable unchanged. [OA-STRUCT] [AN-STRUCT] [GO-STRUCT]

- **FMT-001 [C, MUST] — Output specification.** Represent output kind, MIME type where applicable, schema/grammar identity, strictness, and optional local decoding target separately from the ordinary prompt.
- **FMT-002 [P, MUST] — Schema dialect/subset awareness.** Track supported schema features, including required/optional fields, nullability, references, recursion, unions, enums, formats, numeric/string constraints, additional properties, and complexity limits. Do not silently discard unsupported keywords.
- **FMT-003 [C, MUST] — Explicit schema transformation.** Any flattening, optional-to-null conversion, keyword removal, property reordering, or wrapper insertion must be inspectable and authorized where it changes meaning. Retain original and transmitted schema fingerprints.
- **FMT-004 [C, MUST] — Safe result decoding.** Distinguish transport success, provider completion, valid JSON, schema validity, and Java-object mapping success. Preserve original output and meaningful validation errors; do not return a default-constructed object after parse failure.
- **FMT-005 [C, MUST] — Refusal/incomplete handling.** A refusal, content filter, truncation, or interrupted output must not be mislabeled as a schema violation alone. Represent the upstream terminal condition before attempting structured decoding.
- **FMT-006 [C, MUST] — Parser safety.** Bound schema/output depth, size, references, and computational complexity. Define duplicate-key handling and numeric precision; disable external schema fetching and polymorphic class loading by default.
- **FMT-007 [X, MUST] — Explicit repair.** Optional repair must return provenance and additional usage/attempts. Deterministic syntactic repair and a new model call are distinct operations; neither runs by default.
- **FMT-008 [C, SHOULD] — Reusable schema compilation.** Cache compiled local validators by dialect, schema content, validator version, and security policy. Keep validator/compiler dependencies optional for callers who only need native output control.

## 14. Tools, hosted capabilities, and MCP

### 14.1 Three different tool systems

| Kind | Who executes it? | SDK responsibility |
|---|---|---|
| Client function/custom tool | Application or optional executor | Send declaration, return complete validated call, accept correlated result, preserve history. |
| Provider-hosted tool | Provider, such as search or hosted code execution | Send supported tool configuration; surface progress, results, approvals, artifacts, citations, and usage. |
| Remote tool service, including MCP | Remote server under separate authorization | Represent documented provider integration or use an optional client integration with independent auth and policy. |

A model “supports tools” usually describes a calling capability. It does not mean a universal endpoint can list every tool available to the application. Application-defined tools, provider-hosted catalogs, remote-server tool discovery, and model capability metadata are different sources.

### 14.2 Declaration and exchange requirements

- **TOOL-001 [P, MUST] — Rich tool declaration.** Support names, descriptions, argument schemas, strictness, native/custom input formats, provider tool types, and any required tool-specific configuration. Preserve declaration order when it matters for caching or native behavior.
- **TOOL-002 [P, MUST] — Tool-choice semantics.** Represent auto, disabled, required, explicitly named, and provider-specific allowed-subset controls accurately. Distinguish permission to call tools from permission to execute side effects.
- **TOOL-003 [C, MUST] — Call/result correlation.** Preserve provider call IDs and match every result to its originating call and attempt. Support multiple parallel calls and provider-specific ordering requirements; do not equate array position with durable identity.
- **TOOL-004 [C, MUST] — Safe default boundary.** The transport library returns requested client tool calls without executing them. It must not automatically run shell commands, access files, call URLs, or invoke reflected Java methods.
- **TOOL-005 [P, MUST] — Result representation.** Support success/error tool results with structured, text, and multimodal payloads where accepted. Transport failures, application tool errors, and model-visible error messages must remain distinct.
- **TOOL-006 [P, MUST] — Hosted-tool visibility.** Expose hosted-tool start/end/progress, citations, artifacts, approvals, and separately metered usage where available. Do not disguise provider-side execution as a client function call.
- **TOOL-007 [P, MUST] — Unsupported grammar detection.** Reject combinations that cannot be faithfully represented by the target protocol, such as a native custom-tool format or signed reasoning/tool sequence with no equivalent representation.
- **TOOL-008 [C, MUST] — No claim of hidden visibility.** Expose only provider-returned execution/reasoning information. A progress UI must not pretend to observe internal model computation or undisclosed private reasoning.

### 14.3 Optional orchestration and MCP

- **TOOL-009 [X, MUST] — Bounded automatic tool loop.** If an optional convenience executor is supplied, require a tool registry, authorization/approval policy, maximum rounds, total budget/deadline, cancellation, and duplicate-execution protection. Keep it separable from single-call inference.
- **TOOL-010 [X, MUST] — Side-effect safety.** Tool approval must bind to the exact tool, arguments, principal, and relevant resource scope. Retrying an LLM request or resuming a stream must not automatically replay completed side-effecting tools.
- **TOOL-011 [X, MUST] — MCP version and transport isolation.** Pin a supported MCP version and implement its advertised transport/lifecycle requirements in a separate integration. MCP is a tool/resource protocol, not a replacement for provider inference protocols. [MCP-TRANSPORT]
- **TOOL-012 [X, MUST] — Separate MCP authorization.** Follow the negotiated server authorization contract, resource/audience boundaries, and consent requirements. Never pass an inbound user token or provider token to an arbitrary MCP server. [MCP-AUTH]
- **TOOL-013 [X, MUST] — Untrusted tool metadata.** Treat discovered tool names/descriptions/schemas and returned content as untrusted data. They must not rewrite SDK security policy or authorize new network destinations.
- **TOOL-014 [X, SHOULD] — Dynamic discovery.** Allow tool catalogs to refresh, paginate, and change without rebuilding the core client. A catalog snapshot used in a request should be identifiable for reproducibility and cache analysis.

## 15. Files, attachments, and multimodal content

### 15.1 Input reference types

Represent local file paths, caller-supplied bytes, replayable streams, inline base64/data URIs, remote HTTP(S) URIs, provider file IDs, and approved cloud-object references as different types. A filename is not content; a URI is not permission to fetch it; a provider file ID is not portable across providers or accounts.

- **FILE-001 [C, MUST] — Explicit materialization.** Reading a path, fetching a URL, extracting text, uploading a file, and attaching an existing provider resource are distinct authorized actions. Request construction must not perform them implicitly.
- **FILE-002 [C, MUST] — Attachment plan.** Before dispatch, identify each source, content type, expected size, transformation, destination, upload/reuse mode, readiness requirement, and resource retention/cleanup policy.
- **FILE-003 [P, MUST] — Native upload lifecycle.** Support upload/create, retrieve/status, list where useful, content retrieval where allowed, deletion, expiration, processing readiness, and purpose constraints for the selected provider. Multipart, resumable uploads, and inline data require different implementations. [STD-MULTIPART] [GO-FILES]
- **FILE-004 [C, MUST] — Scoped file handles.** Bind handles to provider/endpoint/account/project/region and capture creation/expiration/readiness. Reject cross-tenant or wrong-provider reuse before dispatch when identifiable.
- **FILE-005 [C, MUST] — Size and replay safety.** Bound reads/uploads/base64 expansion; support streaming when possible; declare replayability; verify optional hashes and source consistency. A retry must not silently send different file contents under the same logical request.
- **FILE-006 [C, MUST] — Cleanup policy.** Distinguish caller-owned from SDK-created remote/local artifacts. Do not delete shared resources automatically; clean temporary files on failure/cancellation; expose failed remote cleanup and orphaned resources.
- **FILE-007 [P, MUST] — Media constraints.** Validate known MIME/extension, dimensions, page count, duration, codec, sample rate, frame count, file count, and total payload limits. Unknown metadata should trigger bounded inspection or remain unknown—not an invented limit.
- **FILE-008 [C, MUST] — Text extraction is a transformation.** Sending a `.txt` file as literal text can be convenient, but requires explicit encoding and conversion policy. PDF/image/audio extraction may lose layout, images, annotations, timing, or other semantics; never silently substitute extracted text for native file input.
- **FILE-009 [C, MUST] — Sensitive metadata policy.** Offer explicit control over filenames, document metadata, EXIF, remote URL query strings, and other incidental data. Any metadata stripping changes the submitted artifact and must be recorded.

### 15.2 Media feature inventory

The following are families to support through capability-specific operations. Supported formats must be supplied by the exact profile, not globally promised.

| Family | Inputs/outputs and options to model |
|---|---|
| Image understanding | Inline/reference/file images; detail/quality controls; multiple-image ordering; accepted formats such as JPEG/PNG/WebP only where documented. |
| Image generation/editing | Prompt, reference images, masks where supported, size/aspect, quality, count, background/transparency, output format, partial image events, revised prompts, generated artifacts. |
| Audio understanding/transcription | Audio bytes/stream/reference, container and codec, language hints, timestamps, diarization where supported, transcription text and structured segments. |
| Speech/audio generation | Text or multimodal input, voice identity, format/codec, sample rate, speed and other documented controls; streamed binary audio and transcript. |
| Video understanding | Uploaded/reference video or frame sequences, timestamps, frame sampling, synchronized audio, segment selection where supported. |
| Video generation/editing | Prompt/reference media, duration, aspect ratio, frame rate where supported, asynchronous jobs, output URI/bytes, expiration and retrieval. |
| Documents | PDF and other supported types; native page/citation annotations, page limits, scanned versus text-based content, embedded media. |
| Generated artifacts | Provider ID/URI, MIME type, size, checksum when available, expiry, source invocation, authorized download strategy. |

- **MEDIA-001 [P, MUST] — Distinct operations.** Image generation, image input to a language model, speech synthesis, transcription, and realtime audio must not be collapsed into a text-chat method with undocumented flags.
- **MEDIA-002 [C, MUST] — No automatic output fetching.** Returning an artifact reference does not automatically authorize following its URL or storing its contents. Make download, retention, and destination policy explicit; preserve expiring URL metadata securely.
- **MEDIA-003 [P, MUST] — Content identity and synchronization.** Preserve media ordering, timestamps, masks/reference associations, transcript relationships, and native part IDs. Do not conflate an audio container format with its codec or sample format.
- **MEDIA-004 [C, MUST] — Resource-bounded helpers.** Optional transcoding/resizing/OCR/extraction must declare dependencies, limits, quality loss, and cost. The core should not require large media toolchains for ordinary text calls.
- **MEDIA-005 [P, MUST] — Multimodal accounting.** Retain provider-native image/audio/video usage units and conversion rules where published. Do not estimate every modality by applying a text tokenizer to base64.

## 16. Conversations, reasoning continuity, and compaction

### 16.1 Different forms of state

| State form | Ownership | Required distinction |
|---|---|---|
| Client transcript | Application/SDK serialization | Full content, tools, native replay material, and resource references. |
| Provider response/conversation reference | Provider account | Opaque reference with retention, continuation, and retrieval rules. |
| WebSocket connection/session state | Connection or provider session | May be short-lived and connection-affine; not equivalent to persisted conversation storage. |
| Reasoning continuation | Provider/model-specific | May include signed, encrypted, or structured items; not a portable summary. |
| Compacted context | Provider or explicit application transform | A new context representation with provenance and possible loss. |
| Application memory | Agent/workflow layer | Outside automatic transport behavior. |

- **STATE-001 [C, MUST] — Explicit state strategy.** Distinguish stateless full-history calls, response-ID continuation, persistent conversation resources, and realtime sessions. A profile must advertise which are available and which settings are incompatible.
- **STATE-002 [P, MUST] — Continuation inheritance rules.** Document whether instructions, tools, sampling, storage choices, and other settings carry across turns or must be resent. Do not infer that a previous-response reference includes the entire previous request configuration.
- **STATE-003 [P, MUST] — Native reasoning replay.** Preserve and replay the exact items required by the selected model/protocol/mode, including signatures, encrypted payloads, thought metadata, and tool relationships. Do not apply a universal rule to strip or resend all reasoning.
- **STATE-004 [C, MUST] — Cross-provider migration report.** A transcript conversion must identify unsupported roles, lost modalities, nonportable file IDs, hosted-tool state, citations, and reasoning material. Default to rejecting unsafe migration rather than silently transplanting opaque state.
- **STATE-005 [P, MUST] — Resource lifecycle.** Expose retention, retrieval, deletion, expiry, conversation forks, and missing-parent errors where supported. Deleting one resource must not be described as deleting every provider copy, cache, log, or billing record.
- **STATE-006 [C, MUST] — No invisible truncation.** Context overflow should yield an actionable failure or an explicitly selected policy. Never silently drop earlier instructions, tool pairs, documents, or reasoning items to fit a request.
- **STATE-007 [X, MUST] — Explicit compaction.** Provider-native compaction and application summarization must be separate operations with inputs, resulting state, usage, compatibility, and provenance. Preserve opaque compaction items; do not present a summary as lossless compression. [OA-COMPACT]
- **STATE-008 [C, SHOULD] — Restart continuity.** Support application-managed persistence of safe continuation data and identifiers. Secret-free transcript serialization may still contain sensitive content and needs a separate retention/encryption policy.

**Provider observations.** Gemini's Interactions guide distinguishes stored interaction history from per-turn configuration. Its current limitations include features still available only through GenerateContent. OpenAI documents conversation-state and reasoning mechanisms separately. DeepSeek documents reasoning replay conditions for tool-enabled conversations. Google documents thought/signature representations whose placement depends on the API. These differences require profile-specific continuation rules. [GO-INTERACTIONS] [OA-STATE] [OA-REASON] [DS-THINK] [GO-THINK] [GO-SIGNATURES]

## 17. Caching and request optimization

### 17.1 Cache taxonomy

| Cache/state type | What is reused | Available SDK controls |
|---|---|---|
| Metadata cache | Model/account/price observations | Local TTL, refresh, provenance, tenant partition, invalidation. |
| HTTP cache | Cacheable HTTP representations | HTTP semantics where applicable; not an inference-answer cache. |
| Provider implicit prompt/KV cache | Provider-side computation for matching input prefixes or native context | Provider-specific hints/keys/retention settings and observed cache usage, if exposed. |
| Provider explicit prompt/content cache | A named or explicit cached resource/block | Create/reference/update/delete/expiry operations only where actually provided. |
| Stateful conversation | Previously stored interaction items | Continuation resource controls; not automatically equivalent to discounted tokens. |
| Local exact-response cache | Prior complete application-level result | Explicit opt-in, exact key construction, expiry, privacy and replay policies. |
| Semantic response cache | Approximate answer reuse for similar input | Separate higher-level extension; never transparent transport behavior. |
| Local runtime KV/slot state | Server-specific context computation | Runtime-specific inspection or control, not a universal public-provider operation. |
| HTTP compression | Encoded bytes on the wire | Content-encoding support; not semantic or token caching. |

- **CACHE-001 [C, MUST] — Separate concepts.** Expose each supported cache type independently. Do not offer a misleading universal `clearCache()` or imply that `store=false` disables all prompt caches and provider retention.
- **CACHE-002 [P, MUST] — Native control fidelity.** Represent block breakpoints, cache keys, retention/TTL, explicit resource references, minimum-size constraints, and model/mode restrictions only where the provider supports them. Distinguish enforceable controls from best-effort hints.
- **CACHE-003 [C, MUST] — Stable request assembly.** Avoid injecting random timestamps, unstable tool/schema ordering, changing whitespace, or unnecessary metadata into cache-relevant content. Preserve semantics and native ordering rather than globally canonicalizing every request.
- **CACHE-004 [C, MUST] — No hit guarantee.** A matching-looking prefix, persistent connection, or previous request does not guarantee a hit, continued residency, cross-model reuse, or reuse across accounts/providers. Report observed cache counters rather than guessing success.
- **CACHE-005 [C, MUST] — Isolated local keys.** Exact-response keys must include effective model/revision/endpoint, account-policy boundary, full semantic input, tool/schema/media identity, relevant parameters, transformations, and policy/version context. Never reuse a private result across tenants accidentally.
- **CACHE-006 [X, MUST] — Cache safety.** Default local response caching off for side-effecting tool flows, nondeterministic external data, expiring artifacts, sensitive content, and incomplete/failed results unless a specific policy handles them. Cache storage and hit telemetry must not leak prompts or secrets.
- **CACHE-007 [P, MUST] — Resource cleanup and metering.** Track explicit cache creation/write/read/storage costs, expiry, and ownership where reported. An expired or wrong-model cache reference must be distinguishable from a generic request error.
- **CACHE-008 [C, MUST] — Optimization transparency.** Token reduction, history compaction, text extraction, and schema simplification are semantic transformations. HTTP compression and connection reuse are transport optimizations. Report them separately with different correctness claims.
- **CACHE-009 [X, SHOULD] — Cache-aware planning.** Allow callers to inspect potentially stable prefixes and estimated cache effects, but do not automatically reorder instruction/tool/media content or add billable warm-up requests.

**Provider reference points.** OpenAI documents automatic prompt caching and associated controls; Anthropic documents its block-oriented caching behavior; Gemini exposes its own caching mechanisms; OpenRouter describes gateway/provider-specific caching behavior. None should become a single invented universal cache protocol. [OA-CACHE] [AN-CACHE] [GO-CACHE] [OR-CACHE]

## 18. Background jobs, batches, webhooks, and realtime

### 18.1 Async client call versus remote job

A `CompletionStage` is a Java consumption mechanism. A provider background job is a server-side resource that may outlive the local connection. A batch is a collection of independently tracked requests. A realtime session is an ongoing bidirectional protocol. The API must keep these concepts separate.

- **JOB-001 [P, MUST] — Job lifecycle.** For supported background/deferred/media operations, expose submit, get/poll, retrieve output, cancel, delete where supported, timestamps, progress if meaningful, and terminal status. Do not invent a completion percentage from elapsed time.
- **JOB-002 [P, MUST] — Durable references.** Preserve provider job IDs and scope so applications can reconnect after restart without resubmitting. Submission with an ambiguous outcome requires an explicit recovery strategy, not automatic duplicate creation.
- **JOB-003 [P, MUST] — Polling policy.** Poll with bounded backoff, total deadline, jitter, cancellation, and provider guidance. Polling and output downloads have separate rate/size limits from submission.
- **JOB-004 [P, MUST] — Background/privacy compatibility.** Validate storage and retention requirements before enabling background operation. A local asynchronous call must not silently select provider background mode. OpenAI documents background execution as a separate feature. [OA-BACKGROUND]

### 18.2 Batches

- **BATCH-001 [P, MUST] — Batch contract.** Support declared operation types, request-file or inline submission, JSONL/native formats, per-item correlation IDs, limits, status, output/error retrieval, cancellation, and retention where available.
- **BATCH-002 [C, MUST] — Per-item truth.** A completed batch may contain failures; preserve each item's result, error, usage, and request identity. Output order may differ from input order.
- **BATCH-003 [P, MUST] — Bounded processing.** Stream large batch input/output files and expose pagination/chunking only within documented limits. Do not load an entire potentially huge result file merely to return one item.
- **BATCH-004 [C, MUST] — Retry accounting.** Retrying failed items must not duplicate successful side effects or overwrite prior charges. Represent each retry as a new attempt with explicit correlation to the original item.

### 18.3 Webhooks

- **HOOK-001 [X, MUST] — Verification first.** Validate the provider's signature against the original request bytes with timestamp/replay protections before treating a webhook as authenticated. Parsing and reserializing JSON is not equivalent to verifying the original signed payload. [OA-WEBHOOK]
- **HOOK-002 [X, MUST] — Duplicate/out-of-order delivery.** Expose event IDs, deduplication hooks, replay windows, and reconciliation with the provider resource. Do not promise exactly-once delivery or trust arrival order as authoritative job order.
- **HOOK-003 [X, MUST] — Framework-neutral integration.** Offer verification/decoding utilities independently of an embedded web server; enforce bounded bodies, tenant-aware secret selection, and secret rotation.

### 18.4 WebSocket and realtime extensions

- **RT-001 [X, MUST] — Protocol separation.** Distinguish text Responses over WebSocket, a multimodal Realtime/Live API, and generic streaming over HTTP. Shared WebSocket transport does not mean interchangeable application messages. [OA-WS] [OA-REALTIME] [GO-LIVE]
- **RT-002 [X, MUST] — Session lifecycle.** Represent connect/authenticate, negotiated configuration, ready, active turns, session limits, reconfiguration, interruption, reconnect, and close. Reconnect must not imply automatic restoration of provider state.
- **RT-003 [X, MUST] — Bidirectional media controls.** Where offered, expose audio input buffers, commit/clear, voice activity detection, turn detection, speech interruption, input/output codecs, sample formats, transcription, and flow control. WebRTC requires its own optional integration rather than a claim that HTTP support is sufficient.
- **RT-004 [X, MUST] — Multiplexing and continuity.** Track lane/stream/session IDs independently from response-parent IDs. Honor connection affinity and provider-documented concurrent-response limits; never assume all WebSocket implementations permit only one in-flight response or unlimited concurrency.
- **RT-005 [X, MUST] — Steering semantics.** Where mid-turn steering is supported, distinguish accepted, queued/pending, applied, rejected, and successor-response outcomes. An accepted control event is not proof the original generation changed. [OA-STEER]
- **RT-006 [X, MUST] — Ephemeral credentials.** Support dedicated short-lived session credentials where documented; expose expiry and allowed session scope. Do not distribute ordinary server API keys to browser or untrusted frontend clients.
- **RT-007 [X, MUST] — Bounded realtime buffering.** Apply separate media/event queue limits and an explicit overflow/latency policy. Arbitrarily dropping tool, state, or authorization events is never an acceptable low-latency optimization.

## 19. Usage, prices, limits, quotas, and accounting

### 19.1 Usage model

- **USAGE-001 [C, MUST] — Preserve raw and normalized usage.** Retain provider-native counters plus a documented normalized interpretation. Record whether values are final, provisional, cumulative, incremental, estimated, or unavailable.
- **USAGE-002 [C, MUST] — Avoid double counting.** Identify subset relationships: cached input can be included in total input; reasoning can be included in total output; media token categories may overlap broader totals. Do not blindly sum every reported counter.
- **USAGE-003 [C, MUST] — Attempt-aware accounting.** Track usage for every dispatched attempt, fallback, repair, token-count request where metered, hosted-tool call, batch item, and optional preprocessing operation. A logical run's cost includes failed or discarded attempts when billed.
- **USAGE-004 [C, MUST] — Incomplete usage remains incomplete.** Stream cancellation or failure may prevent final usage reporting. Preserve what is known and mark the remainder unknown; do not report missing usage as zero.
- **USAGE-005 [P, MUST] — Native unit preservation.** Support input/output/cache-write/cache-read/reasoning/media tokens, characters, requests, images, audio/video duration, search/tool calls, storage, and other provider-defined units without inventing text-token equivalents.
- **USAGE-006 [C, SHOULD] — Aggregation views.** Provide caller-controlled statistics by run, attempt, provider, endpoint, route, model/deployment, operation, tenant, date range, and cache category. The core may expose events rather than require a built-in database.

### 19.2 Price model

A price is a rate with context, not simply `inputPrice` and `outputPrice`.

| Dimension | Required capacity |
|---|---|
| Units | Per token or per N tokens, request, image, second/minute, character, tool call, storage unit/time. |
| Variants | Model/revision, provider route, region, service tier, batch discount, modality, resolution/quality, long-context bracket. |
| Cache | Read, write, TTL-dependent write, storage, ordinary input. |
| Time | Effective interval, retrieval time, price snapshot version; historical rates must remain immutable. |
| Provenance | Published list rate, account-specific contract, gateway reported charge, SDK estimate, reconciled billing. |
| Money | Currency, decimal precision, published tax/fee inclusion if known, optional explicit conversion reference. |
| Uncertainty | Missing rate, incomplete usage, unknown final route, variable surcharge, minimum billing unit. |

- **PRICE-001 [C, MUST] — Decimal arithmetic.** Use decimal money/rate arithmetic and explicit units; never binary floating point for billing totals. Preserve provider decimal strings and rounding rules where available.
- **PRICE-002 [C, MUST] — Rate snapshots.** Bind estimates/attributions to a price snapshot and actual route when known. A later catalog refresh must not silently rewrite historical estimated costs without producing a new calculation version.
- **PRICE-003 [C, MUST] — Distinguish estimates from charges.** Separately represent preflight estimate/range, usage-derived estimate, provider-reported charge, and reconciled invoice/account cost. Agreement is not guaranteed.
- **PRICE-004 [C, MUST] — Unknown is not free.** Distinguish a published zero rate, promotional allowance, missing price, and “not billed by this SDK.” Local inference can have zero provider API charge while still consuming hardware/energy resources; do not invent their cost.
- **PRICE-005 [C, MUST] — Conditional pricing.** Apply documented route/tier/context/modality/cache/tool rules. A model catalog's cheapest advertised endpoint price is not necessarily the charge for a routed invocation.
- **PRICE-006 [C, SHOULD] — Explicit cost provenance.** Present currency, rate source/time, included dimensions, excluded/unknown dimensions, and known usage completeness with every material cost result.

A safe general calculation is:

```text
estimated_cost = Σ over disjoint billable categories k:
                   billable_quantity(k) × rate(k, route, tier, time, conditions)
```

For an illustrative provider where cached input is a subset of input tokens and reasoning is already included in output tokens:

```text
ordinary_input = total_input - cached_input
estimated_token_cost = ordinary_input × input_rate
                     + cached_input × cache_read_rate
                     + total_output × output_rate
```

This is **not** a universal provider formula. Cache writes, modality-specific rates, long-context tiers, hosted tools, storage, and minimum billing increments may require other categories. Validate units and subset relationships before applying any formula.

### 19.3 Context, quotas, and budgets

- **LIMIT-001 [C, MUST] — Separate limits.** Distinguish architectural context size, service input limit, combined input/output budget, generated-token cap, reasoning allocation, configured local capacity, and application reservation. “Context window” must not imply the same usable prompt size everywhere.
- **LIMIT-002 [P, MUST] — Token counting scope.** Support native counting endpoints or optional tokenizer integrations where available. Return exact-provider-count, exact-local-tokenizer-count under stated assumptions, estimate, or unavailable; include tools, templates, schemas, and modalities according to the actual counting contract.
- **LIMIT-003 [C, MUST] — Budget checks are bounded claims.** Validate known context and cost limits conservatively. A preflight estimate cannot guarantee the final invoice or a global spending cap when the provider lacks a matching enforceable limit.
- **LIMIT-004 [C, MUST] — Scoped quotas.** Represent request/token/concurrency quotas, remaining values, reset time, retry delay, and scope: account, project, model, route, region, or operation. Missing headers are not unlimited capacity.
- **LIMIT-005 [X, MUST] — Coordinated budgets.** Optional shared budget enforcement must reserve/reconcile concurrent work and account for retries. In-process counters alone must not be advertised as distributed multi-instance enforcement.
- **LIMIT-006 [C, MUST] — Controlled admission.** Support bounded queues, per-scope concurrency, total deadlines, and caller-visible rejection when exhausted. Never create an unbounded queue of requests waiting for quota.
- **LIMIT-007 [C, SHOULD] — UI-friendly distinctions.** Expose “not published,” “not accessible,” “not configured,” “known remaining,” and “explicitly unbounded” separately. A screenshot or stale catalog is not authoritative account or model information.

## 20. Errors, retries, idempotency, routing, and resilience

### 20.1 Error information

A useful error includes: stage; normalized category; native code/type/message; HTTP status; safe response headers; provider request/response IDs; retry hints; attempt number; partial-output reference; known usage; cause; cancellation status; and whether remote execution may have occurred. Raw diagnostic bodies must be bounded and access-controlled.

| Error category | Examples to distinguish |
|---|---|
| Local validation | Unsupported parameter, invalid schema, context estimate overflow, wrong file scope. |
| Authentication/authorization | Expired token, invalid key, audience mismatch, forbidden model/workspace. |
| Account/limits | Billing restriction, quota exhaustion, rate limit, concurrency rejection. |
| Transport | DNS, TLS, proxy, connection timeout, upload interruption, read timeout. |
| Provider execution | Overload, internal failure, model loading/unavailable, failed hosted tool. |
| Protocol | Malformed event, unknown mandatory behavior, truncated stream, inconsistent item state. |
| Content outcome | Refusal, content filtering, incomplete generation, length limit; not necessarily a transport failure. |
| Application consumption | Decoder/validator failure, observer failure under configured policy, explicit cancellation. |
| Ambiguous completion | Request may have executed, but final response or usage is unavailable. |

- **ERR-001 [C, MUST] — Structured error contract.** Preserve native detail while providing stable machine-readable categories and execution-stage/outcome certainty. Do not require users to parse English error strings.
- **ERR-002 [C, MUST] — Preserve partial truth.** A provider error after output begins must carry partial result and usage state, not erase the successful portion or imply complete success.
- **ERR-003 [C, MUST] — Safe diagnostics.** Redact credentials, signed URLs, private payloads, and token-exchange bodies from exceptions by default. Provide a separate explicitly authorized diagnostic path for sensitive raw details.

### 20.2 Retry and idempotency policy

- **RETRY-001 [C, MUST] — Single retry owner.** Define whether the SDK, underlying provider SDK, transport, or gateway owns retries. Disable or coordinate nested retries so effective attempts, latency, and costs remain bounded and visible.
- **RETRY-002 [C, MUST] — Stage-aware decisions.** Consider method/operation semantics, request-body replayability, provider idempotency support, evidence of acceptance, emitted output, error category, and remaining budget. An HTTP status alone is insufficient.
- **RETRY-003 [C, MUST] — Conservative ambiguity.** Default against automatically replaying an inference/upload/job submission when remote execution may already have occurred and no reliable idempotency mechanism exists. An opt-in duplicate-risk policy must disclose possible extra charges or side effects.
- **RETRY-004 [P, MUST] — Real idempotency only.** Use provider idempotency keys only on operations documented to support them, with their scope and retention rules. Reusing a client request ID is not proof of idempotency.
- **RETRY-005 [C, MUST] — No invisible stream restart.** After user-visible semantic output or a tool side effect, do not concatenate a fresh retry into the same logical response as though it were continuation. Expose a new attempt or use documented resumption.
- **RETRY-006 [C, MUST] — Bounded delays.** Honor documented retry hints, including delay or date forms where applicable; use capped backoff/jitter within the total deadline and maximum attempts/cost budget. Cancellation must interrupt waiting.
- **RETRY-007 [C, MUST] — Retry categories.** Authentication errors, invalid schemas, unsupported fields, context overflow, and content refusals must not enter generic transient-error retry loops. Credential renewal or explicit repair is a different action.
- **RETRY-008 [C, SHOULD] — Recovery diagnostics.** Report why an attempt was or was not retried, which policy authorized it, and the resulting configuration/route/body identity.

### 20.3 Routing, fallback, and circuit breaking

- **ROUTE-001 [X, MUST] — Explicit routing policy.** Support route preferences/requirements for capabilities, allowed providers, region, retention/data-use policy, latency, cost, and availability. Never override a hard privacy or capability constraint for availability.
- **ROUTE-002 [X, MUST] — Fallback revalidation.** Revalidate tools, schemas, media, context, reasoning continuity, file handles, credentials, and budget on a fallback. Changing a model/provider is not a transport-only retry.
- **ROUTE-003 [X, MUST] — Actual route reporting.** Preserve requested route, gateway decision hints, and actual provider/model/region when reported. An unknown upstream must remain unknown.
- **ROUTE-004 [X, MUST] — Scoped resilience.** Partition circuit breakers and rate controllers by relevant endpoint/account/model/operation. One tenant's invalid credential must not trip a global provider circuit; quota rejection need not indicate provider outage.
- **ROUTE-005 [X, MUST] — Hedging opt-in.** Parallel speculative requests may multiply spend and side effects. Keep hedging off by default, restrict eligible operations, and account for all dispatched work even when one result is discarded.
- **ROUTE-006 [X, SHOULD] — Minimal first implementation.** Begin with explicit ordered fallback and bounded retries; advanced adaptive routing should be optional and justified by measured benefit, not required for basic connectivity.

## 21. Security, privacy, and tenant isolation

### 21.1 Network and resource boundaries

- **SEC-001 [C, MUST] — Policy-controlled destinations.** Validate endpoint, attachment, redirect, OAuth-discovery/token, schema-reference, and generated-artifact destinations. A proxy receiving untrusted input must not become an unrestricted network fetcher.
- **SEC-002 [C, MUST] — Local access without universal SSRF bypass.** Permit explicitly configured localhost/private inference services, while preventing untrusted request fields from selecting arbitrary loopback, link-local, cloud-metadata, or internal destinations. Evaluate redirects and resolved addresses under the same policy.
- **SEC-003 [C, MUST] — Honest enforcement boundary.** State whether network restrictions are enforced before dispatch, at connection resolution, or by a trusted network layer. Do not promise DNS-rebinding protection if the selected transport cannot enforce it. Provider-side URL fetching requires separate policy; local checks do not control the provider's network.
- **SEC-004 [C, MUST] — Credential isolation.** Never forward authorization to a new origin automatically; scope pooled authentication/certificates appropriately. Prevent credential leakage through proxy headers, query strings, upload redirects, or native extension fields.
- **SEC-005 [C, MUST] — Filesystem isolation.** Reading a local attachment or credential file must use explicit caller authorization and allowed paths. Protect temporary files, symlink/path traversal boundaries, and cleanup; never infer filesystem permission from model output.

### 21.2 Data handling

- **SEC-006 [C, MUST] — Private by default diagnostics.** Disable prompt, completion, reasoning, tool arguments/results, file content, and secret capture in routine logs/traces. Redacted operational metadata should be sufficient for ordinary diagnostics.
- **SEC-007 [C, MUST] — Tenant-aware state.** Partition credentials, private metadata, exact-result caches, file/job/session mappings, retry budgets, and trace access. Redaction is not a substitute for authorization.
- **SEC-008 [C, MUST] — Retention controls are specific.** Distinguish request storage flags, provider abuse/security retention, application logs, uploaded files, explicit caches, conversation resources, and generated artifacts. A single “privacy mode” must resolve to visible enforceable controls plus unresolved limitations. [OA-DATA]
- **SEC-009 [C, MUST] — No compliance overclaim.** Region selection, storage flags, TLS, or a provider marketing label do not by themselves certify legal compliance. Expose relevant facts and enforce configured policy; leave legal and organizational decisions outside the SDK.
- **SEC-010 [C, MUST] — Untrusted content stays data.** Model output, tool descriptions/results, model cards, provider error messages, remote schemas, and media metadata must not alter credential selection, invoke code, load classes, or change security policy.
- **SEC-011 [C, MUST] — Defensive parsing.** Bound JSON/multipart/event sizes and nesting; reject malicious field/header names; prevent decompression bombs and log injection. Native escape hatches do not bypass these protections.
- **SEC-012 [X, MUST] — Trusted extension boundary.** Code-loading, executable credential helpers, local process launch, JNI, media tools, and plugins require explicit trust/allowlisting. Never execute commands from a downloaded model descriptor or configuration file by default.
- **SEC-013 [C, SHOULD] — Audit trail without content leakage.** Record principal/scope, operation, policy decisions, destinations, resources, approvals, and outcomes using safe identifiers. Hashes of sensitive prompts can still reveal information and need their own policy.
- **SEC-014 [C, MUST] — Secret lifetime honesty.** Minimize secret copies and retention, but do not promise guaranteed memory zeroization for ordinary immutable Java strings or JVM-managed objects.

## 22. Observability and diagnostics

### 22.1 Events, traces, metrics, and logs

- **OBS-001 [C, MUST] — Stable observation hooks.** Expose configuration resolution, preparation, auth acquisition outcome, dispatch, headers, stream lifecycle, retries, route changes, usage, artifacts, and completion as structured events with explicit sensitivity classification.
- **OBS-002 [C, MUST] — Run/attempt separation.** Correlate a logical operation with all network attempts and provider IDs. Distinguish user-request latency from individual provider-attempt latency.
- **OBS-003 [C, MUST] — Meaningful timing definitions.** Measure queue wait, credential wait, upload, time to headers, first event, first visible text/media, stream duration, tool wait, and total completion where observable. A heartbeat is not a first generated token.
- **OBS-004 [C, MUST] — Unit and clock correctness.** Use monotonic time for durations and wall time for timestamps; retain provider units when converting counters such as runtime nanoseconds. Do not infer generation speed from total request time without labeling the measurement.
- **OBS-005 [C, MUST] — Safe, bounded telemetry.** Redact before exporter/logging hooks by default; cap payload sizes and metric cardinality. Do not put raw prompts, API keys, signed URLs, request IDs, or arbitrary model/user strings into unbounded metric labels.
- **OBS-006 [X, SHOULD] — OpenTelemetry integration.** Provide an optional adapter using pinned semantic-convention versions and W3C trace propagation under an allowlist. GenAI convention evolution must not force breaking changes to core result types. [OTEL-GENAI] [STD-TRACE]
- **OBS-007 [C, MUST] — Observation is not mutation.** Separate read-only observers from authorized request/response transformation hooks. Define ordering and failure isolation; an exporter failure must not silently alter the outbound request.
- **OBS-008 [X, MUST] — Controlled capture/replay.** Optional diagnostic recording must be opt-in, encrypted/access-controlled as appropriate, redacted by policy, size-bounded, and expiry-controlled. Replay fixtures must exclude live credentials and avoid accidental real network calls.

### 22.2 Useful diagnostics for applications and node workflows

Expose safe summaries of the selected protocol/profile, resolved endpoint, redacted credential source, metadata freshness, requested/sent settings, transformations, payload sizes, model/route observations, cache counters, usage completeness, estimated/reported charges, warnings, and exact terminal outcome. Offer a machine-readable diagnostic report; do not require applications to scrape log text.

## 23. Proxy and gateway construction

### 23.1 Three gateway modes

| Mode | Contract |
|---|---|
| Opaque passthrough | Forward authorized payload bytes/streams with controlled transport/header changes; do not claim semantic understanding. |
| Native-aware passthrough | Parse enough of the same protocol for policy/observation while preserving supported native data and events. |
| Semantic translation | Decode source protocol, map to a target operation, apply explicit transformations, encode target request, translate result/events with a loss report. |

A client-only `ProviderAdapter.send()` interface is insufficient for a reusable translating gateway. The gateway also needs ingress request decoding, egress result/event encoding, resource identity mapping, and clear unsupported-feature behavior.

- **GW-001 [X, MUST] — Reusable bidirectional codecs.** For each supported gateway protocol, expose request decoding/encoding and response/event decoding/encoding independently of network transport. State which directions and operations are actually implemented.
- **GW-002 [X, MUST] — Explicit translation limits.** Detect features without an equivalent target representation: hosted tools, signed reasoning, server state, citations, realtime controls, candidate semantics, schemas, or multimodal parts. Reject or obtain explicit permission for a documented lossy conversion.
- **GW-003 [X, MUST] — Native extension preservation.** Same-protocol passthrough should preserve unknown fields/events where safe. Cross-protocol translation must not re-label opaque target-specific fields as portable equivalents.
- **GW-004 [X, MUST] — Inbound and outbound identity separation.** Authenticate the caller independently from selecting upstream credentials. Never treat a caller's arbitrary `Authorization` header as permission to use another tenant's provider account.
- **GW-005 [X, MUST] — Resource namespace mapping.** Scope and map provider file, job, response, conversation, cache, and tool IDs. Prevent a caller from retrieving or continuing another tenant's upstream resource by guessing its ID.
- **GW-006 [X, MUST] — Header/status correctness.** Filter hop-by-hop headers and unsafe forwarding headers; recompute content length/encoding and signatures after mutation. Preserve safe provider request IDs and rate hints without leaking internal credentials or routing metadata. [STD-HTTP]
- **GW-007 [X, MUST] — Stream commitment handling.** Once downstream headers/output are committed, failures must use the downstream protocol's valid terminal-error representation or terminate as explicitly incomplete. Do not attempt to change an already-sent HTTP status or fabricate a success terminal event.
- **GW-008 [X, MUST] — Backpressure and disconnects.** Propagate downstream cancellation upstream where possible; bound translation buffers; distinguish client disconnect from provider failure. A disconnected downstream does not prove the upstream stopped executing.
- **GW-009 [X, MUST] — Transformation pipeline.** Apply content transformation before final validation, token estimation, cache-key calculation, serialization, and request signing. Recheck policy after transformations; record original/transformed fingerprints and a safe transformation report.
- **GW-010 [X, MUST] — Compression boundaries.** Distinguish reversible wire compression, syntactic JSON changes, semantic prompt shortening, context compaction, and result caching. Never call semantic shortening lossless merely because a compressor preserved readable prose.
- **GW-011 [X, MUST] — Preservation constraints.** Do not modify signed/encrypted reasoning, tool-call correlation, instruction precedence, schemas, file references, or native state without a proven compatible operation. A compression hook must not bypass these restrictions.
- **GW-012 [X, MUST] — Translation accounting.** Attribute added model calls, uploads, token counts, transformations, retries, and gateway fees separately. The downstream price/usage contract must explain whether it reports upstream usage, gateway metering, or both.
- **GW-013 [X, SHOULD] — Framework neutrality.** Supply gateway-building primitives rather than require Spring, Netty, a database, or an embedded server. Server integrations can wrap the same core codecs/policies.
- **GW-014 [X, MUST] — Capability advertisement honesty.** A gateway's own model/capability endpoint must describe the effective supported translation contract, not copy all capabilities from upstream models it cannot expose faithfully.

### 23.2 Recommended request-processing order

```text
authenticate inbound caller (gateway only)
→ resolve tenant and endpoint/profile policy
→ decode request with bounded parsing
→ select candidate operation/model/route
→ prepare and authorize transformations/resources
→ apply approved transformations
→ validate resulting capabilities, data policy, limits, and budget
→ obtain destination-scoped credentials
→ serialize and sign the actual outbound request
→ dispatch with bounded retry/cancellation policy
→ decode/translate stream incrementally
→ reconcile terminal result, usage, artifacts, and cleanup
```

Some steps may need iteration, such as uploads followed by final request validation. Iteration must be bounded and must not conceal additional billable calls. Observability should surround the pipeline without becoming an uncontrolled mutation layer.

## 24. Local inference and specialized operations

### 24.1 Local runtime requirements

Local serving is an important first-class profile, not merely a cloud API with the hostname changed. Runtime information and administrative authority can differ substantially from public provider metadata.

- **LOCAL-001 [P, MUST] — Effective runtime facts.** Discover or accept explicit information about server build/version, loaded model/tag/digest, quantization, tokenizer/template, configured context, parallel slots, and available operations. Distinguish observed configuration from architectural model capacity.
- **LOCAL-002 [P, MUST] — Native and compatibility separation.** Support useful native inspection/generation features without assuming the runtime's compatibility endpoints expose the same data or behavior. Maintain build/version-dependent manifests.
- **LOCAL-003 [P, MUST] — Readiness states.** Distinguish unreachable, starting, model loading, ready, busy, sleeping/unloaded, and failed where the runtime exposes them. A health endpoint may be reachable before inference is ready.
- **LOCAL-004 [P, MUST] — Residency versus HTTP keep-alive.** Keep model memory-residency settings distinct from connection pooling and socket keep-alive. Do not treat a model unload as an HTTP connection failure.
- **LOCAL-005 [X, MUST] — Separate administration.** Model pull/create/copy/delete, loading/unloading, slot operations, runtime mutation, and process launch require explicit administrative capability and consent. A model dropdown must never automatically download multi-gigabyte weights.
- **LOCAL-006 [C, MUST] — Local origin is not an egress guarantee.** A localhost server may proxy to a cloud service. “Local-only” policy requires a known serving route/runtime configuration, not simply a loopback URL. Ollama documents local use of cloud-backed models. [OL-AUTH]
- **LOCAL-007 [X, MUST] — Embedded backend boundary.** In-process/native/JNI integrations need a separate backend contract for loading, memory, thread safety, crashes, native resources, and cancellation. HTTP clients should not be forced to depend on native libraries.
- **LOCAL-008 [P, SHOULD] — Tokenization services.** Where native tokenize/detokenize operations exist, expose them with tokenizer/build identity and special-token/template options. Do not assume their token count exactly matches a differently templated chat request.

**Runtime examples.** llama.cpp's server documentation describes native runtime inspection and server-configuration-dependent behavior. Ollama exposes its own chat options and timing/usage metadata. These are useful native extensions, not universal cloud fields. [LC-SERVER] [OL-CHAT]

### 24.2 Specialized operation families

| Operation | Additional requirements beyond ordinary generation |
|---|---|
| Embeddings | Single/batched input, returned index mapping, dimensions, output encoding, model/revision, truncation policy, usage; preserve numeric precision and native metadata. |
| Reranking | Query/documents, top-N, result index/score, truncation, optional returned documents; scores are provider/model-specific and not automatically calibrated probabilities. |
| Moderation/classification | Categories, thresholds/configuration where allowed, scores, model version, blocked/result status; keep separate from generative refusal. |
| Token counting | Exact input representation, tools/media/schema inclusion, tokenizer/profile/version, estimate versus authoritative count. |
| Raw completion/FIM | Prefix/suffix/template contract, continuation, token-level constraints, native prompt mode; do not reinterpret as ordinary chat automatically. |
| Images/audio/video | Dedicated request/output/resource contracts from Sections 15 and 18. |
| File search/vector resources | Optional resource creation/indexing/search/lifecycle integration; no mandatory vector database or retrieval pipeline in the core. |
| Managed agent services | Optional provider service adapter preserving its jobs/tools/resources; not the universal inference abstraction. |
| Fine-tuning/deployment administration | Separate optional control-plane modules with privileged operations and independent lifecycle. |

- **OP-001 [C, MUST] — Operation-level capability discovery.** Advertise operations independently. A model supporting text generation does not thereby support embeddings, image generation, reranking, or native token counting.
- **OP-002 [X, MUST] — Specialized contracts.** Each extension must specify request/result types, streaming/job behavior, usage units, supported batch semantics, limits, and native escape hatches. Do not cram unrelated operations into one enormous request DTO.
- **OP-003 [X, MUST] — Embedding identity.** Preserve model/deployment/revision, dimensions, input ordering, and normalization information when known. Cross-model vector comparability must never be assumed by the transport SDK.

### 24.3 Operation/command vocabulary for facade design

These are semantic operation names, **not proposed final Java method names and not universal HTTP paths**:

| Domain | Commands to consider |
|---|---|
| Endpoint | Validate configuration; check reachability/authentication; inspect service metadata; close owned resources. |
| Credentials | Begin/complete authorization; acquire/renew credential; inspect redacted status; revoke where supported. |
| Models | List/filter/get/refresh descriptors; resolve alias/deployment; inspect effective capabilities. |
| Requests | Prepare/validate/count/estimate; generate; stream; cancel; obtain final or partial result. |
| Stateful resources | Create/get/list/update/delete conversation or response where supported; continue/fork; compact explicitly. |
| Tools | Declare/select; receive calls; supply results; inspect hosted execution; approve when supported. |
| Files/artifacts | Plan/materialize/upload; inspect/readiness; retrieve/download; list/delete; cleanup. |
| Caches | Inspect local metadata cache; refresh/invalidate; create/reference/expire/delete explicit provider cache where available. |
| Jobs/batches | Submit/get/poll/cancel; enumerate items; retrieve outputs/errors; resume observation. |
| Realtime | Connect/configure/send/commit/interrupt/steer/close according to the selected protocol. |
| Usage/accounts | Inspect quotas/balances where authorized; retrieve usage/cost; aggregate local execution statistics. |
| Local administration | Inspect runtime; optionally pull/load/unload/delete models or manage supported runtime resources. |
| Gateway primitives | Decode/validate/translate/encode; authorize route; stream forward; map resources; emit supported errors. |

## 25. Java API quality and implementation constraints

### 25.1 API ergonomics

- **JAVA-001 [C, MUST] — Plain Java usability.** Provide ordinary Java value types and a small coherent entry point without requiring Spring, a DI container, Reactor, Kotlin, an agent framework, or a database.
- **JAVA-002 [C, SHOULD] — Explicit JDK baseline decision.** Choose the minimum JDK in an architecture decision record. Java 21 is a reasonable candidate for a modern implementation using standard HTTP/concurrency facilities; this document does not claim it is the latest JDK or mandate it over another supported baseline. [JAVA-HTTP] [JAVA-FLOW]
- **JAVA-003 [C, MUST] — Clear async/cancellation contract.** Expose asynchronous completion and a documented cancellable invocation handle. `CompletionStage` by itself does not define upstream cancellation, so cancellation ownership must not be implicit.
- **JAVA-004 [C, SHOULD] — Standard reactive boundary.** Consider `Flow.Publisher` or a similarly minimal streaming abstraction; make Reactor/Rx/Kotlin adapters optional. Specify demand, single/multi-subscriber behavior, terminal delivery, and callback threading. [JAVA-FLOW]
- **JAVA-005 [C, MUST] — Safe shared clients.** State thread-safety for clients, configuration, builders, requests, streams, credentials, and observers. Immutable shared state must be the default; mutable stream/request state must be per invocation.
- **JAVA-006 [C, MUST] — Resource ownership.** Implement explicit close semantics for owned clients/sessions/streams. Closing a derived model handle must not unexpectedly close a shared transport; caller-owned executors/transports remain caller-owned.
- **JAVA-007 [C, MUST] — Extensible data modeling.** Use typed common fields with a deliberate native/unknown variant. Avoid a sealed set of provider values that breaks whenever a provider adds an event or effort label; also avoid an untyped `Map<String,Object>` as the only public API.
- **JAVA-008 [C, MUST] — Precision and absence.** Use `Duration`, `Instant`, `URI`, decimal money, sufficiently large counters, and explicit absent/null/value semantics where needed. Java primitives must not force unknown limits or prices to zero.
- **JAVA-009 [C, SHOULD] — Small overload surface.** Prefer coherent builders/presets and a few convenient entry points over hundreds of overloads. Keep common text use easy while allowing complete typed results and native requests.
- **JAVA-010 [C, MUST] — Library citizenship.** Never call `System.exit`, install unexpected global handlers, mutate system properties, scan unrelated credential files, or leave non-daemon worker threads running after close. Do not require ambient global mutable configuration.
- **JAVA-011 [C, SHOULD] — Documentation as examples.** Provide complete examples for direct provider, local server, OAuth/WIF, streaming/cancellation, tools, structured output, attachments, metadata-only UI, and gateway translation. Examples should use the same safe defaults as production code.

### 25.2 Dependency and implementation strategy

- **JAVA-012 [C, SHOULD] — Minimal dependency graph.** Keep cloud identity, provider SDKs, advanced schema validation, telemetry, native bindings, and server frameworks optional. Publish a dependency inventory and justify every core runtime dependency.
- **JAVA-013 [C, MUST] — Public API stability.** Define semantic versioning, deprecation, binary/source compatibility goals, serialized format versions, and extension namespace rules. Provider schema evolution must not automatically dictate breaking facade changes.
- **JAVA-014 [C, SHOULD] — Generated wire types behind stable contracts.** Code generation from official schemas can help native coverage, but generated classes should not dictate the entire user API. Preserve unknown fields and supplement generated code with semantic rules and stream tests.
- **JAVA-015 [C, MUST] — Testable infrastructure.** Allow injection of transport, clock, scheduler, credential source, and deterministic IDs where useful. Avoid building a large framework merely to provide test seams.
- **JAVA-016 [C, SHOULD] — Measure implementation cost.** Benchmark dependency size, startup, allocations, decode throughput, cancellation latency, connection reuse, and large-stream memory. Prefer simpler designs unless added abstraction yields measurable correctness or usability benefits.

### 25.3 Build directly, wrap official SDKs, or combine?

Do not choose one approach dogmatically. Official SDKs can reduce maintenance for cloud auth and fast-changing native APIs; direct codecs can offer tighter dependency control, raw fidelity, gateway reuse, and uniform streaming. Evaluate each adapter against cancellation, unknown-field preservation, retries, request inspection, auth, dependency footprint, and bidirectional codec needs.

**Decision rule:** use an official SDK internally when it satisfies the public contract without hidden retries, mandatory orchestration, or information loss. Implement a direct adapter where those constraints cannot be met economically. Avoid simultaneously maintaining two independent semantic implementations for the same operation without a clear reason.

## 26. Node-based workflow and UI requirements

The supplied screenshot motivates reusable endpoint/model/parameter/file/invocation nodes, not a requirement to reproduce its particular UI or trust its displayed values.

### 26.1 Descriptor-driven UI

- **UI-001 [C, SHOULD] — Separate writable configuration from readouts.** Distinguish endpoint/model selection and requested parameters from observed model facts, provider limits, prices, credentials status, and runtime usage. Editing a display value must not rewrite authoritative metadata.
- **UI-002 [C, SHOULD] — Parameter form descriptors.** Expose labels/descriptions, types, units, ranges/enumerations, default knowledge, grouping, applicability conditions, sensitive/read-only flags, and advanced/native status. Keep these descriptors framework-neutral.
- **UI-003 [C, MUST] — Honest status values.** A form must be able to display inherit, explicit value, unknown, unsupported, stale, inaccessible, and changed-from-preset. A two-state checkbox is insufficient for several of these concepts.
- **UI-004 [C, MUST] — Model identity consistency.** Bind metadata, pricing, and capability displays to the selected endpoint/model/route snapshot. Changing the model or endpoint must invalidate mismatched readouts rather than display a previous model's limits beside a new selection.
- **UI-005 [C, SHOULD] — Typed graph values.** Expose distinct values for endpoint handle, model handle/reference, descriptor, generation settings, file sources, prepared attachments, request, event stream, result, usage, and diagnostics. Compatibility checks should use type/capability data rather than node labels.
- **UI-006 [C, MUST] — No execution on rendering.** Opening an inspector, changing node width, subscribing to metadata, or redrawing a graph must not dispatch inference or repeat uploads. Make refresh and execution separate graph actions.
- **UI-007 [C, MUST] — Explicit fan-out semantics.** “All files in one request,” “one request per file,” “batched provider job,” and “concurrent requests” are different execution plans with different cost, context, and failure behavior. Expose counts and budgets before execution.
- **UI-008 [C, MUST] — Secret-free workflow export.** Persist credential references, not raw tokens/API keys. Mark graphs/transcripts containing prompts, attachments, or results as potentially sensitive even after credential removal.
- **UI-009 [C, SHOULD] — Execution visualization.** Provide real lifecycle/tool/upload/route/cache/usage events for progress views. Display reasoning summaries only when returned and allowed; use “waiting/processing” for unobserved work rather than fabricated internal thinking steps.
- **UI-010 [C, MUST] — Numeric and unit boundaries.** Parse localized input in the UI layer, then send typed locale-neutral values to the SDK. Display token versus byte limits, price per unit, and time units explicitly.

### 26.2 Minimal graph composition

```text
Endpoint configuration + credential reference
    → model selection and metadata snapshot
    → request settings + explicit attachment plan
    → capability validation / execution preview
    → invocation handle
        → event stream
        → final/partial structured result
        → usage/cost/diagnostics
```

Metadata retrieval may occur independently of invocation. Shared model/client handles should not share mutable per-run state. A workflow engine owns scheduling and graph recomputation; the transport SDK owns the correctness of each requested operation.

## 27. Standards and wire-format registry

This registry distinguishes formal standards, conventions, and provider protocols. Supporting one entry does not automatically imply all related optional extensions. Pin versions and document tested subsets.

| Area | Standard/format | SDK requirement |
|---|---|---|
| HTTP | RFC 9110 semantics; actual HTTP version handled by transport | Correct methods/status/headers/redirects and streaming; explicit timeout/retry contract. [STD-HTTP] |
| TLS | TLS 1.3 reference, with deployment-appropriate secure compatibility policy | Verified server identity; managed trust/client certificates; no blanket trust-all. [STD-TLS] |
| JSON | RFC 8259, UTF-8 | Bounded parser, precision, safe duplicate-key policy, unknown-field preservation. [STD-JSON] |
| Multipart uploads | RFC 7578 `multipart/form-data` | Correct boundaries/filenames/content types and streaming limits. [STD-MULTIPART] |
| SSE | WHATWG server-sent event format, `text/event-stream` | Event grammar, comments, multiline data, UTF-8 boundaries; provider POST streaming is not browser EventSource behavior. [STD-SSE] |
| NDJSON/JSONL | Line-delimited JSON conventions; exact MIME/profile varies | Separate record decoder; documented blank-line/final-record policy; used by native runtime streams and batch files. [OL-STREAM] |
| WebSocket | RFC 6455 | Frames/close/control behavior through transport; provider application protocol separately implemented. [STD-WS] |
| AWS event stream | Bedrock operation's binary event-stream contract | Dedicated decoder/signing/error handling, not an SSE parser. [AWS-STREAM] |
| Binary/media | MIME types, base64/data URI representations, provider codecs/containers | Explicit representation and accepted-format descriptors, bounded decoding. |
| JSON Schema | Selected dialect such as 2020-12, plus provider subsets | Do not equate standard schema validity with provider acceptance. [STD-SCHEMA] |
| OpenAPI | Provider-published exact version/dialect | Useful for generation/documentation; semantic streaming/auth rules still require tests. Current specification page identifies v3.2.1. [STD-OPENAPI] |
| OAuth security | RFC 9700 security best current practice | Secure redirect/transaction handling and least-privilege grants. [STD-OAUTH-BCP] |
| PKCE | RFC 7636 | S256 for supported public/native authorization-code flows. [STD-PKCE] |
| Native-app OAuth | RFC 8252 | External browser and appropriate callback model; no embedded shared client secret. [STD-NATIVE] |
| Device authorization | RFC 8628 | Polling state machine, expiry/slowdown/cancellation. [STD-DEVICE] |
| Token exchange | RFC 8693 where adopted; provider-specific deviations | Correct subject/audience/result credential semantics and renewal. [STD-EXCHANGE] |
| OAuth discovery | RFC 8414 authorization-server metadata | Verify issuer and endpoints; constrain discovery and client-authentication methods. [STD-AUTH-METADATA] |
| OAuth lifecycle | RFC 7009 revocation; RFC 7662 introspection | Optional authorized operations with separate policies; no universal support assumption. [STD-REVOCATION] [STD-INTROSPECTION] |
| Sender-constrained OAuth | RFC 9449 DPoP; RFC 8705 mTLS/certificate-bound tokens | Optional profiles preserving key/certificate binding and request-specific proofs. [STD-DPOP] [STD-OAUTH-MTLS] |
| Cloud identity/signing | ADC, Entra, IAM/SigV4, documented bearer modes | Separate optional profiles, not assumed interchangeable OAuth/API-key fields. [GO-ADC] [AZ-RESP] [AWS-SIG] |
| MCP | Explicit negotiated specification version | Separate tool/resource transport and authorization integration; reviewed version here is 2026-07-28. [MCP-TRANSPORT] [MCP-AUTH] |
| Tracing | W3C Trace Context | Safe outbound propagation with policy; no secret-bearing baggage by default. [STD-TRACE] |
| GenAI telemetry | OpenTelemetry GenAI semantic conventions | Optional, version-pinned mapping; preserve native metrics when no stable common meaning exists. [OTEL-GENAI] |
| Java concurrency | `CompletionStage`/cancellable operation contract; `Flow` if selected | Explicit completion, demand, threading, cancellation, and resource ownership. [JAVA-FLOW] |
| Native provider APIs | Responses, Messages, Interactions, GenerateContent, Chat Completions, local/native APIs | Versioned provider contracts, not one international LLM protocol standard. |

**STD-001 [C, MUST] — Implement the chosen contract, not a label.** A profile must specify its exact versions, optional extensions, known deviations, and tests. New standards, event types, auth flows, and schema keywords must be introduced deliberately rather than inferred from a provider's brand or endpoint path.

## 28. Conformance, testing, and acceptance criteria

### 28.1 Test strategy

- **TEST-001 [C, MUST] — Deterministic offline suite.** Use fake transports, clocks, credential sources, and captured sanitized fixtures. All correctness-critical tests must run without real credentials or internet access.
- **TEST-002 [P, MUST] — Profile contract tests.** Each advertised provider operation needs request-encoding, response/event-decoding, error, usage, capability, and native-preservation fixtures tied to a source/schema/build version.
- **TEST-003 [C, MUST] — Fragmentation and property tests.** Fuzz framing boundaries, UTF-8, interleaving, duplicate/snapshot events, parser limits, and unknown fields. Test invariants rather than only complete happy-path JSON bodies.
- **TEST-004 [X, MUST] — Controlled live tests.** Live smoke/conformance tests require explicit credentials, approved destinations/models, cost caps, cleanup, and opt-in execution. Results must record date, account/profile scope, and tested parameters; never auto-run them during a normal build.
- **TEST-005 [C, MUST] — No false certification.** Separate documentation-derived expectations, offline codec conformance, and live observed behavior. One smoke test does not certify every feature of an entire provider.
- **TEST-006 [C, SHOULD] — Regression and compatibility gates.** Test Java API compatibility, serialized transcript/config migrations, dependency footprint, thread/resource leaks, cancellation, and gateway round-trip behavior in release gates.

### 28.2 Acceptance scenarios

| ID | Scenario | Passing behavior |
|---|---|---|
| AC-01 | Construct client/request without executing | No unintended discovery, auth browser, upload, or inference traffic. |
| AC-02 | Model listing forbidden but known model supplied | Invocation remains possible under explicit capability uncertainty. |
| AC-03 | Missing price/context/capability | Unknown state retained; no zero/free/unlimited substitution. |
| AC-04 | Paginated model catalog fails mid-refresh | Partial/completeness/error status exposed; prior snapshot not falsely marked fresh. |
| AC-05 | Change endpoint after credential setup | Credential not forwarded to an unapproved origin. |
| AC-06 | Many requests hit token expiry simultaneously | Renewal coalesced; scopes/tenants remain isolated. |
| AC-07 | OAuth callback mismatched, expired, or replayed | Rejected before accepting credentials. |
| AC-08 | Device authorization slows down or is cancelled | Poll cadence honored; no background polling leak. |
| AC-09 | Token exchange yields API key rather than OAuth refresh pair | Correct credential result and renewal behavior exposed. |
| AC-10 | Unset, null, zero, false, and empty list in configuration | Values remain distinguishable and serialize per profile. |
| AC-11 | Same native field set twice via canonical and extension options | Explicit conflict, not silent overwrite. |
| AC-12 | Documented ignored parameter is requested | User informed/rejected according to strictness; not marked applied. |
| AC-13 | Gateway selects route lacking required tools/schema/privacy | Route prevented or invocation rejected, not silently downgraded. |
| AC-14 | UTF-8/SSE event split at every possible byte boundary | Exact equivalent decoded event sequence. |
| AC-15 | Native NDJSON stream sent to selected local profile | Correct record parsing, final usage, terminal state. |
| AC-16 | Interleaved text, two tools, reasoning, and multiple candidates | Correct per-item assembly and correlation. |
| AC-17 | Completed-item snapshot repeats previously streamed text | No duplication in final result. |
| AC-18 | HTTP 200 followed by provider error event | Failed/incomplete outcome with partial result and native error. |
| AC-19 | EOF before required terminal event | Incomplete/protocol failure, never ordinary success. |
| AC-20 | Slow subscriber and very large output | Bounded memory or explicit configured overflow failure. |
| AC-21 | Subscriber cancels during upload/read/retry wait | Local resources promptly released; remote outcome reported honestly. |
| AC-22 | Second subscriber asks for final result | No second billable dispatch unless explicitly requested. |
| AC-23 | Unknown optional event or response field | Preserved safely; known output still available. |
| AC-24 | Tool arguments are partial or invalid JSON | Never auto-executed; incomplete/invalid state exposed. |
| AC-25 | Retry after completed side-effecting tool | Tool not replayed automatically. |
| AC-26 | Signed/encrypted reasoning passes through supported native flow | Opaque value and required associations unchanged. |
| AC-27 | Transcript converted to another provider | Nonportable state and semantic loss reported/rejected. |
| AC-28 | JSON Schema unsupported keyword or refusal | Unsupported constraint/refusal distinguished from decode failure. |
| AC-29 | Cross-tenant provider file ID supplied | Rejected by resource scope policy. |
| AC-30 | Expired upload or processing not complete | Readiness/expiry handled explicitly; no misleading generic success. |
| AC-31 | One-shot input stream needs retry | Retry refused or approved bounded spooling used. |
| AC-32 | URL redirects to internal/cloud-metadata address | Policy blocks access; no credential forwarding. |
| AC-33 | Local server routes to cloud under local-only requirement | Policy does not infer compliance from localhost. |
| AC-34 | Cached/reasoning counters overlap aggregate totals | No double counting; raw counters preserved. |
| AC-35 | Usage missing after disconnection | Unknown remainder and ambiguous cost retained. |
| AC-36 | Catalog rate changes after completion | Historical attribution remains pinned; recalculation separately versioned. |
| AC-37 | Timeout occurs after server may have accepted request | No default unsafe duplicate submission. |
| AC-38 | Underlying official SDK also retries | Single coordinated attempt budget; all dispatches visible. |
| AC-39 | Fallback changes provider and invalidates file/reasoning state | Revalidation fails or explicit migration plan required. |
| AC-40 | Batch completes with reordered successes and failures | Correct item correlation and per-item accounting. |
| AC-41 | Duplicate/out-of-order webhook | Signature/replay validation and idempotent event handling. |
| AC-42 | WebSocket lane reused without valid continuation | New-turn versus continuation semantics preserved. |
| AC-43 | Steering accepted but not yet applied | Pending status retained; no false applied/success event. |
| AC-44 | Proxy error after downstream stream begins | Valid downstream error/incomplete termination, not forged success. |
| AC-45 | Semantic compression touches signed state or required tool pair | Transformation rejected. |
| AC-46 | Inspector rerenders or model selection changes | No inference repetition; stale/mismatched metadata invalidated. |
| AC-47 | Observer/exporter throws | Defined isolation behavior; no protocol corruption or secret dump. |
| AC-48 | Shared client closes during active work | Documented drain/cancel behavior, no executor/socket leak. |
| AC-49 | Native escaping/raw access used | Security, size limits, auth scope, cancellation still enforced. |
| AC-50 | Admin model-delete/pull requested through ordinary inference handle | Explicit unsupported/privileged operation boundary. |

### 28.3 Performance acceptance without arbitrary benchmark promises

Measure overhead using deterministic local fixtures before testing provider latency. Establish project-specific targets for idle footprint, dependency size, large-stream retained memory, incremental parsing throughput, cancellation responsiveness, and concurrent invocation fairness. Compare streaming-only and full-aggregation modes separately. A benchmark must include error/cancellation paths and realistic tool/media/event sizes, not just plain text deltas.

## 29. Delivery slices, open decisions, and design checklist

### 29.1 Suggested implementation slices

These slices prevent a comprehensive baseline from turning into a monolithic first release. A supported feature must still meet its P0 correctness/security contracts immediately.

| Slice | Deliverables | Exit criterion |
|---|---|---|
| **A — Semantic foundation** | Identities, immutable config, credential source, native-preserving content/events, capability states, errors/usage, fake transport, cancellation. | Offline invariants and core acceptance tests pass; no fixed facade yet forces all protocols into chat strings. |
| **B — First vertical path** | One remote native protocol plus one local protocol; simple text, streaming, tools, structured output, metadata, safe API-key/no-auth configuration. | Small end-to-end examples and profile fixtures validate the foundation against genuinely different protocols. |
| **C — Broad provider baseline** | OpenAI Responses/Chat, Anthropic Messages, Gemini Interactions/GenerateContent, OpenRouter, Ollama/llama.cpp, selected xAI/DeepSeek/Qwen profiles. | Per-operation manifests; capabilities and deviations visible; no “all features supported” blanket claims. |
| **D — Production identity and resources** | OAuth/WIF, cloud auth profiles, uploads/artifacts, native caches/state, detailed pricing/quotas, observability. | Rotation, tenant isolation, lifecycle, budget, and cleanup tests pass. |
| **E — Gateway and long-running workloads** | Ingress/egress codecs, explicit translation, jobs/batches/webhooks, bounded routing/fallback. | Loss reports, partial-stream errors, resource mappings, and all-attempt accounting verified. |
| **F — Specialized extensions** | Realtime/media sessions, image/audio/video generation coverage, embeddings/reranking, optional runtime/admin integrations. | Each extension independently meets its declared contract without inflating the base dependency path. |

**Priority recommendation:** settle identity, absence/unknown semantics, native preservation, event lifecycle, cancellation, and credential boundaries before polishing facade names. These decisions are difficult to retrofit. Broad model catalogs, adaptive routing, automatic compaction, and optional executors can follow without blocking a useful SDK.

### 29.2 Decisions to make before implementation

| Decision | Required answer |
|---|---|
| Minimum JDK and packaging | Supported JDK baseline; classpath/JPMS strategy; core artifact versus optional integrations. |
| Async public contract | Completion and cancellation ownership; Flow or alternate stream interface; subscriber semantics. |
| Canonical/native balance | Which common operations are typed; where native payloads/unknown variants live; persistence format. |
| JSON dependency | Parser choice, precision, unknown-field support, bounded streaming, duplicate-key policy. |
| Profile implementation | Direct HTTP versus wrapped official SDK for each profile; retry ownership and codec reuse. |
| Capability strictness | Default behavior for known unsupported versus unknown; explicit conversion approval model. |
| Default storage policy | Preserve provider omission versus explicitly chosen SDK storage settings; unresolved privacy requirements. |
| Metadata strategy | Live discovery, curated snapshots, overrides, freshness, conflict resolution, private cache partitioning. |
| Credentials | Secure secret references, optional persistence, OAuth callbacks, WIF/cloud integrations, trust boundaries. |
| Result retention | Streaming-only/full/partial aggregation defaults, spooling, native raw capture access. |
| Scope of v1 | Exact providers, protocols, operations, and minimum conformance; no ambiguous “supports provider X.” |
| Gateway responsibility | Client only initially, reusable codecs, or supported server integration; actual translation directions. |

### 29.3 LLM-readable design checklist

Before accepting an API proposal, verify:

1. A simple text call does not require knowledge of HTTP headers, token refresh, event framing, or provider internals.
2. An advanced caller can preserve native capabilities and replay material without bypassing safety controls.
3. Unsupported, unknown, omitted, explicit, requested, resolved, and observed are not conflated.
4. Every network side effect, retry, transformation, storage choice, and possible extra charge is visible or governed by an explicit policy.
5. One invocation has one execution identity; streams, partial results, final results, and usage agree.
6. Credentials, resources, metadata, caches, and accounting remain correctly scoped across tenants and routes.
7. Streaming, cancellation, failures, and schema/tool reasoning interactions are first-class—not afterthoughts around a string-returning method.
8. Optional features remain optional dependencies; no unnecessary framework, factory hierarchy, agent loop, or global singleton is required.
9. Provider manifests and acceptance tests—not marketing labels—define what is supported.
10. The resulting design is smaller than a collection of duplicated provider SDK facades while retaining the differences that matter.

## 30. Illustrative machine-readable contracts

The following examples are **conceptual design aids, not an implemented configuration format, final Java API, or real provider metadata**. Model names, endpoint IDs, limits, timestamps, and capability values are illustrative. The invalid example domain is intentionally non-operational. Keep equivalent semantics; the final representation may be more compact.

### 30.1 Secret-free endpoint configuration

```json
{
  "schemaVersion": "1",
  "endpointId": "example-gateway",
  "profile": "example.responses-stateless",
  "baseUri": "https://llm.example.invalid/api/v1/",
  "accountScope": {"tenant": "example-tenant"},
  "authentication": {
    "kind": "api-key",
    "credentialSourceRef": "secret-store:example-llm-key"
  },
  "executionPolicy": {
    "unsupportedParameter": "reject",
    "unknownCapability": "allow-with-warning",
    "ambiguousSubmissionRetry": "deny",
    "totalTimeout": "PT2M"
  },
  "dataPolicy": {
    "captureContent": false,
    "automaticArtifactDownload": false
  },
  "defaultRequest": {"model": "example-model"}
}
```

There is deliberately no temperature, reasoning, storage, or output-token value in this example. Absence must not be expanded into guessed provider defaults. Administratively enforced policies must be represented separately from overridable request presets.

### 30.2 Scoped descriptor with unknown facts and conditional capabilities

```json
{
  "schemaVersion": "1",
  "scope": {
    "endpointId": "example-gateway",
    "protocol": "responses",
    "model": "example-model",
    "accountScopeRef": "example-tenant"
  },
  "snapshotId": "example-snapshot-7",
  "retrievedAt": "2026-09-26T12:00:00Z",
  "facts": {
    "maximumInputTokens": {
      "state": "known",
      "value": 16384,
      "unit": "token",
      "sourceRef": "example-manifest"
    },
    "outputPrice": {
      "state": "unknown",
      "reason": "not-published"
    }
  },
  "capabilities": {
    "generation.streaming": {
      "state": "supported",
      "implementation": "native",
      "sourceRef": "example-manifest"
    },
    "conversation.serverState": {
      "state": "unsupported",
      "sourceRef": "example-manifest"
    },
    "output.jsonSchema": {
      "state": "conditional",
      "constraintsRef": "example-schema-subset",
      "sourceRef": "example-manifest"
    }
  },
  "sources": {
    "example-manifest": {
      "kind": "adapter-manifest",
      "version": "example-1",
      "evidence": "illustration-only"
    }
  }
}
```

A production descriptor should include per-field freshness/effective scope where facts differ. Do not require every fact to share one global source or confidence score.

### 30.3 Prepared invocation with no hidden transformations

```json
{
  "schemaVersion": "1",
  "status": "ready-with-uncertainty",
  "operation": "generate",
  "endpointId": "example-gateway",
  "protocol": "responses",
  "requestedModel": "example-model",
  "configurationSnapshot": "example-config-3",
  "metadataSnapshot": "example-snapshot-7",
  "requiredCapabilities": ["generation.streaming"],
  "transformations": [],
  "pendingSideEffects": ["credential-acquisition", "inference-request"],
  "warnings": [
    {"code": "PRICE_UNKNOWN", "path": "cost", "severity": "warning"}
  ],
  "estimatedCost": {"state": "unknown", "reason": "price-unavailable"}
}
```

The plan describes pending effects; it has not already performed them. Uploads, token-count requests, cache creation, or model warm-up would be additional explicit entries when applicable.

### 30.4 Partial terminal result after a stream failure

```json
{
  "schemaVersion": "1",
  "runId": "example-run-1",
  "attemptId": "example-attempt-1",
  "status": "incomplete",
  "providerResponseId": "example-provider-response",
  "partialOutput": [{"type": "text", "text": "Partial answer"}],
  "usage": {"state": "unavailable", "reason": "terminal-usage-not-received"},
  "error": {
    "category": "transport",
    "stage": "stream-read",
    "remoteExecution": "may-have-completed",
    "automaticRetryAuthorized": false
  },
  "cost": {"state": "unknown", "reason": "incomplete-usage"}
}
```

This outcome must not become an ordinary successful string result or a zero-cost failure. Applications can display partial output and decide whether to authorize another attempt.

## 31. High-impact pitfalls and request traceability

### 31.1 Pitfalls to keep visible during API design

| Tempting simplification | Why it fails | Required alternative |
|---|---|---|
| “Every provider is OpenAI-compatible.” | Shared payload shape does not establish equivalent state, tools, auth, or parameters. | Versioned protocol/provider profiles. |
| “One model ID identifies everything.” | Deployment, route, runtime, account, and revision change behavior. | Scoped identity and requested/resolved/observed distinctions. |
| “One API key field handles authentication.” | OAuth, WIF, cloud signing, expiry, and delegated access have lifecycles. | Credential-source and authorization workflows. |
| “All missing settings get SDK defaults.” | Omission can intentionally delegate to provider behavior. | Explicit absence and documented precedence. |
| “A successful request proves a parameter worked.” | Providers can ignore or coerce accepted fields. | Capability evidence and applied-value uncertainty. |
| “Messages are role plus string.” | Tools, media, refusals, reasoning, citations, and native state get lost. | Ordered typed items plus native preservation. |
| “Streaming is just concatenating text.” | Interleaved tools/items, snapshots, errors, and usage need stateful assembly. | Protocol decoder and event state machine. |
| “Reasoning can always be discarded.” | Some continuation protocols require native material and associations. | Profile-specific replay contracts. |
| “A previous-response ID means the same thing everywhere.” | State support, inheritance, storage, and connection affinity differ. | Explicit state strategy and scope. |
| “JSON mode guarantees my Java object.” | JSON validity, schema conformance, refusal, and object mapping are separate. | Layered outcome/validation model. |
| “Retry all timeouts and 5xx responses.” | Execution may already have occurred; charges or side effects can duplicate. | Stage-aware bounded retry with ambiguity handling. |
| “Cancelled means free.” | Upstream execution and final usage may remain unknown. | Separate local cancellation and remote outcome. |
| “Sum every token counter.” | Cache/reasoning/media counters can overlap aggregates. | Documented disjoint billing categories. |
| “No price means zero.” | Metadata can be absent, stale, route-dependent, or inaccessible. | Unknown state and rate provenance. |
| “One cache toggle controls everything.” | Prompt caches, state, files, HTTP caches, and local answers differ. | Separate cache/storage contracts. |
| “HTTP compression reduces tokens.” | It changes wire representation, not necessarily semantic input. | Separate byte optimization and context transformation. |
| “Localhost means no cloud traffic.” | A local service may forward requests. | Explicit serving-route/egress policy. |
| “Native passthrough means no validation.” | It could bypass tenant, credential, size, or network protections. | Safe native access within the same policy boundary. |
| “A client adapter automatically makes a gateway.” | Ingress decoding, egress streaming, resource mappings, and loss semantics are missing. | Reusable bidirectional codecs plus explicit gateway policy. |
| “Comprehensive requirements require a giant framework.” | Capability breadth does not require mandatory dependencies or orchestration. | Small core, conditional profiles, optional integrations. |

### 31.2 Traceability to the requested scope

| Requested capability/use | Main sections |
|---|---|
| Remote/local endpoints and public providers | 2–7, 24 |
| Authentication including OAuth | 5–7, 21 |
| Provider/model descriptions, capabilities, supported parameters | 3, 8–11, 26 |
| Prices, context/output limits, credits, usage/statistics | 8, 19, 22 |
| Generation, reasoning, effort, temperature/top-k/top-p/min-p/penalties | 10–12, 16 |
| Streaming and asynchronous calls | 7, 12, 18, 25 |
| Files, image input/generation, audio/video | 15, 18, 24 |
| JSON, schemas, response formats, grammars | 11, 13, 27 |
| Tools, tool capabilities, hosted tools, MCP | 8, 14, 16 |
| Prompt caching, cache resources, conversation state | 16–17 |
| Coding-agent harness use without agent-only assumptions | 1, 10, 12, 14, 16, 25 |
| Node-flow workflow and inspectors | 8–9, 22, 26 |
| Own proxy/gateway and traffic analysis/compression | 17, 20–23 |
| Simple API/facade with full native access | 1, 4–5, 9–10, 25, 29–30 |
| Implementation nuances and pitfalls | 6–28, 31 |
| Formats, standards, tests, implementation planning | 27–30 |

**Bottom line:** design the common API around **scoped identity, sourced capabilities, explicit request intent, native-preserving content/events, and an observable cancellable invocation**. Everything else should compose around those contracts. This yields a small facade without creating a small and misleading model of what modern LLM services actually do.

## 32. Official sources and refresh policy

### 32.1 How to use the sources

All sources below are official provider, project, standards-body, or Java documentation. They were consulted for this baseline on **2026-09-26**. The links are living documentation, not frozen evidence artifacts. Some original documentation URLs redirect to newer locations; implementation teams should retain a dated content/schema snapshot or repository commit with each tested adapter release.

Source references support the associated provider/standard observations. They do **not** certify an implementation of the proposed SDK, nor do they mean that every normative design requirement is dictated by a provider. The requirements combine documented protocol constraints with independent engineering recommendations.

For especially volatile facts—prices, model availability, feature flags, authentication methods, context limits, schema subsets, retention, stateful compatibility, and API version status—refresh from the exact endpoint/account/profile before relying on them. Keep historical price and conformance snapshots for reproducibility. Treat a documentation/catalog conflict with live behavior as a recorded discrepancy, not permission to silently rewrite user intent.

### 32.2 Source registry

#### OpenAI

| Key | Official source |
|---|---|
| `OA-RESP` | [Migrating to the Responses API][OA-RESP] |
| `OA-STREAM` | [Streaming API responses][OA-STREAM] |
| `OA-STRUCT` | [Structured model outputs][OA-STRUCT] |
| `OA-REASON` | [Reasoning models][OA-REASON] |
| `OA-STATE` | [Conversation state][OA-STATE] |
| `OA-COMPACT` | [Compaction][OA-COMPACT] |
| `OA-CACHE` | [Prompt caching][OA-CACHE] |
| `OA-DATA` | [Data controls and retention][OA-DATA] |
| `OA-WIF` | [Workload identity federation][OA-WIF] |
| `OA-WS` | [Responses WebSocket mode][OA-WS] |
| `OA-STEER` | [Mid-turn steering][OA-STEER] |
| `OA-REALTIME` | [Realtime API][OA-REALTIME] |
| `OA-BACKGROUND` | [Background mode][OA-BACKGROUND] |
| `OA-WEBHOOK` | [Webhooks][OA-WEBHOOK] |

#### Anthropic / Claude

| Key | Official source |
|---|---|
| `AN-API` | [Claude API overview][AN-API] |
| `AN-AUTH` | [Authentication][AN-AUTH] |
| `AN-WIF` | [Workload Identity Federation][AN-WIF] |
| `AN-MODELS` | [List Models API reference][AN-MODELS] |
| `AN-STREAM` | [Streaming Messages][AN-STREAM] |
| `AN-STRUCT` | [Structured outputs][AN-STRUCT] |
| `AN-CACHE` | [Prompt caching][AN-CACHE] |
| `AN-THINK` | [Extended thinking and model-dependent behavior][AN-THINK] |
| `AN-EFFORT` | [Effort][AN-EFFORT] |

#### Google Gemini and Google Cloud

| Key | Official source |
|---|---|
| `GO-INTERACTIONS` | [Interactions API overview][GO-INTERACTIONS] |
| `GO-MODELS` | [Models API reference][GO-MODELS] |
| `GO-THINK` | [Thinking][GO-THINK] |
| `GO-SIGNATURES` | [Thought signatures][GO-SIGNATURES] |
| `GO-STRUCT` | [Structured output][GO-STRUCT] |
| `GO-CACHE` | [Context caching][GO-CACHE] |
| `GO-FILES` | [Files API][GO-FILES] |
| `GO-LIVE` | [Live API][GO-LIVE] |
| `GO-ADC` | [Application Default Credentials][GO-ADC] |
| `GO-CLOUD` | [Google Cloud model authentication][GO-CLOUD] |

#### Other providers and gateways

| Key | Official source |
|---|---|
| `XA-TEXT` | [xAI text generation][XA-TEXT] |
| `XA-MODELS` | [xAI models][XA-MODELS] |
| `DS-THINK` | [DeepSeek thinking mode and API-format distinctions][DS-THINK] |
| `QW-COMPAT` | [Alibaba Model Studio: Qwen through the OpenAI-compatible API][QW-COMPAT] |
| `OR-MODELS` | [OpenRouter model metadata API][OR-MODELS] |
| `OR-PARAMS` | [OpenRouter request parameters][OR-PARAMS] |
| `OR-OAUTH` | [OpenRouter OAuth PKCE][OR-OAUTH] |
| `OR-ROUTING` | [OpenRouter provider routing][OR-ROUTING] |
| `OR-CACHE` | [OpenRouter prompt caching][OR-CACHE] |
| `OR-RESP` | [OpenRouter Responses API][OR-RESP] |

#### Cloud deployment profiles

| Key | Official source |
|---|---|
| `AZ-RESP` | [Azure OpenAI Responses API][AZ-RESP] |
| `AWS-STREAM` | [Amazon Bedrock ConverseStream API reference][AWS-STREAM] |
| `AWS-SIG` | [AWS Signature Version 4][AWS-SIG] |
| `AWS-KEYS` | [Amazon Bedrock API-key authentication][AWS-KEYS] |

#### Local and self-hosted inference

| Key | Official source |
|---|---|
| `OL-CHAT` | [Ollama chat API][OL-CHAT] |
| `OL-STREAM` | [Ollama streaming][OL-STREAM] |
| `OL-AUTH` | [Ollama authentication][OL-AUTH] |
| `OL-COMPAT` | [Ollama OpenAI compatibility][OL-COMPAT] |
| `LC-SERVER` | [llama.cpp server documentation][LC-SERVER] |
| `VL-SERVER` | [vLLM online serving][VL-SERVER] |
| `SG-API` | [SGLang OpenAI-compatible completions][SG-API] |

#### Standards and security specifications

| Key | Official source |
|---|---|
| `STD-HTTP` | [RFC 9110: HTTP Semantics][STD-HTTP] |
| `STD-TLS` | [RFC 8446: TLS 1.3][STD-TLS] |
| `STD-WS` | [RFC 6455: WebSocket][STD-WS] |
| `STD-JSON` | [RFC 8259: JSON][STD-JSON] |
| `STD-MULTIPART` | [RFC 7578: multipart/form-data][STD-MULTIPART] |
| `STD-SSE` | [WHATWG HTML: Server-sent events][STD-SSE] |
| `STD-SCHEMA` | [JSON Schema Draft 2020-12][STD-SCHEMA] |
| `STD-OPENAPI` | [OpenAPI Specification][STD-OPENAPI] |
| `STD-OAUTH-BCP` | [RFC 9700: Best Current Practice for OAuth 2.0 Security][STD-OAUTH-BCP] |
| `STD-PKCE` | [RFC 7636: Proof Key for Code Exchange][STD-PKCE] |
| `STD-NATIVE` | [RFC 8252: OAuth 2.0 for Native Apps][STD-NATIVE] |
| `STD-DEVICE` | [RFC 8628: OAuth 2.0 Device Authorization Grant][STD-DEVICE] |
| `STD-EXCHANGE` | [RFC 8693: OAuth 2.0 Token Exchange][STD-EXCHANGE] |
| `STD-AUTH-METADATA` | [RFC 8414: OAuth 2.0 Authorization Server Metadata][STD-AUTH-METADATA] |
| `STD-DPOP` | [RFC 9449: OAuth 2.0 Demonstrating Proof of Possession][STD-DPOP] |
| `STD-OAUTH-MTLS` | [RFC 8705: OAuth mTLS and Certificate-Bound Access Tokens][STD-OAUTH-MTLS] |
| `STD-REVOCATION` | [RFC 7009: OAuth 2.0 Token Revocation][STD-REVOCATION] |
| `STD-INTROSPECTION` | [RFC 7662: OAuth 2.0 Token Introspection][STD-INTROSPECTION] |

#### Java, MCP, and observability

| Key | Official source |
|---|---|
| `JAVA-HTTP` | [Java 21 HttpClient API][JAVA-HTTP] |
| `JAVA-FLOW` | [Java 21 Flow API][JAVA-FLOW] |
| `MCP-TRANSPORT` | [MCP 2026-07-28: Transports][MCP-TRANSPORT] |
| `MCP-AUTH` | [MCP 2026-07-28: Authorization][MCP-AUTH] |
| `STD-TRACE` | [W3C Trace Context][STD-TRACE] |
| `OTEL-GENAI` | [OpenTelemetry GenAI semantic conventions][OTEL-GENAI] |

### 32.3 Minimum refresh and maintenance responsibilities

Each profile maintainer should track source/schema versions, model/default changes, authentication changes, added or removed fields/events, known accepted-but-ignored parameters, deprecations, regional differences, and updated acceptance fixtures. Refresh policies should be configurable; do not embed one universal TTL for both volatile balances and rarely changing protocol schemas.

The baseline deliberately contains no authoritative numeric model prices, model inventory counts, or universal token limits. Those belong in versioned metadata/rate snapshots, not in the permanent public API or this requirements document.

---

<!-- Reference definitions: stable citation keys used throughout this document. -->
[OA-RESP]: https://developers.openai.com/api/docs/guides/migrate-to-responses "Migrating to the Responses API"
[OA-STREAM]: https://developers.openai.com/api/docs/guides/streaming-responses "Streaming API responses"
[OA-STRUCT]: https://developers.openai.com/api/docs/guides/structured-outputs "Structured model outputs"
[OA-REASON]: https://developers.openai.com/api/docs/guides/reasoning "Reasoning models"
[OA-STATE]: https://developers.openai.com/api/docs/guides/conversation-state "Conversation state"
[OA-COMPACT]: https://developers.openai.com/api/docs/guides/compaction "Compaction"
[OA-CACHE]: https://developers.openai.com/api/docs/guides/prompt-caching "Prompt caching"
[OA-DATA]: https://developers.openai.com/api/docs/guides/your-data "Data controls and retention"
[OA-WIF]: https://developers.openai.com/api/docs/guides/workload-identity-federation "Workload identity federation"
[OA-WS]: https://developers.openai.com/api/docs/guides/websocket-mode "Responses WebSocket mode"
[OA-STEER]: https://developers.openai.com/api/docs/guides/steering "Mid-turn steering"
[OA-REALTIME]: https://developers.openai.com/api/docs/guides/realtime "Realtime API"
[OA-BACKGROUND]: https://developers.openai.com/api/docs/guides/background "Background mode"
[OA-WEBHOOK]: https://developers.openai.com/api/docs/guides/webhooks "Webhooks"
[AN-API]: https://platform.claude.com/docs/en/api/overview "Claude API overview"
[AN-AUTH]: https://platform.claude.com/docs/en/manage-claude/authentication "Authentication"
[AN-WIF]: https://platform.claude.com/docs/en/manage-claude/workload-identity-federation "Workload Identity Federation"
[AN-MODELS]: https://platform.claude.com/docs/en/api/models/list "List Models API reference"
[AN-STREAM]: https://platform.claude.com/docs/en/build-with-claude/streaming "Streaming Messages"
[AN-STRUCT]: https://platform.claude.com/docs/en/build-with-claude/structured-outputs "Structured outputs"
[AN-CACHE]: https://platform.claude.com/docs/en/build-with-claude/prompt-caching "Prompt caching"
[AN-THINK]: https://platform.claude.com/docs/en/build-with-claude/extended-thinking "Extended thinking and model-dependent behavior"
[AN-EFFORT]: https://platform.claude.com/docs/en/build-with-claude/effort "Effort"
[GO-INTERACTIONS]: https://ai.google.dev/gemini-api/docs/interactions-overview "Interactions API overview"
[GO-MODELS]: https://ai.google.dev/api/models "Models API reference"
[GO-THINK]: https://ai.google.dev/gemini-api/docs/thinking "Thinking"
[GO-SIGNATURES]: https://ai.google.dev/gemini-api/docs/thought-signatures "Thought signatures"
[GO-STRUCT]: https://ai.google.dev/gemini-api/docs/structured-output "Structured output"
[GO-CACHE]: https://ai.google.dev/gemini-api/docs/caching "Context caching"
[GO-FILES]: https://ai.google.dev/gemini-api/docs/files "Files API"
[GO-LIVE]: https://ai.google.dev/gemini-api/docs/live-api "Live API"
[GO-ADC]: https://docs.cloud.google.com/docs/authentication/application-default-credentials "Application Default Credentials"
[GO-CLOUD]: https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/start/gcp-auth "Google Cloud model authentication"
[XA-TEXT]: https://docs.x.ai/developers/model-capabilities/text/generate-text "xAI text generation"
[XA-MODELS]: https://docs.x.ai/developers/models "xAI models"
[DS-THINK]: https://api-docs.deepseek.com/guides/thinking_mode/ "DeepSeek thinking mode and API-format distinctions"
[QW-COMPAT]: https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope "Alibaba Model Studio: Qwen through the OpenAI-compatible API"
[OR-MODELS]: https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties "OpenRouter model metadata API"
[OR-PARAMS]: https://openrouter.ai/docs/api_reference/parameters "OpenRouter request parameters"
[OR-OAUTH]: https://openrouter.ai/docs/guides/overview/auth/oauth "OpenRouter OAuth PKCE"
[OR-ROUTING]: https://openrouter.ai/docs/guides/routing/provider-selection "OpenRouter provider routing"
[OR-CACHE]: https://openrouter.ai/docs/guides/best-practices/prompt-caching "OpenRouter prompt caching"
[OR-RESP]: https://openrouter.ai/docs/api_reference/responses/overview "OpenRouter Responses API"
[AZ-RESP]: https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses?view=foundry-classic "Azure OpenAI Responses API"
[AWS-STREAM]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html "Amazon Bedrock ConverseStream API reference"
[AWS-SIG]: https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv.html "AWS Signature Version 4"
[AWS-KEYS]: https://docs.aws.amazon.com/bedrock/latest/userguide/getting-started-api-keys.html "Amazon Bedrock API-key authentication"
[OL-CHAT]: https://docs.ollama.com/api/chat "Ollama chat API"
[OL-STREAM]: https://docs.ollama.com/api/streaming "Ollama streaming"
[OL-AUTH]: https://docs.ollama.com/api/authentication "Ollama authentication"
[OL-COMPAT]: https://docs.ollama.com/api/openai-compatibility "Ollama OpenAI compatibility"
[LC-SERVER]: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md "llama.cpp server documentation"
[VL-SERVER]: https://docs.vllm.ai/en/latest/serving/online_serving/ "vLLM online serving"
[SG-API]: https://docs.sglang.io/docs/basic_usage/openai_api_completions "SGLang OpenAI-compatible completions"
[STD-HTTP]: https://httpwg.org/specs/rfc9110.html "RFC 9110: HTTP Semantics"
[STD-TLS]: https://www.rfc-editor.org/info/rfc8446/ "RFC 8446: TLS 1.3"
[STD-WS]: https://www.rfc-editor.org/info/rfc6455/ "RFC 6455: WebSocket"
[STD-JSON]: https://www.rfc-editor.org/info/rfc8259/ "RFC 8259: JSON"
[STD-MULTIPART]: https://www.rfc-editor.org/info/rfc7578/ "RFC 7578: multipart/form-data"
[STD-SSE]: https://html.spec.whatwg.org/multipage/server-sent-events.html "WHATWG HTML: Server-sent events"
[STD-SCHEMA]: https://json-schema.org/draft/2020-12 "JSON Schema Draft 2020-12"
[STD-OPENAPI]: https://spec.openapis.org/oas/latest.html "OpenAPI Specification"
[STD-OAUTH-BCP]: https://www.rfc-editor.org/info/rfc9700/ "RFC 9700: Best Current Practice for OAuth 2.0 Security"
[STD-PKCE]: https://www.rfc-editor.org/info/rfc7636/ "RFC 7636: Proof Key for Code Exchange"
[STD-NATIVE]: https://www.rfc-editor.org/info/rfc8252/ "RFC 8252: OAuth 2.0 for Native Apps"
[STD-DEVICE]: https://www.rfc-editor.org/info/rfc8628/ "RFC 8628: OAuth 2.0 Device Authorization Grant"
[STD-EXCHANGE]: https://www.rfc-editor.org/info/rfc8693/ "RFC 8693: OAuth 2.0 Token Exchange"
[STD-AUTH-METADATA]: https://www.rfc-editor.org/rfc/rfc8414.html "RFC 8414: OAuth 2.0 Authorization Server Metadata"
[STD-DPOP]: https://www.rfc-editor.org/info/rfc9449/ "RFC 9449: OAuth 2.0 Demonstrating Proof of Possession"
[STD-OAUTH-MTLS]: https://www.rfc-editor.org/info/rfc8705/ "RFC 8705: OAuth mTLS and Certificate-Bound Access Tokens"
[STD-REVOCATION]: https://www.rfc-editor.org/info/rfc7009/ "RFC 7009: OAuth 2.0 Token Revocation"
[STD-INTROSPECTION]: https://www.rfc-editor.org/info/rfc7662/ "RFC 7662: OAuth 2.0 Token Introspection"
[JAVA-HTTP]: https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html "Java 21 HttpClient API"
[JAVA-FLOW]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.html "Java 21 Flow API"
[MCP-TRANSPORT]: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports "MCP 2026-07-28: Transports"
[MCP-AUTH]: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization "MCP 2026-07-28: Authorization"
[STD-TRACE]: https://www.w3.org/TR/trace-context/ "W3C Trace Context"
[OTEL-GENAI]: https://github.com/open-telemetry/semantic-conventions-genai "OpenTelemetry GenAI semantic conventions"
