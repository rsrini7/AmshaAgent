package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;

import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.persona.AgentRoleRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.security.DestructiveVocabularyFilter;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class IntentDetectorServiceUnitTest {

    @Mock
    IntentClassificationService intentDetector;

    @Mock
    DestructiveVocabularyFilter destructiveFilter;

    @Mock
    AgentRoleRegistry agentRoleRegistry;

    @Mock
    Instance<IntentRoutingProvider> providers;

    @Mock
    IntentDecisionRouter intentDecisionRouter;

    @InjectMocks
    IntentDetectorService service;

    private interface CapableChatModel extends ChatModel, StrictJsonSchemaCapable {}

    @BeforeEach
    void setup() {
        setField(service, "slaTimeoutMs", 5000L);
        setField(service, "slaWarnMs", 2000L);
        lenient()
                .when(agentRoleRegistry.allRoles())
                .thenReturn(List.of(AgentRoleEnum.COORDINATOR, AgentRoleEnum.CODER, AgentRoleEnum.RESEARCHER));
        lenient().when(providers.stream()).thenReturn(Stream.empty());
        service.init();

        ManagedExecutor directExecutor = mock(ManagedExecutor.class);
        lenient()
                .doAnswer(inv -> {
                    Runnable r = inv.getArgument(0);
                    r.run();
                    return null;
                })
                .when(directExecutor)
                .execute(any(Runnable.class));
        setField(service, "managedExecutor", directExecutor);
        setField(service, "objectMapper", new ObjectMapper());
        lenient().when(intentDecisionRouter.isEnabled()).thenReturn(false);
    }

    @Test
    void testClassifyRetryCommand() {
        when(intentDetector.isRetryCommand("retry")).thenReturn(true);
        assertEquals("__CONTINUE__", service.classify("retry"));
    }

    @Test
    void testClassifyContinuationCommand() {
        when(intentDetector.isRetryCommand("continue")).thenReturn(false);
        when(intentDetector.isContinuationCommand("continue")).thenReturn(true);
        assertEquals("__CONTINUE__", service.classify("continue"));
    }

    @Test
    void nativeSchema_attachedWhenStrictCapableAndNativeMode() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp =
                ChatResponse.builder().aiMessage(AiMessage.from("researcher")).build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("tell me about the architecture");
        assertEquals("researcher", result);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel).chat(captor.capture());
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchema_notAttachedWhenNotStrictCapable() {
        ChatModel plainModel = mock(ChatModel.class);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp =
                ChatResponse.builder().aiMessage(AiMessage.from("coder")).build();
        when(plainModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", plainModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("write a function");
        assertEquals("coder", result);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(plainModel).chat(captor.capture());
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchema_notAttachedWhenModeIsPromptDirective() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp =
                ChatResponse.builder().aiMessage(AiMessage.from("coordinator")).build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.PROMPT_DIRECTIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("manage tasks");
        assertEquals("coordinator", result);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel).chat(captor.capture());
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchema_malformedResponse_retriesWithoutNativeSchema() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse successResp =
                ChatResponse.builder().aiMessage(AiMessage.from("coder")).build();
        when(capableModel.chat(any(ChatRequest.class)))
                .thenThrow(new LlmMalformedResponseException("schema rejected"))
                .thenReturn(successResp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("write a test");
        assertEquals("coder", result);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel, times(2)).chat(captor.capture());
        List<ChatRequest> requests = captor.getAllValues();
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(requests.get(0)));
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(requests.get(1)));
        UserMessage retryMsg = (UserMessage) requests.get(1).messages().get(0);
        assertTrue(retryMsg.singleText().contains("CRITICAL: You MUST respond with ONLY a single role name token"));
    }

    @Test
    void nativeSchema_parsesRoleFromJson() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"role\": \"researcher\"}"))
                .build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("find docs");
        assertEquals("researcher", result);
    }

    @Test
    void fastPath_bypassesDecisionRouterEvenWhenEnabled() {
        lenient().when(intentDecisionRouter.isEnabled()).thenReturn(true);
        when(intentDetector.isRetryCommand("retry")).thenReturn(true);

        assertEquals("__CONTINUE__", service.classify("retry"));
        verify(intentDecisionRouter, times(0)).evaluateIntent(any(), any());
    }

    @Test
    void pilotEnabled_directRoute_routesDirectlyToSpecialist() {
        when(intentDecisionRouter.isEnabled()).thenReturn(true);
        when(intentDecisionRouter.isDirectRoutingPermitted()).thenReturn(true);
        IntentPolicyResolution resolution =
                IntentPolicyResolution.direct("coder", 0.95, "amsha.routing.intent.v1", 1, "HIGH_CONFIDENCE_ROUTE");
        when(intentDecisionRouter.evaluateIntent(anyString(), any())).thenReturn(resolution);
        when(destructiveFilter.filter("coder", "write a java class")).thenReturn("coder");

        String result = service.classify("write a java class");
        assertEquals("coder", result);
    }

    @Test
    void pilotEnabled_directRoute_destructiveFilterStillOverrides() {
        when(intentDecisionRouter.isEnabled()).thenReturn(true);
        when(intentDecisionRouter.isDirectRoutingPermitted()).thenReturn(true);
        IntentPolicyResolution resolution = IntentPolicyResolution.direct(
                "researcher", 0.92, "amsha.routing.intent.v1", 1, "HIGH_CONFIDENCE_ROUTE");
        when(intentDecisionRouter.evaluateIntent(anyString(), any())).thenReturn(resolution);
        when(destructiveFilter.filter("researcher", "delete database and search docs"))
                .thenReturn("coordinator");

        String result = service.classify("delete database and search docs");
        assertEquals("coordinator", result);
    }

    @Test
    void pilotEnabled_activationGatePending_blocksDirectRoutingAndFallsThrough() {
        when(intentDecisionRouter.isEnabled()).thenReturn(true);
        when(intentDecisionRouter.isDirectRoutingPermitted()).thenReturn(false);
        IntentPolicyResolution resolution =
                IntentPolicyResolution.direct("coder", 0.95, "amsha.routing.intent.v1", 1, "HIGH_CONFIDENCE_ROUTE");
        when(intentDecisionRouter.evaluateIntent(anyString(), any())).thenReturn(resolution);

        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"role\": \"researcher\"}"))
                .build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("write a java class");
        // Direct route to "coder" was blocked by activation gate, falls through to authoritative model
        assertEquals("researcher", result);
    }

    @Test
    void pilotEnabled_secondaryReview_fallsThroughToAuthoritativeModel() {
        when(intentDecisionRouter.isEnabled()).thenReturn(true);
        IntentPolicyResolution resolution = IntentPolicyResolution.secondaryReview(
                Optional.of("coder"), OptionalDouble.of(0.68), "amsha.routing.intent.v1", 1, "MIDDLE_REVIEW_BAND");
        when(intentDecisionRouter.evaluateIntent(anyString(), any())).thenReturn(resolution);

        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"role\": \"researcher\"}"))
                .build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("examine the codebase architecture");
        assertEquals("researcher", result);
        verify(capableModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void pilotEnabled_fallback_fallsThroughToAuthoritativeModel() {
        when(intentDecisionRouter.isEnabled()).thenReturn(true);
        IntentPolicyResolution resolution = IntentPolicyResolution.fallback(
                "amsha.routing.intent.v1", 1, OptionalDouble.of(0.35), "LOW_CONFIDENCE");
        when(intentDecisionRouter.evaluateIntent(anyString(), any())).thenReturn(resolution);

        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"role\": \"coordinator\"}"))
                .build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("do whatever is needed");
        assertEquals("coordinator", result);
        verify(capableModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void pilotRollback_disablingRestoresStandardRouteWithZeroMigration() {
        // Router is disabled (rollback / kill switch)
        when(intentDecisionRouter.isEnabled()).thenReturn(false);

        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"role\": \"researcher\"}"))
                .build();
        when(capableModel.chat(any(ChatRequest.class))).thenReturn(resp);

        Instance<StructuredOutputMode> modeInstance = mock(Instance.class);
        when(modeInstance.isUnsatisfied()).thenReturn(false);
        when(modeInstance.get()).thenReturn(StructuredOutputMode.NATIVE);

        setField(service, "classifierModel", capableModel);
        setField(service, "structuredOutputModeInstance", modeInstance);

        String result = service.classify("read documents");
        assertEquals("researcher", result);
        verify(intentDecisionRouter, times(0)).evaluateIntent(any(), any());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
