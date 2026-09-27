package net.ai.gate.auth.interaction;

import java.util.List;
import java.util.Optional;

/// A question a login flow asks the user through [AuthInteraction#prompt]. Closed set (changing it is a major
/// version): switch over it exhaustively.
public sealed interface AuthPrompt {
    String message();

    record Text(String message, Optional<String> placeholder) implements AuthPrompt { }

    record SecretText(String message) implements AuthPrompt { }

    record Select(String message, List<Option> options) implements AuthPrompt {
        public Select { options = List.copyOf(options); }
    }

    /// A pasted authorization code or redirect URI; pre-empted when the loopback callback arrives first.
    record Code(String message) implements AuthPrompt { }

    record Option(String id, String label, Optional<String> description) { }
}
