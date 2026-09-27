package net.ai.gate.auth.interaction;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/// Something a login flow tells the user through [AuthInteraction#notify]. Closed set.
public sealed interface AuthNotice {
    /// Open this URL in a browser (desktop), or redirect the user's browser to it (web).
    record OpenUrl(URI url, Optional<String> instructions) implements AuthNotice { }

    record DeviceCode(URI verificationUri, String userCode, Instant expiresAt) implements AuthNotice { }

    record Info(String message, List<URI> links) implements AuthNotice {
        public Info { links = List.copyOf(links); }
    }

    record Progress(String message) implements AuthNotice { }
}
