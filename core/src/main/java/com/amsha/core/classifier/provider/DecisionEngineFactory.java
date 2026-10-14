package com.amsha.core.classifier.provider;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.core.classifier.remote.RemoteDecisionEngine;
import com.amsha.core.classifier.remote.RemoteProviderConfig;
import com.amsha.core.classifier.selfhosted.SelfHostedDecisionEngine;
import com.amsha.core.classifier.selfhosted.SelfHostedProviderConfig;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderGovernance;
import com.amsha.core.config.DecisionProviderSelector;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Creates a {@link DecisionEngine} from an already-resolved provider selection.
 *
 * <p>This is the single provider construction path used by explicit decision evaluation.
 * It creates an engine on demand and performs no provider request itself. Any future production
 * producer must use this factory so governance checks and provider construction cannot diverge
 * across paths.
 *
 * <p><b>This is not a CDI bean.</b> Creating the factory or an engine never registers a
 * {@code DecisionEngine}. Production routing can remain {@code Deferred}, and
 * {@code Instance<DecisionEngine>} stays unsatisfied until production activation is
 * separately approved and implemented.
 *
 * <p><b>It revalidates and fails closed.</b> The factory rechecks the selector's pure rule
 * ({@link DecisionProviderSelector#selectionProblem}) and governance for the resolved lane.
 * A hand-built {@code DecisionProviderSelector.Selection.Selected} cannot bypass checks
 * for an invalid model, missing required key material, unsupported wire profile, timeout,
 * endpoint, or governance declaration. A refusal has a payload-free
 * {@link DecisionProviderUnavailableException} reason.
 *
 * <p><b>Credential strings cannot be zeroed.</b> The caller clears the request-owned
 * {@code byte[]} in a {@code finally} after evaluation. This factory decodes the bytes into
 * an immutable {@link String} used by provider configuration
 * ({@code RemoteProviderConfig.apiKey} or {@code SelfHostedProviderConfig.loopbackToken})
 * while the transient engine is alive. That engine is created per evaluation; closing it
 * ends the object's logical lifetime but cannot force immediate erasure of the decoded
 * string. The environment-backed configuration {@link String} remains in memory for the
 * process lifetime.
 */
public final class DecisionEngineFactory {

    private static final String OMLX_PROVIDER_ID = "omlx";

    private DecisionEngineFactory() {}

    /**
     * Builds the engine for a resolved selection.
     *
     * @param selected the validated selection from {@link DecisionProviderSelector}
     * @param declaredGovernance the bound governance group for the same slot
     * @param credential resolved credential bytes; must be present and non-blank for a provider that
     *     authenticates with a bearer token (see
     *     {@link DecisionProviderSelector#requiresCredentialMaterial}), and may be empty for a lane that
     *     does not; owned by the caller and zeroed after use
     * @param mapper the application's JSON mapper
     * @param outboundPolicyService governed-transport policy, required for the remote lane
     * @param vault the secret vault the remote transport reads for proxy and CA material; may be null in
     *     tests that inject their own transport
     * @return a handle owning the engine and any transport this factory created
     * @throws DecisionProviderUnavailableException when the governance declaration is incomplete, a
     *     credentialed provider was given no credential material, or the provider cannot be built (for
     *     example a remote selection with no model); the message names operator configuration only
     */
    public static DecisionEngineHandle create(
            DecisionProviderSelector.Selection.Selected selected,
            DecisionProviderConfig.Slot.Governance declaredGovernance,
            Optional<byte[]> credential,
            ObjectMapper mapper,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault vault) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(declaredGovernance, "declaredGovernance");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(outboundPolicyService, "outboundPolicyService");

        // Re-check the selection itself before its governance. Selection.Selected is a publicly
        // constructible record, so a hand-built value can carry a model the lane forbids, a
        // credential-less TypeSafe endpoint, a contradictory CLM dialect, a non-positive timeout, or an
        // endpoint the lane validator rejects — none of which the selector would ever emit, but all of
        // which the factory would otherwise build straight through. One pure rule, consulted by both
        // callers, is what stops them from disagreeing about what "usable" means.
        String selectionProblem = DecisionProviderSelector.selectionProblem(
                        selected.providerId(),
                        selected.lane(),
                        selected.endpoint(),
                        selected.model(),
                        selected.credentialConfigured(),
                        selected.timeout(),
                        selected.wireProfile())
                .orElse(null);
        if (selectionProblem != null) {
            throw new DecisionProviderUnavailableException(
                    "Decision provider '" + selected.providerId() + "' " + selectionProblem);
        }

        // Re-check governance for the same reason: the factory is the authoritative construction gate,
        // and tests call it directly with a hand-built selection. Governance.problem is pure, so the
        // second check costs nothing and closes the "selector was bypassed" gap rather than trusting an
        // upstream invariant.
        String governanceProblem = DecisionProviderGovernance.problem(declaredGovernance, selected.lane())
                .orElse(null);
        if (governanceProblem != null) {
            throw new DecisionProviderUnavailableException(
                    "Decision provider '" + selected.providerId() + "' " + governanceProblem);
        }
        DecisionProviderGovernance governance =
                DecisionProviderGovernance.of(selected.providerId(), declaredGovernance, selected.lane());
        String credentialText = credential.map(DecisionEngineFactory::decode).orElse(null);

        // A configured key must resolve to non-empty bytes before building a hosted bearer profile.
        if (DecisionProviderSelector.requiresCredentialMaterial(selected.providerId(), selected.lane())
                && (credentialText == null || credentialText.isBlank())) {
            throw new DecisionProviderUnavailableException("Decision provider '" + selected.providerId()
                    + "' declares an api-key but no credential material was resolved; refusing to build a"
                    + " provider that would authenticate with an empty bearer token");
        }

        try {
            return switch (selected.lane()) {
                case REMOTE ->
                    new DecisionEngineHandle(
                            buildRemote(selected, governance, credentialText, mapper, outboundPolicyService, vault),
                            null);
                case SELF_HOSTED -> buildSelfHosted(selected, governance, credentialText, mapper);
            };
        } catch (IllegalArgumentException | SecurityException ex) {
            // The provider-config records and the endpoint validators report an unusable configuration
            // with these unchecked types (for example, a remote selection with no model, which the
            // records refuse rather than default). Normalise them so every factory failure is the one
            // payload-free refusal type the caller already handles, instead of a mix of exception kinds.
            throw new DecisionProviderUnavailableException(
                    "Decision provider '" + selected.providerId() + "' could not be built: " + ex.getMessage(), ex);
        }
    }

    private static DecisionEngine buildRemote(
            DecisionProviderSelector.Selection.Selected selected,
            DecisionProviderGovernance governance,
            String credentialText,
            ObjectMapper mapper,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault vault) {
        // modelId is guaranteed non-blank by the selector: the hosted contract requires it, and a
        // floating alias would defeat the provenance DecisionMetadata reports back.
        RemoteProviderConfig config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint(selected.endpoint())
                .modelId(selected.model())
                .apiKey(credentialText)
                .timeout(selected.timeout())
                .defaultProjectionPolicy(governance.projectionPolicy())
                .dataLocation(governance.dataLocation())
                .retentionPolicy(governance.retentionPolicy())
                .tenantIsolation(true)
                .build();
        return new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, vault);
    }

    private static DecisionEngineHandle buildSelfHosted(
            DecisionProviderSelector.Selection.Selected selected,
            DecisionProviderGovernance governance,
            String credentialText,
            ObjectMapper mapper) {
        // The loopback lane blocks on HttpClient#send, so it needs a client this factory owns and the
        // handle closes. A connect timeout is set from the request timeout so a hung local wrapper
        // cannot hold a worker thread indefinitely.
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(selected.timeout()).build();
        SelfHostedProviderConfig config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint(selected.endpoint())
                .modelId(selected.model())
                .wireProfile(selected.wireProfile())
                .loopbackToken(credentialText)
                .credentialHeaderStyle(credentialHeaderStyle(selected.providerId()))
                .timeout(selected.timeout())
                .defaultProjectionPolicy(governance.projectionPolicy())
                .build();
        SelfHostedDecisionEngine engine =
                new SelfHostedDecisionEngine(config, client, mapper, DecisionTransactionGuard.system());
        return new DecisionEngineHandle(engine, client);
    }

    /**
     * Decodes credential bytes for a header value.
     *
     * <p>Kept private and called once so the decoded text is a local, never a field: the header API
     * requires a {@code String}, so a {@code String} is unavoidable at the transport boundary, and the
     * only defence is to keep it as short-lived as possible.
     */
    private static SelfHostedProviderConfig.CredentialHeaderStyle credentialHeaderStyle(String providerId) {
        if (OMLX_PROVIDER_ID.equals(providerId)) {
            return SelfHostedProviderConfig.CredentialHeaderStyle.AUTHORIZATION_BEARER;
        }
        return SelfHostedProviderConfig.CredentialHeaderStyle.AMSHA_LOOPBACK_TOKEN;
    }

    private static String decode(byte[] credential) {
        return new String(credential, StandardCharsets.UTF_8);
    }
}
