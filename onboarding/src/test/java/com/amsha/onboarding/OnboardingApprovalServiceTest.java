package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.onboarding.dto.ApprovalResolutionRequestDto;
import com.amsha.onboarding.dto.ApprovalResolutionResponseDto;
import com.amsha.onboarding.dto.ConnectionValidationRequestDto;
import com.amsha.onboarding.dto.ConnectionValidationResponseDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OnboardingApprovalServiceTest {

    private OnboardingDraftStorePort draftStore;
    private OnboardingSelectionScrubber scrubber;
    private ObjectMapper objectMapper;
    private Instance<LlmProviderOnboardingAdapter> providerAdapterInstance;
    private Instance<GovernedTransportService> governedTransportInstance;
    private LlmProviderOnboardingAdapter providerAdapter;
    private GovernedTransportService governedTransport;
    private AuditService auditService;
    private SecretVault vault;
    private OnboardingApprovalService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        draftStore = mock(OnboardingDraftStorePort.class);
        scrubber = mock(OnboardingSelectionScrubber.class);
        objectMapper = new ObjectMapper();
        providerAdapterInstance = mock(Instance.class);
        governedTransportInstance = mock(Instance.class);
        providerAdapter = mock(LlmProviderOnboardingAdapter.class);
        governedTransport = mock(GovernedTransportService.class);
        auditService = mock(AuditService.class);
        vault = mock(SecretVault.class);

        when(providerAdapterInstance.isResolvable()).thenReturn(true);
        when(providerAdapterInstance.get()).thenReturn(providerAdapter);
        when(governedTransportInstance.isResolvable()).thenReturn(true);
        when(governedTransportInstance.get()).thenReturn(governedTransport);
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());

        HttpResponse<?> okResponse = mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        doReturn(okResponse).when(governedTransport).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        service = new OnboardingApprovalService();
        service.draftStore = draftStore;
        service.scrubber = scrubber;
        service.objectMapper = objectMapper;
        service.providerAdapterInstance = providerAdapterInstance;
        service.governedTransportInstance = governedTransportInstance;
        service.auditService = auditService;
        service.vault = vault;
    }

    private OnboardingDraftDto createDraft(OnboardingDraftState state, String pendingApprovals) {
        Instant now = Instant.now();
        return new OnboardingDraftDto(
                "draft_123",
                "owner_1",
                OnboardingDraftPurpose.FIRST_RUN,
                "welcome->connections->review",
                "connections",
                List.of("welcome"),
                List.of("{\"connections\":[{\"provider\":\"groq\",\"modelId\":\"llama-3.3-70b-versatile\"}]}"),
                null,
                pendingApprovals,
                state,
                1,
                now.plusSeconds(3600),
                now,
                now);
    }

    @Test
    void formatValidationReturnsValidWhenScrubberPasses() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.DRAFT, null);
        when(scrubber.validateConnections(any())).thenReturn(null);

        ConnectionValidationResponseDto res =
                service.validateConnection(draft, new ConnectionValidationRequestDto(0, false, false));

        assertEquals("FORMAT_VALID", res.tier());
        assertEquals("OK", res.status());
        assertTrue(res.valid());
    }

    @Test
    void formatValidationReturnsErrorWhenScrubberFails() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.DRAFT, null);
        when(scrubber.validateConnections(any())).thenReturn("generic-openai requires targetUrl");

        ConnectionValidationResponseDto res =
                service.validateConnection(draft, new ConnectionValidationRequestDto(0, false, false));

        assertEquals("FORMAT_VALID", res.tier());
        assertEquals("ERROR", res.status());
        assertFalse(res.valid());
        assertEquals("generic-openai requires targetUrl", res.safeErrors());
    }

    @Test
    void networkProbeWithApprovalRequirementTransitionsToWaitingApproval() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.DRAFT, null);
        when(scrubber.validateConnections(any())).thenReturn(null);

        ConnectionValidationResponseDto res =
                service.validateConnection(draft, new ConnectionValidationRequestDto(0, true, true));

        assertEquals("CONNECTION_PROBE", res.tier());
        assertEquals("WAITING_APPROVAL", res.status());
        assertNotNull(res.approvalId());
        assertTrue(res.approvalId().startsWith("approval_conn_"));
        verify(draftStore).update(any(OnboardingDraftDto.class), eq(1));
    }

    @Test
    void resolveApprovalApprovesAndReturnsDraftToDraftState() {
        String approvalId = "approval_conn_abc";
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, approvalId);

        Optional<ApprovalResolutionResponseDto> res = service.resolveApproval(
                draft, approvalId, new ApprovalResolutionRequestDto(true, "verified by operator"));

        assertTrue(res.isPresent());
        assertEquals("APPROVED", res.get().status());
        assertTrue(res.get().verified());
        verify(draftStore).update(any(OnboardingDraftDto.class), eq(1));
    }

    @Test
    void resolveApprovalDeniesAndRecordsOperatorDenial() {
        String approvalId = "approval_conn_xyz";
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, approvalId);

        Optional<ApprovalResolutionResponseDto> res = service.resolveApproval(
                draft, approvalId, new ApprovalResolutionRequestDto(false, "untrusted provider endpoint"));

        assertTrue(res.isPresent());
        assertEquals("DENIED", res.get().status());
        assertFalse(res.get().verified());
        verify(draftStore).update(any(OnboardingDraftDto.class), eq(1));
    }

    @Test
    void resolveApprovalReturnsEmptyOnUnknownApprovalId() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, "approval_1");

        Optional<ApprovalResolutionResponseDto> res =
                service.resolveApproval(draft, "unknown_approval", new ApprovalResolutionRequestDto(true, "ok"));

        assertTrue(res.isEmpty());
    }

    @Test
    void validateConnectionRejectsInvalidConnectionIndex() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.DRAFT, null);

        ConnectionValidationResponseDto resNegative =
                service.validateConnection(draft, new ConnectionValidationRequestDto(-1, false, false));
        assertFalse(resNegative.valid());
        assertTrue(resNegative.safeErrors().contains("Invalid connectionIndex: -1"));

        ConnectionValidationResponseDto resOutOfRange =
                service.validateConnection(draft, new ConnectionValidationRequestDto(5, false, false));
        assertFalse(resOutOfRange.valid());
        assertTrue(resOutOfRange.safeErrors().contains("Invalid connectionIndex: 5"));
    }

    @Test
    void networkProbeWithoutApprovalRequestFailsClosed() {
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.DRAFT, null);

        ConnectionValidationResponseDto res =
                service.validateConnection(draft, new ConnectionValidationRequestDto(0, true, false));

        assertFalse(res.valid());
        assertEquals("ERROR", res.status());
        assertTrue(res.safeErrors().contains("Outbound network probe requires operator approval"));
    }

    @Test
    void resolveApprovalFailsVerificationWhenEgressPolicyDenies() {
        String approvalId = "approval_conn_0_abc";
        Instant now = Instant.now();
        OnboardingDraftDto draft = new OnboardingDraftDto(
                "draft_123",
                "owner_1",
                OnboardingDraftPurpose.FIRST_RUN,
                "welcome->connections->review",
                "connections",
                List.of("welcome"),
                List.of(
                        "{\"connections\":[{\"provider\":\"generic-openai\",\"modelId\":\"m1\",\"baseUrl\":\"https://untrusted.internal\"}]}"),
                null,
                approvalId,
                OnboardingDraftState.WAITING_APPROVAL,
                1,
                now.plusSeconds(3600),
                now,
                now);

        Mockito.doThrow(new RuntimeException("SSRF blocked"))
                .when(governedTransport)
                .validateEgress(
                        eq("https://untrusted.internal/models"),
                        eq(OutboundPurpose.CATALOG_PROBE),
                        eq("operator_1"),
                        eq(true));

        Optional<ApprovalResolutionResponseDto> res = service.resolveApproval(
                draft, approvalId, new ApprovalResolutionRequestDto(true, "approved"), "operator_1");

        assertTrue(res.isPresent());
        assertFalse(res.get().verified());
        assertTrue(res.get().message().contains("SSRF blocked"));
    }

    @Test
    void resolveApprovalExecutesGovernedTransportWhenBaseUrlProvided() {
        String approvalId = "approval_conn_0_xyz";
        Instant now = Instant.now();
        OnboardingDraftDto draft = new OnboardingDraftDto(
                "draft_123",
                "owner_1",
                OnboardingDraftPurpose.FIRST_RUN,
                "welcome->connections->review",
                "connections",
                List.of("welcome"),
                List.of(
                        "{\"connections\":[{\"provider\":\"generic-openai\",\"modelId\":\"m1\",\"baseUrl\":\"https://api.openai.com\"}]}"),
                null,
                approvalId,
                OnboardingDraftState.WAITING_APPROVAL,
                1,
                now.plusSeconds(3600),
                now,
                now);

        Optional<ApprovalResolutionResponseDto> res = service.resolveApproval(
                draft, approvalId, new ApprovalResolutionRequestDto(true, "approved"), "operator_1");

        assertTrue(res.isPresent());
        assertTrue(res.get().verified());
        verify(governedTransport)
                .validateEgress(
                        eq("https://api.openai.com/models"),
                        eq(OutboundPurpose.CATALOG_PROBE),
                        eq("operator_1"),
                        eq(true));
        verify(governedTransport)
                .send(
                        eq("https://api.openai.com/models"),
                        eq(OutboundPurpose.CATALOG_PROBE),
                        isNull(),
                        eq("operator_1"),
                        eq(true),
                        any(),
                        any());
    }

    @Test
    void resolveApprovalFailsVerificationWhenProbeReturnsHttpError() {
        String approvalId = "approval_conn_0_xyz";
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, approvalId);

        HttpResponse<?> errorResponse = mock(HttpResponse.class);
        when(errorResponse.statusCode()).thenReturn(401);
        doReturn(errorResponse).when(governedTransport).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        Optional<ApprovalResolutionResponseDto> res = service.resolveApproval(
                draft, approvalId, new ApprovalResolutionRequestDto(true, "approved"), "operator");

        assertTrue(res.isPresent());
        assertEquals("APPROVED", res.get().status());
        assertFalse(res.get().verified());
        assertTrue(res.get().message().contains("HTTP 401"));
        verify(draftStore).update(any(OnboardingDraftDto.class), eq(1));
    }

    @Test
    void resolveApprovalFailsClosedWhenAuditServiceIsNull() {
        String approvalId = "approval_conn_0_xyz";
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, approvalId);
        service.auditService = null;

        assertThrows(
                IllegalStateException.class,
                () -> service.resolveApproval(
                        draft, approvalId, new ApprovalResolutionRequestDto(true, "approved"), "operator"));

        verify(draftStore, never()).update(any(OnboardingDraftDto.class), anyInt());
    }

    @Test
    void resolveApprovalFailsClosedWhenAuditServiceThrowsException() {
        String approvalId = "approval_conn_0_xyz";
        OnboardingDraftDto draft = createDraft(OnboardingDraftState.WAITING_APPROVAL, approvalId);

        doThrow(new RuntimeException("Audit backend unavailable"))
                .when(auditService)
                .recordMandatory(any(), any(), any(), any(), any());

        assertThrows(
                RuntimeException.class,
                () -> service.resolveApproval(
                        draft, approvalId, new ApprovalResolutionRequestDto(true, "approved"), "operator"));

        verify(draftStore, never()).update(any(OnboardingDraftDto.class), anyInt());
    }
}
