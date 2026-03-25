package com.arcclaw.app.routing;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for LLM chat model providers.
 * Add a new provider by implementing this interface as a CDI bean —
 * no changes to LlmModelFactory or any core class required.
 *
 * <p>Mirrors {@code EmbeddingProviderFactory} from arcclaw-rag exactly.
 */
public interface LlmProviderFactory {

    /** Lower-case canonical provider identifier (e.g. "groq", "ollama", "openai"). */
    String providerId();

    /** Creates the {@link ChatModel} from the given config bundle. */
    ChatModel create(LlmModelConfig config);

    /** True if this provider requires a non-blank API key to function. Default: true. */
    default boolean requiresApiKey() { return true; }
}
