package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.LoopStuckDetector;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoopStuckTraceTest {

    private final TracePublisher tracePublisher = mock(TracePublisher.class);
    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    private ReasoningMessageAssembler assembler;

    @BeforeEach
    void setup() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble(anyString(), anyString(), anyList())).thenReturn(List.of());
        ToolRegistry toolRegistry = mock(ToolRegistry.class);

        assembler = new ReasoningMessageAssembler(historyAssembler, toolRegistry, new LoopStuckDetector());

        var tracer = new LoopStuckTracer(tracePublisher);
        var field = ReasoningMessageAssembler.class.getDeclaredField("loopStuckTracer");
        field.setAccessible(true);
        field.set(assembler, tracer);
    }

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext(
                "sess1",
                "user1",
                "chat1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                Map.of());
    }

    @Test
    void loopStuckEmitsLoopStuckTrace() {
        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")));

        assembler.assemble(ctx(), "coordinator", "goal", history, "system", Set.of(), false, true, false);

        verify(tracePublisher).publish(captor.capture());
        assertEquals("STUCK", captor.getValue().status());
        assertEquals(TraceStage.LOOP_STUCK, captor.getValue().stage());
        assertEquals("station_06_reasoning", captor.getValue().station().wireValue());
        assertNotEquals(
                "station_02_preflight",
                captor.getValue().station().wireValue(),
                "LOOP_STUCK must not be classified as PREFLIGHT");
    }
}
