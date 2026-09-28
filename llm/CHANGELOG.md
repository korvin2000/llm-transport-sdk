# Changelog

`0.x`: APIs marked `@ApiStatus.Experimental` may change in minor versions; each promotion to stable gets an entry.

## 0.1.0 (unreleased) — transport facts for agent hosts

Added (source-compatible):
- Accounting after failure: `ChatEvent.UsageUpdate`, `Usage.finalForCall()`; a partial reply (`LlmException.partial()`,
  `RequestEvent.Finished`) carries the last observed usage, priced where possible.
- Partial replies mark cut-off parts: `AssistantMessage.incompleteParts()` / `complete()`; they are never replayed and
  cannot be answered by a tool result.
- Cache-write classes: `Usage.cacheWrites()` (`SHORT` 5 min, `LONG` 1 h), `Prices.cacheWriteLongPerMillion()`; counters
  the wire omits stay absent.
- Per-breakpoint cache retention: `CacheBreakpoint`, `Conversation.Builder.cacheBreakpoint(CacheRetention)`.
- `strict()` now fails billing- and limit-changing adaptations: the `max_tokens` raise above a thinking budget, cache
  markers beyond the maximum, `LONG` retention fallbacks. `ChatOptions.strictCodes(…)` fails chosen warning codes.
- `ToolResult.of(callId, toolName, parts, error)`.
- Attempt ledger: `Attempt`, `ResponseInfo.attemptsDetail()`, `RequestEvent.Finished.attemptsDetail()`.
- UI surface: `ChatOptions.fields(model)`, `Provider.fields()`, `ApiCompat.fields()`, `ProvidersConfig.validate(…)`,
  `RequestEvent.Progress`; `ChatOptions.set` accepts `strictCodes` and `historyPolicy`.
- Archive form of a reply: `AssistantMessage.toJson()` / `fromJson()` (`ai-gate.reply/1`, raw usage and call facts).
- `maven-publish`; the version lives in `gradle.properties`.

Added, experimental:
- `Llm.start(…)` → `LlmCall` with `CallOutcome` (reply or error, partial, usage, cancellation, attempts).
- `Llm.prepare(…)` → `PreparedCall` (request, effective options and conversation, digest); `Llm.start(prepared)`,
  `Llm.complete(prepared)`.
- `Llm.features(model)` → `ApiFeatures`; `WireApi.features(…)`; connection probes `usageFields()`, `toolRoundTrip()`,
  `cacheRoundTrip()` (report kinds `USAGE`, `TOOLS`, `CACHE`).
- `Llm.countTokens(prepared)` → `TokenCount` (Anthropic `count_tokens`, OpenAI `responses/input_tokens`, Gemini
  `countTokens`, else a registered `Tokenizer`, else an estimate with its margin).
- `HistoryPolicy` (`SAME_ORIGIN_REQUIRED`, `REJECT_LOSSY`, `ALLOW_ADAPTATION`) and `Llm.check(…)` → `HistoryIssue`s.

Forms: `ai-gate.conversation/2` and `ai-gate.options/2` are written only when a new member is present; version 1 is
still written otherwise and both are read.

Not yet: first-class continuation and compaction (`previous_response_id` stays a raw Responses option); a Kotlin
coroutine artifact.
