package com.amsha.core.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.classifier.wire.WireProfile;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration-driven decision provider selection, mirroring the LLM provider convention
 * ({@code AMSHA_LLM_PROVIDER} plus {@code amsha.llm.<provider>.{base-url,api-key,model}}) so that a
 * closed provider and any local provider are interchangeable without a code change.
 *
 * <p><b>The axis is the endpoint, not the model.</b> A "provider" here is the System One endpoint that
 * receives {@code POST /v1/systemone}, exactly as an LLM provider is the endpoint that receives
 * {@code /v1/chat/completions}. A provider id may therefore name a specific decision server
 * ({@code laya}, {@code kev}, {@code clm}), a generic local runtime host that serves whichever decision
 * model is loaded into it ({@code omlx}), or a hosted API ({@code typesafe}). {@link Slot#model()} is
 * what selects the decision checkpoint served there — the same role {@code amsha.llm.<provider>.model}
 * plays. The two are deliberately separate: {@code omlx} is a runtime, {@code laya} is a model it can
 * serve, and the same runtime slot can serve either by changing {@code model}.
 *
 * <p>The prefix is {@code amsha.decision.providers} rather than {@code amsha.decision} on purpose:
 * {@code amsha.decision.shadow.*} already exists for the classifier switches, and mapping a broader
 * prefix would make those properties look like unknown members of this mapping. The {@code slots}
 * container exists for the same reason — it keeps the per-provider map from competing with
 * {@link #selected()} for the same level.
 *
 * <p><b>Declared slots are the whole allow-list.</b> {@link #selected()} names one of the slots under
 * {@link #slots()}; a provider id that is not declared resolves to nothing. The selector never
 * invents an endpoint from an id, so a typo cannot silently redirect traffic to an unintended host.
 *
 * <p><b>This mapping is inert.</b> It is a bean definition and nothing more: no engine, producer, or
 * router reads it to obtain a working {@code DecisionEngine}. Wiring it to one is the ADR change and
 * must remain a separate, reviewed step.
 */
@ConfigMapping(prefix = "amsha.decision.providers")
public interface DecisionProviderConfig {

    /**
     * The provider id to use for decision evaluation, matching a key under {@link #slots()}.
     * Blank — the default — selects nothing, which is the shipped Deferred posture.
     */
    @WithDefault("")
    String selected();

    /**
     * Declared provider slots, keyed by provider id.
     *
     * @return the declared slots, empty when none are configured
     */
    Map<String, Slot> slots();

    /**
     * One provider's settings — the endpoint, and what to ask of it.
     *
     * <p>Everything except {@link #enabled()} and {@link #timeout()} is optional in the mapping, but
     * the selector refuses an enabled slot that has no {@link #endpoint()} or no {@link #model()}.
     */
    interface Slot {

        /**
         * Whether this slot may be selected. Defaults to false so that adding configuration never
         * enables anything by itself.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * The {@code POST /v1/systemone} endpoint — this provider's identity, mirroring an LLM
         * provider's {@code base-url}.
         *
         * <p>The lane is derived from this value, not declared: a literal {@code 127.0.0.1} host
         * selects the self-hosted lane, anything else the remote lane. A loopback-ish host that is
         * not the literal {@code 127.0.0.1} (such as {@code localhost} or {@code ::1}) is rejected as
         * ambiguous rather than silently routed through the remote lane, because the two lanes carry
         * different governance.
         */
        Optional<String> endpoint();

        /**
         * The decision model to request — the {@code model} field on the wire, mirroring
         * {@code amsha.llm.<provider>.model}. Names the checkpoint served by this endpoint, for
         * example {@code laya-multilingual}, {@code clm-latest}, {@code kev}, or {@code jev-1.13.0}.
         *
         * <p>This is a separate axis from the provider id, which is why it exists at all: the same
         * runtime endpoint can serve different decision models, and a specific decision server
         * ({@code laya-serve}) still needs the checkpoint named explicitly. Required whenever the slot
         * is enabled — the System One contract makes {@code model} mandatory, so an enabled slot
         * without one is refused rather than silently defaulted.
         */
        Optional<String> model();

        /**
         * A {@code SecretVault} <em>reference</em> for the bearer credential — never a literal key.
         * Resolution belongs to the vault and the wiring layer; this mapping only carries the name.
         */
        Optional<String> apiKeyRef();

        /** Request execution timeout. */
        @WithDefault("PT5S")
        Duration timeout();

        /**
         * How this endpoint expects the {@code state} field to be rendered.
         *
         * <p>This belongs to the <em>model</em>, not the endpoint: the accepted profiles are a closed
         * set, and CLM's heads were trained on prose, so a JSON {@code state} degrades its answers
         * silently instead of failing loudly. Defaults to {@link WireProfile#JSON_OBJECT}, which
         * TypeSafe, Laya, and Kev accept. A CLM model must declare {@link WireProfile#PROSE}, and the
         * selector refuses the contradiction rather than sending it.
         */
        @WithDefault("JSON_OBJECT")
        WireProfile wireProfile();
    }
}
