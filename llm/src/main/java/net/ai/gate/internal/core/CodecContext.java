package net.ai.gate.internal.core;

import java.util.OptionalInt;

import net.ai.gate.Provider;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Model;
import net.ai.gate.spi.protocol.ApiCompat;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;

/// What codecs see of a call: provider, model, merged compat flags, the mapper, and the call's notes.
final class CodecContext implements EncodeContext, DecodeContext {
    private static final long ANSWER_MARGIN = 256;

    private final Provider provider;
    private final Model model;
    private final Notes notes;
    private final JsonMapper json;
    private final long inputEstimate;

    CodecContext(Provider provider, Model model, Notes notes, JsonMapper json, long inputEstimate) {
        this.provider = provider; this.model = model; this.notes = notes; this.json = json; this.inputEstimate = inputEstimate;
    }

    @Override public Provider provider() { return provider; }
    @Override public Model model() { return model; }
    @Override public boolean strict() { return notes.strict(); }
    @Override public JsonMapper json() { return json; }
    @Override public void warn(Warning warning) { notes.warn(warning); }
    @Override public void adapt(Warning warning) { notes.adapt(warning.code(), warning.message()); }

    @SuppressWarnings("unchecked") // every merge step keeps the class of `defaults`
    @Override public <C extends ApiCompat> C compat(C defaults) {
        ApiCompat merged = defaults;
        for (var flags : new ApiCompat[] {provider.compat().orElse(null), model.compat().orElse(null)})
            if (flags != null && flags.getClass() == defaults.getClass()) merged = merged.overriddenBy(flags);
        return (C) merged;
    }

    @Override public OptionalInt defaultMaxTokens() {
        var maximum = model.maxOutputTokens();
        if (maximum.isEmpty()) return OptionalInt.empty();
        long value = maximum.getAsLong();
        var window = model.contextWindow();
        if (window.isPresent()) value = Math.min(value, Math.max(1, window.getAsLong() - inputEstimate - ANSWER_MARGIN));
        int tokens = (int) Math.min(Integer.MAX_VALUE, value);
        notes.note("max_tokens_from_catalog", "max_tokens=" + tokens + " derived from the catalog");
        return OptionalInt.of(tokens);
    }
}
