package net.ai.gate.vendors.google;

import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.json.Json;

/// Gemini-hosted tools.
public final class GeminiTools {
    private GeminiTools() { }

    public static ProviderTool googleSearch() { return ProviderTool.of(Gemini.GENERATE_CONTENT.id(), "google_search", Json.object()); }
    public static ProviderTool codeExecution() { return ProviderTool.of(Gemini.GENERATE_CONTENT.id(), "code_execution", Json.object()); }
    public static ProviderTool urlContext() { return ProviderTool.of(Gemini.GENERATE_CONTENT.id(), "url_context", Json.object()); }
}
