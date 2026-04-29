package com.arcclaw.onboarding;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import com.arcclaw.core.agent.reasoning.AgentChatModelProvider;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.smallrye.common.annotation.Identifier;
import org.mockito.Mockito;

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
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @ApplicationScoped
    @Identifier("compaction")
    @Alternative
    @Priority(1)
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
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
        return Mockito.mock(EmbeddingModel.class);
    }
}
