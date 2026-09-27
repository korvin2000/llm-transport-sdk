package net.ai.gate.vendors.openai;

import java.net.URI;
import java.util.List;

import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;

/// OpenAI-hosted tools for the Responses API. Sent to another API they are dropped with a warning.
public final class OpenAiTools {
    private OpenAiTools() { }

    public static ProviderTool webSearch() { return tool("web_search", Json.object("type", "web_search")); }

    public static ProviderTool fileSearch(List<String> vectorStoreIds) {
        return tool("file_search", Json.object("type", "file_search", "vector_store_ids", vectorStoreIds));
    }

    public static ProviderTool codeInterpreter() { return tool("code_interpreter", Json.object("type", "code_interpreter")); }

    public static ProviderTool remoteMcp(String label, URI serverUrl) {
        return tool("mcp", Json.object("type", "mcp", "server_label", label, "server_url", serverUrl));
    }

    private static ProviderTool tool(String name, JsonObject config) { return ProviderTool.of(OpenAi.RESPONSES.id(), name, config); }
}
