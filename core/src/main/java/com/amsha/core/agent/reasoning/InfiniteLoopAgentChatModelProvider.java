package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.routing.InfiniteLoopChatModel;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.StructuredOutputMode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.LaunchMode;

@ApplicationScoped
public class InfiniteLoopAgentChatModelProvider implements AgentChatModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopAgentChatModelProvider.class);

    @ConfigProperty(name = "amsha.infinite-loop.llm.providers", defaultValue = "omlx,groq,openrouter")
    String loopProviders;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.max-latency-ms", defaultValue = "60000")
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
        String[] providerNames = loopProviders.split(",", -1);
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
                String baseUrl = provider.baseUrl();
                slots.add(new InfiniteLoopChatModel.Slot(name, model, baseUrl));
                log.infof("InfiniteLoop: Slot [%s] initialized (%s) baseUrl=%s.", name, model, baseUrl);
            } catch (Exception e) {
                log.warnf("InfiniteLoop: Slot [%s] failed to initialize: %s. Skipping.", name, LogUtil.rootMessage(e));
            }
        }

        if (slots.isEmpty()) {
            boolean isTest = LaunchMode.current() == LaunchMode.TEST;
            if (isTest && !Boolean.getBoolean("amsha.test.force-error")) {
                log.warn("InfiniteLoop: No slots could be initialized in test profile. Using dummy chat model.");
                ChatModel dummy = new ChatModel() {
                    @Override
                    public ChatResponse chat(ChatRequest request) {
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Dummy response"))
                                .build();
                    }
                };
                return new InfiniteLoopChatModel(
                        "agent-chat",
                        List.of(new InfiniteLoopChatModel.Slot("dummy", dummy, null)),
                        cooldownBaseSeconds,
                        cooldownMaxSeconds,
                        cooldownTransientSeconds,
                        maxLatencyMs);
            }
            throw new IllegalStateException(
                    "InfiniteLoop: No slots could be initialized. Check AMSHA_INFINITE_LOOP_LLM_PROVIDERS="
                            + loopProviders);
        }

        log.infof(
                "InfiniteLoop: Ready with %d slot(s): %s.",
                slots.size(),
                slots.stream().map(InfiniteLoopChatModel.Slot::name).collect(Collectors.joining(", ")));

        // Log provider summary table
        StringBuilder table = new StringBuilder();
        table.append("\n┌─────────────────────────────────────────────────────────────────────┐\n");
        table.append("│                    LLM PROVIDER CONFIGURATION                       │\n");
        table.append("├──────────────┬──────────────────────────────────────────────────────┤\n");
        table.append(String.format("│ %-12s │ %-52s │\n", "Provider", "Model(s)"));
        table.append("├──────────────┼──────────────────────────────────────────────────────┤\n");

        for (InfiniteLoopChatModel.Slot slot : slots) {
            String modelStr = slot.model().toString();
            // Extract model name from MultiModel or single model
            String modelName;
            if (slot.model() instanceof MultiModelChatModel multi) {
                modelName = multi.toString().replace("MultiModel[", "").replace("]", "");
            } else {
                modelName = modelStr.length() > 52 ? modelStr.substring(0, 49) + "..." : modelStr;
            }
            if (modelName.length() > 52) {
                modelName = modelName.substring(0, 49) + "...";
            }
            table.append(String.format("│ %-12s │ %-52s │\n", slot.name(), modelName));
        }

        table.append("├──────────────┴──────────────────────────────────────────────────────┤\n");
        table.append(String.format(
                "│ Cooldown: %ds base, %ds max, %ds transient │ %6dms max latency   │\n",
                cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs));
        table.append("└─────────────────────────────────────────────────────────────────────┘");
        log.infof("InfiniteLoop: Provider summary:%s", table);

        log.infof("InfiniteLoop: TIP — Free models change frequently. If a slot starts failing with "
                + "'model not found' or 404, check the provider's model list and update AMSHA_*_MODEL.");

        return new InfiniteLoopChatModel(
                "agent-chat", slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
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
