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
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;

import org.jboss.logging.Logger;

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
    private static final int MAX_INPUT_TOKENS = 2048;
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private static final int DEFAULT_OUTPUT_TOKENS = 512;
    private static final int MAX_MODELS = 3;
    private static final long TIMEOUT_SECONDS = 30L;
    private static final int MAX_SLOT_LENGTH = ModelArenaHelper.MAX_SLOT_LENGTH;

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

    @Context
    SecurityContext securityContext;

    @POST
    @Path("/compare")
    public Response compareModels(ArenaCompareRequest request) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Internal operator role required for Model Arena"))
                    .build();
        }

        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Prompt is required"))
                    .build();
        }

        String prompt = request.prompt(); // Faithful prompt invocation: preserve leading/trailing whitespace
        int estimatedInTokens = ModelArenaHelper.estimateTokens(prompt);
        if (estimatedInTokens > MAX_INPUT_TOKENS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Prompt exceeds maximum input budget of " + MAX_INPUT_TOKENS
                            + " tokens (estimated: " + estimatedInTokens + ")"))
                    .build();
        }

        List<String> modelSlots = request.models();
        if (modelSlots == null || modelSlots.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("At least one model slot must be specified"))
                    .build();
        }
        if (modelSlots.size() > MAX_MODELS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Maximum of " + MAX_MODELS + " models can be compared concurrently"))
                    .build();
        }

        for (String slot : modelSlots) {
            try {
                ModelArenaHelper.validateAndExtractProvider(slot);
            } catch (IllegalArgumentException _) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new AmshaErrorResponse("Model slot is not allowlisted"))
                        .build();
            }
        }

        int maxOutputTokens = request.maxTokens() != null
                ? Math.min(Math.max(1, request.maxTokens()), MAX_OUTPUT_TOKENS)
                : DEFAULT_OUTPUT_TOKENS;

        String callerUserId = resolveUserId();
        String promptHash = ModelArenaHelper.hashText(prompt);

        // Security Stage 1: Credential Detection (Fail-Closed Rejection)
        CredentialDetectionResult credResult = credentialDetector.detect(prompt);
        if (credResult.detected()) {
            recordErrorAudit(
                    callerUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "Prompt credential detected: type=" + credResult.credentialType() + " promptHash=" + promptHash);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(
                            "Prompt contains prohibited credentials (" + credResult.credentialType() + ")"))
                    .build();
        }

        // Security Stage 2: PII Policy Enforcement
        String promptToUse = evaluatePiiPolicy(prompt, callerUserId, promptHash);
        if (promptToUse == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Prompt rejected by PII policy"))
                    .build();
        }

        // Execution Stage: Parallel Provider Invocation on Virtual Threads with Global Deadline
        long overallStartNs = System.nanoTime();
        List<ArenaModelResult> results = new ArrayList<>();
        var executor = new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor());
        try {
            List<Future<ArenaModelResult>> futures = new ArrayList<>();
            for (String slot : modelSlots) {
                futures.add(
                        executor.submit(() -> executeModelBenchmark(slot, promptToUse, maxOutputTokens, callerUserId)));
            }

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<ArenaModelResult> f = futures.get(i);
                String slot = modelSlots.get(i);
                long remainingNs = deadlineNs - System.nanoTime();
                try {
                    if (f.isDone()) {
                        results.add(f.get());
                    } else if (remainingNs <= 0) {
                        f.cancel(true);
                        results.add(new ArenaModelResult(slot, "PROVIDER_TIMEOUT", 0, 0, TIMEOUT_SECONDS * 1000L, 0.0));
                    } else {
                        results.add(f.get(remainingNs, TimeUnit.NANOSECONDS));
                    }
                } catch (TimeoutException _) {
                    f.cancel(true);
                    results.add(new ArenaModelResult(slot, "PROVIDER_TIMEOUT", 0, 0, TIMEOUT_SECONDS * 1000L, 0.0));
                } catch (InterruptedException _) {
                    f.cancel(true);
                    Thread.currentThread().interrupt();
                    results.add(new ArenaModelResult(slot, "PROVIDER_INTERRUPTED", 0, 0, 0L, 0.0));
                } catch (ExecutionException e) {
                    f.cancel(true);
                    log.warnf(
                            "ModelArena provider execution error for slot %s: errorClass=%s",
                            slot, e.getClass().getSimpleName());
                    results.add(new ArenaModelResult(slot, "PROVIDER_EXECUTION_ERROR", 0, 0, 0L, 0.0));
                }
            }

            long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - overallStartNs);
            int totalTokens = results.stream()
                    .mapToInt(r -> r.inputTokens() + r.outputTokens())
                    .sum();
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
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new AmshaErrorResponse("Mandatory audit logging failed"))
                        .build();
            }

            return Response.ok(new ArenaCompareResponse(results, totalDurationMs))
                    .build();
        } finally {
            executor.shutdownNow();
        }
    }

    private String evaluatePiiPolicy(String prompt, String callerUserId, String promptHash) {
        if (piiInputGuard.isResolvable()) {
            String sessionId =
                    WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : "arena-session";
            GuardDecision decision = piiInputGuard.get().validate(sessionId, callerUserId, prompt, prompt);
            if (decision.isBlocked()) {
                recordErrorAudit(
                        callerUserId,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "Prompt blocked by PII policy: promptHash=" + promptHash);
                return null;
            }
            if (decision.isSanitize()) {
                return ((GuardDecision.Sanitize) decision).sanitizedInput();
            }
        }
        return prompt;
    }

    private ArenaModelResult executeModelBenchmark(
            String slot, String prompt, int maxOutputTokens, String callerUserId) {
        long startNs = System.nanoTime();
        try {
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
                            .maxOutputTokens(maxOutputTokens)
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
                outTokens =
                        (response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null)
                                ? response.tokenUsage().outputTokenCount()
                                : ModelArenaHelper.estimateTokens(output);
                if (response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null) {
                    inTokens = response.tokenUsage().inputTokenCount();
                }
                if (outTokens > maxOutputTokens || ModelArenaHelper.estimateTokens(output) > maxOutputTokens) {
                    output = ModelArenaHelper.truncateToTokenBudget(output, maxOutputTokens);
                    outTokens = Math.min(ModelArenaHelper.estimateTokens(output), maxOutputTokens);
                }
            } else {
                output = "";
                outTokens = 0;
            }

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            if (traceRedactor == null) {
                return new ArenaModelResult(slot, "ARENA_REDACTION_UNAVAILABLE", 0, 0, durationMs, 0.0);
            }
            String safeOutput = traceRedactor.redactText(output, 2048);
            if (safeOutput == null) {
                safeOutput = "";
            }

            // Estimated USD – placeholder rates based on common blended token pricing
            double cost = (inTokens * 0.0000015) + (outTokens * 0.000002);

            return new ArenaModelResult(slot, safeOutput, inTokens, outTokens, durationMs, cost);
        } catch (Exception e) {
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
            return new ResolvedProvider(model, ModelDefaults.DEFAULT_OLLAMA_BASE_URL);
        }

        String providerName = slot;
        String customModel = null;
        if (slot.contains(":")) {
            int idx = slot.indexOf(':');
            providerName = slot.substring(0, idx);
            customModel = slot.substring(idx + 1);
        }

        String cleanProvider = providerName.trim().toLowerCase(Locale.ROOT);
        if (providers != null) {
            for (AgentChatModelProvider p : providers) {
                if (p != null && p.providerName().equalsIgnoreCase(cleanProvider)) {
                    ChatModel model =
                            (customModel != null && !customModel.isBlank()) ? p.create(customModel) : p.create();
                    String base = p.baseUrl();
                    String targetUrl = (base != null && !base.isBlank() && !"unknown".equalsIgnoreCase(base.trim()))
                            ? base.trim()
                            : ModelArenaHelper.PROVIDER_BASE_URLS.get(cleanProvider);
                    return new ResolvedProvider(model, targetUrl);
                }
            }
        }

        String fallbackUrl = ModelArenaHelper.PROVIDER_BASE_URLS.get(cleanProvider);
        return new ResolvedProvider(null, fallbackUrl);
    }

    private void validateProviderDispatch(String cleanProv, String targetUrl, String callerUserId) {
        if (governedTransport == null || !governedTransport.isResolvable()) {
            throw new IllegalStateException("Governed provider transport is unavailable");
        }
        if (targetUrl != null && !targetUrl.isBlank()) {
            governedTransport.get().validateEgress(targetUrl, OutboundPurpose.PROVIDER_API_CALL, callerUserId);
        }
        recordGovernedProviderAudit(callerUserId, cleanProv);
    }

    private void recordGovernedProviderAudit(String callerUserId, String provider) {
        if (auditService != null) {
            auditService.recordMandatory(
                    "arena-egress",
                    callerUserId,
                    "operator",
                    SecurityEventType.TOOL_INVOKED,
                    "governed_egress_enforced purpose=" + OutboundPurpose.PROVIDER_API_CALL + " provider=" + provider);
        }
    }

    private void recordMandatoryAudit(String callerUserId, SecurityEventType type, String message) {
        if (auditService != null) {
            auditService.recordMandatory("arena", callerUserId, "operator", type, message);
        }
    }

    private void recordErrorAudit(String callerUserId, SecurityEventType type, String message) {
        if (auditService != null) {
            try {
                auditService.recordMandatory("arena", callerUserId, "operator", type, message);
            } catch (Exception e) {
                log.warnf(e, "ModelArena: error audit logging failed; message=%s", message);
            }
        }
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
