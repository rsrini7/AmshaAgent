package com.arcclaw.app.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ModelProducer {

    private static final Logger log = Logger.getLogger(ModelProducer.class);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url",
                    defaultValue = "https://api.groq.com/openai/v1")
    String openaiBaseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String openaiApiKey;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.model",
                    defaultValue = "llama-3.1-8b-instant")
    String intentModelName;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
                    defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.ollama-model",
                    defaultValue = "llama3.2")
    String ollamaModelId;

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier")
    public ChatModel intentClassifierModel() {
        log.infof("ModelProducer: intent-classifier provider=%s model=%s",
                  provider, intentModelName);
        return switch (provider.toLowerCase()) {
            case "groq" -> OpenAiChatModel.builder()
                    .baseUrl(openaiBaseUrl)
                    .apiKey(openaiApiKey)
                    .modelName(intentModelName)
                    .temperature(0.0)
                    .maxTokens(10)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            case "openai" -> OpenAiChatModel.builder()
                    .apiKey(openaiApiKey)
                    .modelName(intentModelName)
                    .temperature(0.0)
                    .maxTokens(10)
                    .logRequests(false)
                    .logResponses(false)
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
