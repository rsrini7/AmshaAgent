package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.routing.InfiniteLoopChatModel;
import com.arcclaw.core.routing.MultiModelChatModel;

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
                log.warnf("InfiniteLoop: Slot [%s] failed to initialize: %s. Skipping.", name, LogUtil.rootMessage(e));
            }
        }

        if (slots.isEmpty()) {
            throw new IllegalStateException(
                    "InfiniteLoop: No slots could be initialized. Check ARCCLAW_INFINITE_LOOP_MODELS_LIST="
                            + loopModels);
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
                modelName = multi.toString().replaceAll("MultiModel\\[", "").replaceAll("\\]", "");
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
                + "'model not found' or 404, check the provider's model list and update ARCCLAW_*_MODEL.");

        return new InfiniteLoopChatModel(
                slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
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
