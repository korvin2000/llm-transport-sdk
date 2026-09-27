package net.ai.gate.vendors.google;

import java.time.Duration;
import java.util.List;

import net.ai.gate.chat.Message;
import net.ai.gate.model.Model;

/// Explicit Gemini context caches, obtained with `llm.providerApi("google", Gemini.CACHES)`. Remote resources with
/// their own lifetime: closing a runtime never deletes them. Every method performs network I/O.
public interface GeminiCaches {
    CachedContent create(Model model, List<Message> contents, Duration ttl);

    CachedContent get(String name);

    List<CachedContent> list();

    CachedContent extend(String name, Duration ttl);

    void delete(String name);
}
