package net.ai.gate.internal.core;

import java.util.stream.Collectors;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

import net.ai.gate.error.ErrorCode;
import net.ai.gate.event.RequestEvent.Finished.Outcome;
import net.ai.gate.metadata.Usage;
import org.jspecify.annotations.Nullable;

/// Flight Recorder events: emitted for every call, recorded only while a recording is active. `jdk.jfr` is an
/// optional module; without it nothing is loaded.
final class Jfr {
    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private Jfr() { }

    static void record(Call call, Outcome outcome, Usage usage, boolean fromCache, @Nullable ErrorCode error) {
        if (AVAILABLE) Request.commit(call, outcome, usage, fromCache, error);
    }

    @Name("net.ai.gate.Request")
    @Label("LLM Request")
    @Category("AI Gate")
    static final class Request extends Event {
        @Label("Request Id") String requestId;
        @Label("Provider") String provider;
        @Label("API") String api;
        @Label("Model") String model;
        @Label("Streaming") boolean streaming;
        @Label("Outcome") String outcome;
        @Label("Error Code") String errorCode;
        @Label("Attempts") int attempts;
        @Label("Input Tokens") long inputTokens;
        @Label("Output Tokens") long outputTokens;
        @Label("Cost") String cost;
        @Label("From Cache") boolean fromCache;
        @Label("Tags") String tags;
        @Label("Latency") @Timespan(Timespan.NANOSECONDS) long latency;

        static void commit(Call call, Outcome outcome, Usage usage, boolean fromCache, @Nullable ErrorCode error) {
            var event = new Request();
            if (!event.isEnabled()) return;
            event.requestId = call.requestId;
            event.provider = call.provider.id();
            event.api = call.api;
            event.model = call.model.modelId();
            event.streaming = call.streaming;
            event.outcome = outcome.name();
            event.errorCode = error == null ? "" : error.value();
            event.attempts = call.attempts();
            event.inputTokens = usage.totalInput().orElse(usage.input().orElse(-1));
            event.outputTokens = usage.output().orElse(-1);
            event.cost = usage.cost().map(Object::toString).orElse("");
            event.fromCache = fromCache;
            event.tags = call.tags().entrySet().stream().map(t -> t.getKey() + "=" + t.getValue()).collect(Collectors.joining(","));
            event.latency = call.elapsed().toNanos();
            event.commit();
        }
    }
}
