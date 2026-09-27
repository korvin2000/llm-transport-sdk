package net.ai.gate.internal.validation;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Argument rules shared by the value types of the API; each rule has one owner here. Module-internal.
public final class Checks {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /// Headers owned by the SDK — credentials, content negotiation and protocol versions — rejected as host headers.
    /// One policy with `Redaction`: everything protected here is also redacted there.
    public static final Set<String> PROTECTED_HEADERS = Set.of("authorization", "proxy-authorization", "x-api-key", "api-key",
            "x-goog-api-key", "cookie", "set-cookie", "content-type", "content-length", "host", "anthropic-version",
            "transfer-encoding", "connection");

    private Checks() { }

    public static String id(String id, String what) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException(what + " '" + id + "' must match " + ID);
        return id;
    }

    public static String notBlank(String value, String what) {
        if (value.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
        return value;
    }

    public static Duration positive(Duration value, String what) {
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(what + " must be positive: " + value);
        return value;
    }

    public static int positive(int value, String what) {
        if (value <= 0) throw new IllegalArgumentException(what + " must be positive: " + value);
        return value;
    }

    /// A host-settable header name: a valid HTTP token that the SDK does not own.
    public static String header(String name) {
        if (!HEADER_NAME.matcher(name).matches()) throw new IllegalArgumentException("Not a valid header name: '" + name + "'");
        if (PROTECTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Header '" + name + "' is controlled by the SDK; use the provider's auth "
                    + "strategy or API revision instead");
        return name;
    }

    /// A header value without line breaks or NUL — nothing that could split a request.
    public static String headerValue(String value, String name) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) throw new IllegalArgumentException("Header '" + name + "' has an invalid character in its value");
        }
        return value;
    }

    /// An absolute `http(s)` URI without user info, query or fragment: the origin credentials are sent to.
    public static URI baseUrl(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")))
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URI with a host: " + uri);
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("baseUrl must not carry user info (credentials belong in the store): " + uri.getHost());
        if (uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("baseUrl must not carry a query or fragment: " + uri);
        return uri;
    }
}
