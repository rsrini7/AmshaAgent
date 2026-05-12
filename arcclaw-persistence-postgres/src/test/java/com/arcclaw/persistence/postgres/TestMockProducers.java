package com.arcclaw.persistence.postgres;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.reasoning.PromptTemplateEngine;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.routing.LlmModelFactory;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import com.arcclaw.persistence.api.SecretStorePort;
import com.arcclaw.persistence.api.SkillStorePort;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.smallrye.common.annotation.Identifier;

/**
 * Provides mock CDI beans for core dependencies that are not needed
 * in postgres integration tests but are required by the CDI bean
 * validation graph.
 */
@ApplicationScoped
public class TestMockProducers {

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return mock(ChatModel.class);
    }

    @Produces
    @Identifier("compaction")
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public ChatModel compactionModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("mock-compaction-result");
        return m;
    }

    @Produces
    @Identifier("intent-classifier")
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public ChatModel intentDetectorModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("mock-intent-result");
        return m;
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
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return mock(EmbeddingModel.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public CompactionStorePort compactionStorePort() {
        return mock(CompactionStorePort.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public MemoryRetrievalPort memoryRetrievalPort() {
        return mock(MemoryRetrievalPort.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public SkillStorePort skillStorePort() {
        return mock(SkillStorePort.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public SecretStorePort secretStorePort() {
        return mock(SecretStorePort.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public ChatMemoryStorePort chatMemoryStorePort() {
        return mock(ChatMemoryStorePort.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public PersistenceAdapterPort persistenceAdapterPort() {
        return mock(PersistenceAdapterPort.class);
    }

    @Produces
    @ApplicationScoped
    public LlmModelFactory llmModelFactory() {
        return mock(LlmModelFactory.class);
    }

    @Produces
    @ApplicationScoped
    public PromptTemplateEngine promptTemplateEngine() {
        return mock(PromptTemplateEngine.class);
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class MockReasoningEngine implements ReasoningEngine {
        @Override
        public ReasoningStep nextStep(AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> history) {
            return ReasoningStep.of("mock-persistence-test", ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "No-op mock reasoning engine."), null, 0, 0);
        }

        @Override
        public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
            return "{}";
        }
    }
}
