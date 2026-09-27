package net.ai.gate.vendors.anthropic;

import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;

/// Anthropic-hosted and Anthropic-defined tools. The codec maps each name to the tool type of its revision.
public final class AnthropicTools {
    private AnthropicTools() { }

    public static ProviderTool webSearch(int maxUses) { return tool("web_search", Json.object("max_uses", maxUses)); }
    public static ProviderTool codeExecution() { return tool("code_execution", Json.object()); }
    public static ProviderTool bash() { return tool("bash", Json.object()); }
    public static ProviderTool textEditor() { return tool("text_editor", Json.object()); }

    public static ProviderTool computerUse(int displayWidth, int displayHeight) {
        return tool("computer", Json.object("display_width_px", displayWidth, "display_height_px", displayHeight));
    }

    private static ProviderTool tool(String name, JsonObject config) { return ProviderTool.of(Anthropic.MESSAGES.id(), name, config); }
}
