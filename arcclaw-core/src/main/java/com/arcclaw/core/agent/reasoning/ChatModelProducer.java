package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the single {@link ChatModel} bean used by the agent reasoning engine.
 * Selects the active {@link AgentChatModelProvider} based on
 * {@code arcclaw.llm.provider} config and delegates model creation to it.
 */
@ApplicationScoped
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        return providers.stream()
            .filter(p -> p.providerName().equalsIgnoreCase(provider))
            .findFirst()
            .map(p -> {
                log.infof("ChatModelProducer: using provider=%s", provider);
                return p.create();
            })
            .orElseThrow(() -> new IllegalStateException(
                "No AgentChatModelProvider found for provider='" + provider +
                "'. Available providers: " +
                providers.stream().map(AgentChatModelProvider::providerName).toList()));
    }
}