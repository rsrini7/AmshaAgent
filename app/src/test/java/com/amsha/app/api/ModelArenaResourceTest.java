package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import com.amsha.app.ui.dto.ArenaCompareRequest;
import com.amsha.app.ui.dto.ArenaCompareResponse;
import com.amsha.core.agent.reasoning.ChatModelProducer;
import com.amsha.core.agent.trace.TraceRedactor;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.guardrail.GuardDecision;
import com.amsha.core.guardrail.PiiInputGuard;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.routing.ModelRouteSelector;
import com.amsha.core.security.DefaultCredentialDetector;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelArenaResourceTest {

    private ModelArenaResource resource;
    private AuditService auditService;
    private SecurityContext securityContext;
    private PiiInputGuard piiInputGuard;
    private Instance<PiiInputGuard> piiGuardInstance;
    private Instance<AgentChatModelProvider> providersInstance;
    private Instance<GovernedTransportService> transportInstance;
    private GovernedTransportService transportService;
    private Instance<ModelRouteSelector> routeSelectorInstance;
    private ModelRouteSelector routeSelector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new ModelArenaResource();
        auditService = mock(AuditService.class);
        securityContext = mock(SecurityContext.class);
        piiInputGuard = mock(PiiInputGuard.class);
        piiGuardInstance = mock(Instance.class);
        providersInstance = mock(Instance.class);
        transportInstance = mock(Instance.class);
        transportService = mock(GovernedTransportService.class);
        routeSelectorInstance = mock(Instance.class);
        routeSelector = mock(ModelRouteSelector.class);

        when(piiGuardInstance.isResolvable()).thenReturn(true);
        when(piiGuardInstance.get()).thenReturn(piiInputGuard);

        when(transportInstance.isResolvable()).thenReturn(true);
        when(transportInstance.get()).thenReturn(transportService);
        resource.governedTransport = transportInstance;

        when(routeSelectorInstance.isResolvable()).thenReturn(true);
        when(routeSelectorInstance.get()).thenReturn(routeSelector);
        when(routeSelector.select(any(), any(), any())).thenAnswer(inv -> {
            List<ModelRoute> routes = inv.getArgument(2);
            return routes.isEmpty() ? Optional.empty() : Optional.of(routes.get(0));
        });
        resource.modelRouteSelector = routeSelectorInstance;

        resource.credentialDetector = new DefaultCredentialDetector();
        resource.piiInputGuard = piiGuardInstance;
        resource.traceRedactor = new TraceRedactor();
        resource.auditService = auditService;
        resource.chatModelProducer = mock(ChatModelProducer.class);
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());
        resource.providers = providersInstance;
        resource.securityContext = securityContext;
        resource.defaultProviderName = "groq";
    }

    @Test
    void nonInternalCallerGetsForbidden() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.compareModels(new ArenaCompareRequest("hello", List.of("groq"), 100));
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), resp.getStatus());
    }

    @Test
    void validationErrorsOnInvalidInput() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response r1 = resource.compareModels(new ArenaCompareRequest(null, List.of("groq"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        Response r2 = resource.compareModels(new ArenaCompareRequest("hello", List.of(), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r2.getStatus());

        Response r3 = resource.compareModels(new ArenaCompareRequest("hello", List.of("m1", "m2", "m3", "m4"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r3.getStatus());

        Response r4 = resource.compareModels(new ArenaCompareRequest("a".repeat(8195), List.of("m1"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r4.getStatus());
    }

    @Test
    void promptWithCredentialsRejectedWith400AndAuditedWithoutSecret() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        String secretPrompt = "Benchmark with api_key: sk-proj-1234567890abcdef12345678 now";
        Response resp = resource.compareModels(new ArenaCompareRequest(secretPrompt, List.of("groq"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .recordMandatory(
                        eq("arena"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        msgCaptor.capture());

        String auditMsg = msgCaptor.getValue();
        assertTrue(auditMsg.contains("promptHash="));
        assertFalse(auditMsg.contains("sk-proj"));
        assertFalse(auditMsg.contains("1234567890abcdef"));
    }

    @Test
    void promptBlockedByPiiPolicyRejectedWith400() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        when(piiInputGuard.validate(any(), any(), any(), any()))
                .thenReturn(new GuardDecision.Block("PII", "National ID blocked"));

        Response resp = resource.compareModels(new ArenaCompareRequest("My SSN is 000-11-2222", List.of("groq"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());

        verify(auditService)
                .recordMandatory(
                        eq("arena"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        anyString());
    }

    @Test
    void cleanPromptExecutesBenchmarkAndAuditsHashOnly() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("This is the model completion."))
                        .tokenUsage(new TokenUsage(5, 6))
                        .build());

        Iterator<AgentChatModelProvider> providerIterator =
                List.of(mockProvider).iterator();
        when(providersInstance.iterator()).thenReturn(providerIterator);

        String prompt = "Explain Newton's second law.";
        Response resp = resource.compareModels(new ArenaCompareRequest(prompt, List.of("omlx"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ArenaCompareResponse body = (ArenaCompareResponse) resp.getEntity();
        assertEquals(1, body.results().size());
        assertEquals("omlx", body.results().get(0).modelSlot());
        assertEquals("This is the model completion.", body.results().get(0).output());
        assertTrue(body.results().get(0).costUsd() > 0.0);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .recordMandatory(
                        eq("arena"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.SESSION_INSPECTED),
                        msgCaptor.capture());

        String auditMsg = msgCaptor.getValue();
        assertTrue(auditMsg.contains("promptHash="));
        assertTrue(auditMsg.contains("models=omlx"));
        assertTrue(auditMsg.contains("totalCostUsd="));
        assertFalse(auditMsg.contains("totalCostUsd=0.0"));
        assertFalse(auditMsg.contains(prompt));
        assertFalse(auditMsg.contains("This is the model completion"));
    }

    @Test
    void sanitizedPromptExceedingInputBudgetIsTruncatedBeforeDispatch() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        // PII guard expands input beyond 2048 tokens
        String expandedPrompt = "A".repeat(2048 * 4 + 100);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Sanitize(expandedPrompt));

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);

        ArgumentCaptor<ChatRequest> reqCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(mockModel.chat(reqCaptor.capture()))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Completion"))
                        .tokenUsage(new TokenUsage(10, 10))
                        .build());

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Initial valid prompt", List.of("omlx"), 50));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ChatRequest dispatchedReq = reqCaptor.getValue();
        assertNotNull(dispatchedReq);
        UserMessage userMsg = (UserMessage) dispatchedReq.messages().get(0);
        assertTrue(userMsg.singleText().length() <= 2048 * 4);
    }

    @Test
    void excessiveTokensRejected() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        // 2050 words produces > 2048 estimated tokens
        String massivePrompt = "token ".repeat(2050);
        Response resp = resource.compareModels(new ArenaCompareRequest(massivePrompt, List.of("omlx"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("Prompt exceeds maximum input budget"));
    }

    @Test
    void providerErrorReturnsCategoricalCodeWithoutRawSecretsOrUrls() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("401 Unauthorized https://api.omlx.local/chat sk-sensitive-token"));

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test prompt", List.of("omlx"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ArenaCompareResponse body = (ArenaCompareResponse) resp.getEntity();
        assertEquals(1, body.results().size());
        assertEquals("PROVIDER_AUTHENTICATION_ERROR", body.results().get(0).output());
        assertFalse(body.results().get(0).output().contains("sk-sensitive-token"));
        assertFalse(body.results().get(0).output().contains("https://"));
    }

    @Test
    void defaultSlotResolvesViaChatModelProducer() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        ChatModel mockModel = mock(ChatModel.class);
        when(resource.chatModelProducer.chatModel()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Default completion"))
                        .tokenUsage(new TokenUsage(5, 10))
                        .build());

        Response resp = resource.compareModels(new ArenaCompareRequest("Hello default", List.of("default"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ArenaCompareResponse body = (ArenaCompareResponse) resp.getEntity();
        assertEquals(1, body.results().size());
        assertEquals("default", body.results().get(0).modelSlot());
        assertEquals("Default completion", body.results().get(0).output());
        assertEquals(10, body.results().get(0).outputTokens());
        verify(transportService)
                .validateEgress("https://api.groq.com/openai/v1", OutboundPurpose.PROVIDER_API_CALL, "test-operator");
    }

    @Test
    void governedTransportValidationAndRouteSelectionEnforced() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Governed response"))
                        .tokenUsage(new TokenUsage(5, 5))
                        .build());

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test governed", List.of("omlx"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        verify(transportService)
                .validateEgress(
                        eq(ModelDefaults.DEFAULT_OMLX_BASE_URL),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        eq("test-operator"));
        verify(routeSelector).select(eq("arena"), any(), any());
    }

    @Test
    void unsupportedChatRequestFailsClosedWithoutDroppingTokenCap() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenThrow(new UnsupportedOperationException("ChatRequest unsupported"));

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test fallback", List.of("omlx"), 10));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ArenaCompareResponse body = (ArenaCompareResponse) resp.getEntity();
        assertEquals(1, body.results().size());
        assertEquals("PROVIDER_UNSUPPORTED_CAPABILITY", body.results().get(0).output());
        assertEquals(0, body.results().get(0).outputTokens());
        verify(mockModel, never()).chat(anyString());
    }

    @Test
    void validateProviderDispatchUsesConfiguredProviderBaseUrl() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("generic-openai");
        when(mockProvider.baseUrl()).thenReturn("http://127.0.0.1:9090/v1");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Generic response"))
                        .build());

        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test prompt", List.of("generic-openai"), 10));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        verify(transportService)
                .validateEgress("http://127.0.0.1:9090/v1", OutboundPurpose.PROVIDER_API_CALL, "test-operator");
    }

    @Test
    void validateProviderDispatchFailsClosedWhenEndpointUrlIsMissing() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("generic-openai");
        when(mockProvider.baseUrl()).thenReturn(null);
        when(mockProvider.create()).thenReturn(mockModel);
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test prompt", List.of("generic-openai"), 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse error = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Provider configuration failure", error.error());
    }

    @Test
    void mandatoryAuditFailureFailsClosedWith500() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(mockProvider).iterator());

        doThrow(new RuntimeException("Audit disk full"))
                .when(auditService)
                .recordMandatory(eq("arena"), any(), any(), eq(SecurityEventType.SESSION_INSPECTED), any());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test audit failure", List.of("omlx"), 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse error = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Mandatory audit logging failed", error.error());
    }

    @Test
    void mandatoryAuditFailureOnEgressFailsClosedWith500() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(mockProvider).iterator());

        doThrow(new RuntimeException("Audit disk full"))
                .when(auditService)
                .recordMandatory(eq("arena-egress"), any(), any(), any(), any());

        Response resp =
                resource.compareModels(new ArenaCompareRequest("Test egress audit failure", List.of("omlx"), 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse error = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Mandatory audit logging failed", error.error());
    }

    @Test
    void unallowlistedModelSlotRejectedWith400() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response r1 = resource.compareModels(new ArenaCompareRequest("test", List.of("unknown_provider"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        Response r2 = resource.compareModels(new ArenaCompareRequest("test", List.of("groq; drop table"), 100));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r2.getStatus());
    }

    @Test
    void faithfulPromptInvocationPreservesWhitespace() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenReturn(mockModel);

        ArgumentCaptor<ChatRequest> reqCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(mockModel.chat(reqCaptor.capture()))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .tokenUsage(new TokenUsage(5, 2))
                        .build());

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        String untrimmed = "  leading and trailing spaces  ";
        Response resp = resource.compareModels(new ArenaCompareRequest(untrimmed, List.of("omlx"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        ChatRequest captured = reqCaptor.getValue();
        assertEquals(untrimmed, ((UserMessage) captured.messages().get(0)).singleText());
    }

    @Test
    void customModelSlotWithSlashAndColonAllowed() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockProvider.providerName()).thenReturn("openrouter");
        when(mockProvider.create("minimax/minimax-m3:free")).thenReturn(mockModel);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Custom slot ok"))
                        .tokenUsage(new TokenUsage(5, 3))
                        .build());

        when(providersInstance.iterator()).thenReturn(List.of(mockProvider).iterator());

        Response resp = resource.compareModels(
                new ArenaCompareRequest("Test custom slot", List.of("openrouter:minimax/minimax-m3:free"), 100));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        ArenaCompareResponse compareResp = (ArenaCompareResponse) resp.getEntity();
        assertEquals(1, compareResp.results().size());
        assertEquals(
                "openrouter:minimax/minimax-m3:free",
                compareResp.results().get(0).modelSlot());
        assertEquals("Custom slot ok", compareResp.results().get(0).output());
    }

    @Test
    void providerConstructionFailureFailsClosedWith500() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(piiInputGuard.validate(any(), any(), any(), any())).thenReturn(new GuardDecision.Allow());

        AgentChatModelProvider mockProvider = mock(AgentChatModelProvider.class);
        when(mockProvider.providerName()).thenReturn("omlx");
        when(mockProvider.create()).thenThrow(new RuntimeException("Configuration failure: missing credentials"));
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(mockProvider).iterator());

        Response resp = resource.compareModels(new ArenaCompareRequest("Test prompt", List.of("omlx"), 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse error = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Provider configuration failure", error.error());
    }
}
