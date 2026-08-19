package com.amsha.llm.api;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Port SPI for creating low-level ChatModel adapters without binding consumers
 * to concrete LangChain4j provider SDKs.
 */
public interface ChatModelCreationPort {

    /**
     * Builds a {@link ChatModel} for a resolved profile credential.
     */
    ChatModel createForAdapter(String provider, String baseUrl, String apiKey, String model, AgentLlmConfig config);

    /**
     * Checks if this port implementation supports the given provider adapter identifier.
     */
    boolean supportsAdapter(String provider);
}
