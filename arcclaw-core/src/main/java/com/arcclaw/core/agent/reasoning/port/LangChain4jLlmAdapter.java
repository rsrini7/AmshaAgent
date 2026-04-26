package com.arcclaw.core.agent.reasoning.port;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

@ApplicationScoped
public class LangChain4jLlmAdapter implements LlmProviderPort {

    private final ChatModel chatModel;

    @Inject
    public LangChain4jLlmAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ChatResponse nextStep(ChatRequest request) {
        try {
            return chatModel.chat(request);
        } catch (Exception e) {
            throw LlmExceptionClassifier.classify(e);
        }
    }
}
