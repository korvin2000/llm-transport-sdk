package net.ai.gate.metadata;

import java.time.Duration;
import java.time.Instant;

import net.ai.gate.error.ErrorCode;
import org.jspecify.annotations.Nullable;

/// Immutable facts about one attempt of a call. The core retries only failures that were documented as not
/// processed, so every attempt but the last has `outcomeUnknown() == false`; `sent()` tells whether the request
/// left the client at all — an attempt that was never sent cannot have been billed.
/// @param index 1 for the first attempt
/// @param duration until the attempt failed, or until the call settled for the last one
/// @param httpStatus the response status, when one arrived
/// @param error why the attempt failed; `null` for the one that produced the reply
public record Attempt(int index, Instant startedAt, Duration duration, @Nullable Integer httpStatus, @Nullable ErrorCode error,
                      boolean sent, boolean outcomeUnknown) { }
