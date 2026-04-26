package com.arcclaw.core.agent.reasoning.port;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

@ApplicationScoped
public class LangChain4jLlmAdapter implements LlmProviderPort {

    private final ChatModel chatModel;

    @ConfigProperty(name = "arcclaw.llm.circuit-breaker.enabled", defaultValue = "true")
    boolean cbEnabled;

    @Inject
    public LangChain4jLlmAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Inject
    LangChain4jLlmAdapter self; // self-proxy for @CircuitBreaker

    @Override
    public ChatResponse nextStep(ChatRequest request) {
        if (!cbEnabled) {
            return chatModel.chat(request);
        }
        return self.nextStepWithCb(request);
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000, successThreshold = 2)
    public ChatResponse nextStepWithCb(ChatRequest request) {
        try {
            return chatModel.chat(request);
        } catch (Exception e) {
            throw LlmExceptionClassifier.classify(e);
        }
    }
}
