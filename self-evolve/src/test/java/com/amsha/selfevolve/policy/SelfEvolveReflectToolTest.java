package com.amsha.selfevolve.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfEvolveReflectToolTest {

    @Mock
    InteractionPolicySynthesizer synthesizer;

    @Mock
    ShadowLifecycleManager lifecycleManager;

    @Mock
    DirectiveStorePort directiveStore;

    SelfEvolveReflectTool tool;

    @BeforeEach
    void setUp() {
        tool = new SelfEvolveReflectTool();
        tool.synthesizer = synthesizer;
        tool.lifecycleManager = lifecycleManager;
        tool.directiveStore = directiveStore;
    }

    @Test
    void testSynthesizeAction() {
        SynthesizedDirective d = new SynthesizedDirective(
                "d-1",
                "user-1",
                "Always format responses cleanly",
                DirectiveState.SHADOW,
                0.9,
                "[]",
                "hash-123",
                1,
                0,
                Instant.now(),
                Instant.now());
        when(synthesizer.synthesize(anyString())).thenReturn(List.of(d));

        ToolResponse resp = tool.execute(mock(ToolExecutionContext.class), Map.of("action", "synthesize"));
        assertTrue(resp.success());
        assertTrue(resp.output().contains("Synthesized 1 directive"));
    }

    @Test
    void testReconcileAction() {
        when(lifecycleManager.reconcileAll()).thenReturn(2);

        ToolResponse resp = tool.execute(mock(ToolExecutionContext.class), Map.of("action", "reconcile"));
        assertTrue(resp.success());
        assertTrue(resp.output().contains("2 directive(s) promoted or disabled"));
    }

    @Test
    void testStatusAction() {
        SynthesizedDirective d = new SynthesizedDirective(
                "d-2",
                "user-1",
                "Be concise",
                DirectiveState.ACTIVE,
                0.95,
                "[]",
                "hash-456",
                3,
                0,
                Instant.now(),
                Instant.now());
        when(directiveStore.getAll(anyString(), anyInt())).thenReturn(List.of(d));

        ToolResponse resp = tool.execute(mock(ToolExecutionContext.class), Map.of("action", "status"));
        assertTrue(resp.success());
        assertTrue(resp.output().contains("ACTIVE"));
    }
}
