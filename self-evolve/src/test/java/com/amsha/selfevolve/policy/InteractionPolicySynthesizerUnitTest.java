package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.DirectiveBundle;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InteractionPolicySynthesizerUnitTest {

    @Mock
    SelfEvolveConfig config;

    @Mock
    ExtractedMemoryStorePort extractedStore;

    @Mock
    DirectiveStorePort directiveStore;

    @Mock
    ShadowLifecycleManager lifecycleManager;

    @Mock
    ChatModel chatModel;

    @InjectMocks
    InteractionPolicySynthesizer synthesizer;

    @Test
    void testSynthesizeDisabled() {
        when(config.enabled()).thenReturn(false);
        List<SynthesizedDirective> result = synthesizer.synthesize("user-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void testSynthesizeEmptyMemories() {
        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);
        when(extractedStore.getByUser("user-1", 50)).thenReturn(List.of());

        List<SynthesizedDirective> result = synthesizer.synthesize("user-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void testSynthesizeNonActionableMemories() {
        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);
        ExtractedMemory factMemory = ExtractedMemory.create(
                "m1", "user-1", MemoryRecordType.FACT, "Fact content", 0.9, "source-1", List.of());
        when(extractedStore.getByUser("user-1", 50)).thenReturn(List.of(factMemory));

        List<SynthesizedDirective> result = synthesizer.synthesize("user-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void testSynthesizeSuccess() {
        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);

        ExtractedMemory failureMemory = ExtractedMemory.create(
                "m2", "user-1", MemoryRecordType.FAILURE_PATTERN, "Tool failed", 0.9, "source-1", List.of());
        when(extractedStore.getByUser("user-1", 50)).thenReturn(List.of(failureMemory));

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("[{\"text\":\"When tool fails use fallback\",\"confidence\":0.8}]"))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        SynthesizedDirective created =
                SynthesizedDirective.create("user-1", "When tool fails use fallback", 0.8, "", "");
        when(directiveStore.insert(any())).thenReturn(1);
        when(lifecycleManager.activateShadow(any())).thenReturn(created.withState(DirectiveState.SHADOW));

        List<SynthesizedDirective> result = synthesizer.synthesize("user-1");
        assertEquals(1, result.size());
        assertEquals(DirectiveState.SHADOW, result.get(0).state());
    }

    @Test
    void testBuildDirectivesDisabled() {
        when(config.enabled()).thenReturn(false);
        DirectiveBundle bundle = synthesizer.buildDirectives("user-1");
        assertTrue(bundle.activeDirectives().isEmpty());
        assertTrue(bundle.shadowDirectives().isEmpty());
        assertEquals(0, bundle.totalAvailable());
    }

    @Test
    void testBuildDirectivesEnabled() {
        when(config.enabled()).thenReturn(true);
        when(config.directivesEnabled()).thenReturn(true);

        SynthesizedDirective active = SynthesizedDirective.create("user-1", "active directive", 0.9, "", "");
        active = active.withState(DirectiveState.ACTIVE);

        SynthesizedDirective shadow = SynthesizedDirective.create("user-1", "shadow directive", 0.8, "", "");
        shadow = shadow.withState(DirectiveState.SHADOW);

        when(directiveStore.getInjectable(eq("user-1"), anyInt())).thenReturn(List.of(active, shadow));

        DirectiveBundle bundle = synthesizer.buildDirectives("user-1");
        assertEquals(1, bundle.activeDirectives().size());
        assertEquals(1, bundle.shadowDirectives().size());
        assertEquals(2, bundle.totalAvailable());
    }
}
