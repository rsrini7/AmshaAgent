package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OllamaProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.chat-model.model-id", defaultValue = "llama3.2")
    String modelId;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public ChatModel create() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .build();
    }
}
