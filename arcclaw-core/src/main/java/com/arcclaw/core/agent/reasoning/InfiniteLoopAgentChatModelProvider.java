package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.InfiniteLoopChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InfiniteLoopAgentChatModelProvider implements AgentChatModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopAgentChatModelProvider.class);

    @ConfigProperty(name = "arcclaw.infinite-loop.models", defaultValue = "omlx,groq,openrouter")
    String loopModels;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.max-latency-ms", defaultValue = "60000")
    long maxLatencyMs;

    @Inject
    @Any
    Instance<AgentChatModelProvider> allProviders;

    @Override
    public String providerName() {
        return "infinite-loop";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return StructuredOutputMode.PROMPT_DIRECTIVE;
    }

    @Override
    public ChatModel create() {
        String[] providerNames = loopModels.split(",");
        List<InfiniteLoopChatModel.Slot> slots = new ArrayList<>();

        for (String raw : providerNames) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            if (name.equalsIgnoreCase("infinite-loop")) {
                log.warn("InfiniteLoop: Skipping self-reference in slot list");
                continue;
            }
            AgentChatModelProvider provider = findProvider(name);
            if (provider == null) {
                log.warnf("InfiniteLoop: No AgentChatModelProvider found for [%s]. Skipping.", name);
                continue;
            }
            try {
                ChatModel model = provider.create();
                slots.add(new InfiniteLoopChatModel.Slot(name, model));
                log.infof("InfiniteLoop: Slot [%s] initialized (%s).", name, model);
            } catch (Exception e) {
                log.warnf("InfiniteLoop: Slot [%s] failed to initialize: %s. Skipping.", name, e.getMessage());
            }
        }

        if (slots.isEmpty()) {
            throw new IllegalStateException(
                    "InfiniteLoop: No slots could be initialized. Check ARCCLAW_INFINITE_LOOP_MODELS_LIST=" + loopModels);
        }

        log.infof("InfiniteLoop: Ready with %d slot(s): %s.",
                slots.size(), slots.stream().map(InfiniteLoopChatModel.Slot::name).collect(Collectors.joining(", ")));
        return new InfiniteLoopChatModel(slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
    }

    private AgentChatModelProvider findProvider(String name) {
        for (AgentChatModelProvider p : allProviders) {
            if (p.providerName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
