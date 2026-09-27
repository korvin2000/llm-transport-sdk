package net.ai.gate.chat.tool;

import net.ai.gate.internal.validation.Checks;

/// Whether and which tools the model must call.
public sealed interface ToolChoice {
    static ToolChoice auto() { return Auto.INSTANCE; }
    static ToolChoice none() { return None.INSTANCE; }
    static ToolChoice required() { return Required.INSTANCE; }
    static ToolChoice only(String toolName) { return new Only(Checks.notBlank(toolName, "Tool name")); }

    enum Auto implements ToolChoice { INSTANCE }
    enum None implements ToolChoice { INSTANCE }
    enum Required implements ToolChoice { INSTANCE }
    record Only(String toolName) implements ToolChoice { }
}
