package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    // Read these from application.yaml — use EXISTING property names already
    // in use for the current Groq/OpenAI config. Do not invent new ones.
    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String openaiApiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url")
    String openaiBaseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String openaiModelName;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
                    defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.chat-model.model-id",
                    defaultValue = "llama3.2")
    String ollamaModelId;

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        log.infof("ChatModelProducer: activating provider=%s", provider);
        return switch (provider.toLowerCase()) {
            case "groq" -> OpenAiChatModel.builder()
                    .baseUrl(openaiBaseUrl)
                    .apiKey(openaiApiKey)
                    .modelName(openaiModelName)
                    .build();
            case "openai" -> OpenAiChatModel.builder()
                    .apiKey(openaiApiKey)
                    .modelName(openaiModelName)
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(ollamaModelId)
                    .build();
            default -> throw new IllegalStateException(
                    "Unknown LLM provider: " + provider +
                    ". Supported: groq | openai | ollama");
        };
    }
}