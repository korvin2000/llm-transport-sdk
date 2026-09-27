package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// Missing, invalid or expired credentials: `invalid_credentials`, `login_required`, `refresh_failed`, `login_cancelled`, `permission_denied`, `credential_store`. HTTP 401 and 403.
public final class AuthenticationException extends LlmException {
    public AuthenticationException(Details details) { super(details); }

    public AuthenticationException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
