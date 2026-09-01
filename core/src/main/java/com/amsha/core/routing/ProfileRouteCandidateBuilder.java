package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.MdcKeys;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Builds ordered {@link ModelRoute} candidates from the profile-resolution chain for a given
 * agent role. Each candidate carries an explicit {@link MultimodalCapability} resolved from
 * {@code capabilityJson} first, then falling back to the {@code defaultCapabilityForModel}
 * heuristic. The builder integrates with {@link ChatModelPool} for lazy, bounded model
 * initialization and never performs network I/O or LLM calls inside transactions.
 *
 * <p>This builder is the Slab B materializer: it takes the identity-aware profile chain from
 * {@link ModelProfileResolver} and produces typed, capability-stamped {@link ModelRoute}
 * candidates for the {@link ModelRouteSelector}.</p>
 */
@ApplicationScoped
public class ProfileRouteCandidateBuilder {

    private static final Logger log = Logger.getLogger(ProfileRouteCandidateBuilder.class);

    @Inject
    ModelProfileResolver profileResolver;

    @Inject
    ChatModelPool modelPool;

    @Inject
    ModelProfileCredentialResolver credentialResolver;

    @Inject
    LlmProfileStorePort profileStore;

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    BeanManager beanManager;

    @ConfigProperty(name = "amsha.llm.profile-routing.enabled", defaultValue = "false")
    boolean profileRoutingEnabled;

    private Optional<ChatModelCreationPort> findCreationPort() {
        return CoreSpiResolutionValidator.resolveOptional(beanManager, ChatModelCreationPort.class);
    }

    private ChatModelCreationPort creationPort() {
        return findCreationPort()
                .orElseThrow(() -> new IllegalStateException("No ChatModelCreationPort bean found on classpath."));
    }

    /**
     * Builds an ordered list of {@link ModelRoute} candidates from the profile-resolution chain
     * for the given agent role. Returns an empty list when no eligible profiles are found or when
     * profile routing is disabled.
     *
     * @param agentRole the agent role for profile resolution
     * @return ordered candidate routes, never {@code null}
     */
    public List<ModelRoute> buildCandidates(String agentRole) {
        if (!profileRoutingEnabled) {
            return List.of();
        }
        RouteResolution resolution = profileResolver.resolve(agentRole);
        if (!resolution.resolved() || resolution.chain().isEmpty()) {
            return List.of();
        }
        String actorUserId = currentUserId();
        List<ModelRoute> candidates = new ArrayList<>();
        for (ResolvedProfile profile : resolution.chain()) {
            ModelRoute route = buildRouteFromProfile(profile, agentRole, actorUserId);
            if (route != null) {
                candidates.add(route);
            }
        }
        return candidates;
    }

    private ModelRoute buildRouteFromProfile(ResolvedProfile profile, String agentRole, String actorUserId) {
        Optional<LlmConnectionDto> conn = profileStore.findConnection(profile.connectionId());
        if (conn.isEmpty()) {
            return null;
        }
        Optional<ResolvedCredential> cred = credentialResolver.resolve(conn.get(), actorUserId);
        if (cred.isEmpty()) {
            log.warnf(
                    "[ROUTE-BUILDER] No credential for connection %s, skipping profile %s",
                    profile.connectionId(), profile.profileId());
            return null;
        }
        try (ResolvedCredential _ = cred.get()) {
            // Credential presence verified; closed immediately to prevent secret leakage
        }
        MultimodalCapability capability = resolveCapability(profile);

        // Defer pool lookup and factory execution until the model is actually used, preventing
        // eager initialization of unselected models.
        ChatModel lazyModel = new LazyChatModel(() -> {
            Optional<ModelProfileDto> liveProf = profileStore.findModelProfile(profile.profileId());
            if (liveProf.isEmpty()
                    || !liveProf.get().enabled()
                    || liveProf.get().revision() != profile.profileRevision()) {
                throw new IllegalStateException("Profile " + profile.profileId()
                        + " is disabled, missing, or revision mismatched at execution time");
            }
            Optional<LlmConnectionDto> liveConn = profileStore.findConnection(profile.connectionId());
            if (liveConn.isEmpty() || liveConn.get().revision() != profile.connectionRevision()) {
                throw new IllegalStateException("Connection " + profile.connectionId()
                        + " is missing or revision mismatched at execution time");
            }
            Optional<ResolvedCredential> liveCred = credentialResolver.resolve(liveConn.get(), actorUserId);
            if (liveCred.isEmpty()) {
                throw new IllegalStateException(
                        "Credential missing for lazy initialization of profile " + profile.profileId());
            }
            try (ResolvedCredential c = liveCred.get()) {
                return modelPool.getOrCreateTyped(
                        profile.scopeType(),
                        profile.scopeId(),
                        profile.connectionId(),
                        profile.connectionRevision(),
                        profile.profileId(),
                        profile.profileRevision(),
                        profile.providerAdapterId(),
                        profile.modelId(),
                        () -> creationPort()
                                .createForAdapter(
                                        profile.providerAdapterId(),
                                        c.baseUrl(),
                                        c.apiKey(),
                                        profile.modelId(),
                                        agentConfig));
            }
        });

        return new ModelRoute(
                "profile:" + profile.profileId(),
                profile.providerAdapterId(),
                profile.modelId(),
                profile,
                lazyModel,
                capability,
                List.of(),
                profile.profileRevision());
    }

    /**
     * Resolves capability from the profile's persisted {@code capabilityJson} first,
     * falling back to the heuristic when absent.
     */
    private MultimodalCapability resolveCapability(ResolvedProfile profile) {
        Optional<ModelProfileDto> dto = profileStore.findModelProfile(profile.profileId());
        if (dto.isPresent()) {
            String capJson = dto.get().capabilityJson();
            return CapabilityJsonParser.resolve(capJson, profile.providerAdapterId(), profile.modelId());
        }
        return MultimodalCapabilities.defaultCapabilityForModel(profile.providerAdapterId(), profile.modelId());
    }

    private String currentUserId() {
        if (WorkspaceContext.USER_ID.isBound() && WorkspaceContext.USER_ID.get() != null) {
            return WorkspaceContext.USER_ID.get();
        }
        String mdcUser = (String) MDC.get(MdcKeys.USER_ID);
        return mdcUser != null ? mdcUser : "unknown";
    }
}
