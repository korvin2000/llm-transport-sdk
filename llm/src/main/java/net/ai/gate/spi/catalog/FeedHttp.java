package net.ai.gate.spi.catalog;

import java.io.IOException;
import java.net.URI;

import net.ai.gate.json.JsonValue;

/// Unauthenticated, deadline-bound GET of a feed's own absolute URL.
@FunctionalInterface
public interface FeedHttp {
    JsonValue get(URI url) throws IOException;
}
