package net.ai.gate.error;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.ai.gate.internal.validation.Checks;

/// Immutable open value type with well-known constants; APIs may add codes. Callers branch on exception type and code.
public final class ErrorCode {
    private static final Map<String, ErrorCode> KNOWN = new ConcurrentHashMap<>();

    public static final ErrorCode
            INVALID_REQUEST = known("invalid_request"), UNSUPPORTED_FEATURE = known("unsupported_feature"),
            CONTEXT_OVERFLOW = known("context_overflow"), MODEL_NOT_FOUND = known("model_not_found"),
            REQUEST_TOO_LARGE = known("request_too_large"), INVALID_CREDENTIALS = known("invalid_credentials"),
            LOGIN_REQUIRED = known("login_required"), REFRESH_FAILED = known("refresh_failed"),
            LOGIN_CANCELLED = known("login_cancelled"), PERMISSION_DENIED = known("permission_denied"),
            CREDENTIAL_STORE = known("credential_store"), RATE_LIMITED = known("rate_limited"),
            QUOTA_EXHAUSTED = known("quota_exhausted"), OVERLOADED = known("overloaded"),
            SERVER_ERROR = known("server_error"), CONNECT_FAILED = known("connect_failed"),
            STREAM_INTERRUPTED = known("stream_interrupted"), OUTCOME_UNKNOWN = known("outcome_unknown"),
            MALFORMED_RESPONSE = known("malformed_response"), INVALID_TOOL_ARGUMENTS = known("invalid_tool_arguments"),
            OUTPUT_INVALID = known("output_invalid"), OUTPUT_TRUNCATED = known("output_truncated"),
            OUTPUT_REFUSED = known("output_refused"), DEADLINE_EXCEEDED = known("deadline_exceeded"),
            STREAM_IDLE_TIMEOUT = known("stream_idle_timeout"), CANCELLED = known("cancelled"),
            CACHE_MISS = known("cache_miss"),
            // the Continuation a call continued from is unknown to the API: discarded, never stored, or of another project
            CONTINUATION_EXPIRED = known("continuation_expired");

    private final String value;

    private ErrorCode(String value) { this.value = value; }

    private static ErrorCode known(String value) {
        var code = new ErrorCode(value);
        KNOWN.put(value, code);
        return code;
    }

    public static ErrorCode of(String value) {
        var known = KNOWN.get(value);
        return known != null ? known : new ErrorCode(Checks.notBlank(value, "Error code"));
    }

    public String value() { return value; }

    @Override public boolean equals(Object o) { return o instanceof ErrorCode c && value.equals(c.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return value; }
}
