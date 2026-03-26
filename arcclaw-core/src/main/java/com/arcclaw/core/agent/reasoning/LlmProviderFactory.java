package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for LLM provider discovery and instantiation.
 */
public interface LlmProviderFactory {
    /** The identifier used in arcclaw.llm.provider config. */
    String providerName();

    /** Build the ChatModel instance. */
    ChatModel create();
}
