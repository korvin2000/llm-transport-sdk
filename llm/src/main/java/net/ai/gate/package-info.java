/// The entry point: the [net.ai.gate.Llm] runtime facade and the [net.ai.gate.Provider] it is built from. Everything else
/// is grouped by noun in the sub-packages: `chat`, `model`, `metadata`, `catalog`, `providers`, `config`, `cache`,
/// `diagnostics`, `lifecycle`, `error`, `auth`, `event`, `json`, the `spi` and the `vendors`.
///
/// Every type states whether it is *immutable*, *thread-safe* or *not thread-safe*. Unknown facts (limits, prices,
/// usage) are absent or `UNKNOWN`, never `0` or `false`.
@NullMarked
package net.ai.gate;

import org.jspecify.annotations.NullMarked;
