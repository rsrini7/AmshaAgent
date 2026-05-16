package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
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
        AgentChatModelProvider providerFactory = findProvider();
        log.debugf("ChatModelProducer: using provider=%s", provider);
        return providerFactory.create();
    }

    @Produces
    @Identifier("agent-output-mode")
    @Dependent
    @Unremovable
    public StructuredOutputMode structuredOutputMode() {
        return findProvider().structuredOutputMode();
    }

    private AgentChatModelProvider findProvider() {
        String cleanProvider = clean(provider != null ? provider : "groq");
        for (AgentChatModelProvider p : providers) {
            if (p.providerName().equalsIgnoreCase(cleanProvider)) {
                return p;
            }
        }
        throw new RuntimeException("No AgentChatModelProvider found for provider='" + cleanProvider + "' (raw: '" + provider + "')");
    }

    private String clean(String value) {
        if (value == null) return null;
        return value.split("#")[0].trim();
    }
}
