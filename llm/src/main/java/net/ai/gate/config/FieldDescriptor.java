package net.ai.gate.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.model.Model;
import org.jspecify.annotations.Nullable;

/// Immutable description of one form field. "Connect provider" forms (`ApiKeyAuth.fields()`) and model parameter
/// forms ([Model#parameters()]) share it, so one renderer draws both. Values travel back as canonical strings
/// through `ChatOptions.Builder.set(key, raw)` or a credential's settings.
public final class FieldDescriptor {
    public enum Kind { TEXT, SECRET, URL, INTEGER, DECIMAL, BOOLEAN, CHOICE, DURATION, JSON }

    private final String key, label;
    private final Kind kind;
    private final boolean required;
    private final @Nullable String defaultValue, help, group, unit;
    private final List<String> choices;
    private final @Nullable BigDecimal min, max;

    private FieldDescriptor(Builder b) {
        key = b.key; label = b.label == null ? b.key : b.label; kind = b.kind; required = b.required;
        defaultValue = b.defaultValue; help = b.help; group = b.group; unit = b.unit; choices = b.choices;
        min = b.min; max = b.max;
    }

    public static Builder builder(String key, Kind kind) { return new Builder(Checks.notBlank(key, "Field key"), kind); }

    /// `apiKey`, `CLOUDFLARE_ACCOUNT_ID`, `temperature`, `reasoning`…
    public String key() { return key; }
    public String label() { return label; }
    public Kind kind() { return kind; }
    public boolean required() { return required; }
    public Optional<String> defaultValue() { return Optional.ofNullable(defaultValue); }
    public Optional<String> help() { return Optional.ofNullable(help); }
    /// `Connection`, `Generation`, `Sampling`, `Caching`…
    public Optional<String> group() { return Optional.ofNullable(group); }
    public List<String> choices() { return choices; }
    public Optional<BigDecimal> min() { return Optional.ofNullable(min); }
    public Optional<BigDecimal> max() { return Optional.ofNullable(max); }
    /// `tokens`, `s`…
    public Optional<String> unit() { return Optional.ofNullable(unit); }

    @Override public String toString() { return "FieldDescriptor[" + key + ": " + kind + (required ? ", required" : "") + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private final String key;
        private final Kind kind;
        private @Nullable String label, defaultValue, help, group, unit;
        private boolean required;
        private List<String> choices = List.of();
        private @Nullable BigDecimal min, max;

        private Builder(String key, Kind kind) { this.key = key; this.kind = kind; }

        public Builder label(String text) { label = text; return this; }
        public Builder required() { required = true; return this; }
        public Builder defaultValue(String value) { defaultValue = value; return this; }
        public Builder help(String text) { help = text; return this; }
        public Builder group(String name) { group = name; return this; }
        public Builder choices(List<String> values) { choices = List.copyOf(values); return this; }
        public Builder range(@Nullable BigDecimal minimum, @Nullable BigDecimal maximum) { min = minimum; max = maximum; return this; }
        public Builder unit(String name) { unit = name; return this; }

        public FieldDescriptor build() {
            if (kind == Kind.CHOICE && choices.isEmpty()) throw new IllegalArgumentException("CHOICE field '" + key + "' needs choices");
            return new FieldDescriptor(this);
        }
    }
}
