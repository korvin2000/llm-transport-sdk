package net.ai.gate.chat.content;

import java.util.Objects;

import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;

/// Immutable tool call requested by the model. The id is synthesized when an API provides none; the raw argument
/// text is always kept, parsing happens on access.
public final class ToolCall implements Content {
    private final String id, name, argumentsJson;

    private ToolCall(String id, String name, String argumentsJson) {
        this.id = Checks.notBlank(id, "Tool call id");
        this.name = Checks.notBlank(name, "Tool name");
        this.argumentsJson = argumentsJson;
    }

    public static ToolCall of(String id, String name, String argumentsJson) { return new ToolCall(id, name, argumentsJson); }
    public static ToolCall of(String id, String name, JsonObject arguments) { return new ToolCall(id, name, arguments.toJson()); }

    public String id() { return id; }
    public String name() { return name; }
    /// The arguments as the model produced them.
    public String argumentsJson() { return argumentsJson; }

    /// @throws InvalidResponseException `invalid_tool_arguments` when the text is not a JSON object
    public JsonObject arguments() {
        if (argumentsJson.isBlank()) return Json.object();
        try {
            if (Json.parse(argumentsJson) instanceof JsonObject o) return o;
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        throw invalid("not a JSON object");
    }

    /// Binds the arguments to a record, as declared with `Tool.of(name, description, type)`.
    /// @throws InvalidResponseException `invalid_tool_arguments` when they do not fit `type`
    public <T> T arguments(Class<T> type) {
        try {
            return Json.convert(arguments(), type);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    /// A copy with another id (hand-off between APIs with different id rules).
    public ToolCall withId(String newId) { return new ToolCall(newId, name, argumentsJson); }

    private InvalidResponseException invalid(String problem) {
        return new InvalidResponseException(LlmException.Details.builder(ErrorCode.INVALID_TOOL_ARGUMENTS,
                "Arguments of tool call '" + name + "' (" + id + ") are invalid: " + problem).build());
    }

    @Override public boolean equals(Object o) {
        return o instanceof ToolCall c && id.equals(c.id) && name.equals(c.name) && argumentsJson.equals(c.argumentsJson);
    }

    @Override public int hashCode() { return Objects.hash(id, name, argumentsJson); }
    @Override public String toString() { return "ToolCall[" + name + ", id=" + id + "]"; }
}
