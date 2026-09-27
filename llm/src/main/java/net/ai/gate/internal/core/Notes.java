package net.ai.gate.internal.core;

import java.util.ArrayList;
import java.util.List;

import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.metadata.Warning;

/// The warnings and derived-value notes of one call. `adapt` is the one place where `strict()` turns a soft
/// adaptation into a failure. Confined to the call's thread.
final class Notes {
    private final boolean strict;
    private final List<Warning> warnings = new ArrayList<>(), notes = new ArrayList<>();

    Notes(boolean strict) { this.strict = strict; }

    boolean strict() { return strict; }

    void warn(String code, String message) { warn(new Warning(code, message)); }

    void warn(Warning warning) { if (!warnings.contains(warning)) warnings.add(warning); }

    /// A soft adaptation: a warning, or `unsupported_feature` under `strict()`.
    void adapt(String code, String message) {
        if (strict) throw new InvalidRequestException(LlmException.Details.builder(ErrorCode.UNSUPPORTED_FEATURE,
                message + " (a soft adaptation, rejected because the call is strict)").build());
        warn(code, message);
    }

    void note(String code, String message) {
        var note = new Warning(code, message);
        if (!notes.contains(note)) notes.add(note);
    }

    List<Warning> warnings() { return List.copyOf(warnings); }

    List<Warning> notes() { return List.copyOf(notes); }
}
