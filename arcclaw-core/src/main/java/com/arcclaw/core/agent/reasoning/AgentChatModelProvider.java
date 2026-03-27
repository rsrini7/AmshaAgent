package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for LLM provider discovery and instantiation for the Agent loop.
 * Formerly LlmProviderFactory.
 */
public interface AgentChatModelProvider {
    /** The identifier used in arcclaw.llm.provider config. */
    String providerName();

    /** Build the ChatModel instance. */
    ChatModel create();
}
