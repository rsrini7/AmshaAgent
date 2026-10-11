package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.classifier.evaluation.DecisionEvaluationOutcome;
import com.amsha.core.classifier.evaluation.DecisionEvaluationRefusedException;
import com.amsha.core.classifier.evaluation.DecisionEvaluationRequest;
import com.amsha.core.classifier.evaluation.DecisionEvaluationService;
import com.amsha.core.classifier.evaluation.DecisionEvidenceReport;
import com.amsha.core.classifier.evaluation.DecisionEvidenceRunner;
import com.amsha.core.classifier.evaluation.DecisionEvidenceSummary;
import com.amsha.core.classifier.evaluation.DecisionRefusalReason;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderGovernance;
import com.amsha.core.config.DecisionProviderSelector;
import com.amsha.core.scope.WorkspaceContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Internal-only operator surface for the opt-in decision evaluation path.
 *
 * <p>This is the only HTTP entry point that can cause a decision-provider call. It is deliberately
 * narrow: three endpoints, every one guarded by {@link #isInternal()}, and none of them able to widen
 * the egress bounds the registered decision and the provider's governance declaration already set.
 *
 * <ul>
 *   <li>{@code GET /admin/decision/provider} — resolved selection status: identifiers and booleans
 *       only. It reads the configuration and never touches the vault, so it exposes no secret and
 *       emits no {@code VAULT_ACCESS} audit event.</li>
 *   <li>{@code POST /admin/decision/evaluate} — one evaluation over a registered decision.</li>
 *   <li>{@code POST /admin/decision/evidence/run} — runs a registered versioned evidence corpus and returns a
 *       summary with the M1–M5 gate metrics, as JSON or (with {@code format=markdown}) as a review-ready
 *       report. The version defaults to 1 and is selected from the corpus registry; an unregistered version
 *       is refused. The summary is attributed with a run id and the resolved provider and configured model;
 *       {@code diagnostics=true} adds aggregate disposition counts and grouped provider/model/lane token
 *       coverage, without exposing case-level data.</li>
 * </ul>
 *
 * <p>Every method runs on a virtual thread ({@link RunOnVirtualThread}), so the provider call can
 * block without pinning an event-loop thread; the service additionally refuses an event-loop caller
 * as defence in depth.
 */
@ApplicationScoped
@Path("/admin/decision")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DecisionAdminResource {

    private static final Set<String> EVALUATE_FIELDS = Set.of("decisionId", "stateJson", "choiceOptions", "timeoutMs");

    @Inject
    DecisionEvaluationService evaluationService;

    @Inject
    DecisionEvidenceRunner evidenceRunner;

    @Inject
    DecisionProviderConfig providerConfig;

    @Inject
    ObjectMapper mapper;

    @Context
    SecurityContext securityContext;

    /** Reports the resolved provider selection as identifiers and booleans only. */
    @GET
    @Path("/provider")
    public Response provider() {
        if (!isInternal()) {
            return forbidden();
        }
        boolean evaluationEnabled = evaluationService.isEnabled();
        DecisionProviderSelector.Selection selection = DecisionProviderSelector.select(providerConfig);
        if (selection instanceof DecisionProviderSelector.Selection.Selected selected) {
            DecisionProviderConfig.Slot slot = providerConfig.slots().get(selected.providerId());
            boolean governanceDeclared = slot != null
                    && DecisionProviderGovernance.problem(slot.governance(), selected.lane())
                            .isEmpty();
            return Response.ok(new ProviderStatusResponse(
                            selected.providerId(),
                            selected.lane().name(),
                            selected.endpoint().getHost(),
                            blankToNull(selected.model()),
                            governanceDeclared,
                            selected.credentialConfigured(),
                            evaluationEnabled,
                            null))
                    .build();
        }
        String reason = ((DecisionProviderSelector.Selection.Unavailable) selection).reason();
        return Response.ok(new ProviderStatusResponse(
                        blankToNull(providerConfig.selected().orElse(null)),
                        null,
                        null,
                        null,
                        false,
                        false,
                        evaluationEnabled,
                        reason))
                .build();
    }

    /** Runs one evaluation over a registered decision and returns a payload-free outcome. */
    @POST
    @Path("/evaluate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response evaluate(String jsonBody) {
        if (!isInternal()) {
            return forbidden();
        }
        DecisionEvaluationRequest request;
        try {
            request = parseEvaluateRequest(jsonBody).toCore();
        } catch (IOException | IllegalArgumentException | NullPointerException ex) {
            return badRequest("invalid_request", "decision evaluation request is missing or malformed");
        }
        try {
            DecisionEvaluationOutcome outcome = evaluationService.evaluate(request, resolveUserId());
            return Response.ok(EvaluateResponse.from(outcome)).build();
        } catch (DecisionEvaluationRefusedException ex) {
            return Response.status(statusFor(ex.reason()))
                    .entity(new AmshaErrorResponse(ex.reason().name(), ex.getMessage()))
                    .build();
        }
    }

    private EvaluateRequest parseEvaluateRequest(String jsonBody) throws IOException {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new IllegalArgumentException("request body must not be blank");
        }
        try (JsonParser parser = mapper.getFactory().createParser(jsonBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("request body must contain exactly one JSON value");
            }
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!EVALUATE_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("unknown request field");
                }
            }

            String decisionId = textField(root, "decisionId");
            String stateJson = textField(root, "stateJson");
            Map<String, List<String>> choiceOptions = choiceOptionsField(root);
            Long timeoutMs = timeoutField(root);
            return new EvaluateRequest(decisionId, stateJson, choiceOptions, timeoutMs);
        }
    }

    private static String textField(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private static Map<String, List<String>> choiceOptionsField(JsonNode root) {
        JsonNode optionsNode = root.get("choiceOptions");
        if (optionsNode == null || optionsNode.isNull()) {
            return Map.of();
        }
        if (!optionsNode.isObject()) {
            throw new IllegalArgumentException("choiceOptions must be an object");
        }
        Map<String, List<String>> options = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = optionsNode.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!entry.getValue().isArray()) {
                throw new IllegalArgumentException("choiceOptions values must be arrays");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode option : entry.getValue()) {
                if (!option.isTextual()) {
                    throw new IllegalArgumentException("choiceOptions values must be strings");
                }
                values.add(option.textValue());
            }
            options.put(entry.getKey(), List.copyOf(values));
        }
        return Map.copyOf(options);
    }

    private static Long timeoutField(JsonNode root) {
        JsonNode timeout = root.get("timeoutMs");
        if (timeout == null || timeout.isNull()) {
            return null;
        }
        if (!timeout.isIntegralNumber() || !timeout.canConvertToLong()) {
            throw new IllegalArgumentException("timeoutMs must be an integer");
        }
        return timeout.longValue();
    }

    /**
     * Runs the registered evidence corpus and returns a summary with the M1–M5 metrics.
     *
     * <p>{@code format=json} (the default) returns the structured summary; {@code format=markdown} returns
     * the same content rendered for review, so an operator can redirect it straight into an evidence file
     * (for example {@code ... '?format=markdown' > .scratch/jev-decision-plane/evidence/report.md}). An
     * unrecognised format is refused as a client error.
     *
     * <p><b>Each call is a separate run.</b> The corpus is evaluated anew for either format, so the two
     * forms are not two views of one measurement. The response carries a run identifier and the resolved
     * provider and configured model, which is what lets a saved artifact be attributed to a specific run
     * rather than to whichever run happened to produce it.
     *
     * <p><b>{@code diagnostics=true} is opt-in.</b> It adds payload-free aggregate disposition counts and
     * provider/model/lane token coverage and totals. It contains no case identifiers, labels, policy
     * decisions, inputs, or error details.
     */
    @POST
    @Path("/evidence/run")
    public Response evidenceRun(
            @QueryParam("version") @DefaultValue("1") int version,
            @QueryParam("format") @DefaultValue("json") String format,
            @QueryParam("diagnostics") String diagnosticsParameter) {
        if (!isInternal()) {
            return forbidden();
        }
        String normalizedDiagnostics = diagnosticsParameter == null ? "false" : diagnosticsParameter.trim();
        if (!"true".equalsIgnoreCase(normalizedDiagnostics) && !"false".equalsIgnoreCase(normalizedDiagnostics)) {
            return badRequest("INVALID_REQUEST", "diagnostics must be true or false");
        }
        boolean diagnostics = Boolean.parseBoolean(normalizedDiagnostics);
        String normalized = format == null ? "json" : format.trim().toLowerCase(Locale.ROOT);
        boolean markdown = "markdown".equals(normalized);
        if (!markdown && !"json".equals(normalized)) {
            return badRequest("INVALID_REQUEST", "format must be json or markdown");
        }
        try {
            DecisionEvidenceSummary summary = evidenceRunner.run(version, resolveUserId(), diagnostics);
            if (markdown) {
                return Response.ok(DecisionEvidenceReport.toMarkdown(summary))
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .build();
            }
            return Response.ok(summary).build();
        } catch (DecisionEvaluationRefusedException ex) {
            return Response.status(statusFor(ex.reason()))
                    .entity(new AmshaErrorResponse(ex.reason().name(), ex.getMessage()))
                    .build();
        }
    }

    private static Response.Status statusFor(DecisionRefusalReason reason) {
        return switch (reason) {
            case DISABLED, PROVIDER_UNAVAILABLE, CORPUS_NOT_MEASURABLE -> Response.Status.CONFLICT;
            case UNREGISTERED_DECISION, INVALID_REQUEST -> Response.Status.BAD_REQUEST;
            case EVENT_LOOP -> Response.Status.INTERNAL_SERVER_ERROR;
        };
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new AmshaErrorResponse("Only internal API keys can use the decision evaluation path"))
                .build();
    }

    private static Response badRequest(String code, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new AmshaErrorResponse(code, message))
                .build();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    /**
     * Resolved provider status exposes identifiers, booleans, and a payload-free reason only.
     * {@code credentialDeclared} remains the wire field name and means a direct provider API
     * key is configured; it never contains credential material.
     */
    public record ProviderStatusResponse(
            String providerId,
            String lane,
            String host,
            String model,
            boolean governanceDeclared,
            boolean credentialDeclared,
            boolean evaluationEnabled,
            String reason) {}

    /** One evaluation request. It carries no policy — only the decision id, state, and options. */
    public record EvaluateRequest(
            String decisionId, String stateJson, Map<String, List<String>> choiceOptions, Long timeoutMs) {

        DecisionEvaluationRequest toCore() {
            Optional<Duration> timeout =
                    timeoutMs == null ? Optional.empty() : Optional.of(Duration.ofMillis(timeoutMs));
            return new DecisionEvaluationRequest(
                    decisionId, stateJson, choiceOptions == null ? Map.of() : choiceOptions, timeout);
        }
    }

    /** Payload-free evaluation outcome. */
    public record EvaluateResponse(
            String providerId,
            String lane,
            String model,
            boolean opinion,
            String ruleId,
            String noOpinionReason,
            Map<String, String> answers,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens) {

        static EvaluateResponse from(DecisionEvaluationOutcome outcome) {
            return new EvaluateResponse(
                    outcome.providerId(),
                    outcome.lane(),
                    outcome.model(),
                    outcome.opinion(),
                    outcome.ruleId(),
                    outcome.noOpinionReason(),
                    outcome.answers(),
                    outcome.metadata().latency() == null
                            ? null
                            : outcome.metadata().latency().toMillis(),
                    outcome.metadata().inputTokens(),
                    outcome.metadata().outputTokens());
        }
    }
}
