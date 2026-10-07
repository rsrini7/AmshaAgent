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
 * The single place that turns a resolved provider selection into a usable {@link DecisionEngine}.
 *
 * <p>This exists so there is exactly one construction path. Before it, the only callers of
 * {@code RemoteProviderConfig.builder()} and {@code new RemoteDecisionEngine(...)} were tests, which is
 * why configuring a provider changed nothing. Any future production producer MUST build through here
 * too, or the two paths will drift and a governance rule enforced in one will be missing from the other.
 *
 * <p><b>It is deliberately not a CDI bean, and it registers no bean.</b> The factory is a pure
 * construction seam: it is handed an already-resolved selection, a declared governance group and
 * resolved credential material, and returns an engine the caller owns and closes. It performs no I/O
 * beyond creating an {@link HttpClient} for the loopback lane. That is what lets it exist while the
 * decision plane is Deferred — constructing an engine is not registering one, so
 * {@code Instance<DecisionEngine>} stays unresolvable.
 *
 * <p><b>It fails closed.</b> The selection is re-validated with the selector's own rule
 * ({@link DecisionProviderSelector#selectionProblem}) and its governance is validated for the resolved
 * lane, so a hand-built {@code Selection.Selected} cannot smuggle in a model the lane forbids, a
 * credentialed provider with no reference or with a reference that resolved to no material, a
 * contradictory state dialect, a non-positive timeout, or a rejected endpoint. Any of those raises
 * {@link DecisionProviderUnavailableException} with a payload-free reason rather than being completed
 * with a permissive default.
 *
 * <p><b>The credential is transient but not zeroable end to end.</b> The caller resolves it and zeroes
 * the bytes in a {@code finally} once the engine has consumed them. The remote lane needs a
 * {@code String} for the {@code Authorization} header and the loopback lane for its token header, so the
 * bytes are decoded once here — and that {@code String} is then held by the provider config
 * ({@code RemoteProviderConfig.apiKey} / {@code SelfHostedProviderConfig.loopbackToken}) for as long as
 * the engine lives. Closing the handle cannot erase it, because a {@code String} is immutable. The
 * exposure is therefore bounded by the engine's lifetime rather than eliminated, which makes it a
 * production security decision and not a property of this class.
 */
public final class DecisionEngineFactory {

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
                        selected.apiKeyRef(),
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

        // A declared reference is not a resolved credential. selectionProblem refuses a credentialed
        // provider that names no api-key-ref; this closes the mirror gap — a reference the vault could not
        // resolve to any bytes, which would otherwise build a provider that sends an empty Authorization
        // header and only fails at the provider. Refused here, where the reason names the configuration.
        // Only the remote bearer profiles are covered; the self-hosted lane authenticates with its own
        // optional loopback header, so an empty credential is legitimate there.
        if (DecisionProviderSelector.requiresCredentialMaterial(selected.providerId(), selected.lane())
                && (credentialText == null || credentialText.isBlank())) {
            throw new DecisionProviderUnavailableException("Decision provider '" + selected.providerId()
                    + "' declares an api-key-ref but no credential material was resolved; refusing to build a"
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
    private static String decode(byte[] credential) {
        return new String(credential, StandardCharsets.UTF_8);
    }
}
