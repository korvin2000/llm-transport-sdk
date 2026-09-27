package net.ai.gate.auth;

import java.util.Optional;

/// What an [ApiKeyAuth] may read: the stored key of its provider, and the environment.
public record AuthInput(Optional<ApiKeyCredential> stored, Environment environment) { }
