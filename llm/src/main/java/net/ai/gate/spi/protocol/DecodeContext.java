package net.ai.gate.spi.protocol;

import net.ai.gate.Provider;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Model;

/// What a codec may read and report while decoding.
public interface DecodeContext {
    Provider provider();

    Model model();

    <C extends ApiCompat> C compat(C defaults);

    JsonMapper json();

    void warn(Warning warning);
}
