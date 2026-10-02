package com.amsha.core.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.amsha.core.classifier.remote.RemoteEndpointValidator;
import com.amsha.core.classifier.selfhosted.SelfHostedEndpointValidator;
import com.amsha.core.classifier.wire.WireProfile;

/**
 * Resolves the configured decision provider into a validated, lane-specific selection.
 *
 * <p>This is the seam the {@code .env} switch needs: one place that answers "which provider, which
 * lane, and is it actually usable?" without the caller having to know that a loopback endpoint and a
 * hosted one travel through different validators and different governance.
 *
 * <p>Three properties are deliberate:
 *
 * <ul>
 *   <li><b>It is a pure function.</b> No CDI, no engine, no I/O. It cannot activate anything, which
 *       is what lets it exist while the decision plane stays Deferred.</li>
 *   <li><b>It fails closed.</b> An undeclared, disabled, endpoint-less, malformed, or
 *       validator-rejected provider yields {@link Selection.Unavailable} with a reason instead of a
 *       partial configuration. That includes the rules this class adds on top of the mapping: a remote
 *       slot with no model (the hosted contract requires one, whereas a self-hosted slot may omit it),
 *       a credentialed remote provider with no {@code api-key-ref}, a non-positive timeout, and a CLM
 *       model not declared as prose. The reason describes operator configuration only — never user
 *       state, credentials, or a resolved secret.</li>
 *   <li><b>It never resolves a credential.</b> {@code apiKeyRef} is carried through as a
 *       {@code SecretVault} reference; turning it into a bearer token is the vault's job at the wiring
 *       layer. That wiring does not exist yet, and the self-hosted lane does not consume an
 *       {@code apiKeyRef} at all — it authenticates with its own loopback header — so a credential ref
 *       is currently carried and checked for presence, nothing more.</li>
 * </ul>
 *
 * <p><b>No production caller exists yet, by design.</b> Consuming a selection means building a
 * provider config, resolving its credential, and constructing an engine — and constructing a
 * production {@code DecisionEngine} is the ADR change the plane is deliberately Deferred on. Until
 * that is approved, the selector is the configuration surface and nothing more.
 */
public final class DecisionProviderSelector {

    /**
     * Model ids whose heads were trained on prose, so {@code state} must be rendered as prose. CLM is
     * the only such family today; a second one is a deliberate addition here, not an inferred default.
     */
    private static final Pattern REQUIRES_PROSE = Pattern.compile("(?i)^clm([-_.].*)?$");

    private DecisionProviderSelector() {}

    /** Which governance lane an endpoint belongs to. */
    public enum Lane {

        /** HTTPS egress through the governed transport; hosted providers. */
        REMOTE,

        /** Separate-process loopback provider bound to the literal {@code 127.0.0.1}. */
        SELF_HOSTED
    }

    /**
     * Outcome of resolving the configured provider.
     */
    public sealed interface Selection {

        /**
         * A declared, enabled, and validated provider.
         *
         * @param providerId the declared slot id that was selected
         * @param lane governance lane derived from the endpoint host
         * @param endpoint the validated endpoint URI
         * @param model the decision model to request; never blank
         * @param apiKeyRef a {@code SecretVault} reference, blank when unset; never a secret
         * @param timeout request execution timeout
         * @param wireProfile how this provider expects {@code state} to be rendered
         */
        record Selected(
                String providerId,
                Lane lane,
                URI endpoint,
                String model,
                String apiKeyRef,
                Duration timeout,
                WireProfile wireProfile)
                implements Selection {}

        /**
         * Nothing usable is configured.
         *
         * @param reason payload-free explanation naming the operator configuration at fault
         */
        record Unavailable(String reason) implements Selection {}
    }

    /**
     * Resolves the configured provider.
     *
     * @param config the decision provider configuration
     * @return the validated selection, or an {@link Selection.Unavailable} reason
     */
    public static Selection select(DecisionProviderConfig config) {
        Objects.requireNonNull(config, "config");
        String providerId = trimToEmpty(config.selected());
        if (providerId.isEmpty()) {
            return new Selection.Unavailable("No decision provider is selected"
                    + " (amsha.decision.providers.selected is blank), so the decision plane stays inert");
        }
        DecisionProviderConfig.Slot slot = config.slots().get(providerId);
        if (slot == null) {
            return new Selection.Unavailable("Decision provider '" + providerId
                    + "' is selected but not declared under amsha.decision.providers,"
                    + " so no endpoint can be resolved for it");
        }
        if (!slot.enabled()) {
            return new Selection.Unavailable("Decision provider '" + providerId + "' is declared but not enabled");
        }
        return resolveEndpoint(providerId, slot);
    }

    /**
     * Parses and sanity-checks the slot's endpoint before any lane is chosen.
     */
    private static Selection resolveEndpoint(String providerId, DecisionProviderConfig.Slot slot) {
        String endpointText = trimToEmpty(slot.endpoint().orElse(""));
        if (endpointText.isEmpty()) {
            return unavailableFor(providerId, "has no endpoint configured");
        }
        URI endpoint;
        try {
            endpoint = URI.create(endpointText);
        } catch (IllegalArgumentException _) {
            return unavailableFor(providerId, "has an endpoint that is not a valid URI");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            return unavailableFor(providerId, "has an endpoint with no host");
        }
        return resolveLane(providerId, slot, endpoint, host);
    }

    /**
     * Derives the lane from the endpoint host, refusing a host that would make the choice ambiguous.
     */
    private static Selection resolveLane(
            String providerId, DecisionProviderConfig.Slot slot, URI endpoint, String host) {
        String normalized = normalizeHost(host);
        if (SelfHostedEndpointValidator.LOOPBACK_IPV4.equals(normalized)) {
            return validate(providerId, slot, endpoint, Lane.SELF_HOSTED);
        }
        if (isLoopbackLike(normalized)) {
            // localhost / ::1 / 127.x: the self-hosted lane rejects these by design (resolution drift
            // and DNS rebinding), and the remote lane would accept them as "loopback for testing" and
            // skip the loopback guarantee entirely. Neither lane is the right answer, so refuse.
            return unavailableFor(
                    providerId,
                    "uses loopback host '" + host
                            + "', which is ambiguous: the self-hosted lane requires the literal"
                            + " 127.0.0.1, and the remote lane would bypass that guarantee");
        }
        return validate(providerId, slot, endpoint, Lane.REMOTE);
    }

    /**
     * Whether this provider's documented profile authenticates with a bearer credential, so a slot
     * naming it is incomplete without an {@code api-key-ref}.
     *
     * <p>Kept as an explicit, named check rather than inferred from the lane: "remote" does not imply
     * "needs a bearer token", because a remote deployment may authenticate by mTLS or network policy
     * instead. TypeSafe is the closed hosted reference implementation whose contract documents
     * {@code Authorization: Bearer}. A second such provider is a deliberate addition here.
     */
    private static boolean requiresCredentialRef(String providerId) {
        return "typesafe".equals(providerId);
    }

    /**
     * Applies the rules that depend on the slot together with the lane it resolved to — a model where
     * the lane requires one, a credential where the provider's profile requires one, a usable timeout,
     * a consistent state dialect — and finally the lane's own endpoint validator. Returns the resolved
     * selection only when every one of them passes, so that a {@link Selection.Selected} never carries
     * a value the caller cannot actually use.
     */
    private static Selection validate(String providerId, DecisionProviderConfig.Slot slot, URI endpoint, Lane lane) {
        String model = trimToEmpty(slot.model().orElse(""));
        if (model.isEmpty() && lane == Lane.REMOTE) {
            // The hosted System One contract makes `model` mandatory, so a remote slot without one is
            // refused rather than sent. The self-hosted lane is deliberately different: those servers
            // select their own checkpoint, and the mapper omits the field rather than inventing one.
            return unavailableFor(
                    providerId, "has no model configured; the remote System One contract requires a 'model' field");
        }
        WireProfile wireProfile = slot.wireProfile();
        if (REQUIRES_PROSE.matcher(model).matches() && wireProfile != WireProfile.PROSE) {
            // A wrong state dialect is not rejected by the provider — it degrades answers silently —
            // so the contradiction is refused here rather than sent.
            return unavailableFor(
                    providerId,
                    "names CLM model '" + model + "' but declares wire-profile " + wireProfile
                            + "; CLM was trained on prose and requires " + WireProfile.PROSE);
        }
        String apiKeyRef = trimToEmpty(slot.apiKeyRef().orElse(""));
        if (lane == Lane.REMOTE && requiresCredentialRef(providerId) && apiKeyRef.isEmpty()) {
            return unavailableFor(
                    providerId,
                    "is a credentialed remote provider but declares no api-key-ref; its documented profile"
                            + " authenticates with a bearer token, so the configuration is incomplete");
        }
        Duration timeout = slot.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // Both provider config constructors reject a non-positive timeout, so a Selected carrying
            // one would be a "validated" result that cannot actually be built. Refuse it here.
            return unavailableFor(
                    providerId, "has a non-positive timeout (" + timeout + "); a request deadline must be positive");
        }
        try {
            if (lane == Lane.SELF_HOSTED) {
                SelfHostedEndpointValidator.validateLoopbackEndpoint(endpoint);
            } else {
                RemoteEndpointValidator.validateRemoteEndpoint(endpoint);
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            return unavailableFor(providerId, "failed endpoint validation: " + ex.getMessage());
        }
        return new Selection.Selected(providerId, lane, endpoint, model, apiKeyRef, timeout, wireProfile);
    }

    private static Selection.Unavailable unavailableFor(String providerId, String problem) {
        return new Selection.Unavailable("Decision provider '" + providerId + "' " + problem);
    }

    /** Lowercases the host and strips the brackets an IPv6 literal carries in a URI. */
    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /** True for any host that resolves to the local machine, whether or not the loopback lane accepts it. */
    private static boolean isLoopbackLike(String normalizedHost) {
        return "localhost".equals(normalizedHost) || "::1".equals(normalizedHost) || normalizedHost.startsWith("127.");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
