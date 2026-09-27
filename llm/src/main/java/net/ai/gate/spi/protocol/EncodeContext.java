package net.ai.gate.spi.protocol;

import java.util.OptionalInt;

import net.ai.gate.Provider;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.metadata.Warning;

/// What a codec may read and report while encoding. Confined to the encoding thread.
public interface EncodeContext {
    Provider provider();

    /// `defaults` overridden field by field by the provider's and then the model's flags of the same type.
    <C extends ApiCompat> C compat(C defaults);

    /// Soft adaptations fail under `strict()`; prefer [#adapt(Warning)].
    boolean strict();

    JsonMapper json();

    /// A non-fatal note attached to the reply and the preview.
    void warn(Warning warning);

    /// A soft adaptation: warned, or `InvalidRequestException(unsupported_feature)` under `strict()`.
    void adapt(Warning warning);

    /// The output limit to send when the API requires one and the call set none: the model's maximum clamped to the
    /// context window minus the estimated input. Reported as a preview note when used.
    OptionalInt defaultMaxTokens();
}
