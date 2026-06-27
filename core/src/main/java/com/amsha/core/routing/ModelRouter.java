package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.AgentChatModelProvider;
import com.amsha.core.agent.role.AgentGroupRegistry;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.smallrye.config.SmallRyeConfigProviderResolver;

/**
 * Routes agent roles to their resolved ChatModel using a 3-tier resolution:
 * <ol>
 *   <li><b>Persona override</b> — {@code amsha.llm.persona-models.{role}} (highest priority)</li>
 *   <li><b>Group default</b> — {@code amsha.llm.group-models.{groupId}} (medium priority)</li>
 *   <li><b>Global default</b> — {@code amsha.llm.provider} + {@code amsha.llm.model} (fallback)</li>
 * </ol>
 *
 * <p>Supports multiple models per persona/group for automatic failover.
 * When multiple models are configured, wraps them in {@link MultiModelChatModel}
 * for round-robin rotation and health-based failover.
 *
 * <p>Models are pooled by {@link ChatModelPool} — multiple personas sharing
 * the same provider+model get a single instance.
 */
@ApplicationScoped
public class ModelRouter {

    private static final Logger log = Logger.getLogger(ModelRouter.class);

    @Inject
    ChatModelPool modelPool;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    AgentGroupRegistry groupRegistry;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String globalProvider;

    @ConfigProperty(name = "amsha.llm.model", defaultValue = "")
    String globalModel;

    /**
     * Resolves the ChatModel for a given agent role.
     * Resolution: persona override → group default → global default.
     * If multiple models are configured, wraps them in a {@link MultiModelChatModel}
     * for automatic failover and round-robin rotation.
     */
    public ChatModel resolve(String agentRole) {
        // 1. Try persona override (may return multiple)
        var personaConfigs = getPersonaConfigs(agentRole);
        if (!personaConfigs.isEmpty()) {
            return buildModelFromConfigs(personaConfigs);
        }

        // 2. Try group default (may return multiple)
        var groupConfigs = getGroupConfigs(agentRole);
        if (!groupConfigs.isEmpty()) {
            return buildModelFromConfigs(groupConfigs);
        }

        // 3. Global default
        return getOrCreateModel(globalProvider, globalModel);
    }

    /**
     * Resolves the provider+model configs for a role (without creating models).
     * Returns a list — single entry for single model, multiple for fallback chain.
     */
    public List<ModelConfig> resolveConfigs(String agentRole) {
        var personaConfigs = getPersonaConfigs(agentRole);
        if (!personaConfigs.isEmpty()) return personaConfigs;

        var groupConfigs = getGroupConfigs(agentRole);
        if (!groupConfigs.isEmpty()) return groupConfigs;

        return List.of(new ModelConfig(globalProvider, globalModel));
    }

    // ── Private ────────────────────────────────────────────────────────────

    /**
     * Reads persona model configs. Supports multiple entries for fallback chain.
     * Config format:
     *   Single:  amsha.llm.persona-models.{role}.provider / .model
     *   Multi:   amsha.llm.persona-models.{role}.0.provider / .model
     *            amsha.llm.persona-models.{role}.1.provider / .model
     */
    private List<ModelConfig> getPersonaConfigs(String agentRole) {
        List<ModelConfig> configs = new ArrayList<>();
        var config = SmallRyeConfigProviderResolver.instance()
                .getConfig(Thread.currentThread().getContextClassLoader());

        // Try numbered entries first: persona-models.brahma.0.provider, .1.provider, etc.
        for (int i = 0; i < 10; i++) {
            String base = "amsha.llm.persona-models." + agentRole + "." + i;
            Optional<String> provider = config.getOptionalValue(base + ".provider", String.class);
            Optional<String> model = config.getOptionalValue(base + ".model", String.class);
            if (provider.isPresent() && model.isPresent()) {
                configs.add(new ModelConfig(provider.get(), model.get()));
            } else {
                break; // No more numbered entries
            }
        }

        // If no numbered entries, try unnumbered
        if (configs.isEmpty()) {
            String base = "amsha.llm.persona-models." + agentRole;
            Optional<String> provider = config.getOptionalValue(base + ".provider", String.class);
            Optional<String> model = config.getOptionalValue(base + ".model", String.class);
            if (provider.isPresent() && model.isPresent()) {
                configs.add(new ModelConfig(provider.get(), model.get()));
            }
        }

        if (!configs.isEmpty()) {
            log.debugf("[MODEL-ROUTER] Persona configs for %s: %s", agentRole, configs);
        }
        return configs;
    }

    /**
     * Reads group model configs. Supports multiple entries for fallback chain.
     */
    private List<ModelConfig> getGroupConfigs(String agentRole) {
        return groupRegistry
                .findByMember(agentRole)
                .map(group -> {
                    List<ModelConfig> configs = new ArrayList<>();
                    var config = SmallRyeConfigProviderResolver.instance()
                            .getConfig(Thread.currentThread().getContextClassLoader());

                    // Try numbered entries first
                    for (int i = 0; i < 10; i++) {
                        String base = "amsha.llm.group-models." + group.groupId() + "." + i;
                        Optional<String> provider = config.getOptionalValue(base + ".provider", String.class);
                        Optional<String> model = config.getOptionalValue(base + ".model", String.class);
                        if (provider.isPresent() && model.isPresent()) {
                            configs.add(new ModelConfig(provider.get(), model.get()));
                        } else {
                            break;
                        }
                    }

                    // If no numbered entries, try unnumbered
                    if (configs.isEmpty()) {
                        String base = "amsha.llm.group-models." + group.groupId();
                        Optional<String> provider = config.getOptionalValue(base + ".provider", String.class);
                        Optional<String> model = config.getOptionalValue(base + ".model", String.class);
                        if (provider.isPresent() && model.isPresent()) {
                            configs.add(new ModelConfig(provider.get(), model.get()));
                        }
                    }

                    if (!configs.isEmpty()) {
                        log.debugf("[MODEL-ROUTER] Group configs for %s (%s): %s", agentRole, group.groupId(), configs);
                    }
                    return configs;
                })
                .orElse(List.of());
    }

    /**
     * Builds a ChatModel from a list of configs.
     * If single config → returns a pooled ChatModel.
     * If multiple configs → wraps in MultiModelChatModel for cross-provider failover.
     */
    private ChatModel buildModelFromConfigs(List<ModelConfig> configs) {
        if (configs.size() == 1) {
            return getOrCreateModel(configs.get(0).provider(), configs.get(0).model());
        }

        // Multiple models → MultiModelChatModel with cross-provider failover
        String poolKey = configs.stream()
                .map(c -> c.provider() + "#" + c.model())
                .reduce((a, b) -> a + "," + b)
                .orElse("multi");

        return modelPool.getOrCreate(poolKey, () -> {
            List<MultiModelChatModel.ModelEntry> entries = new ArrayList<>();
            for (ModelConfig mc : configs) {
                String label = mc.provider() + "/" + mc.model();
                ChatModel model = getOrCreateModel(mc.provider(), mc.model());
                entries.add(new MultiModelChatModel.ModelEntry(label, model));
            }
            log.infof("[MODEL-ROUTER] Creating MultiModelChatModel with %d entries: %s", entries.size(), poolKey);
            return new MultiModelChatModel("multi", entries, 5);
        });
    }

    private ChatModel getOrCreateModel(String provider, String model) {
        String key = ChatModelPool.poolKey(provider, model);
        return modelPool.getOrCreate(key, () -> {
            AgentChatModelProvider factory = findProvider(provider);
            if (model != null && !model.isBlank()) {
                return factory.create(model);
            }
            return factory.create();
        });
    }

    private AgentChatModelProvider findProvider(String providerName) {
        for (AgentChatModelProvider p : providers) {
            if (p.providerName().equalsIgnoreCase(providerName)) {
                return p;
            }
        }
        throw new RuntimeException("No AgentChatModelProvider found for provider='" + providerName + "'");
    }

    /**
     * Resolved model configuration.
     */
    public record ModelConfig(String provider, String model) {}
}
