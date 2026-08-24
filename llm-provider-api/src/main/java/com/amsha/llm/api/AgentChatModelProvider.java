package com.amsha.llm.api;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for pluggable LLM provider factories used by the agent reasoning engine.
 * Implementations are discovered via CDI Instance scan.
 * Each implementation handles one provider (groq, openai, ollama, openrouter, etc.).
 */
public interface AgentChatModelProvider {

    /**
     * Provider name - must match the value of {@code amsha.llm.provider} config.
     * e.g. "groq", "openai", "ollama", "openrouter"
     */
    String providerName();

    /**
     * Creates and returns a fully configured {@link ChatModel} for agent reasoning
     * using the provider's default model name from config.
     */
    ChatModel create();

    /**
     * Creates a {@link ChatModel} with a specific model name, overriding the default.
     *
     * <p>Default implementation delegates to {@link #create()} (ignores custom model name).
     * Provider factories that support dynamic model names should override this method.
     *
     * @param customModelName The model name to use (e.g., "deepseek-coder-33b")
     * @return A configured ChatModel using the custom model name
     */
    default ChatModel create(String customModelName) {
        return create();
    }

    default String baseUrl() {
        return "unknown";
    }

    /**
     * Returns the structured output compliance mode for this model.
     * Defaults to NATIVE for modern reliable models.
     */
    default StructuredOutputMode structuredOutputMode() {
        return StructuredOutputMode.NATIVE;
    }
}
