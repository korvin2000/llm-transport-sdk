package net.ai.gate.internal.http;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// One redaction policy for wire logs, previews, descriptions and errors: credential headers and query strings
/// never appear. Every credential header rejected as a host header (`Checks.PROTECTED_HEADERS`) is redacted here.
public final class Redaction {
    public static final String REDACTED = "<redacted>";
    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "proxy-authorization", "x-api-key", "api-key",
            "x-goog-api-key", "cookie", "set-cookie", "x-amz-security-token");

    private Redaction() { }

    public static boolean secret(String header) {
        var name = header.toLowerCase(Locale.ROOT);
        return SECRET_HEADERS.contains(name) || name.contains("token") || name.contains("secret") || name.endsWith("-key")
                || name.contains("password") || name.contains("credential");
    }

    /// The same headers with secret values replaced; names keep their order, compared case-insensitively.
    public static Map<String, String> headers(Map<String, String> headers) {
        var redacted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> redacted.put(name, secret(name) ? REDACTED : value));
        return redacted;
    }

    /// Query strings may carry keys: they are replaced as a whole; user info never prints.
    public static String uri(URI uri) {
        var text = uri.toString();
        if (uri.getRawUserInfo() != null) text = text.replace(uri.getRawUserInfo() + "@", REDACTED + "@");
        int query = text.indexOf('?');
        return query < 0 ? text : text.substring(0, query) + "?" + REDACTED;
    }
}
