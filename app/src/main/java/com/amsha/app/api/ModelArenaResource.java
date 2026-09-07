package com.amsha.app.api;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.app.ui.dto.ArenaCompareRequest;
import com.amsha.app.ui.dto.ArenaCompareResponse;
import com.amsha.app.ui.dto.ArenaModelResult;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ChatModelProducer;
import com.amsha.core.agent.trace.TraceRedactor;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.guardrail.GuardDecision;
import com.amsha.core.guardrail.PiiInputGuard;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.routing.ModelRouteSelector;
import com.amsha.core.routing.TurnRequirements;
import com.amsha.core.scope.ContextAwareExecutorService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.CredentialDetectionResult;
import com.amsha.core.security.CredentialDetector;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportMetrics;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Internal-only Model Arena endpoint for benchmarking configured LLM provider slots.
 *
 * <p>Enforces input token bounding (<=2,048), output token bounding (<=1,024), fail-closed credential detection,
 * PII policy guardrails, governed transport validation via GovernedTransportService, route selection via
 * ModelRouteSelector, virtual thread execution with strict overall deadlines, categorical error classification,
 * and cryptographic audit hashing with zero prompt or completion disclosure.
 */
@ApplicationScoped
@Path("/admin/arena")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ModelArenaResource {

    private static final Logger log = Logger.getLogger(ModelArenaResource.class);

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String defaultProviderName;

    @Inject
    CredentialDetector credentialDetector;

    @Inject
    Instance<PiiInputGuard> piiInputGuard;

    @Inject
    TraceRedactor traceRedactor;

    @Inject
    AuditService auditService;

    @Inject
    ChatModelProducer chatModelProducer;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    Instance<GovernedTransportService> governedTransport;

    @Inject
    Instance<ModelRouteSelector> modelRouteSelector;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    @Context
    SecurityContext securityContext;

    @POST
    @Path("/compare")
    public Response compareModels(ArenaCompareRequest request) {
        if (!isInternal()) {
            return error(Response.Status.FORBIDDEN, "Internal operator role required for Model Arena");
        }

        Response validationError = ModelArenaHelper.validateCompareRequest(request);
        if (validationError != null) {
            return validationError;
        }

        long overallStartNs = System.nanoTime();
        MeterRegistry meterRegistry = GovernedTransportMetrics.resolve(meterRegistryInstance);
        String prompt = request.prompt(); // Faithful prompt invocation: preserve leading/trailing whitespace
        List<String> modelSlots = request.models();
        int maxOutputTokens = request.maxTokens() != null
                ? Math.min(Math.max(1, request.maxTokens()), ModelArenaHelper.MAX_OUTPUT_TOKENS)
                : ModelArenaHelper.DEFAULT_OUTPUT_TOKENS;

        String callerUserId = resolveUserId();
        String promptHash = ModelArenaHelper.hashText(prompt);

        // Security Stage 1: Credential Detection (Fail-Closed Rejection)
        CredentialDetectionResult credResult = credentialDetector.detect(prompt);
        if (credResult.detected()) {
            try {
                recordMandatoryAudit(
                        callerUserId,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "Prompt credential detected: type=" + credResult.credentialType() + " promptHash="
                                + promptHash);
            } catch (Exception ex) {
                log.errorf("ModelArena mandatory error audit failure: %s", LogUtil.rootMessage(ex));
                ModelArenaMetrics.recordComparison(
                        meterRegistry, "audit_error", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
                return error(Response.Status.INTERNAL_SERVER_ERROR, "Mandatory audit logging failed");
            }
            ModelArenaMetrics.recordComparison(
                    meterRegistry,
                    "credential_blocked",
                    modelSlots.size(),
                    System.nanoTime() - overallStartNs,
                    0,
                    0,
                    0.0);
            return error(
                    Response.Status.BAD_REQUEST,
                    "Prompt contains prohibited credentials (" + credResult.credentialType() + ")");
        }

        // Security Stage 2: PII Policy Enforcement
        String promptToUse;
        try {
            promptToUse = evaluatePiiPolicy(prompt, callerUserId, promptHash);
        } catch (Exception ex) {
            log.errorf("ModelArena mandatory error audit failure: %s", LogUtil.rootMessage(ex));
            ModelArenaMetrics.recordComparison(
                    meterRegistry, "audit_error", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Mandatory audit logging failed");
        }
        if (promptToUse == null) {
            ModelArenaMetrics.recordComparison(
                    meterRegistry, "policy_blocked", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
            return error(Response.Status.BAD_REQUEST, "Prompt rejected by PII policy");
        }
        final String benchmarkPrompt = ModelArenaHelper.estimateTokens(promptToUse) > ModelArenaHelper.MAX_INPUT_TOKENS
                ? ModelArenaHelper.truncateToTokenBudget(promptToUse, ModelArenaHelper.MAX_INPUT_TOKENS)
                : promptToUse;

        // Execution Stage: Parallel Provider Invocation on Virtual Threads with Global Deadline
        List<ArenaModelResult> results;
        try {
            results = executeInParallel(modelSlots, benchmarkPrompt, maxOutputTokens, callerUserId);
        } catch (ModelArenaHelper.ArenaAuditException cause) {
            log.errorf("ModelArena mandatory governance failure: %s", LogUtil.rootMessage(cause));
            ModelArenaMetrics.recordComparison(
                    meterRegistry, "audit_error", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Mandatory audit logging failed");
        } catch (ModelArenaHelper.ArenaConfigurationException ace) {
            log.errorf(
                    "ModelArena provider configuration failure: %s (root: %s)",
                    ace.getMessage(), LogUtil.rootMessage(ace));
            ModelArenaMetrics.recordComparison(
                    meterRegistry, "config_error", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Provider configuration failure");
        }

        long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - overallStartNs);
        int inTok = results.stream().mapToInt(ArenaModelResult::inputTokens).sum();
        int outTok = results.stream().mapToInt(ArenaModelResult::outputTokens).sum();
        int totalTokens = inTok + outTok;
        double totalCostUsd =
                results.stream().mapToDouble(ArenaModelResult::costUsd).sum();

        // Security Stage 3: Mandatory Audit Logging (Hashes and Categorical Metadata Only)
        try {
            recordMandatoryAudit(
                    callerUserId,
                    SecurityEventType.SESSION_INSPECTED,
                    "Arena comparison executed: models=" + ModelArenaHelper.categoricalModelSlots(modelSlots)
                            + " promptHash=" + promptHash
                            + " totalTokens=" + totalTokens
                            + " durationMs=" + totalDurationMs
                            + " totalCostUsd=" + totalCostUsd);
        } catch (Exception ex) {
            log.errorf("ModelArena mandatory audit failure: %s", LogUtil.rootMessage(ex));
            ModelArenaMetrics.recordComparison(
                    meterRegistry, "audit_error", modelSlots.size(), System.nanoTime() - overallStartNs, 0, 0, 0.0);
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Mandatory audit logging failed");
        }

        ModelArenaMetrics.recordComparison(
                meterRegistry,
                "success",
                modelSlots.size(),
                System.nanoTime() - overallStartNs,
                inTok,
                outTok,
                totalCostUsd);

        return Response.ok(new ArenaCompareResponse(results, totalDurationMs)).build();
    }

    private List<ArenaModelResult> executeInParallel(
            List<String> modelSlots, String benchmarkPrompt, int maxOutputTokens, String callerUserId) {
        List<ArenaModelResult> results = new ArrayList<>();
        var executor = new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor());
        boolean wasInterrupted = false;
        try {
            List<Future<ArenaModelResult>> futures = new ArrayList<>();
            for (String slot : modelSlots) {
                futures.add(executor.submit(
                        () -> executeModelBenchmark(slot, benchmarkPrompt, maxOutputTokens, callerUserId)));
            }

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(ModelArenaHelper.TIMEOUT_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<ArenaModelResult> f = futures.get(i);
                String slot = modelSlots.get(i);
                long remainingNs = deadlineNs - System.nanoTime();
                try {
                    if (f.isDone()) {
                        results.add(f.get());
                    } else if (remainingNs <= 0) {
                        f.cancel(true);
                        results.add(new ArenaModelResult(
                                slot, "PROVIDER_TIMEOUT", 0, 0, ModelArenaHelper.TIMEOUT_SECONDS * 1000L, 0.0));
                    } else {
                        results.add(f.get(remainingNs, TimeUnit.NANOSECONDS));
                    }
                } catch (TimeoutException _) {
                    f.cancel(true);
                    results.add(new ArenaModelResult(
                            slot, "PROVIDER_TIMEOUT", 0, 0, ModelArenaHelper.TIMEOUT_SECONDS * 1000L, 0.0));
                } catch (InterruptedException _) {
                    f.cancel(true);
                    wasInterrupted = true;
                    results.add(new ArenaModelResult(slot, "PROVIDER_INTERRUPTED", 0, 0, 0L, 0.0));
                } catch (ExecutionException e) {
                    f.cancel(true);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ModelArenaHelper.ArenaAuditException aae) {
                        throw aae;
                    }
                    if (cause instanceof ModelArenaHelper.ArenaConfigurationException ace) {
                        throw ace;
                    }
                    log.warnf(
                            "ModelArena provider execution error for slot %s: errorClass=%s",
                            slot, cause.getClass().getSimpleName());
                    results.add(new ArenaModelResult(slot, "PROVIDER_EXECUTION_ERROR", 0, 0, 0L, 0.0));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String evaluatePiiPolicy(String prompt, String callerUserId, String promptHash) {
        if (piiInputGuard.isResolvable()) {
            String sessionId =
                    WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : "arena-session";
            GuardDecision decision = piiInputGuard.get().validate(sessionId, callerUserId, prompt, prompt);
            if (decision != null && decision.isBlocked()) {
                recordMandatoryAudit(
                        callerUserId,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "Prompt blocked by PII policy: promptHash=" + promptHash);
                return null;
            }
            if (decision instanceof GuardDecision.Sanitize sanitize) {
                return sanitize.sanitizedInput();
            }
        }
        return prompt;
    }

    private ArenaModelResult executeModelBenchmark(
            String slot, String prompt, int maxOutputTokens, String callerUserId) {
        long startNs = System.nanoTime();
        int tokenCap = Math.min(maxOutputTokens, ModelArenaHelper.MAX_OUTPUT_TOKENS);
        try {
            return ScopedValue.where(WorkspaceContext.USER_ID, callerUserId).call(() -> {
                String cleanProv = ModelArenaHelper.validateAndExtractProvider(slot);
                ResolvedProvider resolved = resolveProviderAndModel(slot);
                if (resolved == null || resolved.model() == null) {
                    return new ArenaModelResult(slot, "PROVIDER_UNKNOWN_MODEL", 0, 0, 0L, 0.0);
                }

                validateProviderDispatch(cleanProv, resolved.targetUrl(), callerUserId);

                ChatModel model = resolved.model();
                if (modelRouteSelector != null && modelRouteSelector.isResolvable()) {
                    MultimodalCapability cap = MultimodalCapabilities.resolveCapability(model);
                    ModelRoute candidate = new ModelRoute(slot, slot, null, null, model, cap, List.of(), 0L);
                    Optional<ModelRoute> selected =
                            modelRouteSelector.get().select("arena", TurnRequirements.textOnly(), List.of(candidate));
                    if (selected.isEmpty()) {
                        return new ArenaModelResult(slot, "PROVIDER_UNSUPPORTED_MODALITY", 0, 0, 0L, 0.0);
                    }
                    model = selected.get().model();
                } else {
                    return new ArenaModelResult(slot, "PROVIDER_ROUTE_UNAVAILABLE", 0, 0, 0L, 0.0);
                }

                ChatRequest chatReq = ChatRequest.builder()
                        .messages(UserMessage.from(prompt))
                        .parameters(ChatRequestParameters.builder()
                                .maxOutputTokens(tokenCap)
                                .build())
                        .build();

                ChatResponse response = model.chat(chatReq);
                String output;
                int outTokens;
                int inTokens = ModelArenaHelper.estimateTokens(prompt);
                if (response != null && response.aiMessage() != null) {
                    output = response.aiMessage().text() != null
                            ? response.aiMessage().text()
                            : "";
                    outTokens = (response.tokenUsage() != null
                                    && response.tokenUsage().outputTokenCount() != null)
                            ? response.tokenUsage().outputTokenCount()
                            : ModelArenaHelper.estimateTokens(output);
                    if (response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null) {
                        inTokens = response.tokenUsage().inputTokenCount();
                    }
                } else {
                    output = "";
                    outTokens = 0;
                }

                if (outTokens > tokenCap || ModelArenaHelper.estimateTokens(output) > tokenCap) {
                    output = ModelArenaHelper.truncateToTokenBudget(output, tokenCap);
                    outTokens = Math.min(ModelArenaHelper.estimateTokens(output), tokenCap);
                }

                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                if (traceRedactor == null) {
                    return new ArenaModelResult(slot, "ARENA_REDACTION_UNAVAILABLE", 0, 0, durationMs, 0.0);
                }
                int maxRedactionBytes = tokenCap * 4;
                String safeOutput = traceRedactor.redactText(output, maxRedactionBytes);
                if (safeOutput == null) {
                    safeOutput = "";
                }

                double cost = ModelArenaHelper.estimateCostUsd(inTokens, outTokens);
                return new ArenaModelResult(slot, safeOutput, inTokens, outTokens, durationMs, cost);
            });
        } catch (Exception e) {
            if (e instanceof ModelArenaHelper.ArenaAuditException
                    || e instanceof ModelArenaHelper.ArenaConfigurationException) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String categoricalCode = ModelArenaHelper.categorizeError(e);
            log.warnf(
                    "ModelArena: provider execution failed for slot %s: code=%s errorClass=%s",
                    slot, categoricalCode, e.getClass().getSimpleName());
            return new ArenaModelResult(slot, categoricalCode, 0, 0, durationMs, 0.0);
        }
    }

    private record ResolvedProvider(ChatModel model, String targetUrl) {}

    private ResolvedProvider resolveProviderAndModel(String slot) {
        if (slot == null || slot.isBlank()) {
            return null;
        }

        if ("default".equalsIgnoreCase(slot)) {
            ChatModel model = chatModelProducer != null ? chatModelProducer.chatModel() : null;
            String cleanDefault = (defaultProviderName != null && !defaultProviderName.isBlank())
                    ? defaultProviderName.trim().toLowerCase(Locale.ROOT)
                    : "groq";
            return new ResolvedProvider(model, ModelArenaHelper.resolveBaseUrl(cleanDefault, providers));
        }

        String providerName = slot;
        String customModel = null;
        if (slot.contains(":")) {
            int idx = slot.indexOf(':');
            providerName = slot.substring(0, idx);
            customModel = slot.substring(idx + 1);
        }

        String cleanProvider = providerName.trim().toLowerCase(Locale.ROOT);
        ChatModel model = null;
        if (providers != null) {
            for (AgentChatModelProvider p : providers) {
                if (p != null && p.providerName().equalsIgnoreCase(cleanProvider)) {
                    try {
                        model = (customModel != null && !customModel.isBlank()) ? p.create(customModel) : p.create();
                    } catch (Exception e) {
                        throw new ModelArenaHelper.ArenaConfigurationException(
                                "Failed to construct model provider '" + cleanProvider + "': " + e.getMessage(), e);
                    }
                    break;
                }
            }
        }
        return new ResolvedProvider(model, ModelArenaHelper.resolveBaseUrl(cleanProvider, providers));
    }

    private void validateProviderDispatch(String cleanProv, String targetUrl, String callerUserId) {
        if (governedTransport == null || !governedTransport.isResolvable()) {
            throw new ModelArenaHelper.ArenaConfigurationException("Governed provider transport is unavailable");
        }
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new ModelArenaHelper.ArenaConfigurationException(
                    "Configured provider endpoint URL is missing or blank for provider: " + cleanProv);
        }
        governedTransport.get().validateEgress(targetUrl, OutboundPurpose.PROVIDER_API_CALL, callerUserId);
        recordGovernedProviderAudit(callerUserId, cleanProv);
    }

    private void recordGovernedProviderAudit(String callerUserId, String provider) {
        recordStreamAudit(
                "arena-egress",
                callerUserId,
                SecurityEventType.PROVIDER_EGRESS_VALIDATED,
                "governed_egress_enforced purpose=" + OutboundPurpose.PROVIDER_API_CALL + " provider=" + provider);
    }

    private void recordMandatoryAudit(String callerUserId, SecurityEventType type, String message) {
        recordStreamAudit("arena", callerUserId, type, message);
    }

    private void recordStreamAudit(String stream, String callerUserId, SecurityEventType type, String message) {
        if (auditService == null) {
            throw new ModelArenaHelper.ArenaAuditException("AuditService is unavailable for mandatory audit logging");
        }
        try {
            auditService.recordMandatory(stream, callerUserId, "operator", type, message);
        } catch (Exception e) {
            throw new ModelArenaHelper.ArenaAuditException("Mandatory audit logging failed: " + e.getMessage(), e);
        }
    }

    private static Response error(Response.Status status, String msg) {
        return Response.status(status).entity(new AmshaErrorResponse(msg)).build();
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
}
