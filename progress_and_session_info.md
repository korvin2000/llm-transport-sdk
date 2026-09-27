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
