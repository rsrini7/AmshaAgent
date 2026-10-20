package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentPersona;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.MdcKeys;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.smallrye.config.SmallRyeConfigProviderResolver;

/**
 * Routes agent roles to their resolved ChatModel using a 3-tier resolution:
 * <ol>
 *   <li><b>Persona override</b> — {@code amsha.llm.persona.models.{role}} (highest priority)</li>
 *   <li><b>Group default</b> — {@code amsha.llm.group.models.{groupId}} (medium priority)</li>
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
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    AgentGroupRegistry groupRegistry;

    @Inject
    TaskClassifierService classifierService;

    @Inject
    ChatModelPool modelPool;

    @Inject
    ModelProfileResolver profileResolver;

    @Inject
    ModelProfileCredentialResolver credentialResolver;

    @Inject
    LlmProfileStorePort profileStore;

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    BeanManager beanManager;

    private Optional<ChatModelCreationPort> findCreationPort() {
        return CoreSpiResolutionValidator.resolveOptional(beanManager, ChatModelCreationPort.class);
    }

    private ChatModelCreationPort creationPort() {
        return findCreationPort()
                .orElseThrow(
                        () -> new IllegalStateException(
                                "No ChatModelCreationPort bean found on classpath. Ensure amsha-llm-providers is included in the application."));
    }

    @ConfigProperty(name = LlmConfigKeys.PROFILE_ROUTING_ENABLED, defaultValue = "false")
    boolean profileRoutingEnabled;

    @ConfigProperty(name = LlmConfigKeys.PROVIDER, defaultValue = ModelDefaults.PROVIDER_GROQ)
    String globalProvider;

    @ConfigProperty(name = LlmConfigKeys.MODEL)
    Optional<String> globalModel;

    /**
     * Resolves the ChatModel for a given agent role.
     * When {@code amsha.llm.profile-routing.enabled} is true, an owned/workspace/system profile
     * route (resolved via {@link ModelProfileResolver}) takes precedence over deployment config.
     * Otherwise resolution is the legacy persona → group → global path.
     */
    public ChatModel resolve(String agentRole) {
        return resolve(agentRole, false);
    }

    /**
     * Resolves the ChatModel for a given agent role, optionally bypassing the lightweight-goal
     * downgrade for exactly this call (memory-synthesis escalation: one stronger-model pass
     * after repeated failed cheap-model synthesis).
     */
    public ChatModel resolve(String agentRole, boolean bypassLightweightDowngrade) {
        if (profileRoutingEnabled && !bypassLightweightDowngrade) {
            ChatModel profiled = resolveProfileRouted(agentRole);
            if (profiled != null) {
                return profiled;
            }
        }
        var configs = resolveConfigs(agentRole, bypassLightweightDowngrade);
        if (configs.isEmpty()) {
            return getOrCreateModel(globalProvider, globalModel.orElse(""));
        }
        return buildModelFromConfigs(configs);
    }

    /**
     * Returns true when bypassing the lightweight downgrade would resolve a different primary
     * provider/model than the downgrade path — i.e. a genuinely higher tier exists to escalate to.
     */
    public boolean hasDistinctEscalationTier(String agentRole) {
        try {
            var downgraded = resolveConfigs(agentRole, false);
            var escalated = resolveConfigs(agentRole, true);
            if (downgraded.isEmpty() || escalated.isEmpty()) return false;
            var b = downgraded.get(0);
            var e = escalated.get(0);
            return !b.provider().equals(e.provider()) || !b.model().equals(e.model());
        } catch (RuntimeException ignored) {
            // Routing metadata is best-effort; treat resolution failures as "no escalation tier".
            return false;
        }
    }

    /**
     * Resolves via the profile store precedence chain. Returns null when no eligible owned/workspace
     * route applies, so the caller falls back to legacy deployment/system routing.
     */
    private ChatModel resolveProfileRouted(String agentRole) {
        RouteResolution resolution = profileResolver.resolve(agentRole);
        if (!resolution.resolved() || resolution.chain().isEmpty()) {
            return null;
        }
        String actorUserId = currentUserId();
        List<OrderedFailoverChatModel.Entry> entries = new ArrayList<>();
        for (ResolvedProfile p : resolution.chain()) {
            Optional<LlmConnectionDto> conn = profileConnection(p.connectionId());
            if (conn.isEmpty()) {
                continue;
            }
            Optional<ResolvedCredential> cred = credentialResolver.resolve(conn.get(), actorUserId);
            if (cred.isEmpty()) {
                log.warnf(
                        "[MODEL-ROUTER] No credential for connection %s, skipping profile %s",
                        p.connectionId(), p.profileId());
                continue;
            }
            try (ResolvedCredential c = cred.get()) {
                ChatModel model = modelPool.getOrCreateTyped(
                        p.scopeType(),
                        p.scopeId(),
                        p.connectionId(),
                        p.connectionRevision(),
                        p.profileId(),
                        p.profileRevision(),
                        p.providerAdapterId(),
                        p.modelId(),
                        () -> creationPort()
                                .createForAdapter(
                                        p.providerAdapterId(), c.baseUrl(), c.apiKey(), p.modelId(), agentConfig));
                entries.add(new OrderedFailoverChatModel.Entry(p.profileId(), model));
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        // Route wrappers retain the resolved chain. Scope identity is therefore part of their
        // cache key; a precedence tier such as "user-role" alone is shared by every user.
        ResolvedProfile routeScope = resolution.chain().getFirst();
        String routeKey = resolution.precedenceTier()
                + ":"
                + routeScope.scopeType()
                + ":"
                + routeScope.scopeId()
                + ":"
                + agentRole;
        return modelPool.getOrCreateProfileRoute(routeKey, () -> new OrderedFailoverChatModel(routeKey, entries, 30));
    }

    private Optional<LlmConnectionDto> profileConnection(String connectionId) {
        return profileStore.findConnection(connectionId);
    }

    private String currentUserId() {
        if (WorkspaceContext.USER_ID.isBound() && WorkspaceContext.USER_ID.get() != null) {
            return WorkspaceContext.USER_ID.get();
        }
        String mdcUser = (String) MDC.get(MdcKeys.USER_ID);
        return mdcUser != null ? mdcUser : "unknown";
    }

    /**
     * Resolves the provider+model configs for a role (without creating models).
     * Returns a list — single entry for single model, multiple for fallback chain.
     */
    public List<ModelConfig> resolveConfigs(String agentRole) {
        return resolveConfigs(agentRole, false);
    }

    /**
     * Resolves provider+model configs for a role. When {@code bypassLightweightDowngrade} is
     * set, the lightweight-goal downgrade to the low-cost global provider is skipped so the
     * persona/group/global (full-strength) path applies — used by memory-synthesis escalation.
     */
    public List<ModelConfig> resolveConfigs(String agentRole, boolean bypassLightweightDowngrade) {
        String goal = WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;
        if (!bypassLightweightDowngrade && goal != null && isLightweightTask(goal)) {
            log.infof(
                    "[MODEL-ROUTER] Goal classified as lightweight. Routing to default low-cost provider: %s",
                    globalProvider);
            return List.of(new ModelConfig(globalProvider, globalModel.orElse("")));
        }

        var personaConfigs = getPersonaConfigs(agentRole);
        if (!personaConfigs.isEmpty()) return personaConfigs;

        var groupConfigs = getGroupConfigs(agentRole);
        if (!groupConfigs.isEmpty()) return groupConfigs;

        return List.of(new ModelConfig(globalProvider, globalModel.orElse("")));
    }

    // ── Private ────────────────────────────────────────────────────────────

    /**
     * Reads persona model configs. Supports multiple entries for fallback chain.
     * Config format:
     *   Single:  amsha.llm.persona.models.{role}.provider / .model
     *   Multi:   amsha.llm.persona.models.{role}.0.provider / .model
     *            amsha.llm.persona.models.{role}.1.provider / .model
     */
    private List<ModelConfig> getPersonaConfigs(String agentRole) {
        List<ModelConfig> configs = new ArrayList<>();
        var config = SmallRyeConfigProviderResolver.instance()
                .getConfig(Thread.currentThread().getContextClassLoader());

        List<String> roleKeys = new ArrayList<>();
        roleKeys.add(agentRole);

        AgentPersona.fromString(agentRole).ifPresent(persona -> {
            String nameLower = persona.name().toLowerCase(Locale.ROOT);
            if (!roleKeys.contains(nameLower)) {
                roleKeys.add(nameLower);
            }
            for (String alias : persona.aliases()) {
                String aliasLower = alias.toLowerCase(Locale.ROOT);
                if (!roleKeys.contains(aliasLower)) {
                    roleKeys.add(aliasLower);
                }
            }
        });

        for (String rKey : roleKeys) {
            // Try numbered entries first
            for (int i = 0; i < 10; i++) {
                String base = "amsha.llm.persona.models." + rKey + "." + i;
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
                String base = "amsha.llm.persona.models." + rKey;
                Optional<String> provider = config.getOptionalValue(base + ".provider", String.class);
                Optional<String> model = config.getOptionalValue(base + ".model", String.class);
                if (provider.isPresent() && model.isPresent()) {
                    configs.add(new ModelConfig(provider.get(), model.get()));
                }
            }

            if (!configs.isEmpty()) {
                log.debugf(
                        "[MODEL-ROUTER] Persona configs resolved for %s using config key suffix '%s': %s",
                        agentRole, rKey, configs);
                break;
            }
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
                        String base = "amsha.llm.group.models." + group.groupId() + "." + i;
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
                        String base = "amsha.llm.group.models." + group.groupId();
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
        throw new ModelRoutingException("No AgentChatModelProvider found for provider='" + providerName + "'");
    }

    private boolean isLightweightTask(String goal) {
        if (goal == null) {
            return true;
        }
        String goalLower = goal.toLowerCase(Locale.ROOT);
        if (goalLower.startsWith("/") || goalLower.matches(".*\\b(hello|hi|hey|clear|reset|resethard)\\b.*")) {
            return true;
        }
        if (classifierService == null) {
            return false;
        }
        var domains = classifierService.classify(goal);
        boolean hasHeavyDomain = domains.contains(TaskClassifierService.TaskDomain.CODING)
                || domains.contains(TaskClassifierService.TaskDomain.DEBUGGING)
                || domains.contains(TaskClassifierService.TaskDomain.SECURITY)
                || domains.contains(TaskClassifierService.TaskDomain.ARCHITECTURE);
        if (hasHeavyDomain) {
            return false;
        }
        return goal.trim().length() < 15 || domains.isEmpty();
    }

    /**
     * Resolved model configuration.
     */
    public record ModelConfig(String provider, String model) {}
}
