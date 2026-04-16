package com.arcclaw.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.arcclaw.core.agent.reasoning.AgentChatModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.smallrye.common.annotation.Identifier;
import org.mockito.Mockito;

@ApplicationScoped
public class MockChatModelProducer implements AgentChatModelProvider {

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public ChatModel create() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @ApplicationScoped
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @ApplicationScoped
    public EmbeddingModel mockEmbeddingModel() {
        return Mockito.mock(EmbeddingModel.class);
    }
}
