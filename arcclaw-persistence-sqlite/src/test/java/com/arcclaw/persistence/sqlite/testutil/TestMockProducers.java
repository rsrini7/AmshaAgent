package com.arcclaw.persistence.sqlite.testutil;

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
import com.arcclaw.core.agent.persona.Agent;
import com.arcclaw.core.agent.persona.AgentRole;
import com.arcclaw.core.agent.reasoning.PromptTemplateEngine;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.routing.LlmModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.smallrye.common.annotation.Identifier;

/**
 * Provides mock CDI beans for core dependencies that are not needed
 * in persistence-sqlite integration tests but are required by the CDI
 * bean validation graph.
 */
@ApplicationScoped
public class TestMockProducers {

    @Produces
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return mock(ChatModel.class);
    }

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("mock-compaction-result");
        return m;
    }

    @Produces
    @Identifier("intent-classifier")
    @ApplicationScoped
    public ChatModel intentClassifierModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("mock-intent-result");
        return m;
    }

    @Produces
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return mock(EmbeddingModel.class);
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

    @Produces
    @AgentRole("synthesizer")
    @ApplicationScoped
    public Agent synthesizerAgent() {
        return mock(Agent.class);
    }

    /**
     * Mock ReasoningEngine so beans that depend on it
     * can be instantiated without needing real LLM providers or Qute templates.
     */
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
