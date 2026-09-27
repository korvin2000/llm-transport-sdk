# Этап 3 — доработка транспорта: подписочный OAuth (Codex), медиа-выходы, адаптации параметров, compat в конфиге

Документ самодостаточен: новая сессия coding agent начинает отсюда. Сначала прочитай раздел 0, затем задачи.

---

## 0. Как начать (обязательно)

1. Прочитай в таком порядке:
   - этот файл целиком;
   - `llm/README.md` — статус, что реализовано, известные ограничения;
   - `progress_and_session_info.md` — хронология сессий 1–3, решения и отказы (читать конец файла, «Session 2», «Session 3»);
   - `docs/proposals/final-architecture.md` — целевая архитектура (отклонения перечислены в progress-файле);
   - `git status` — незакоммиченные изменения пользователя не трогать.
2. Навыки: при работе с Java-кодом загрузи скилл `compact-code` (правила «компактного эталонного Java») и следуй
   духу `ponytail` (ленивый senior: сначала JDK/готовое, минимум классов и строк, без оверинжиниринга).
   Для публичных API — `java-sdk-design`. Глобальные правила пользователя — `~/.claude/CLAUDE.md` (экономия токенов,
   минимальные изменения, проверять узко, коммитить только по просьбе).
3. Сборка (JDK 26 лежит в репозитории, сеть для Gradle не нужна):

   ```bash
   cd llm && export JAVA_HOME=/c/work.ai/llm-transport-sdk/tools/jdk-26.0.2.1+1
   ./gradlew compileJava --offline -q                  # быстрая проверка компиляции (-Xlint:all -Werror)
   ./gradlew test --offline -q --tests '*GeminiWireTest'   # узкий прогон
   ./gradlew build --offline -q                        # полный: тесты, javadoc (doclint), ArchUnit, jar
   ./gradlew updateModelCatalog                        # СЕТЬ: перегенерирует models.json из models.dev
   ```

   Результаты тестов: `llm/build/test-results/test/TEST-*.xml` (grep `<failure`). Полная сборка ~2–5 мин.
   Базовая линия на начало этапа: `clean build` зелёный, 173 теста, 0 падений, 2 пропуска (оба ожидаемые).
4. Ничего не коммитить без просьбы пользователя. В конце — дописать сводку в `progress_and_session_info.md`
   (раздел «Session 4»), обновить `llm/README.md` (таблица «Implemented», «Known limits»).

---

## 1. Проект: карта и инварианты

Один Gradle-модуль и один JPMS-модуль `net.ai.gate` (`llm/src/main/java/module-info.java`), Java 26, без runtime-зависимостей
(только JDK; jspecify и jetbrains-annotations — compileOnly; Kotlin только в тестах).

| Пакет | Что там |
|---|---|
| `net.ai.gate` | фасад `Llm`, `Provider` (пресет: id, baseUrl, auth, APIs, compat, defaults, transport) |
| `chat`, `chat.content`, `chat.tool`, `chat.stream`, `chat.options` | `Conversation`, сообщения, `Content` (sealed: Text, Image, Document, Audio, Reasoning, Refusal, Unknown, ToolCall, ToolResult), инструменты, `ChatEvent`, `ChatOptions` |
| `model`, `metadata` | `Model`, `Capabilities`, `Prices`, `ReasoningLevel`; `Usage`, `Warning`, `Cost` |
| `catalog` | `ModelCatalog`, `CatalogOptions`, `ModelsDevFeed`; `catalog.internal.BundledCatalog` + `resources/net/ai/gate/catalog/models.json` (сгенерирован) |
| `auth`, `auth.oauth`, `auth.interaction` | `ApiKeyAuth`, `Credential*`, `OAuthAuth` (SPI), `OAuthConfig`, `OAuthCredential`, `AuthInteraction` |
| `config`, `cache`, `diagnostics`, `lifecycle`, `error`, `event`, `json` | политики, кэш ответов, preview/test, `CancelToken`, исключения с `ErrorCode`, события, JSON-модель |
| `spi.protocol` | `WireApi` (кодек), `StreamDecoder`, `Frame`, `ApiCompat`, `ProviderOptions`, **`Codecs`** (общие шаги кодеков) |
| `spi.http`, `spi.catalog`, `spi.provider` | `HttpCall`/`HttpReply`/`HttpTransport`; фиды и live-листинги; `ProviderBundle`, `ProviderApi`, `RawApi` |
| `vendors.openai` (+`.internal`) | `OpenAi` (RESPONSES, CHAT_COMPLETIONS), `OpenAiCompatible` (пресеты: OpenRouter, DeepSeek, xAI, Qwen, Mistral, Groq, Ollama, LM Studio, vLLM, LiteLLM, Azure), `OpenAiCompletionsCompat`, `OpenAiResponsesOptions`; кодеки `CompletionsCodec`, `ResponsesCodec` |
| `vendors.anthropic` (+`.internal`) | `Anthropic`, `AnthropicCompat`, `AnthropicOptions`, `AnthropicTools`; `MessagesCodec` |
| `vendors.google` (+`.internal`) | `Gemini`, `GeminiOptions`, `GeminiCaches`; `GenerateContentCodec`, `CachesClient` |
| `providers` | `Providers` (индекс пресетов), `ProvidersConfig` (JSON-конфиг провайдеров без секретов) |
| `internal.*` | ядро: `core` (Engine, Call, Resolver, Handoff, Accumulator, DefaultChatStream, CodecContext, Notes), `http` (JdkHttpTransport, FrameReader, HttpErrors), `auth` (AuthResolver, DefaultAuth, stores, `oauth.StandardOAuth`, `oauth.Loopback`), `catalog`, `cache`, `json`, `serialization`, `validation.Checks` |
| `testing` | `FakeProvider`, `FakeWireApi` (эталонный маленький кодек), `ScriptedReply`, `RecordingListener` |

### Ключевые контракты (не ломать)

- **Кодек (`WireApi`) чистый**: без I/O, кредов, ретраев, часов. `encode(ApiRequest, EncodeContext) → HttpCall` с URI
  относительно baseUrl; `decode(HttpReply, DecodeContext) → AssistantMessage`; `streamDecoder(ctx)` — события по
  фреймам. Тело запроса должно быть **байт-стабильным** для равных запросов (ключи кэша ответов, prompt caching).
- **Мягкие/жёсткие адаптации**: `ctx.adapt(Warning)` — предупреждение, но `InvalidRequestException(unsupported_feature)`
  под `strict()`; `ctx.warn(Warning)` — только предупреждение (никогда не падает). Невыразимое жёсткое — исключение.
- **Стрим**: ядро (`Accumulator`) собирает части по `index`; `PartEnd` — авторитетная часть (заменяет дельты);
  `Done` несёт stop reason/usage/ids, контент подставляет ядро. Без `Done` — `stream_interrupted`.
- **Hand-off** (`Handoff`): кодек получает историю, уже адаптированную к целевой модели: подписи reasoning и
  `Content.Unknown` приходят только в своих (same-origin) сообщениях; чужие tool-call id нормализуются через
  `WireApi.normalizeToolCallId`.
- **Usage** — непересекающиеся корзины: `input` без cache read/write; `output` включает reasoning.
- **Ошибки HTTP**: `WireApi.decodeError` → `HttpErrors.details` (статус → код, `Retry-After`, overflow-паттерны);
  ошибки внутри стрима — `Codecs.streamError(type, message)`.

### Правила архитектуры (проверяет `ArchitectureTest`, ArchUnit)

- `vendors..`, `catalog.internal..`, `providers..`, `testing..` **не зависят от `internal..`**.
- `internal..` не зависит от `vendors..`/`providers..`; вендоры не зависят друг от друга.
- Реализации `WireApi` в `vendors` не используют `java.net.http..` и `net.ai.gate.auth..`.
- В публичных сигнатурах нет internal-типов; в пакете ≤ 20 top-level типов; запрещены пакеты `util/impl/manager/helper/common/misc`.
- `json` пакет зависит только от себя, `internal.json`, JDK, jspecify.

### Стиль кода

- Javadoc — Markdown `///` (JEP 467), doclint строгий; комментарии объясняют *почему*, не *что*.
- Компактный современный Java: records, sealed + switch, pattern matching, `var`, стримы там, где читаемо.
  Без FQN в теле кода (импорты), без неиспользуемых импортов (javac их не ловит — проверить вручную/скриптом).
- `.toList()` возвращает неизменяемый список — если потом `set()`, оборачивать в `new ArrayList<>(…)`.
- В Java-регэкспах `"[?]"` вместо `"\\?"`, если правишь файл через shell/python (экранирование ломается).
- Тесты — доказательства, а не церемония: end-to-end через реальный пресет и ядро с подменой только сети.

### Инструменты тестирования (уже есть)

- `llm/src/test/java/net/ai/gate/vendors/WireScript.java` — сценарный HTTP-эндпоинт: `json(...)`, `sse(...)`,
  `error(status, body)`, `calls()`, `body(i)`, `runtime(provider, "ENV_KEY")`. Образцы: `vendors/*/*WireTest.java`.
- `internal/auth/oauth/StandardOAuthTest.java` — in-process OAuth-издатель на `com.sun.net.httpserver.HttpServer`,
  «браузер», который вызывает loopback-callback. Образец для Codex OAuth.
- `catalog/ModelsDevFeedTest.java` + `src/test/resources/net/ai/gate/catalog/models-dev-sample.json` — контракт фида.
- `testing.Fixtures`, `FakeProvider` — для тестов ядра.

---

## 2. Задачи этапа

Для каждой задачи: реализовать, покрыть тестом наблюдаемое поведение, обновить Javadoc/README. Держать объём
минимальным; переиспользовать `StandardOAuth`, `ResponsesCodec`, `Codecs`.

### Задача 1. Подписочный OAuth: ChatGPT Plus / Pro (OpenAI Codex) — РЕАЛИЗОВАТЬ ПОЛНОСТЬЮ

**Цель**: пользователь с подпиской ChatGPT Plus/Pro входит через браузер (или device code) и вызывает модели Codex
через тот же `Llm`, без API-ключа.

**Эталоны (читать, не копировать)**:
- `examples/ai/src/auth/oauth/openai-codex.ts` (вход, refresh, device flow, account id);
- `examples/ai/src/api/openai-codex-responses.ts` (транспорт: URL, заголовки, тело, ошибки лимитов);
- `examples2/backend/src/main/java/com/unbi/engine/llm/auth/ChatGptOAuthClient.java`, `CodexCredentials.java`
  (Java-версия; также чтение `~/.codex/auth.json` / `$CODEX_HOME/auth.json` только на чтение).

**Факты из эталонов** (перепроверить веб-поиском, см. задачу 9):
- Клиент: `client_id = app_EMoamEEZ73f0CkXaXp7hrann`; issuer `https://auth.openai.com`;
  authorize `https://auth.openai.com/oauth/authorize`, token `https://auth.openai.com/oauth/token` (form-urlencoded).
- Redirect **фиксированный**: `http://localhost:1455/auth/callback` (loopback на порту 1455, host `localhost`).
- Scope: `openid profile email offline_access`. Доп. параметры authorize: `id_token_add_organizations=true`,
  `codex_cli_simplified_flow=true`, `originator=<имя клиента>`; плюс стандартные PKCE S256 и `state`.
- Refresh: `grant_type=refresh_token`, `refresh_token`, `client_id` (form).
- Account id: JWT access-токена, claim `https://api.openai.com/auth` → `chatgpt_account_id`; обязателен в заголовке.
- Device flow **нестандартный**: `POST https://auth.openai.com/api/accounts/deviceauth/usercode` (JSON
  `{client_id}`) → `device_auth_id`, `user_code`, `interval`; пользователь идёт на `https://auth.openai.com/codex/device`;
  опрос `POST .../api/accounts/deviceauth/token` (JSON `{device_auth_id, user_code}`) → `authorization_code` +
  `code_verifier` (403/404 = pending), затем обычный обмен кода на `/oauth/token` с
  `redirect_uri=https://auth.openai.com/deviceauth/callback`.
- Транспорт: base `https://chatgpt.com/backend-api`, путь `codex/responses` (Responses-формат, SSE).
  Заголовки: `Authorization: Bearer <access>`, `chatgpt-account-id`, `originator`, `OpenAI-Beta: responses=experimental`,
  `accept: text/event-stream`, опционально `session-id` / `x-client-request-id` = sessionId.
  Тело: `store: false` (обязательно, `true` отклоняется), непустой `instructions`, `stream: true`,
  `include: ["reasoning.encrypted_content"]`, `prompt_cache_key`, `text.verbosity`, `reasoning {effort, summary}`.
  Проверить, какие параметры backend **отклоняет** (в эталоне не шлются `max_output_tokens`, `temperature`).
- Ошибки лимита подписки: коды `usage_limit_reached`, `usage_not_included`, `rate_limit_exceeded` / HTTP 429 →
  `RateLimitedException` (`quota_exhausted` для исчерпанной подписки).

**Дизайн (предложение, выбрать самый компактный вариант)**:
- OAuth: расширить `OAuthConfig` минимально — `authorizationParameters(Map<String,String>)` (доп. параметры
  authorize) и способ добавить заголовки из учётки (account id) в `toAuth`: например, `OAuthConfig.accountHeader(name)`
  или `tokenResponseMapper`, кладущий `chatgpt_account_id` в `OAuthCredential.account()`/`extra()`. Fixed redirect уже
  поддержан (`redirectUri` на loopback → слушаем его порт и путь). Нестандартный device flow — отдельная
  маленькая стратегия (реализация `OAuthAuth` в `internal.auth.oauth` или параметризация `StandardOAuth`), без
  дублирования HTTP-кода `StandardOAuth`.
- Транспорт: пресет `OpenAi.codex()` (или `OpenAiCompatible.chatGptCodex()`), id провайдера `openai-codex`,
  `auth(OAuthAuth…)`, baseUrl `https://chatgpt.com/backend-api`, API — диалект Responses. Предпочтительно не новый
  кодек, а `ResponsesCodec` + compat-флаги диалекта (новый `ApiCompat` для Responses, напр. `OpenAiResponsesCompat`
  с `path`, `requireStreaming`, `requiredInstructions`, `omitMaxOutputTokens`, `omitSampling`) — это же пригодится
  для Azure/xAI. Если backend принимает только стрим, а `complete()` ядра ожидает JSON-тело: либо кодек разбирает SSE
  в `decode()` (прогнать свой `StreamDecoder` по фреймам тела), либо ядро переводит такой API в стрим и собирает
  результат — выбрать меньший по коду и обосновать.
- Модели Codex в каталоге: добавить записи провайдера `openai-codex` (из models.dev, если там есть подходящий
  провайдер; иначе — через `ModelsDevFeed.PROVIDERS` копировать модели `openai` с id из эталона `openai-codex.models.ts`).
- Опционально (если ≤ 40 строк): импорт существующего входа Codex CLI из `~/.codex/auth.json` только на чтение.

**Критерии приёмки**:
- `llm.auth().login("openai-codex", AuthType.OAUTH, AuthInteraction.console())` открывает браузер, ловит callback на
  `localhost:1455`, сохраняет учётку с account id; refresh работает; `revoke/logout` работают.
- `llm.complete/stream(llm.model("openai-codex", "<model>"), …)` шлёт корректные URL, заголовки, тело.
- Тесты: in-process издатель (как `StandardOAuthTest`) для браузерного и device-потоков; `WireScript` для транспорта
  (заголовки, `store:false`, instructions, SSE-разбор, ошибки лимитов). Живой прогон делает пользователь.
- README: раздел «ChatGPT subscription (Codex)» с примером и оговоркой, что это неофициальный путь для сторонних
  клиентов и может измениться со стороны OpenAI.

**Claude Pro/Max (подписка Claude Code) — НЕ реализовывать**: условия Anthropic ограничивают подписку собственными
приложениями; для Claude использовать API-ключ.

### Задача 2. Провайдеры Bedrock / Vertex AI / Mistral native / Azure (Entra) — оставить как есть

### Задача 3. Files API и embeddings — оставить как есть

### Задача 4. Картинки и аудио в ответах модели — ИСПРАВИТЬ

Сейчас изображения/аудио, которые **возвращает** модель, декодируются как `Content.Unknown`. Типы уже есть:
`Content.Image` (с `Source.Inline`) и `Content.Audio` (с `transcript`). Нужно декодировать в них:
- Gemini: части `inlineData {mimeType, data}` (image/*, audio/*) в ответе → `Content.Image.of(new Source.Inline(bytes), mime, null)` /
  `Content.Audio.of(bytes, format, null)`; в стриме — `PartEnd` с этой частью. Replay в `model`-роли — обратно в `inlineData`.
- OpenAI Responses: элемент `image_generation_call` (`result` = base64) → `Content.Image`; сохранить исходный item для
  same-origin replay (как у reasoning — через provider data или `Unknown` рядом) — выбрать компактно.
- OpenAI Chat Completions: `message.audio {id, data, transcript, format}` (audio output моделей) → `Content.Audio`;
  стрим — `delta.audio` (накопить base64 и transcript, `PartEnd` в конце).
- Anthropic изображения не генерирует — ничего.
- Убедиться, что `Accumulator.sizeOf` и `ConversationJson` корректно работают с inline-медиа из ответа, а `Handoff`
  не шлёт ассистентские картинки туда, где их нельзя (адаптация с предупреждением).
Приёмка: тесты декодирования (complete и stream) для Gemini image/audio, Responses image, Completions audio.

### Задача 5. Anthropic: citations и «thinking + принудительный инструмент» — ИСПРАВИТЬ

- **Citations**: текстовые блоки несут `citations: [...]` (типы `web_search_result_location` {url, title, cited_text},
  `char_location` / `page_location` / `content_block_location` для документов). Маппинг в `Content.Citation(title,
  URI source, startIndex, endIndex)`: цитата относится ко всему блоку → `0..text.length()`; для документов без URL —
  решить (например, `URI` вида `document:<index>` или пропустить) и задокументировать. В стриме — `citations_delta`
  (накопить на блоке, выдать `PartEnd(Text.of(text, citations))` на `content_block_stop` для текстовых блоков с цитатами).
  Replay текста с цитатами — как обычный текст (цитаты не отправляются обратно, проверить документацию).
- **Thinking + tool_choice any/tool**: API отклоняет. Адаптация: при включённом thinking и `ToolChoice.Required`/`Only`
  понизить до `auto` через `ctx.adapt(new Warning("option_adapted", …))` (под `strict()` — ошибка). Проверить по
  документации, нет ли уже поддержки (тогда ничего не делать).
Приёмка: тесты в `AnthropicWireTest` (complete + stream с цитатами; адаптация tool_choice).

### Задача 6. OpenAI reasoning-модели и `temperature`/`top_p` — ИСПРАВИТЬ

Требование пользователя: не отправлять, писать предупреждение в лог, ничего не ломать (т.е. `ctx.warn`, не `ctx.adapt`).
- Источник истины — каталог: в models.dev у модели есть поле `"temperature": false`. Добавить в модель признак
  (например, `Capability.TEMPERATURE` = `UNSUPPORTED`/`SUPPORTED`, маппинг в `ModelsDevFeed`, в каноническом JSON
  через `capabilities`) и перегенерировать `models.json` (`./gradlew updateModelCatalog`).
- `ResponsesCodec` и `CompletionsCodec`: если модель не поддерживает temperature (или, когда в каталоге нет данных,
  для семейств o1/o3/o4/gpt-5* при reasoning ≠ OFF — эвристика по id, как принято в эталоне), не слать
  `temperature` и `top_p`, добавить `Warning("option_dropped", …)` через `ctx.warn`.
- Логирование: проверить, пишет ли ядро предупреждения ответа в `System.Logger("net.ai.gate")`; если нет — добавить
  одну строку уровня `WARNING`/`INFO` в общем месте (`Notes`/`Engine.finish`), а не в каждом кодеке.
- То же правило для Anthropic при thinking уже есть (`option_dropped`) — привести к единому поведению.
Приёмка: тест — gpt-5 с `temperature(0.2)` → в теле нет `temperature`, в `reply.warnings()` есть `option_dropped`, запрос успешен.

### Задача 7. Mistral: id вызова инструмента — ровно 9 символов `[a-zA-Z0-9]` — ИСПРАВИТЬ

- Флаг в `OpenAiCompletionsCompat` (например, `toolCallIdLength(9)` / `ToolCallIdFormat.MISTRAL`), выставить в пресете
  `OpenAiCompatible.mistral()`.
- `WireApi.normalizeToolCallId` не видит compat, поэтому переписывание делать в `CompletionsCodec.encode`:
  детерминированное отображение каждого id (в `tool_calls[].id` и `tool_call_id` результатов) в 9 символов
  (например, base62 от хэша; коллизии внутри запроса разрешить), свои 9-символьные id Mistral не трогать.
Приёмка: тест — история с OpenAI-id `call_abc…` отправляется в Mistral с согласованными 9-символьными id.

### Задача 8. `compat` в `ProvidersConfig` — РЕАЛИЗОВАТЬ (нужно для coding agent harness)

- Сейчас `ProvidersConfig.read` бросает `'compat' is not supported`, `write` — ошибку, если compat отличается от пресета.
- Каноническая JSON-форма флагов: у каждого `ApiCompat` (`OpenAiCompletionsCompat`, `AnthropicCompat`, новый
  Responses-compat из задачи 1) — `toJson()` (только заданные поля, enum — в нижнем регистре) и `fromJson(JsonObject)`
  со строгой проверкой (неизвестное поле — ошибка с именем поля; префикс `x-` допустим, как в остальном конфиге).
- `ProvidersConfig`: читать `compat` провайдера, выбирать тип по API провайдера (default API пресета/шаблона) —
  простой `switch` в `providers`, без реестра/рефлексии; писать — разницу с пресетом.
- Опционально: `compat` у отдельной модели в `models[]` конфига (если это ≤ 20 строк).
- Обновить Javadoc класса (убрать «Pending»), `ProvidersConfigTest` (round-trip: vLLM с `reasoningFormat: qwen`,
  `maxTokensField: max_tokens`, `developerRole: false`; ошибка на неизвестном поле).

### Задача 9. Сверка с живыми API без доступа — через веб-поиск и документацию

У пользователя нет ключей ко всем провайдерам. Проверить по официальной документации и рабочим open-source
примерам (SDK провайдеров, LiteLLM, Vercel AI SDK, pi-ai, OpenRouter docs) и исправить расхождения:
- Anthropic: structured outputs — `output_config.format` vs `output_format` + beta-заголовок
  (`structured-outputs-*`); `thinking.type: adaptive` + `output_config.effort` (какие модели, какие значения:
  `low|medium|high|xhigh|max`); лимиты `budget_tokens`; допустимость `temperature` с thinking; версии hosted tools
  (`web_search_2025…`, `code_execution_2025…`, `text_editor_…`, `computer_…`) и их beta-заголовки.
- Gemini: `parametersJsonSchema` (functionDeclarations) и `responseJsonSchema` (generationConfig) — имена и поддержка
  в `v1beta`; `thinkingConfig.thinkingLevel` (значения, какие модели) vs `thinkingBudget`; `cachedContents` (минимум
  токенов, формат `ttl`, `updateMask`).
- OpenAI: значения `reasoning.effort` по моделям (`none`, `minimal`, `xhigh`), `max_output_tokens` минимум,
  `prompt_cache_retention`, `text.format` json_schema, ограничения reasoning-моделей на `temperature/top_p/seed`;
  Chat Completions `max_completion_tokens`, `stream_options`.
- Codex backend (задача 1): актуальные URL, заголовки, отклоняемые параметры.
- Совместимые провайдеры: DeepSeek (`thinking`, `reasoning_content` replay), Qwen (`enable_thinking`),
  OpenRouter (`reasoning`, `x-session-id`), Groq, xAI.
Фиксировать найденное в тестовых фикстурах (`*WireTest`) и ссылках в Javadoc кодеков; источники — в progress-файл.

Дополнительно (рекомендуется): opt-in живые smoke-тесты — Gradle-задача `liveTest` по образцу `updateModelCatalog`,
тесты с `@EnabledIfEnvironmentVariable` (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, …): complete, stream,
tool call + второй ход, reasoning replay, structured output, cacheRead > 0 на повторе, `llm.test(model)`. Малые
модели и `maxTokens`, чтобы прогон стоил центы. Пользователь запустит их сам.

---

## 3. Готово, когда

- Задачи 1, 4, 5, 6, 7, 8 реализованы и покрыты тестами; задача 9 — расхождения исправлены, источники записаны.
- `./gradlew clean build --offline` зелёный (`-Werror`, doclint, ArchUnit), нет неиспользуемых импортов.
- `llm/README.md` и `progress_and_session_info.md` обновлены; в ответе пользователю — что сделано, что проверено,
  что не проверено вживую, риски.
