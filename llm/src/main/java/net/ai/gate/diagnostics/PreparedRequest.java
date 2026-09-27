package net.ai.gate.diagnostics;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonValue;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
import net.ai.gate.spi.http.HttpCall;
import org.jspecify.annotations.Nullable;

/// Immutable result of `preview()`: exactly what `complete()` would transmit, or why it would not. Credentials
/// appear only as environment-variable placeholders such as `$ANTHROPIC_API_KEY`, in the headers the provider's
/// auth strategy owns.
public final class PreparedRequest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$[A-Z_][A-Z0-9_]*");

    private final ModelRef model;
    private final String api;
    private final @Nullable HttpCall call;
    private final List<Warning> warnings, notes;
    private final List<String> problems;
    private final Set<String> credentialHeaders;

    private PreparedRequest(ModelRef model, String api, @Nullable HttpCall call, List<Warning> warnings,
                            List<Warning> notes, List<String> problems, Set<String> credentialHeaders) {
        this.model = model; this.api = api; this.call = call;
        this.warnings = List.copyOf(warnings); this.notes = List.copyOf(notes); this.problems = List.copyOf(problems);
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(credentialHeaders);
        this.credentialHeaders = names;
    }

    /// For the core: a sendable request with an absolute URI; `credentialHeaders` names the headers whose values are
    /// placeholders.
    public static PreparedRequest of(ModelRef model, String api, HttpCall call, List<Warning> warnings, List<Warning> notes,
                                     Set<String> credentialHeaders) {
        return new PreparedRequest(model, api, call, warnings, notes, List.of(), credentialHeaders);
    }

    /// For the core: why the request would be rejected.
    public static PreparedRequest rejected(ModelRef model, String api, List<String> problems, List<Warning> warnings) {
        return new PreparedRequest(model, api, null, warnings, List.of(), problems, Set.of());
    }

    /// Field paths and reasons; empty when sendable.
    public List<String> problems() { return problems; }
    public boolean sendable() { return call != null; }
    public ModelRef model() { return model; }
    public String api() { return api; }
    public String method() { return call == null ? "" : call.method(); }
    public URI uri() { return call == null ? URI.create("") : call.uri(); }
    /// Every header as sent, credentials as placeholders.
    public Map<String, String> headers() { return call == null ? Map.of() : call.headers(); }
    /// The headers carrying credential placeholders.
    public Set<String> credentialHeaders() { return credentialHeaders; }
    /// Stable key order: byte-identical to the wire.
    public JsonValue body() { return call == null ? JsonNull.INSTANCE : call.body().orElse(JsonNull.INSTANCE); }
    /// Adaptations, as the reply would carry them.
    public List<Warning> warnings() { return warnings; }
    /// Derived values (output limit from the catalog, reasoning mapping, cache markers); informational.
    public List<Warning> notes() { return notes; }

    /// A runnable POSIX shell command. Every literal is single-quoted, so quotes, `$`, backticks and newlines in
    /// the request stay literal; only the `$VARIABLE` placeholders of credential headers are left expandable.
    public String toCurl() {
        if (call == null) return "# not sendable: " + String.join("; ", problems);
        var command = new StringBuilder("curl -X ").append(call.method()).append(' ').append(quote(call.uri().toString()));
        call.headers().forEach((name, value) -> command.append(" \\\n  -H ")
                .append(credentialHeaders.contains(name) ? credentialHeader(name, value) : quote(name + ": " + value)));
        call.body().ifPresent(body -> command.append(" \\\n  -d ").append(quote(body.toJson())));
        return command.toString();
    }

    /// `'x-api-key: '"$ANTHROPIC_API_KEY"`: literal parts single-quoted, placeholders in double quotes.
    private static String credentialHeader(String name, String value) {
        var out = new StringBuilder();
        var literal = new StringBuilder(name).append(": ");
        var matcher = PLACEHOLDER.matcher(value);
        int last = 0;
        while (matcher.find()) {
            literal.append(value, last, matcher.start());
            if (!literal.isEmpty()) out.append(quote(literal.toString()));
            literal.setLength(0);
            out.append('"').append(matcher.group()).append('"');
            last = matcher.end();
        }
        literal.append(value.substring(last));
        if (!literal.isEmpty()) out.append(quote(literal.toString()));
        return out.toString();
    }

    private static String quote(String literal) { return "'" + literal.replace("'", "'\\''") + "'"; }

    @Override public String toString() {
        return "PreparedRequest[" + model + ", " + api + (sendable() ? ", " + method() + " " + uri() : ", problems=" + problems) + "]";
    }
}
