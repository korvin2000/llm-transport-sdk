package net.ai.gate.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.ai.gate.chat.HistoryIssue;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.metadata.Warning;

/// The warnings, derived-value notes and located history issues of one call. `adapt` is where `strict()` turns a
/// soft adaptation into a failure, and while the call is prepared a warning whose code is in `strictCodes` fails
/// too; decoding a reply never fails for a note. Confined to the call's thread.
final class Notes {
    private final boolean strict;
    private final Set<String> strictCodes;
    private final List<Warning> warnings = new ArrayList<>(), notes = new ArrayList<>();
    private final List<HistoryIssue> issues = new ArrayList<>();
    private boolean preparing = true;

    Notes(boolean strict, Set<String> strictCodes) { this.strict = strict; this.strictCodes = strictCodes; }

    boolean strict() { return strict; }

    void warn(String code, String message) { warn(new Warning(code, message)); }

    void warn(Warning warning) {
        if (preparing && strictCodes.contains(warning.code())) throw rejected(warning.message(), "its code " + warning.code() + " is in strictCodes");
        if (!warnings.contains(warning)) warnings.add(warning);
    }

    /// A soft adaptation: a warning, or `unsupported_feature` under `strict()`.
    void adapt(String code, String message) {
        if (strict) throw rejected(message, "a soft adaptation, rejected because the call is strict");
        warn(code, message);
    }

    /// A located hand-off conversion: recorded for `Llm.check`, then adapted (`soft`) or warned.
    void issue(HistoryIssue issue, boolean soft) {
        issues.add(issue);
        if (soft) adapt(issue.warning().code(), issue.warning().message());
        else warn(issue.warning());
    }

    void note(String code, String message) {
        var note = new Warning(code, message);
        if (!notes.contains(note)) notes.add(note);
    }

    /// The request is encoded: later warnings (decoding) never fail the call.
    void prepared() { preparing = false; }

    /// A prepared call's notes for one execution of it, so executions share no mutable state.
    Notes copy() {
        var copy = new Notes(strict, strictCodes);
        copy.warnings.addAll(warnings);
        copy.notes.addAll(notes);
        copy.issues.addAll(issues);
        copy.preparing = preparing;
        return copy;
    }

    List<Warning> warnings() { return List.copyOf(warnings); }

    List<Warning> notes() { return List.copyOf(notes); }

    List<HistoryIssue> issues() { return List.copyOf(issues); }

    private static InvalidRequestException rejected(String message, String why) {
        return new InvalidRequestException(LlmException.Details.builder(ErrorCode.UNSUPPORTED_FEATURE, message + " (" + why + ")").build());
    }
}
