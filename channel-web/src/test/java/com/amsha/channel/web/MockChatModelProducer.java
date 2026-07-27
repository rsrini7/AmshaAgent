package com.amsha.channel.web;

import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import com.amsha.core.agent.reasoning.AgentChatModelProvider;
import com.amsha.core.agent.reasoning.StructuredOutputMode;

import io.smallrye.common.annotation.Identifier;

/**
 * Mock producer and provider to satisfy LangChain4J ChatModel and EmbeddingModel dependencies
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class MockChatModelProducer implements AgentChatModelProvider {

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public ChatModel create() {
        return mock(ChatModel.class);
    }

    @Produces
    @ApplicationScoped
    @Identifier("compaction")
    @Alternative
    @Priority(1)
    public ChatModel compactionModel() {
        return mock(ChatModel.class);
    }

    @Produces
    @Identifier("compaction-mode")
    @Alternative
    @Priority(1)
    public StructuredOutputMode compactionMode() {
        return StructuredOutputMode.NATIVE;
    }

    @Produces
    @Identifier("intent-classifier-mode")
    @Alternative
    @Priority(1)
    public StructuredOutputMode intentClassifierMode() {
        return StructuredOutputMode.NATIVE;
    }

    @Produces
    @Identifier("agent-output-mode")
    @Alternative
    @Priority(1)
    public StructuredOutputMode agentOutputMode() {
        return StructuredOutputMode.NATIVE;
    }

    @Produces
    @ApplicationScoped
    @Alternative
    @Priority(1)
    public EmbeddingModel mockEmbeddingModel() {
        return mock(EmbeddingModel.class);
    }
}
