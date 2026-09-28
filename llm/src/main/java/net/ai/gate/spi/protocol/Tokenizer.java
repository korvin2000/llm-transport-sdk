package net.ai.gate.spi.protocol;

import net.ai.gate.model.Model;

/// **SPI**. A local tokenizer (jtokkit, a SentencePiece binding…) registered with `Llm.Builder.tokenizer(…)`, used by
/// `Llm.countTokens(…)` where the API has no counting endpoint. Thread-safe and pure.
public interface Tokenizer {
    boolean supports(Model model);

    long count(String text);
}
