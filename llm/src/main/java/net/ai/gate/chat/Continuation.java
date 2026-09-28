package net.ai.gate.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.model.ModelRef;
import org.jetbrains.annotations.ApiStatus;

/// A handle to server-side conversation state a reply left behind — the OpenAI Responses `previous_response_id` —
/// so that the next call sends only what came after that reply. Valid for the provider and API that issued it,
/// until `expiresAt` where the API states one. Obtained from `AssistantMessage.continuation()` and used through
/// `ChatOptions.Builder.continueFrom(…)`; an expired or unknown handle fails the call with
/// `ErrorCode.CONTINUATION_EXPIRED`.
/// @param model the reply's model
/// @param api the wire API that holds the state
/// @param opaqueId the state's id on that API
/// @param expiresAt when the state is discarded, where the API reports it
/// @param effectiveHistoryTokens the tokens the state stands for — the continued reply's input and output — where known
@ApiStatus.Experimental
public record Continuation(ModelRef model, String api, String opaqueId, Optional<Instant> expiresAt, OptionalLong effectiveHistoryTokens) {
    public Continuation {
        Objects.requireNonNull(model, "model");
        Checks.notBlank(api, "API id");
        Checks.notBlank(opaqueId, "Continuation id");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(effectiveHistoryTokens, "effectiveHistoryTokens");
    }

    /// Whether `reply` is the reply this handle continues from: it carries this handle, or it is the response of
    /// this id on this API.
    public boolean continues(AssistantMessage reply) {
        if (reply.continuation().map(c -> c.api().equals(api) && c.opaqueId().equals(opaqueId)).orElse(false)) return true;
        return reply.api().equals(api) && reply.responseId().filter(opaqueId::equals).isPresent();
    }
}
