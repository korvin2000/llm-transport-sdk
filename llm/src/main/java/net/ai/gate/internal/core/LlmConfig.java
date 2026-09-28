package net.ai.gate.internal.core;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;

import net.ai.gate.Provider;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.cache.ResponseCache;
import net.ai.gate.catalog.CatalogOptions;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.config.HttpOptions;
import net.ai.gate.event.LlmListener;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.spi.http.WireInterceptor;
import net.ai.gate.spi.protocol.Tokenizer;
import org.jspecify.annotations.Nullable;

/// The validated product of `Llm.Builder`: everything a runtime is built from.
public record LlmConfig(List<Provider> providers, boolean discoverProviders, CredentialStore credentials, Environment environment,
                        ChatOptions defaults, CatalogOptions catalog, HttpOptions http, @Nullable ResponseCache responseCache,
                        List<WireInterceptor> interceptors, List<LlmListener> listeners, @Nullable Executor executor,
                        @Nullable JsonMapper jsonMapper, List<Tokenizer> tokenizers, Clock clock) { }
