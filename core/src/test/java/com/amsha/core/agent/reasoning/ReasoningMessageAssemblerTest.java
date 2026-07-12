package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.LoopStuckDetector;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.skill.SkillAdaptationManager;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.search.ToolSearchIndex;

import org.junit.jupiter.api.Test;

class ReasoningMessageAssemblerTest {

    @Test
    void assemble_injectsLoopStuckHintForRepeatedIdenticalToolCalls() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertTrue(containsHint(result, "STOP repeating"), "Expected a loop-stuck hint user message to be injected");
    }

    @Test
    void assemble_injectsLoopStuckHintForReadOnlyLoop() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "ls /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo/a")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "grep x /foo/b")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertTrue(
                containsHint(result, "STOP inspecting"), "Expected a read-only-loop hint user message to be injected");
    }

    @Test
    void assemble_noHintWhenHistoryIsProgressing() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "echo done > /foo/out")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertFalse(
                containsHint(result, "STOP repeating") || containsHint(result, "STOP inspecting"),
                "No hint should be injected when history shows progress");
    }

    private static ReasoningMessageAssembler createAssembler() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble("sess-1", "goal", Collections.emptyList()))
                .thenReturn(Collections.emptyList());

        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn(ToolCapability.SANDBOX_EXEC.toolName());
        when(mockTool.description()).thenReturn("sandbox exec tool");
        when(mockTool.parametersSchema()).thenReturn(JsonObjectSchema.builder().build());
        when(mockTool.isEnabled()).thenReturn(true);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.findTool(ToolCapability.SANDBOX_EXEC.toolName())).thenReturn(Optional.of(mockTool));

        ReasoningMessageAssembler assembler =
                new ReasoningMessageAssembler(historyAssembler, toolRegistry, new LoopStuckDetector());

        injectField(assembler, "toolSearchIndex", mock(ToolSearchIndex.class));
        injectField(assembler, "skillRegistry", mock(SkillRegistry.class));
        injectField(assembler, "adaptationManager", mock(SkillAdaptationManager.class));
        return assembler;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = ReasoningMessageAssembler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ReasoningMessageAssembler.AssembledMessages assemble(
            ReasoningMessageAssembler assembler, List<ReasoningStep> history) {
        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "skill:test",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());

        return assembler.assemble(
                context,
                "skill:test",
                "goal",
                history,
                "system prompt",
                Set.of(ToolCapability.SANDBOX_EXEC.toolName()),
                false,
                false,
                false);
    }

    private static boolean containsHint(ReasoningMessageAssembler.AssembledMessages result, String marker) {
        boolean inSystem = result.messages().stream()
                .filter(m -> m instanceof SystemMessage _)
                .map(m -> ((SystemMessage) m).text())
                .anyMatch(text -> text.contains(marker));
        boolean inUser = result.messages().stream()
                .filter(m -> m instanceof UserMessage _)
                .map(m -> ((UserMessage) m).singleText())
                .anyMatch(text -> text.contains(marker));
        return inSystem || inUser;
    }
}
