package net.ai.gate.chat.tool;

/// Sealed: a portable function the host executes, or a provider-hosted tool created by a provider package
/// (`OpenAiTools.webSearch()`, `AnthropicTools.codeExecution()`). The SDK transports calls; it never executes them.
public sealed interface Tool permits FunctionTool, ProviderTool {

    /// A function whose parameter schema is derived from the record `argumentsType`; bind calls back with
    /// `call.arguments(argumentsType)`.
    static FunctionTool of(String name, String description, Class<?> argumentsType) {
        return function(name).description(description).parameters(argumentsType).build();
    }

    static FunctionTool.Builder function(String name) { return FunctionTool.builder(name); }

    String name();
}
