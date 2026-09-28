package net.ai.gate.internal.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Pattern;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RateLimitedException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.error.TransportException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.spi.http.HttpReply;
import org.jspecify.annotations.Nullable;

/// Error facts from HTTP replies, and the one mapping from error codes to exception types — so every API's errors
/// look alike. Error bodies are bounded: at most [#MAX_ERROR_BODY] characters are retained, as JSON when they parse.
public final class HttpErrors {
    /// Upper bound of the retained error body (characters); longer bodies keep a truncated text.
    public static final int MAX_ERROR_BODY = 64 << 10;
    private static final int MAX_MESSAGE = 300;
    /// Provider-neutral context-overflow wording; codecs add their API's patterns in `decodeError`.
    private static final Pattern OVERFLOW = Pattern.compile(
            "context.{0,20}(length|window|limit)|maximum context|too many (input )?tokens|prompt is too long|exceeds? the (max|context)",
            Pattern.CASE_INSENSITIVE);
    /// Provider codes of an exhausted budget or plan (OpenAI, the ChatGPT Codex backend): not a transient rate limit.
    public static final Set<String> QUOTA = Set.of("insufficient_quota", "usage_limit_reached", "usage_not_included");

    private HttpErrors() { }

    /// The default `WireApi.decodeError`: status-based code, provider message and code, `Retry-After`, request id.
    public static LlmException.Details details(HttpReply reply) {
        int status = reply.status();
        String text;
        try { text = reply.text(); } catch (RuntimeException e) { text = ""; }
        var body = errorBody(text);
        var error = body instanceof JsonObject o ? o.object("error") : null;
        var message = first(error, "message", body instanceof JsonObject o ? string(o, "message") : null);
        if (message == null) message = text.isBlank() ? "no details" : text.substring(0, Math.min(text.length(), MAX_MESSAGE));
        else if (message.length() > MAX_MESSAGE) message = message.substring(0, MAX_MESSAGE) + "…";
        var code = switch (status) {
            case 400, 422 -> OVERFLOW.matcher(message).find() ? ErrorCode.CONTEXT_OVERFLOW : ErrorCode.INVALID_REQUEST;
            case 401 -> ErrorCode.INVALID_CREDENTIALS;
            case 402 -> ErrorCode.QUOTA_EXHAUSTED;
            case 403 -> ErrorCode.PERMISSION_DENIED;
            case 404 -> ErrorCode.MODEL_NOT_FOUND;
            case 408, 504 -> ErrorCode.DEADLINE_EXCEEDED;
            case 413 -> OVERFLOW.matcher(message).find() ? ErrorCode.CONTEXT_OVERFLOW : ErrorCode.REQUEST_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503, 529 -> ErrorCode.OVERLOADED;
            default -> status >= 500 ? ErrorCode.SERVER_ERROR : ErrorCode.INVALID_REQUEST;
        };
        var providerCode = first(error, "type", error == null ? null : string(error, "code"));
        if (QUOTA.contains(String.valueOf(providerCode))) code = ErrorCode.QUOTA_EXHAUSTED;
        return LlmException.Details.builder(code, "HTTP " + status + ": " + message).httpStatus(status).providerCode(providerCode)
                .providerRequestId(reply.header("x-request-id").or(() -> reply.header("request-id")).orElse(null))
                .retryAfter(retryAfter(reply)).outcomeUnknown(status == 500 || status == 502 || status == 504).errorBody(body).build();
    }

    /// The bounded body: parsed JSON when it parses and fits, else truncated text, else nothing.
    private static @Nullable JsonValue errorBody(String text) {
        if (text.isEmpty()) return null;
        if (text.length() <= MAX_ERROR_BODY) {
            try { return Json.parse(text); } catch (RuntimeException e) { return JsonString.of(text); }
        }
        return JsonString.of(text.substring(0, MAX_ERROR_BODY) + "…");
    }

    /// The exception type for an error code; unknown codes fall back by HTTP status.
    public static LlmException exception(LlmException.Details d, @Nullable Throwable cause) {
        return switch (d.code().value()) {
            case "rate_limited", "quota_exhausted" -> new RateLimitedException(d, cause);
            case "invalid_credentials", "login_required", "refresh_failed", "login_cancelled", "permission_denied",
                 "credential_store" -> new AuthenticationException(d, cause);
            case "overloaded", "server_error" -> new ProviderException(d, cause);
            case "connect_failed", "stream_interrupted", "outcome_unknown" -> new TransportException(d, cause);
            case "malformed_response", "invalid_tool_arguments", "output_invalid", "output_truncated", "output_refused" ->
                    new InvalidResponseException(d, cause);
            case "deadline_exceeded", "stream_idle_timeout" -> new RequestTimeoutException(d, cause);
            case "cancelled" -> new RequestCancelledException(d, cause);
            case "continuation_expired" -> new InvalidRequestException(d, cause);
            default -> {
                int status = d.httpStatus().orElse(0);
                yield status >= 500 ? new ProviderException(d, cause)
                        : status == 401 || status == 403 ? new AuthenticationException(d, cause)
                        : status == 429 ? new RateLimitedException(d, cause)
                        : new InvalidRequestException(d, cause);
            }
        };
    }

    /// The same failure with call facts added; the type follows the code.
    public static LlmException withFacts(LlmException e, String requestId, String providerId, int attempts, @Nullable AssistantMessage partial) {
        var d = e.details().toBuilder().requestId(requestId).providerId(providerId).attempts(attempts);
        if (partial != null) d.partial(partial);
        return exception(d.build(), e.getCause() != null ? e.getCause() : e);
    }

    private static @Nullable Duration retryAfter(HttpReply reply) {
        var millis = reply.header("retry-after-ms");
        if (millis.isPresent()) {
            try { return Duration.ofMillis(Long.parseLong(millis.get().strip())); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        var value = reply.header("retry-after").orElse(null);
        if (value == null) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            try {
                var at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var delay = Duration.between(Instant.now(), at);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static @Nullable String first(@Nullable JsonObject error, String name, @Nullable String fallback) {
        var value = error == null ? null : string(error, name);
        return value != null ? value : fallback;
    }

    private static @Nullable String string(JsonObject object, String name) {
        return object.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }
}
