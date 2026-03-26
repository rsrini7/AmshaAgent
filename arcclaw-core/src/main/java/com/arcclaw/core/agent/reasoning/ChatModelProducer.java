package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the {@link ChatModel} for the ArcClaw reasoning engine.
 * <p>
 * Uses {@link LlmProviderFactory} SPI to discover and instantiate the correct 
 * model based on the {@code arcclaw.llm.provider} property.
 */
@ApplicationScoped
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);

    @Inject
    Instance<LlmProviderFactory> factories;

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        log.infof("ChatModelProducer: activating provider=%s", provider);
        
        return factories.stream()
                .filter(factory -> factory.providerName().equalsIgnoreCase(provider))
                .findFirst()
                .map(LlmProviderFactory::create)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown or unsupported LLM provider: " + provider +
                        ". Ensure an LlmProviderFactory implementation is present on the classpath."));
    }
}
