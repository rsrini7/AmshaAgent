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
import java.util.stream.Stream;

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
