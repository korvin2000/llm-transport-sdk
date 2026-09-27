/// Internal: the execution core — one pipeline for every API.
///
/// ```
/// complete(model, conversation, options)
///  ─► Core.provider(id)      unknown id → IllegalArgumentException naming the known ids; never a fallback
///  ─► Resolver               call ▷ provider ▷ runtime options; reasoning mapping, clamps, provider-option scoping
///  ─► Handoff                history adapted to (provider, api, model); the stored conversation is untouched
///  ─► WireApi.encode         pure codec ─► payload hook ─► Prepared      (preview() stops here)
///  ─► response cache         opt-in; keyed by credential namespace + effective request; replay goes through the same codec
///  ─► Call.send              auth ▷ interceptors ▷ transport; retries, deadline, cancellation, events
///  ─► Call.read / stream     body read inside the call's lifetime │ FrameReader → StreamDecoder → Accumulator
///  ─► Engine.finish          cost, info, warnings, silent-overflow detection ─► AssistantMessage
/// ```
@NullMarked
package net.ai.gate.internal.core;

import org.jspecify.annotations.NullMarked;
