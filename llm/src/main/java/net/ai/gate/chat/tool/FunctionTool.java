package net.ai.gate.chat.tool;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonSchema;
import org.jspecify.annotations.Nullable;

/// Immutable portable function tool.
public final class FunctionTool implements Tool {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final JsonSchema NO_PARAMETERS = JsonSchema.of(Json.object("type", "object", "properties", Json.object()));

    private final String name;
    private final @Nullable String description;
    private final JsonSchema parameters;
    private final boolean strict;

    private FunctionTool(Builder b) { name = b.name; description = b.description; parameters = b.parameters; strict = b.strict; }

    static Builder builder(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Tool name '" + name + "' must match " + NAME);
        return new Builder(name);
    }

    @Override public String name() { return name; }
    public Optional<String> description() { return Optional.ofNullable(description); }
    public JsonSchema parameters() { return parameters; }
    /// Ask APIs that support it to enforce the schema exactly.
    public boolean strict() { return strict; }

    @Override public boolean equals(Object o) {
        return o instanceof FunctionTool t && name.equals(t.name) && Objects.equals(description, t.description)
                && parameters.equals(t.parameters) && strict == t.strict;
    }

    @Override public int hashCode() { return Objects.hash(name, description, parameters, strict); }
    @Override public String toString() { return "FunctionTool[" + name + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private final String name;
        private @Nullable String description;
        private JsonSchema parameters = NO_PARAMETERS;
        private boolean strict;

        private Builder(String name) { this.name = name; }

        public Builder description(String text) { description = text; return this; }
        public Builder parameters(JsonSchema schema) { parameters = schema; return this; }
        /// Derives the schema from a record; see `Json.schemaOf`.
        public Builder parameters(Class<?> recordType) { parameters = Json.schemaOf(recordType); strict = true; return this; }
        public Builder strict() { strict = true; return this; }
        public FunctionTool build() { return new FunctionTool(this); }
    }
}
