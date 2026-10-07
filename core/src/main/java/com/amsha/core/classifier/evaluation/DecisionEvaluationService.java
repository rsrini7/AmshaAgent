package com.amsha.core.classifier.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionAnswer;
import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.IntentDecisionPolicy;
import com.amsha.classifier.api.Noul;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.Score;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.classifier.provider.DecisionCredentialResolver;
import com.amsha.core.classifier.provider.DecisionEngineFactory;
import com.amsha.core.classifier.provider.DecisionEngineHandle;
import com.amsha.core.classifier.provider.DecisionProviderUnavailableException;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderSelector;
import com.amsha.core.routing.IntentDecisionRouter;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.vertx.core.Context;

/**
 * The sanctioned way to make one real decision-provider call.
 *
 * <p><b>It is not a router.</b> It never feeds a routing decision, never registers an engine bean,
 * and never runs on its own. It is the explicit, opt-in path an operator uses to gather evidence for
 * a provider, and it is the only caller of the {@link DecisionEngineFactory} construction seam. That
 * separation is what lets a real provider call exist while the decision plane stays Deferred:
 * constructing an engine for one evaluation is not registering one, so {@code Instance<DecisionEngine>}
 * remains unsatisfiable.
 *
 * <p>Five properties are load-bearing:
 *
 * <ul>
 *   <li><b>Opt-in.</b> {@code amsha.decision.evaluation.enabled} defaults to {@code false}; while it is
 *       off the service refuses before it touches a provider, so no egress is possible.</li>
 *   <li><b>Internal-only and off the event loop.</b> The HTTP surface is internal-only, and this
 *       service refuses to run on a Vert.x event-loop thread rather than blocking it.</li>
 *   <li><b>Nothing widens the egress bounds.</b> Retention, redaction, the projection allow-list,
 *       {@code allowedProviders} and {@code failMode} come from the registered
 *       {@link RegisteredDecision} and the provider's governance declaration; a request may only
 *       supply the options of a question the registry marked {@code CALLER_SUPPLIED}.</li>
 *   <li><b>Audited before egress.</b> One payload-free mandatory event is written immediately before
 *       the provider call. If the audit write fails, the call does not happen.</li>
 *   <li><b>Payload-free everywhere.</b> The outcome carries answer labels and telemetry, never state,
 *       a credential, or a provider body; the credential is zeroed in a {@code finally}.</li>
 * </ul>
 */
@ApplicationScoped
public class DecisionEvaluationService {

    private static final Logger log = Logger.getLogger(DecisionEvaluationService.class);

    private static final String AUDIT_STREAM = "decision-evaluation";
    private static final String PROVIDER_UNAVAILABLE_MESSAGE =
            "No eligible decision provider is configured; evaluation was refused";

    private final DecisionProviderConfig providerConfig;
    private final DecisionDefinitionRegistry registry;
    private final SecretVault vault;
    private final ObjectMapper mapper;
    private final OutboundHttpPolicyService outboundPolicyService;
    private final AuditService auditService;
    private final boolean enabled;

    @Inject
    public DecisionEvaluationService(
            DecisionProviderConfig providerConfig,
            DecisionDefinitionRegistry registry,
            SecretVault vault,
            ObjectMapper mapper,
            OutboundHttpPolicyService outboundPolicyService,
            AuditService auditService,
            @ConfigProperty(name = "amsha.decision.evaluation.enabled", defaultValue = "false") boolean enabled) {
        this.providerConfig = Objects.requireNonNull(providerConfig, "providerConfig");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outboundPolicyService = Objects.requireNonNull(outboundPolicyService, "outboundPolicyService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.enabled = enabled;
    }

    /** Whether the opt-in evaluation switch is on. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs one registered decision and returns a payload-free outcome.
     *
     * @param request the decision id, state, and any caller-supplied options
     * @param actorUserId the operator the audit event is attributed to
     * @return the payload-free outcome
     * @throws DecisionEvaluationRefusedException when the switch is off, the decision is unknown, the
     *     options are invalid, the call would run on the event loop, or no usable provider is configured
     */
    public DecisionEvaluationOutcome evaluate(DecisionEvaluationRequest request, String actorUserId) {
        Objects.requireNonNull(request, "request");
        requireEnabled();
        requireWorkerThread();
        RegisteredDecision decision = requireRegistered(request.decisionId());
        requireValidOptions(decision, request);
        DecisionProviderSelector.Selection.Selected selected = requireSelection();
        requireAllowedProvider(decision, selected);
        DecisionRequest engineRequest = buildEngineRequest(decision, request, selected);
        return execute(selected, decision, request, engineRequest, actorUserId);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.DISABLED,
                    "Decision evaluation is disabled (amsha.decision.evaluation.enabled=false); no egress is performed");
        }
    }

    private static void requireWorkerThread() {
        if (Context.isOnEventLoopThread()) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.EVENT_LOOP,
                    "Decision evaluation must run on a worker or virtual thread, never a Vert.x event-loop thread");
        }
    }

    private static final String UNREGISTERED_DECISION_MESSAGE = "No registered decision matches the requested id";

    private RegisteredDecision requireRegistered(String decisionId) {
        return registry.find(decisionId)
                .orElseThrow(() -> new DecisionEvaluationRefusedException(
                        DecisionRefusalReason.UNREGISTERED_DECISION, UNREGISTERED_DECISION_MESSAGE));
    }

    private static void requireValidOptions(RegisteredDecision decision, DecisionEvaluationRequest request) {
        decision.optionsProblem(request.choiceOptions()).ifPresent(problem -> {
            throw new DecisionEvaluationRefusedException(DecisionRefusalReason.INVALID_REQUEST, problem);
        });
    }

    private DecisionProviderSelector.Selection.Selected requireSelection() {
        DecisionProviderSelector.Selection selection = DecisionProviderSelector.select(providerConfig);
        if (selection instanceof DecisionProviderSelector.Selection.Selected selected) {
            return selected;
        }
        throw new DecisionEvaluationRefusedException(
                DecisionRefusalReason.PROVIDER_UNAVAILABLE, PROVIDER_UNAVAILABLE_MESSAGE);
    }

    private static DecisionRequest buildEngineRequest(
            RegisteredDecision decision,
            DecisionEvaluationRequest request,
            DecisionProviderSelector.Selection.Selected selected) {
        DecisionDefinition definition = decision.definition();
        DecisionRequest.Builder builder =
                DecisionRequest.builder(definition.decisionId(), definition.schemaVersion(), request.stateJson());
        for (QuestionSpec question : decision.questions()) {
            registerQuestion(builder, question, request);
        }
        builder.timeout(effectiveTimeout(definition.timeout(), request.timeout()))
                .targetCategory(categoryFor(selected.lane()))
                .redactionPolicy(definition.redactionPolicy())
                .retentionPolicy(definition.retentionPolicy());
        return builder.build();
    }

    private static void registerQuestion(
            DecisionRequest.Builder builder, QuestionSpec question, DecisionEvaluationRequest request) {
        switch (question.kind()) {
            case CHOICE ->
                builder.registerChoice(question.handle(), question.criteria(), optionsFor(question, request));
            case SCORE -> builder.registerScore(question.handle(), question.criteria(), question.fixedOptions());
            case NOUL -> builder.registerNoul(question.handle(), question.criteria());
        }
    }

    private static List<String> optionsFor(QuestionSpec question, DecisionEvaluationRequest request) {
        if (!question.callerSupplied()) {
            return question.fixedOptions();
        }
        return request.choiceOptions().getOrDefault(question.handle(), List.of());
    }

    /** The definition's deadline is a ceiling: a request may tighten it, never extend it. */
    private static Duration effectiveTimeout(Duration ceiling, Optional<Duration> requested) {
        if (requested.isEmpty()) {
            return ceiling;
        }
        Duration value = requested.get();
        return value.compareTo(ceiling) <= 0 ? value : ceiling;
    }

    private static ProviderCategory categoryFor(DecisionProviderSelector.Lane lane) {
        return lane == DecisionProviderSelector.Lane.SELF_HOSTED
                ? ProviderCategory.SELF_HOSTED
                : ProviderCategory.REMOTE;
    }

    /**
     * Refuses a decision that forbids the lane the provider selector resolved to.
     *
     * <p>The request cannot widen egress, and neither can a misconfigured selector: a decision whose
     * {@code allowedProviders} excludes the selected lane must not be sent there. Both seeded decisions
     * permit every lane today, so this is a guard for future definitions rather than a change to current
     * behaviour.
     */
    private static void requireAllowedProvider(
            RegisteredDecision decision, DecisionProviderSelector.Selection.Selected selected) {
        ProviderCategory category = categoryFor(selected.lane());
        if (!decision.definition().allowedProviders().contains(category)) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.INVALID_REQUEST,
                    "The selected '" + category + "' provider lane is not permitted for decision '"
                            + decision.decisionId() + "'");
        }
    }

    private DecisionEvaluationOutcome execute(
            DecisionProviderSelector.Selection.Selected selected,
            RegisteredDecision decision,
            DecisionEvaluationRequest request,
            DecisionRequest engineRequest,
            String actorUserId) {
        DecisionProviderConfig.Slot slot = providerConfig.slots().get(selected.providerId());
        if (slot == null) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.PROVIDER_UNAVAILABLE,
                    "Decision provider '" + selected.providerId() + "' is selected but not declared");
        }
        byte[] credential = null;
        try {
            credential = new DecisionCredentialResolver(vault)
                    .resolve(selected.providerId(), selected.apiKeyRef())
                    .orElse(null);
            recordEgressAudit(selected, decision, actorUserId);
            return evaluateWithEngine(selected, slot, credential, decision, request, engineRequest);
        } catch (DecisionProviderUnavailableException _) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.PROVIDER_UNAVAILABLE, PROVIDER_UNAVAILABLE_MESSAGE);
        } finally {
            DecisionCredentialResolver.zero(credential);
        }
    }

    private void recordEgressAudit(
            DecisionProviderSelector.Selection.Selected selected, RegisteredDecision decision, String actorUserId) {
        String model = selected.model() == null || selected.model().isBlank() ? "default" : selected.model();
        auditService.recordMandatory(
                AUDIT_STREAM,
                actorUserId,
                "operator",
                SecurityEventType.DECISION_EVALUATION_REQUESTED,
                "decision=" + decision.decisionId() + " provider=" + selected.providerId() + " lane=" + selected.lane()
                        + " model=" + model);
    }

    private DecisionEvaluationOutcome evaluateWithEngine(
            DecisionProviderSelector.Selection.Selected selected,
            DecisionProviderConfig.Slot slot,
            byte[] credential,
            RegisteredDecision decision,
            DecisionEvaluationRequest request,
            DecisionRequest engineRequest) {
        try (DecisionEngineHandle handle = DecisionEngineFactory.create(
                selected, slot.governance(), Optional.ofNullable(credential), mapper, outboundPolicyService, vault)) {
            DecisionResult result =
                    normalizeResult(decision.definition(), handle.engine().evaluate(engineRequest));
            log.debugf(
                    "Decision evaluation completed: decision=%s provider=%s opinion=%b",
                    engineRequest.decisionId(), selected.providerId(), result.isOpinion());
            return toOutcome(selected, decision, request, result);
        }
    }

    private static DecisionResult normalizeResult(DecisionDefinition definition, DecisionResult result) {
        if (result == null
                || !definition.decisionId().equals(result.decisionId())
                || definition.schemaVersion() != result.schemaVersion()) {
            return new DecisionResult.NoOpinion(
                    definition.decisionId(), definition.schemaVersion(), NoOpinionReason.INFERENCE_ERROR);
        }
        return result;
    }

    private static DecisionEvaluationOutcome toOutcome(
            DecisionProviderSelector.Selection.Selected selected,
            RegisteredDecision decision,
            DecisionEvaluationRequest request,
            DecisionResult result) {
        String lane = selected.lane().name();
        String model = selected.model();
        DecisionEvaluationOutcome outcome;
        if (result instanceof DecisionResult.Opinion opinion) {
            Map<String, String> answers = new LinkedHashMap<>();
            for (Map.Entry<QuestionKey<?>, DecisionAnswer> entry :
                    opinion.answers().entrySet()) {
                answers.put(entry.getKey().name(), summarize(entry.getValue()));
            }
            outcome = DecisionEvaluationOutcome.opinion(
                    selected.providerId(), lane, model, opinion.ruleId(), answers, opinion.metadata());
        } else {
            DecisionResult.NoOpinion noOpinion = (DecisionResult.NoOpinion) result;
            outcome = DecisionEvaluationOutcome.noOpinion(
                    selected.providerId(),
                    lane,
                    model,
                    noOpinion.ruleId(),
                    noOpinion.reason().name());
        }
        return attachPolicyResolution(outcome, decision, request, result);
    }

    private static DecisionEvaluationOutcome attachPolicyResolution(
            DecisionEvaluationOutcome outcome,
            RegisteredDecision decision,
            DecisionEvaluationRequest request,
            DecisionResult result) {
        DecisionDefinition definition = decision.definition();
        if (IntentDecisionRouter.DECISION_ID.equals(definition.decisionId())) {
            Set<String> validRoles = new LinkedHashSet<>(
                    request.choiceOptions().getOrDefault(IntentDecisionRouter.QUESTION_HANDLE, List.of()));
            return outcome.withIntentPolicyResolution(IntentDecisionPolicy.resolve(
                    result,
                    definition.decisionId(),
                    definition.schemaVersion(),
                    null,
                    validRoles,
                    definition.reviewBand()));
        }
        if (DecisionToolRiskShadowClassifier.DECISION_ID.equals(definition.decisionId())) {
            return outcome.withToolRiskPolicyResolution(ToolRiskDecisionPolicy.resolveCumulative(
                    null,
                    result,
                    DecisionToolRiskShadowClassifier.TOOL_RISK_KEY,
                    definition.reviewBand(),
                    definition.failMode()));
        }
        return outcome;
    }

    /** A bounded, payload-free rendering of one answer: a label and confidence, never the state. */
    private static String summarize(DecisionAnswer answer) {
        if (answer instanceof Choice choice) {
            return choice.confidence().isPresent()
                    ? choice.label() + " (" + choice.confidence().getAsDouble() + ")"
                    : choice.label();
        }
        if (answer instanceof Score score) {
            return score.confidence().isPresent()
                    ? "score=" + score.score() + " (" + score.confidence().getAsDouble() + ")"
                    : "score=" + score.score();
        }
        if (answer instanceof Noul noul) {
            return "probability=" + noul.probability();
        }
        return answer.primitive().name();
    }
}
