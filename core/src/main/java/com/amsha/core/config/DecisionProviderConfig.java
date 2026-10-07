package com.amsha.core.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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
     * Empty means no provider is selected, keeping the shipped Deferred posture.
     */
    Optional<String> selected();

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
     * the selector refuses an enabled slot that has no {@link #endpoint()}, and one that has no
     * {@link #model()} on the <em>remote</em> lane — a self-hosted slot may omit the model, because
     * those servers select their own checkpoint.
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
         * ({@code laya-serve}) still needs the checkpoint named explicitly. Required on the
         * <em>remote</em> lane — the hosted System One contract makes {@code model} mandatory, so a
         * remote slot without one is refused rather than silently defaulted. The self-hosted lane may
         * omit it: those servers select their own checkpoint, and the mapper omits the field rather
         * than inventing one.
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

        /**
         * The data-governance declaration for this provider.
         *
         * <p>Every member is optional or defaulted, so an undeclared group binds to an empty
         * declaration rather than failing the whole mapping. Completeness is a <em>lane-dependent</em>
         * rule enforced when an engine is actually built — see {@code DecisionProviderGovernance} —
         * not at binding time: a self-hosted loopback slot needs no residency declaration, whereas a
         * remote slot that egresses state must declare one. Requiring it here would refuse the
         * self-hosted lane for a rule that does not apply to it.
         */
        Governance governance();

        /**
         * Data-governance controls for one provider slot.
         *
         * <p>These are the declarations the parent ADR's Governance gate requires before state may
         * leave the process. Declaring them here records an operator decision; it does not by itself
         * activate anything.
         */
        interface Governance {

            /**
             * Declared geographic or cloud location of the provider (for example {@code US_EAST},
             * {@code EU_WEST}, {@code SELF_HOSTED_LOCAL}). Required for a remote slot.
             */
            Optional<String> dataLocation();

            /**
             * Maximum duration the provider may retain submitted state. {@code PT0S} — the default —
             * declares zero retention, which is a positive statement, not an absence of one.
             */
            @WithDefault("PT0S")
            Duration retentionWindow();

            /**
             * Whether the provider may train on submitted state. Must remain {@code false}; a
             * {@code true} value is refused rather than sent.
             */
            @WithDefault("false")
            boolean allowTrainingUse();

            /**
             * Whether the provider enforces tenant isolation. Must remain {@code true}; a
             * {@code false} value is refused.
             */
            @WithDefault("true")
            boolean tenantIsolation();

            /**
             * The top-level state field names permitted to leave the process, comma-separated.
             *
             * <p>This is the allow-list the projection redactor applies before serialization. It is
             * deliberately explicit: the projection fails closed rather than inventing an allow-list
             * from question handles, so an enabled slot that egresses state without one is refused.
             */
            Optional<List<String>> projectionAllowList();

            /**
             * The authorised cost ceiling per 1,000 decisions, if one has been approved. Required
             * before production activation; not required to gather evaluation evidence.
             */
            Optional<BigDecimal> costCeilingPerThousand();
        }
    }
}
