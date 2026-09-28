package net.ai.gate.chat.content;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Immutable answer of the host to one [ToolCall], carrying its id and tool name.
public final class ToolResult implements Content {
    private final String callId, toolName;
    private final List<Content> content;
    private final boolean error;

    private ToolResult(String callId, String toolName, List<Content> content, boolean error) {
        this.callId = callId; this.toolName = toolName; this.content = List.copyOf(content); this.error = error;
    }

    public static ToolResult of(ToolCall call, String text) { return of(call, List.of(Content.text(text))); }
    public static ToolResult of(ToolCall call, JsonValue json) { return of(call, json.toJson()); }
    /// Records, maps, lists and scalars in their JSON form.
    public static ToolResult of(ToolCall call, @Nullable Object value) { return of(call, Json.valueOf(value)); }
    /// Text and images.
    public static ToolResult of(ToolCall call, List<Content> parts) { return new ToolResult(call.id(), call.name(), parts, false); }
    public static ToolResult error(ToolCall call, String message) {
        return new ToolResult(call.id(), call.name(), List.of(Content.text(message)), true);
    }
    /// For hosts that keep their own history items: the call's id and tool name without its [ToolCall]; an error
    /// result may carry any parts.
    public static ToolResult of(String callId, String toolName, List<Content> parts, boolean error) {
        return new ToolResult(Checks.notBlank(callId, "Tool call id"), Checks.notBlank(toolName, "Tool name"), parts, error);
    }

    public String callId() { return callId; }
    public String toolName() { return toolName; }
    public List<Content> content() { return content; }
    public boolean isError() { return error; }

    /// The text parts, concatenated.
    public String text() {
        return content.stream().filter(Text.class::isInstance).map(c -> ((Text) c).text()).collect(Collectors.joining());
    }

    /// A copy answering a call whose id was normalized for another API.
    public ToolResult withCallId(String id) { return new ToolResult(Checks.notBlank(id, "Tool call id"), toolName, content, error); }

    /// A copy with other parts (image downgrades during hand-off).
    public ToolResult withContent(List<Content> parts) { return new ToolResult(callId, toolName, parts, error); }

    @Override public boolean equals(Object o) {
        return o instanceof ToolResult r && callId.equals(r.callId) && toolName.equals(r.toolName) && content.equals(r.content) && error == r.error;
    }

    @Override public int hashCode() { return Objects.hash(callId, toolName, content, error); }
    @Override public String toString() { return "ToolResult[" + toolName + ", id=" + callId + (error ? ", error" : "") + "]"; }
}
