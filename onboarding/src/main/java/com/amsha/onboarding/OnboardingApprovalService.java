package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.llm.api.ConnectionValidationRequest;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.onboarding.dto.ApprovalResolutionRequestDto;
import com.amsha.onboarding.dto.ApprovalResolutionResponseDto;
import com.amsha.onboarding.dto.ConnectionValidationRequestDto;
import com.amsha.onboarding.dto.ConnectionValidationResponseDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles HITL approval workflows and sensitive network validation gating for onboarding drafts.
 *
 * <p>Invariant (§3, §5):
 * Connection validation has explicit tiers. format valid is free/local; connection probe is
 * optional and HITL-gated. During approval the draft enters WAITING_APPROVAL state. An outbound
 * probe can never report verified without passing operator approval and governed transport policy.
 */
@ApplicationScoped
public class OnboardingApprovalService {

    private static final Logger log = Logger.getLogger(OnboardingApprovalService.class);

    @Inject
    OnboardingDraftStorePort draftStore;

    @Inject
    OnboardingSelectionScrubber scrubber;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Instance<LlmProviderOnboardingAdapter> providerAdapterInstance;

    @Inject
    Instance<GovernedTransportService> governedTransportInstance;

    @Inject
    AuditService auditService;

    @Inject
    SecretVault vault;

    /**
     * Evaluates tiered connection validation. Returns immediate format validation or gates an
     * outbound probe behind an approval request.
     */
    public ConnectionValidationResponseDto validateConnection(
            OnboardingDraftDto draft, ConnectionValidationRequestDto request) {
        if (scrubber != null) {
            String scrubberErrors = scrubber.validateConnections(draft.selectedNonSecretIds());
            if (scrubberErrors != null && !scrubberErrors.isBlank()) {
                return ConnectionValidationResponseDto.formatError(scrubberErrors);
            }
        }
        int connIndex = request != null ? request.connectionIndex() : 0;
        List<Map<String, Object>> connections = extractConnections(draft.selectedNonSecretIds());
        if (connections.isEmpty() || connIndex < 0 || connIndex >= connections.size()) {
            return ConnectionValidationResponseDto.formatError("Invalid connectionIndex: " + connIndex);
        }

        Map<String, Object> targetConnection = connections.get(connIndex);
        String provider = String.valueOf(targetConnection.getOrDefault("provider", ""));
        String model = String.valueOf(targetConnection.getOrDefault("modelId", ""));
        String baseUrl = String.valueOf(targetConnection.getOrDefault(
                "customBaseUrl",
                targetConnection.getOrDefault("baseUrl", targetConnection.getOrDefault("targetUrl", ""))));
        boolean hasKey = targetConnection.containsKey("apiKeyVaultRef")
                || (targetConnection.get("apiKey") != null
                        && !String.valueOf(targetConnection.get("apiKey")).isBlank());

        List<String> allowedHosts = List.of();
        Object egress = targetConnection.get("allowedEgressHosts");
        if (egress instanceof List<?> list) {
            allowedHosts = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }

        if ("generic-openai".equalsIgnoreCase(provider) && baseUrl.isBlank()) {
            return ConnectionValidationResponseDto.formatError(
                    "Provider generic-openai requires a targetUrl or baseUrl");
        }

        if (providerAdapterInstance != null && providerAdapterInstance.isResolvable()) {
            ProviderValidationResult result = providerAdapterInstance
                    .get()
                    .validate(new ConnectionValidationRequest(provider, model, baseUrl, hasKey, allowedHosts));
            if (!result.eligible()) {
                return ConnectionValidationResponseDto.formatError(String.join("; ", result.errors()));
            }
        }

        if (request == null || !request.probeNetwork()) {
            return ConnectionValidationResponseDto.formatValid();
        }

        // Outbound probe requested - HITL approval is mandatory
        if (!request.requestApproval()) {
            return ConnectionValidationResponseDto.formatError(
                    "Outbound network probe requires operator approval (requestApproval must be true)");
        }

        String approvalId = "approval_conn_" + connIndex + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        String summary = "Outbound connection validation probe for draft " + draft.draftId() + " [connIndex="
                + connIndex + " provider=" + provider + "]";

        List<String> pending = parseApprovals(draft.pendingApprovalIds());
        if (!pending.contains(approvalId)) {
            pending.add(approvalId);
        }

        OnboardingDraftDto waiting = new OnboardingDraftDto(
                draft.draftId(),
                draft.ownerId(),
                draft.purpose(),
                draft.planFingerprint(),
                draft.currentStepId(),
                draft.completedStepIds(),
                draft.selectedNonSecretIds(),
                draft.safeFieldErrors(),
                joinApprovals(pending),
                OnboardingDraftState.WAITING_APPROVAL,
                draft.revision(),
                draft.expiresAt(),
                draft.createdAt(),
                Instant.now());

        draftStore.update(waiting, draft.revision());
        log.infof("[ONBOARDING] Draft %s entered WAITING_APPROVAL (approvalId=%s)", draft.draftId(), approvalId);
        return ConnectionValidationResponseDto.waitingApproval(approvalId, summary);
    }

    /**
     * Resolves a pending approval for a draft with default operator identity.
     */
    public Optional<ApprovalResolutionResponseDto> resolveApproval(
            OnboardingDraftDto draft, String approvalId, ApprovalResolutionRequestDto resolution) {
        return resolveApproval(draft, approvalId, resolution, "operator");
    }

    /**
     * Resolves a pending approval for a draft with explicit operator identity.
     */
    public Optional<ApprovalResolutionResponseDto> resolveApproval(
            OnboardingDraftDto draft,
            String approvalId,
            ApprovalResolutionRequestDto resolution,
            String approverUserId) {
        if (auditService == null) {
            log.errorf(
                    "[ONBOARDING] Audit service is unavailable; rejecting approval resolution for draft %s",
                    draft.draftId());
            throw new IllegalStateException("Mandatory audit service is unavailable");
        }

        List<String> pending = parseApprovals(draft.pendingApprovalIds());
        if (!pending.contains(approvalId)) {
            return Optional.empty();
        }

        List<String> remaining = new ArrayList<>(pending);
        remaining.remove(approvalId);
        OnboardingDraftState nextState =
                remaining.isEmpty() ? OnboardingDraftState.DRAFT : OnboardingDraftState.WAITING_APPROVAL;

        String safeErrors = draft.safeFieldErrors();
        boolean verified = false;
        String message = "";

        if (resolution.approved()) {
            int connIndex = parseConnIndexFromApprovalId(approvalId);
            List<Map<String, Object>> connections = extractConnections(draft.selectedNonSecretIds());
            if (connIndex < 0 || connIndex >= connections.size()) {
                verified = false;
                message = "Invalid connection index in approval: " + connIndex;
                safeErrors = message;
            } else {
                Map<String, Object> conn = connections.get(connIndex);
                String provider = String.valueOf(conn.getOrDefault("provider", ""));
                String model = String.valueOf(conn.getOrDefault("modelId", ""));
                String baseUrl = String.valueOf(conn.getOrDefault(
                        "customBaseUrl", conn.getOrDefault("baseUrl", conn.getOrDefault("targetUrl", ""))));
                boolean hasKey = conn.containsKey("apiKeyVaultRef")
                        || (conn.get("apiKey") != null
                                && !String.valueOf(conn.get("apiKey")).isBlank());

                List<String> allowedHosts = List.of();
                Object egress = conn.get("allowedEgressHosts");
                if (egress instanceof List<?> list) {
                    allowedHosts = list.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .toList();
                }

                boolean adapterValid = true;
                if (providerAdapterInstance != null && providerAdapterInstance.isResolvable()) {
                    ProviderValidationResult valResult = providerAdapterInstance
                            .get()
                            .validate(new ConnectionValidationRequest(provider, model, baseUrl, hasKey, allowedHosts));
                    if (!valResult.eligible()) {
                        adapterValid = false;
                        verified = false;
                        message = "Connection probe rejected by adapter: " + String.join("; ", valResult.errors());
                        safeErrors = message;
                    }
                }

                if (adapterValid) {
                    ProbeExecutionResult probeResult = executeGovernedProbe(
                            provider, baseUrl, conn, draft.ownerId(), approverUserId, approvalId, draft.draftId());
                    verified = probeResult.verified();
                    message = probeResult.message();
                    if (probeResult.safeErrors() != null) {
                        safeErrors = probeResult.safeErrors();
                    }
                }

                auditService.recordMandatory(
                        "onboarding-approval",
                        approverUserId,
                        "operator",
                        SecurityEventType.APPROVAL_GRANTED,
                        "action=approve_connection_probe draftId=" + draft.draftId() + " approvalId=" + approvalId
                                + " provider=" + provider + " verified=" + verified);
            }
        } else {
            safeErrors = "Connection probe denied by operator"
                    + (resolution.reason() != null && !resolution.reason().isBlank() ? ": " + resolution.reason() : "");
            message = "Connection probe denied by operator";
            log.infof(
                    "[ONBOARDING] Approval %s denied for draft %s by %s: %s",
                    approvalId, draft.draftId(), approverUserId, resolution.reason());
            auditService.recordMandatory(
                    "onboarding-approval",
                    approverUserId,
                    "operator",
                    SecurityEventType.APPROVAL_DENIED,
                    "action=deny_connection_probe draftId=" + draft.draftId() + " approvalId=" + approvalId + " reason="
                            + resolution.reason());
        }

        OnboardingDraftDto updated = new OnboardingDraftDto(
                draft.draftId(),
                draft.ownerId(),
                draft.purpose(),
                draft.planFingerprint(),
                draft.currentStepId(),
                draft.completedStepIds(),
                draft.selectedNonSecretIds(),
                safeErrors,
                joinApprovals(remaining),
                nextState,
                draft.revision(),
                draft.expiresAt(),
                draft.createdAt(),
                Instant.now());

        draftStore.update(updated, draft.revision());
        return Optional.of(new ApprovalResolutionResponseDto(
                approvalId, resolution.approved() ? "APPROVED" : "DENIED", verified, message));
    }

    private record ProbeExecutionResult(boolean verified, String message, String safeErrors) {}

    private ProbeExecutionResult executeGovernedProbe(
            String provider,
            String baseUrl,
            Map<String, Object> conn,
            String ownerId,
            String approverUserId,
            String approvalId,
            String draftId) {
        String probeUrl = resolveProbeUrl(provider, baseUrl);
        if (probeUrl.isBlank()) {
            String err = "Unable to resolve valid probe URL for provider: " + provider;
            return new ProbeExecutionResult(false, err, err);
        }
        if (governedTransportInstance == null || !governedTransportInstance.isResolvable()) {
            String err = "Governed transport service is unavailable";
            return new ProbeExecutionResult(false, err, err);
        }
        GovernedTransportService transport = governedTransportInstance.get();
        try {
            transport.validateEgress(probeUrl, OutboundPurpose.CATALOG_PROBE, approverUserId, true);

            String apiKey = null;
            if (conn.get("apiKey") != null
                    && !String.valueOf(conn.get("apiKey")).isBlank()) {
                apiKey = String.valueOf(conn.get("apiKey"));
            } else if (conn.get("apiKeyVaultRef") != null && vault != null) {
                String ref = String.valueOf(conn.get("apiKeyVaultRef"));
                Optional<VaultKey> vkOpt = vault.get(ref, ownerId);
                if (vkOpt.isPresent()) {
                    try (VaultKey vk = vkOpt.get()) {
                        apiKey = new String(vk.get(), StandardCharsets.UTF_8);
                    }
                }
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");

            if (apiKey != null && !apiKey.isBlank()) {
                if ("anthropic".equalsIgnoreCase(provider) || "claude-code".equalsIgnoreCase(provider)) {
                    reqBuilder.header("x-api-key", apiKey);
                    reqBuilder.header("anthropic-version", "2023-06-01");
                } else {
                    reqBuilder.header("Authorization", "Bearer " + apiKey);
                }
            }

            HttpRequest httpRequest = reqBuilder.GET().build();
            HttpResponse<String> response = transport.send(
                    probeUrl,
                    OutboundPurpose.CATALOG_PROBE,
                    null,
                    approverUserId,
                    true,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response != null && response.statusCode() >= 200 && response.statusCode() < 300) {
                log.infof(
                        "[ONBOARDING] Approval %s approved and verified for draft %s by %s (probe HTTP %d)",
                        approvalId, draftId, approverUserId, response.statusCode());
                return new ProbeExecutionResult(
                        true, "Connection probe approved and verified via governed transport", null);
            }
            int statusCode = response != null ? response.statusCode() : 500;
            String err = "Connection probe failed: provider returned HTTP " + statusCode;
            log.warnf(
                    "[ONBOARDING] Approval %s probe failed with HTTP %d for draft %s", approvalId, statusCode, draftId);
            return new ProbeExecutionResult(false, err, err);
        } catch (Exception e) {
            String err = "Connection probe failed: " + LogUtil.rootMessage(e);
            log.warnf(
                    "[ONBOARDING] Approval %s probe encountered error for draft %s: %s",
                    approvalId, draftId, LogUtil.rootMessage(e));
            return new ProbeExecutionResult(false, err, err);
        }
    }

    private String resolveProbeUrl(String provider, String baseUrl) {
        String effectiveBase = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl.trim()
                : ModelDefaults.defaultBaseUrlForProvider(provider);
        if (effectiveBase.isBlank()) {
            return "";
        }
        String normalized =
                effectiveBase.endsWith("/") ? effectiveBase.substring(0, effectiveBase.length() - 1) : effectiveBase;
        if ("ollama".equalsIgnoreCase(provider)) {
            return normalized.endsWith("/v1") ? normalized + "/models" : normalized + "/api/tags";
        }
        if ("anthropic".equalsIgnoreCase(provider) || "claude-code".equalsIgnoreCase(provider)) {
            return normalized.endsWith("/v1") ? normalized + "/models" : normalized + "/v1/models";
        }
        if (normalized.endsWith("/models")) {
            return normalized;
        }
        return normalized + "/models";
    }

    private int parseConnIndexFromApprovalId(String approvalId) {
        if (approvalId != null && approvalId.startsWith("approval_conn_")) {
            String[] parts = approvalId.split("_");
            if (parts.length >= 4) {
                try {
                    return Integer.parseInt(parts[2]);
                } catch (NumberFormatException _) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractConnections(List<String> selections) {
        if (selections == null || selections.isEmpty()) return List.of();
        for (String sel : selections) {
            try {
                Map<String, Object> map = objectMapper.readValue(sel, Map.class);
                if (map.get("connections") instanceof List<?> list) {
                    List<Map<String, Object>> conns = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            conns.add((Map<String, Object>) m);
                        }
                    }
                    return conns;
                }
            } catch (Exception _) {
                // skip non-json
            }
        }
        return List.of();
    }

    private List<String> parseApprovals(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        for (String id : raw.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private String joinApprovals(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return String.join(",", list);
    }
}
