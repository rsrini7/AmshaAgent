package com.arcclaw.app.channel.telegram;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ModelProducer {

    @ConfigProperty(name = "GROQ_API_KEY")
    String groqApiKey;

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier")
    public ChatLanguageModel intentClassifierModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName("llama-3.1-8b-instant")
                .temperature(0.0)
                .maxTokens(10)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
