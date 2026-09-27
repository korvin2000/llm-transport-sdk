/// The one login protocol between flows and the host UI: prompts, notices and the web-redirect variant. Prompts reach
/// only the caller of `login()`, never listeners or logs.
@NullMarked
package net.ai.gate.auth.interaction;

import org.jspecify.annotations.NullMarked;
