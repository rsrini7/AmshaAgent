package com.amsha.core.routing;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Announces a satisfied activation gate that names a placeholder rollout owner.
 *
 * <p>{@code amsha.routing.intent-decision-router.activation-gate.rollout-owner} is free text, and the only
 * rule {@code IntentDecisionRouter.isActivationGateApproved()} enforces on it is <em>non-blank</em>. There
 * is no closed set, no enum, and no pattern, so a placeholder such as {@code TBD}, {@code x} or {@code true}
 * satisfies the gate exactly as a real owner would. Because the other two gate criteria are booleans that an
 * operator sets deliberately, a junk owner silently converts "not yet approved" into "formally approved" —
 * strictly worse than leaving the field empty, which is the honest Deferred state.
 *
 * <p>This bean exists to make that specific mistake visible once, at startup. It is deliberately
 * <em>advisory</em>: it does not validate, reject, normalise, or block anything, and it does not change
 * {@code isActivationGateApproved()}. Rejecting placeholder values would change activation-gate semantics —
 * configuration that currently opens the gate would begin failing — which is an ADR-level change and must
 * not arrive as a side effect of a diagnostic.
 *
 * <p>It warns whether or not the router switch is enabled, because the hazard is that the gate is already
 * open: flipping {@code amsha.routing.intent-decision-router.enabled} later would find the gate satisfied.
 * It logs nothing under the default configuration, and nothing when the owner is blank.
 *
 * <p>This is a separate diagnostic from {@link com.amsha.core.classifier.DecisionPlaneReadiness} and uses a
 * different logger name on purpose. That bean's "exactly one WARN" contract (invariant §27.2, and JEV-12 in
 * {@code rest-http/scripts/validate_decision_plane.sh}) counts log lines containing its own logger name;
 * emitting a second, differently-triggered warning from that logger would make the count two and read as a
 * broken {@code @Startup} initialization.
 */
@Startup
@ApplicationScoped
public class ActivationGateReadiness {

    private static final Logger log = Logger.getLogger(ActivationGateReadiness.class);

    /**
     * Whole-value placeholders: strings that satisfy the non-blank gate check while identifying nobody.
     * Matched case-insensitively against the trimmed value, never as a substring — a real owner may
     * legitimately contain one of these words (for example {@code test-ops@example.com}).
     *
     * <p>Only values that no other rule already catches belong here. {@link #looksLikePlaceholder}
     * rejects anything two characters or shorter, and anything made of a single repeated character, as
     * structure rather than as a vocabulary — so {@code "no"}, {@code "na"} and {@code "xxx"} are
     * covered without appearing below, and listing them would be dead weight that reads as if it were
     * doing the work.
     */
    private static final Set<String> PLACEHOLDER_TOKENS = Set.of(
            "true",
            "false",
            "yes",
            "tbd",
            "tba",
            "tbc",
            "todo",
            "fixme",
            "none",
            "null",
            "nil",
            "n/a",
            "dummy",
            "placeholder",
            "changeme",
            "pending",
            "unknown",
            "wip");

    private final boolean candidateEvidenceApproved;
    private final boolean costCeilingApproved;
    private final String rolloutOwner;

    @Inject
    public ActivationGateReadiness(
            @ConfigProperty(
                            name = "amsha.routing.intent-decision-router.activation-gate.candidate-evidence-approved",
                            defaultValue = "false")
                    boolean candidateEvidenceApproved,
            @ConfigProperty(
                            name = "amsha.routing.intent-decision-router.activation-gate.cost-ceiling-approved",
                            defaultValue = "false")
                    boolean costCeilingApproved,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.activation-gate.rollout-owner")
                    Optional<String> rolloutOwnerOpt) {
        this.candidateEvidenceApproved = candidateEvidenceApproved;
        this.costCeilingApproved = costCeilingApproved;
        // CDI never injects a null Optional for a @ConfigProperty, so an absent owner arrives as
        // Optional.empty() and needs no null guard.
        this.rolloutOwner = rolloutOwnerOpt.orElse("");
    }

    @PostConstruct
    void reportPlaceholderOwner() {
        placeholderOwnerWarning(candidateEvidenceApproved, costCeilingApproved, rolloutOwner)
                .ifPresent(log::warn);
    }

    /**
     * Builds the startup warning for a satisfied activation gate whose rollout owner is a placeholder.
     *
     * @param candidateEvidenceApproved whether the candidate-evidence criterion is approved
     * @param costCeilingApproved whether the cost-ceiling criterion is approved
     * @param rolloutOwner the configured rollout owner, which may be null or blank
     * @return the warning text, or empty when the gate is not satisfied or the owner is absent/genuine
     */
    static Optional<String> placeholderOwnerWarning(
            boolean candidateEvidenceApproved, boolean costCeilingApproved, String rolloutOwner) {
        // A partially approved gate cannot be opened by the owner, so there is nothing to warn about.
        if (!candidateEvidenceApproved || !costCeilingApproved) {
            return Optional.empty();
        }
        if (!looksLikePlaceholder(rolloutOwner)) {
            return Optional.empty();
        }
        return Optional.of("Intent-decision-router activation gate is SATISFIED by a placeholder rollout owner"
                + " ('" + rolloutOwner.trim()
                + "'). amsha.routing.intent-decision-router.activation-gate.rollout-owner is"
                + " free text whose only enforced rule is NON-BLANK, so a value like this opens the gate while naming no"
                + " accountable owner, and the two approved booleans then count as a formal approval. Set a real owner, or"
                + " leave the field empty to keep the pilot honestly Deferred. Advisory only: nothing is enabled,"
                + " validated, or blocked by this warning.");
    }

    /**
     * Whether an owner value satisfies the non-blank gate check while identifying nobody.
     *
     * <p>Deliberately conservative in shape and never substring-based, so a genuine owner is not flagged:
     * an email, an {@code @handle}, a {@code team:slug}, or a plain name all pass. Blank is not a
     * placeholder — it is the honest "not approved" state, which leaves the gate closed.
     *
     * @param owner the configured rollout owner, which may be null
     * @return true when the value looks like a placeholder rather than an accountable owner
     */
    static boolean looksLikePlaceholder(String owner) {
        if (owner == null) {
            return false;
        }
        String value = owner.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        // Two characters or fewer cannot identify an accountable owner ("x", "?", "--", "JD").
        // This deliberately over-flags: a two-letter string could in principle be a person's initials,
        // and the cost of the false positive is one advisory startup WARN. The cost of the false
        // negative is the opposite — a satisfied gate attributed to nobody — which is the hazard this
        // diagnostic exists to surface. `looksLikePlaceholder` never rejects anything, so erring
        // toward warning is the safe direction.
        if (value.length() <= 2) {
            return true;
        }
        if (PLACEHOLDER_TOKENS.contains(value)) {
            return true;
        }
        // A bracketed or templated value ("<owner>", "[TBD]", "(none)", "{team}") was never filled in.
        char first = value.charAt(0);
        if (first == '<' || first == '[' || first == '(' || first == '{') {
            return true;
        }
        // An all-one-character run ("---", "???", "xxx") is a keyboard placeholder, not a name.
        return value.chars().distinct().count() == 1;
    }
}
