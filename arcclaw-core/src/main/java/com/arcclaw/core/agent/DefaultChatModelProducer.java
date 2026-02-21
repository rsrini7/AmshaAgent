package com.arcclaw.core.agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class DefaultChatModelProducer {

    @ConfigProperty(name = "arcclaw.ai.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;

    @ConfigProperty(name = "arcclaw.ai.ollama.model-name", defaultValue = "llama3.2:1b")
    String modelName;

    @ConfigProperty(name = "arcclaw.ai.ollama.timeout-seconds", defaultValue = "120")
    int timeoutSeconds;

    @Produces
    @ApplicationScoped
    public ChatLanguageModel createChatLanguageModel() {
        return OllamaChatModel.builder()
                .httpClientBuilder(new JaxRsHttpClientBuilder())
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
