package com.amsha.core.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
 *       a credentialed remote provider with no {@code api-key-ref}, a non-positive timeout, a CLM
 *       model not declared as prose, and an incomplete data-governance declaration (see
 *       {@link DecisionProviderGovernance#problem}). The reason describes operator configuration only —
 *       never user state, credentials, or a resolved secret.</li>
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
         * <p><b>This record does not validate itself.</b> {@link DecisionProviderSelector#select} only
         * ever emits a value that passed {@link DecisionProviderSelector#selectionProblem}, but the
         * canonical constructor is public, so any other producer — a test double, or a future code path —
         * must consult that rule before using the value. {@code DecisionEngineFactory} does exactly that,
         * and refuses an unusable selection rather than building from it.
         *
         * @param providerId the declared slot id that was selected
         * @param lane governance lane derived from the endpoint host
         * @param endpoint the validated endpoint URI
         * @param model the decision model to request; blank is permitted only on the self-hosted lane
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
        String providerId =
                config.selected().map(DecisionProviderSelector::trimToEmpty).orElse("");
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
     * Applies the rules that depend on the slot together with the lane it resolved to, then the
     * governance rule, and returns the resolved selection only when every one of them passes — so that
     * a {@link Selection.Selected} never carries a value the caller cannot actually use.
     */
    private static Selection validate(String providerId, DecisionProviderConfig.Slot slot, URI endpoint, Lane lane) {
        String model = trimToEmpty(slot.model().orElse(""));
        String apiKeyRef = trimToEmpty(slot.apiKeyRef().orElse(""));
        Duration timeout = slot.timeout();
        WireProfile wireProfile = slot.wireProfile();
        Optional<String> problem = selectionProblem(providerId, lane, endpoint, model, apiKeyRef, timeout, wireProfile);
        if (problem.isPresent()) {
            return unavailableFor(providerId, problem.get());
        }
        // Governance is checked last, once the lane is certain: its completeness rules are
        // lane-dependent (residency is required only where state actually egresses), and a slot whose
        // endpoint is unusable should report that rather than a governance rule it would never reach.
        Optional<String> governanceProblem = DecisionProviderGovernance.problem(slot.governance(), lane);
        if (governanceProblem.isPresent()) {
            return unavailableFor(providerId, governanceProblem.get());
        }
        return new Selection.Selected(providerId, lane, endpoint, model, apiKeyRef, timeout, wireProfile);
    }

    /**
     * The reason a resolved selection is not usable, if any — the canonical rule that both this
     * selector and {@code DecisionEngineFactory} consult.
     *
     * <p>It is extracted rather than inlined so the two callers cannot disagree. The selector turns a
     * problem into a {@link Selection.Unavailable}; the factory turns the same problem into a refusal.
     * That second caller is why this is public: {@link Selection.Selected} is a publicly constructible
     * record, so a hand-built value can carry a model the lane forbids, a credential-less TypeSafe
     * endpoint, a contradictory CLM dialect, a non-positive timeout, or an endpoint the lane validator
     * rejects. The selector would never emit one, but the factory is the authoritative construction
     * gate and must not depend on an upstream invariant it cannot verify.
     *
     * <p>Pure and payload-free: no I/O, no credential resolution, and a reason naming operator
     * configuration only. Governance completeness is deliberately <em>not</em> part of this rule — it is
     * lane-dependent and validated separately by {@link DecisionProviderGovernance#problem}, which both
     * callers also consult.
     *
     * @param providerId the declared slot id
     * @param lane the lane the endpoint resolved to
     * @param endpoint the endpoint URI
     * @param model the requested model; may be blank where the lane allows it
     * @param apiKeyRef a {@code SecretVault} reference, never a secret; blank when unset
     * @param timeout the request deadline
     * @param wireProfile how the provider expects {@code state} rendered
     * @return the reason the selection is unusable, or empty when it is well formed
     */
    public static Optional<String> selectionProblem(
            String providerId,
            Lane lane,
            URI endpoint,
            String model,
            String apiKeyRef,
            Duration timeout,
            WireProfile wireProfile) {
        if (lane == null) {
            return Optional.of("has no governance lane resolved");
        }
        if (endpoint == null) {
            return Optional.of("has no endpoint configured");
        }
        if (wireProfile == null) {
            return Optional.of("declares no wire-profile");
        }
        String effectiveModel = trimToEmpty(model);
        if (effectiveModel.isEmpty() && lane == Lane.REMOTE) {
            // The hosted System One contract makes `model` mandatory, so a remote slot without one is
            // refused rather than sent. The self-hosted lane is deliberately different: those servers
            // select their own checkpoint, and the mapper omits the field rather than inventing one.
            return Optional.of("has no model configured; the remote System One contract requires a 'model' field");
        }
        if (REQUIRES_PROSE.matcher(effectiveModel).matches() && wireProfile != WireProfile.PROSE) {
            // A wrong state dialect is not rejected by the provider — it degrades answers silently —
            // so the contradiction is refused here rather than sent.
            return Optional.of("names CLM model '" + effectiveModel + "' but declares wire-profile " + wireProfile
                    + "; CLM was trained on prose and requires " + WireProfile.PROSE);
        }
        if (lane == Lane.REMOTE
                && requiresCredentialRef(providerId)
                && trimToEmpty(apiKeyRef).isEmpty()) {
            return Optional.of("is a credentialed remote provider but declares no api-key-ref; its documented profile"
                    + " authenticates with a bearer token, so the configuration is incomplete");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // Both provider config constructors reject a non-positive timeout, so a Selected carrying
            // one would be a "validated" result that cannot actually be built. Refuse it here.
            return Optional.of("has a non-positive timeout (" + timeout + "); a request deadline must be positive");
        }
        try {
            if (lane == Lane.SELF_HOSTED) {
                SelfHostedEndpointValidator.validateLoopbackEndpoint(endpoint);
            } else {
                RemoteEndpointValidator.validateRemoteEndpoint(endpoint);
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            return Optional.of("failed endpoint validation: " + ex.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Whether a resolved selection must be given non-empty credential material before it can be built.
     *
     * <p>This is the second half of the credential rule. {@link #selectionProblem} refuses a credentialed
     * provider that declares no {@code api-key-ref}; this answers whether the material that reference
     * names is mandatory. {@link com.amsha.core.classifier.provider.DecisionEngineFactory} consults it so
     * a provider is never built to authenticate with an empty bearer token — a reference the vault could
     * not resolve to any bytes is as unusable as no reference at all, but only the second was refused
     * before.
     *
     * <p>It is deliberately <em>not</em> "remote implies credentialed": a remote deployment may
     * authenticate by mTLS or network policy instead of a bearer token, and the self-hosted lane
     * authenticates with its own optional loopback header, so a self-hosted selection that names a
     * reference still builds without material. Only the closed hosted profiles that document a bearer
     * token require material, exactly as in {@link #selectionProblem}.
     *
     * @param providerId the declared slot id
     * @param lane the lane the endpoint resolved to
     * @return true when the provider authenticates with a bearer credential
     */
    public static boolean requiresCredentialMaterial(String providerId, Lane lane) {
        return lane == Lane.REMOTE && requiresCredentialRef(providerId);
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
