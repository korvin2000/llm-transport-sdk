package net.ai.gate.diagnostics;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import net.ai.gate.error.LlmException;
import org.jspecify.annotations.Nullable;

/// Immutable staged result of `llm.test(model)`. Steps run in order and stop at the first failure; later steps
/// are `SKIPPED`. Successful authorization proves credentials, not quota or billing: `MODEL_ACCESS` and the opt-in
/// `INFERENCE` probe answer those; the opt-in `USAGE`, `TOOLS` and `CACHE` probes follow it.
public final class ConnectionReport {
    public enum Kind { CONFIGURATION, NETWORK, AUTHENTICATION, MODEL_ACCESS, INFERENCE, USAGE, TOOLS, CACHE }
    public enum Status { PASSED, FAILED, SKIPPED, NOT_SUPPORTED }

    private final List<Step> steps;

    private ConnectionReport(List<Step> steps) { this.steps = List.copyOf(steps); }

    public static ConnectionReport of(List<Step> steps) { return new ConnectionReport(steps); }

    public boolean ok() { return firstFailure().isEmpty(); }
    public List<Step> steps() { return steps; }
    public Optional<Step> firstFailure() { return steps.stream().filter(s -> s.status == Status.FAILED).findFirst(); }

    @Override public String toString() { return "ConnectionReport" + steps; }

    /// Immutable outcome of one stage.
    public static final class Step {
        private final Kind kind;
        private final Status status;
        private final @Nullable Duration latency;
        private final String message;
        private final @Nullable LlmException error;

        private Step(Kind kind, Status status, @Nullable Duration latency, String message, @Nullable LlmException error) {
            this.kind = kind; this.status = status; this.latency = latency; this.message = message; this.error = error;
        }

        public static Step of(Kind kind, Status status, @Nullable Duration latency, String message, @Nullable LlmException error) {
            return new Step(kind, status, latency, message, error);
        }

        public Kind kind() { return kind; }
        public Status status() { return status; }
        public Optional<Duration> latency() { return Optional.ofNullable(latency); }
        public String message() { return message; }
        public Optional<LlmException> error() { return Optional.ofNullable(error); }

        @Override public String toString() { return kind + "=" + status + (message.isEmpty() ? "" : " (" + message + ")"); }
    }
}
