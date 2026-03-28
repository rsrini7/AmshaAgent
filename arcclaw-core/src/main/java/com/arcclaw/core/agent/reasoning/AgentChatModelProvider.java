package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for pluggable LLM provider factories used by the agent reasoning engine.
 * Implementations are discovered by {@link ChatModelProducer} via CDI Instance scan.
 * Each implementation handles one provider (groq, openai, ollama, openrouter, etc.).
 */
public interface AgentChatModelProvider {

    /**
     * Provider name — must match the value of {@code arcclaw.llm.provider} config.
     * e.g. "groq", "openai", "ollama", "openrouter"
     */
    String providerName();

    /**
     * Creates and returns a fully configured {@link ChatModel} for agent reasoning.
     * Called once at startup by {@link ChatModelProducer} to produce the
     * {@code @ApplicationScoped ChatModel} bean used by {@link LangChainReasoningEngine}.
     */
    ChatModel create();
}