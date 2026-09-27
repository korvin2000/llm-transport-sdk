# Document containing last session information and  implementation progress and remaining TODO's

## First draft-implementation goals. User prompt:
User prompt: 'Implement the first testable version of the SDK architecture specified in docs/proposals/final-architecture.md under llm/.
Use Java 26 and Gradle, with an API usable from Kotlin. Implement as single module with src/main/java, src/main/resources, src/test/java, and src/test/resources. Use a compact package hierarchy rooted at net.ai.gate (groupId: net.ai.gate, artifactId: ai-gate).
Implement the core architecture, configuration, interfaces, and public facades sufficiently to exercise and test the design. Add clear stubs for provider protocols, endpoints, and vendor-specific behavior; their implementations belong to later stages. Keep the names readable and concise, use well-thought hierarchical project structure, and include tests that validate the architectural concepts and public API.'

## Work progress: step by step
Environment's ready — network, JDK (Temurin 26.0.2.1), and Gradle 9.7.1 are all set, and I've confirmed latest stable versions for Kotlin, JUnit, jspecify, JetBrains annotations, and ArchUnit. Now writing the build file.

Using `tools/jdk-26.0.2.1+1` and `tools/gradle-9.7.1` for the build (Gradle's current JVM satisfies the 26 toolchain). Continuing with sources: JSON package first.

JSON layer done; now writing the root API package (value types first).

Now the eight exception subclasses (generated from one template to keep them identical in shape).

Exceptions fixed. Next: `Usage`, `Prices`, `Model`, `FieldDescriptor`.

Core value types written; now conversation model (content, tools, messages).

Messages and conversation done; writing `ChatOptions`, policies and `CancelToken`.

Adding `Model.rekeyed` (used by `Provider` when a preset is re-identified), then the `Llm` facade.

Root API done. Now the `event` package.

Events done. Now the `auth` package.

Auth API written. Now the `spi` package.

Public API and SPI are complete (~100 types). Next: the internal implementation, starting with HTTP transport and stream framing.

That `CredentialJson` draft has leftover junk lines; rewriting it cleanly.

Auth internals done. Now the response cache internals.

Cache done. Now the model catalog internals (JSON form, freshest-wins merge, background refresh).

Catalog complete. I'll add two small public API additions: `ChatOptions.Builder.providers(List)` for replacing providers, and a nullable `reasoning(...)` to unset unsupported levels.

Now the execution core — first the small collaborators.

Next: codec context, watchdog and the per-call state machine (`Call`: auth → interceptors → transport, retries, deadlines, cancellation, events).

Writing the stream implementation; refining `close()` so a closed-but-unread stream still ends with `Finished(CANCELLED)`.

Now `Core` (shared runtime state), `DefaultLlm` (facade) and `ConnectionTester`.

Core engine written. Compiling early to shake out errors before the provider packages: generating the Gradle wrapper first.

Main code compiles cleanly under `-Xlint:all -Werror`. Fixing the helper script's output filter, then writing the provider packages.

I'll move `ModelJson` into `internal.json` so `Json.valueOf(model)`/`Json.convert(json, Model.class)` provide the canonical form as specified, letting provider/catalog packages rely solely on public API.

Now the provider families, starting with OpenAI (presets as data, codec stubs, typed options/compat, hosted tools).

OpenAI family done. Now Anthropic.

Anthropic done. Now Google (Gemini), including the typed provider-only `Gemini.CACHES` API as a stub.

Provider families done. Now the catalog data package and the `providers` index/config.

Now the `providers` package: the preset index and the secret-free `ProvidersConfig` format.

Providers are done. Next I'll build the testing kit: a scripted FakeProvider with its own wire protocol, codec, and in-process transport, so resolution, hand-off, retries, SSE framing, accumulation, caching, and events get exercised just like in production.

That frame-index placeholder hack is needlessly clever — replacing it with explicit indexes.

Now `FakeProvider`, `RecordingListener` and `LlmErrors`.

All sources written. Adding `module-info.java` and the classpath `ServiceLoader` registration, then compiling.

Suspiciously clean — verifying that compilation actually ran over all sources.

212 sources → 328 classes, zero warnings under `-Xlint:all -Werror`. Checking Javadoc (Markdown `///`, doclint) next.

Javadoc is clean too. Sweeping leftover fully qualified names in code bodies (style consistency).

Main code: compiles and Javadoc-clean. Now the test suite — end-to-end through `FakeProvider`, plus focused unit tests. First the facade tests.

Found a bug in the fake codec—it was embedding a request counter in the body, which would break byte-stable request bodies used for cache keys. I'm fixing it to key requests by their canonical body instead.

Running the facade tests first to validate the engine before writing the rest.

16/17 pass on the first run. Inspecting the cache-test failure.

Found the root cause: the fake codec only encoded message count, so conversations with the same count produced identical cache keys. I'll fix it by having the codec actually encode the message content.

All 17 facade tests pass. I'm now refining a resolver rule per spec §14.3 so cataloged models without reasoning support drop the setting, while unlisted models still pass it through.

Writing the remaining unit tests (JSON, catalog, auth, framing, config, architecture) and the Kotlin interop test.

Found it: the errors are in my test file, since `object` is a Kotlin keyword and `Json.object(…)` needs backticks. I'll fix that in the test code.

All green. Verifying test counts per class (and that ArchUnit really imported the Java 26 classes rather than passing vacuously).

59 tests, 0 failures. The ArchitectureTest report matched a suspicious pattern — checking it's not a vacuous pass.

Rules check real classes (the match was just a test name). Next: an unused-import sweep, since javac doesn't lint those.

Clean `build` passes: 59 tests, jar + sources + Javadoc jars, zero warnings. Final checks: module descriptor and size.

## Implementation finished. Summary info

Готово: в `llm/` лежит базовый каркас SDK по `final-architecture.md`. Чистый `./gradlew clean build` на JDK 26 проходит: 59 тестов (Java и Kotlin), ноль предупреждений при `-Xlint:all -Werror`, Javadoc без замечаний, собираются jar, sources и javadoc.

## Структура
- **Один модуль** с maven-раскладкой (`src/main/java`, `src/main/resources`, `src/test/java`, плюс `src/test/kotlin` для проверки работы из Kotlin). Координаты `net.ai.gate:ai-gate`, JPMS-модуль `net.ai.gate`.
- **Пакеты**: корневой пакет — фасад `Llm` и типы-значения. Рядом `auth`, `event`, `json`, `spi`, `internal/{core,http,auth,catalog,cache,json}`, `openai`, `anthropic`, `google`, `catalog`, `providers`, `testing`. Всего 212 исходников, около 10,7 тыс. строк.
- **Сборка**: Gradle 9.7.1 с wrapper, version catalog; Kotlin 2.4.20 компилирует тесты с таргетом JVM 26. Используется `tools/jdk-26.0.2.1+1`; если JDK 26 не установлен, его скачает toolchain resolver.

## Что реализовано
Проверено end-to-end через `FakeProvider`. Он заскриптован и работает за настоящим runtime, поэтому имитируется только сетевой endpoint.

- **Фасад и опции**: три уровня `ChatOptions` (вызов ▷ провайдер ▷ runtime), значения по умолчанию из каталога, режим `strict()` и мягкие адаптации с предупреждениями.
- **Смена модели посреди разговора**: история адаптируется под новую модель, reasoning переносится как `<thinking>`-текст, id вызовов инструментов нормализуются.
- **Выполнение**: таймауты (общий и простоя потока), ретраи с учётом `Retry-After` и `outcomeUnknown`, отмена деревом `CancelToken`, события, JFR.
- **Стриминг**: `result()` совпадает с результатом `complete()`, аргументы инструментов доступны частично по мере прихода.
- **Прочее**: кеш ответов (в памяти, кассеты в каталоге, режим `OFFLINE`), стоимость с учётом ценовых порогов, `preview()` и `toCurl()`, проверка соединения `test()`, `describe()`.
- **Аутентификация**: цепочка источников ключей, хранилища (в памяти, файл, по пользователям), вход по API-ключу, взаимодействие через консоль и через web-redirect.
- **Каталог моделей**: слияние по принципу «свежие данные важнее», фиды, живые списки моделей, фоновое обновление, файл-снимок.
- **Архитектурные правила** проверяются тестами ArchUnit: например, пакеты провайдеров не видят `internal` ядра.

## Заглушки
Все заглушки бросают `UnsupportedOperationException` с номером этапа roadmap. Ожидаемый маппинг описан прямо в Javadoc каждого класса.

- Кодеки OpenAI Responses и Chat Completions, Anthropic Messages, Gemini.
- OAuth-потоки (PKCE, device code, refresh).
- Фид models.dev, `Gemini.CACHES`, собственные TLS-настройки.
- Поля `compat` и `defaults` в `ProvidersConfig`.

Образец для настоящих кодеков — `testing/FakeWireApi`: это полноценный маленький кодек.

## Где я отошёл от документа
- `Provider.transport()` — транспорт, привязанный к провайдеру. Нужен для фейков и серверов, работающих в том же процессе.
- `EncodeContext`: `compat(defaults)` вместо рефлексии, плюс `adapt()` и `defaultMaxTokens()`.
- `Model.api()` возвращает `Optional`; у `HttpCall` wither-методы вместо builder.
- В `ChatOptions.Builder` добавлен `providers(List)`, а `reasoning(null)` снимает уровень.
- Один `models.json` вместо пакетных каталогов по семействам. `Json.valueOf/convert` поддерживают `Model`.

## Что стоит знать
- **Данные-заглушки**: цены и лимиты в `models.json` иллюстративные и не проверены. URL пресетов и имена переменных окружения нужно сверить с документацией провайдеров перед реальным использованием. Client id для OAuth у OpenRouter — заглушка.
- **Kotlin**: `Json.object(...)` из Kotlin приходится писать с обратными кавычками, потому что `object` — ключевое слово.
- **Не сделано в этом срезе**: контекст вызова через `ScopedValue`, JSON-форма `Conversation`. Таймаут соединения задан на уровне клиента (10 с) и per-call не переопределяется.

Кеширование и `preview()` рассчитаны на то, что тело запроса детерминировано. Реальные кодеки должны это сохранять: при написании теста я поймал нарушение в собственном фейковом кодеке и исправил его.

Ничего не коммитил. Незакоммиченные изменения в корне репозитория (`.gitignore`, удалённый `requirements-mini.md`) не трогал. Подробности — в [llm/README.md](llm/README.md).
---

# Session 2 — review-driven refactor and fixes (2026-09-27)

Inputs: `review_goals.md` (own analysis), `review_and_fixes.md` (independent review). Both reviews were compared; the
package plan and the P1/P2 fixes below take the stronger option of each. Build: `cd llm && ./gradlew build` with
`tools/jdk-26.0.2.1+1` — green, `-Xlint:all -Werror`, doclint, ArchUnit.

## Package restructure (all 61 root types re-homed; every package ≤ 20 top-level types, enforced)
- Root keeps only `Llm` and `Provider`. New public packages: `chat` (+`content`, `tool`, `stream`, `options`), `model`,
  `metadata`, `catalog` (+`catalog.internal` for `models.json`), `config`, `cache`, `diagnostics`, `lifecycle`, `error`,
  `auth.oauth`, `auth.interaction`, `spi.protocol`, `spi.http`, `spi.catalog`, `spi.provider`,
  `vendors.{openai,anthropic,google}` (+`.internal`). Internals: `internal.{core,http,cache,catalog,json,serialization,validation}`,
  `internal.auth.{store,interaction,oauth}`. `module-info` exports and `META-INF/services` updated.
- Intentional deviation from `final-architecture.md` §6.3 ("at most three levels below the root"): the requested deeper,
  noun-based hierarchy wins; `vendors.*.internal` is four levels deep.
- Cross-package sealed hierarchies (`Credential` → `auth.oauth.OAuthCredential`) verified on JDK 26 from the module
  path and the class path (`ModuleBoundaryTest` compiles and runs an external consumer against the jar).

## Behavioral fixes (each with regression tests)
- Response cache: key = credential namespace (store identity, scope, OAuth account) + effective request incl. headers;
  interceptors disable caching; cassettes stay replayable across processes.
- Call lifetime: own token linked to the caller's for the call only (no leak on long-lived tokens); cancellation and the
  total deadline cover credential resolution, send, reply body, error body and backoff; outside interrupts stay pending;
  `outcomeUnknown` reflects whether the request left; one `Finished` per call; bounded `close()` incl. the JDK client.
- Streams: one view, one iterator; lock-owned state; `close()` from another thread cancels then ends once; bounds on
  frame lines, whole events, retained parts and cache recording.
- Options: partial `TimeoutPolicy`/`RetryPolicy` inherit field by field; `strict(false)`, `stops(List)`; case-insensitive
  header merge; header name/value validation; `Cookie`/`Set-Cookie` protected; `baseUrl` rejects user info/query.
- OAuth: a 401 refreshes exactly the rejected token once, single-flight across callers; store failures keep
  `credential_store`; refresh state is per store instance.
- Catalog: live listings run with each view's credentials, availability per namespace; prices/compat merge component by
  component; publication serialized; temp files cleaned; bundled data loaded lazily (build does no I/O).
- Diagnostics: `toCurl()` single-quotes literals and exposes only credential placeholders; `describe()` redacts and shows
  effective policies; error bodies bounded; connection test shares one budget and reports unverifiable credentials as
  `NOT_SUPPORTED`; JFR events carry tags.
- Hand-off: cache breakpoints remapped past dropped turns; normalized tool-call ids never collide.
- JSON: strict number grammar, `char` binding, exact integer conversion and member-named errors in `ModelJson`;
  `Json`/`RecordBinder` no longer know `Model` (`Model.toJson()`/`fromJson()` instead).
- File credential store: JVM-wide per-path coordination (no `OverlappingFileLockException`), schema validation,
  best-effort owner-only ACL on Windows.
- Testing kit: bounded request tracking, `sends()`, `truncated()`, `malformed()`, `stall()` fixtures.
- Build: Kotlin is test-only (main `runtimeClasspath` has no dependencies).

## Still deferred (unchanged scope)
Vendor codecs, OAuth network flows, models.dev feed, `Gemini.CACHES`, custom TLS, `compat` in `ProvidersConfig`
(explicitly rejected, never silently dropped), per-field catalog provenance, account binding of `Content.fileRef`,
`ScopedValue` call context (explicit `Call` object kept on purpose).

---

# Session 3 — second implementation phase (2026-09-27)

Input: `second_phase.md`. References: `examples/ai` (pi-ai TypeScript: codecs, OAuth, models.dev generator) for wire
details; `examples2` (Spring-based) cross-checked for RFC 8628 / refresh semantics only — nothing imported.

## Done (all offline-verified; `./gradlew clean build` green)
- Codecs: `CompletionsCodec` (compat flags: reasoning formats, max-tokens field, developer role, `reasoning_content`
  replay, Anthropic-style cache markers, session headers; `[DONE]`/usage chunks), `ResponsesCodec` (stateless
  `store:false`, encrypted reasoning replay, citations, typed SSE with authoritative `output_item.done` parts),
  `MessagesCodec` (adaptive vs budget thinking by model id, signed/redacted thinking replay, ≤4 cache markers + 1h TTL,
  hosted tools + betas, `output_config`), `GenerateContentCodec` (thinking level vs budget, thought signatures on any
  part re-attached on replay, synthesized call ids, JSON-schema output), `CachesClient` (create/get/list/extend/delete).
- Shared codec steps: `spi.protocol.Codecs`; JSON accessors `JsonObject.array/objects/optString/optLong/bool`.
- OAuth: `StandardOAuth` (PKCE S256 via `Loopback` listener, pasted code, `RedirectInteraction`; device code; refresh
  rotation keeping the account; RFC 7009 revoke), `OAuthAuth.revoke` default, `OAuthConfig.redirectParameter` /
  `jsonTokenRequests` (OpenRouter dialect), `DefaultAuth.revoke` revokes remotely.
- Catalog: `ModelsDevFeed` mapping (explicit provider map, effort → levels, budget/toggle ⇒ switchable, tiers,
  deprecated); `CatalogOptions.feeds(List)` excludes discovered feeds (hermetic tests); `./gradlew updateModelCatalog`
  regenerated `models.json` (184 text models, no OpenRouter, no deprecated).
- TLS: `HttpOptions.sslContext()` (trust store, client cert, insecure via extended trust manager) used by the JDK
  transport.
- Tests: `vendors/*WireTest` (13, scripted endpoint `WireScript`), `StandardOAuthTest` (5, in-process issuer),
  `ModelsDevFeedTest` (pinned sample + regeneration), `HttpOptionsTest`; stub-asserting tests updated.

## Decisions
- Thinking format chosen by model id where the API differs by generation (as the reference does), overridable by
  typed options' `thinkingBudget`.
- Confidential OAuth clients / client-credentials grant: rejected explicitly (no store access in the SPI).
- `compat` in `ProvidersConfig`, per-field provenance, `fileRef` account binding, `ScopedValue` context: unchanged.

## Not verified
- No calls against real provider endpoints (no keys in this environment); fixtures follow the documented wire formats.

---

# Session 4 — step three (2026-09-27)

Input: `step_three.md` (tasks 1, 4–9). Build: `./gradlew clean build --offline` green (`-Xlint:all -Werror`, doclint,
ArchUnit); 186 tests (173 + 13), 0 failures, 2 expected skips; `./gradlew liveTest` added (4 cases, skipped without keys).

## Done
- **Codex (ChatGPT Plus/Pro), task 1:** `OpenAi.codex()` (id `openai-codex`, base `https://chatgpt.com/backend-api/codex`,
  header `originator: ai-gate`), registered in `OpenAiBundle`/`Providers`. OAuth via `StandardOAuth` with three small
  `OAuthConfig` additions — `authorizationParameter`, `accountClaim(path…)` + `accountHeader(name)` (the account id
  from the access-token claim `https://api.openai.com/auth`.`chatgpt_account_id`, sent as `chatgpt-account-id`) and
  `DeviceDialect.OPENAI` (JSON `…/deviceauth/usercode` + `…/deviceauth/token`, 403/404 = pending, code + verifier
  exchanged with redirect `/deviceauth/callback`, page `/codex/device`) merged into the one device loop. Transport:
  new `OpenAiResponsesCompat` (`streamingOnly`, `defaultInstructions`, `maxOutputTokens`, `sessionHeaders`) on
  `ResponsesCodec`. Stream-only endpoints: `Engine.complete` reads a `text/event-stream` reply through the stream
  pipeline (same result, cached as frames) — generic, no SPI change. Plan limits (`usage_limit_reached`,
  `usage_not_included`, also OpenAI's `insufficient_quota`) → `quota_exhausted`, never retried. Catalog:
  `ModelsDevFeed.CODEX` copies the Codex client's models (gpt-6-astra/sol/luna, gpt-5.6-sol/terra/luna, gpt-5.5) from
  `openai` to `openai-codex` with the 272k window and no per-token prices.
- **Media outputs, task 4:** `Content.Image` got `providerData` (replay data, like `Reasoning`); Gemini `inlineData`
  → `Image`/`Audio` (format = MIME subtype) and back to `inlineData` in the model turn; Responses
  `image_generation_call` → `Image` (item without `result` as provider data; replayed as `{type,id,status,result}`);
  Chat Completions `message.audio` / `delta.audio` → `Audio` with transcript (replayed as the transcript: audio ids
  expire). `Handoff`: foreign images omitted, foreign audio → transcript (`history_adapted`). `ConversationJson` and
  `Accumulator.sizeOf` carry `providerData`. `OpenAiTools.imageGeneration()`.
- **Anthropic, task 5:** citations → `Content.Citation` over the whole block (`document:<i>`, `search-result:<i>`
  without URL), `citations_delta` accumulated to an authoritative `PartEnd`; replay as plain text. Forced tool
  choice → `auto` (`option_adapted`, strict fails) under budget thinking and on Opus 5.5 / Fable 5.1 / Mythos 5.1
  (adaptive thinking may force a tool per current docs).
- **Sampling, task 6:** `Capability.TEMPERATURE` from models.dev `temperature`; `Codecs.sampling(request, ctx,
  reasoningRejects)` drops temperature/topP/topK with `option_dropped` via `ctx.warn` — used by all four codecs
  (OpenAI family heuristic `o1…/gpt-5+` while reasoning; Claude while thinking, now warn instead of adapt).
  `Engine.finish` logs reply warnings once at `INFO` on `net.ai.gate`.
- **Mistral, task 7:** `OpenAiCompletionsCompat.ToolCallIdFormat.MISTRAL` on the preset: foreign ids → nine base-62
  characters of a name-based UUID, collisions probed, native nine-character ids kept.
- **compat in `ProvidersConfig`, task 8:** `ApiCompat.toJson()` + static helpers (`json/flag/text/choice/unknown`);
  `fromJson` on the three compat types (unknown field fails by name, `x-` ignored); read by the default API's type
  (plain switch), written as the difference from the preset (unsetting a preset flag fails); per-model `compat` too.
- **Research, task 9** (sources below) — fixed: `MAX` → `max` (the catalog clamps to supported levels);
  `prompt_cache_retention` not sent to GPT-5.6+/GPT-6 (`cache_hint_ignored`); `reasoning.encrypted_content` included
  whenever the model reasons without server state; feed: a budget without toggle and minimum > 0 (Gemini 2.5 Pro) is
  not switchable; Anthropic hosted tools (`code_execution` without beta, `web_fetch`, `computer_20251124` + beta);
  Gemini function-call ids from the API sent back with the call and its response.
- Regenerated `models.json` (191 models); removed four unused imports (three pre-existing).

## Decisions
- Redirect `http://127.0.0.1:1455/auth/callback` (what the Codex CLI now uses; the step file said `localhost`, which
  pi-ai uses — both are loopback on 1455). No `OpenAI-Beta: responses=experimental`: the Codex CLI dropped it for HTTP.
- `complete()` against a stream-only backend: core collects the event stream (smaller than an SSE parser per codec,
  and it keeps caching, watchdog and cancellation).
- Codex CLI login import (`~/.codex/auth.json`) not done: sharing a rotating refresh token signs one client out.
- Generated audio replays as its transcript rather than `audio: {id}` (ids expire).

## Not verified live
- No keys in this environment: Codex login/transport, media generation, citations and all task-9 fixes follow the
  documentation and reference clients; `./gradlew liveTest` is ready for the user.
- Open items from the research: Gemini 3 tool-result media inside `functionResponse.parts`; OpenRouter
  `reasoning_details` replay; Groq/xAI effort vocabularies (clamped by catalog levels only); Qwen `preserve_thinking`.

## Sources (task 9)
platform.claude.com/docs (extended-thinking, thinking, citations, search-results, web-search-tool, tool-reference);
ai.google.dev/gemini-api/docs (thinking, caching, function-calling, speech-generation, image-generation) and the v1beta
discovery document; developers.openai.com/api/docs/guides (latest-model, reasoning, prompt-caching,
tools-image-generation, audio); github.com/openai/codex (codex-rs login server and device code, token_data,
model-provider-info, codex-api headers/api_bridge, models-manager/models.json); api-docs.deepseek.com (thinking mode);
openrouter.ai/docs (reasoning tokens, prompt caching); console.groq.com/docs/reasoning; docs.x.ai/docs/guides/reasoning;
Mistral tool-call id reports (zed #53034, vercel/ai #11802); pi-ai `examples/ai` (openai-codex OAuth and transport).
