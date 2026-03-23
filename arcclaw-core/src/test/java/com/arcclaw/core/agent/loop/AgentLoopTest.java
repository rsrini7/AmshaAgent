package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;

import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

        private ChatModel model;
        private ToolRegistry toolRegistry;
        private RoleRegistry roleRegistry;
        private SystemPromptBuilder promptBuilder;
        private ChatHistoryAssembler historyAssembler;
        private ReasoningStepParser stepParser;
        private LangChainReasoningEngine engine;

        @BeforeEach
        void setUp() throws Exception {
                model = mock(ChatModel.class);
                toolRegistry = mock(ToolRegistry.class);
                roleRegistry = mock(RoleRegistry.class);
                promptBuilder = mock(SystemPromptBuilder.class);
                historyAssembler = mock(ChatHistoryAssembler.class);
                stepParser = mock(ReasoningStepParser.class);

                when(promptBuilder.build(any(), any(), any())).thenReturn("MOCK SYSTEM PROMPT");

                engine = new LangChainReasoningEngine();

                // Use reflection to set private fields
                setField(engine, "chatModel", model);
                setField(engine, "toolRegistry", toolRegistry);
                setField(engine, "roleRegistry", roleRegistry);
                setField(engine, "promptBuilder", promptBuilder);
                setField(engine, "historyAssembler", historyAssembler);
                setField(engine, "stepParser", stepParser);
        }

        private void setField(Object target, String name, Object value) throws Exception {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
        }

        @Test
        void testAgentLoopDelegatesToStateMachineRunner() {
                // Reflection check to ensure methods moved to the runner are indeed gone from
                // AgentLoop
                Class<AgentLoop> clazz = AgentLoop.class;
                String[] deletedMethods = { "runLoop", "processEngineStep", "validateTool", "invokeToolAndObserve",
                                "handleFinishAction", "saveGoalToChatMemory" };

                for (String methodName : deletedMethods) {
                        boolean exists = Arrays.stream(clazz.getDeclaredMethods())
                                        .anyMatch(m -> m.getName().equals(methodName));
                        assertFalse(exists, "Method " + methodName + " should have been deleted from AgentLoop");
                }

                // Also check if the injection is there
                boolean hasRunner = Arrays.stream(clazz.getDeclaredFields())
                                .anyMatch(f -> f.getName().equals("stateMachineRunner"));
                assertTrue(hasRunner, "AgentLoop should have stateMachineRunner injected");
        }

        @Test
        void channelStampFailureDoesNotCorruptAwaitingApprovalResult() throws Exception {
                AgentLoop loop = new AgentLoop();

                com.arcclaw.core.state.StatefulAgentStore agentStore = mock(
                                com.arcclaw.core.state.StatefulAgentStore.class);
                AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
                CheckpointResumeHandler checkpointResumeHandler = mock(CheckpointResumeHandler.class);
                com.arcclaw.core.observability.AgentOtelContext otelContext = mock(
                                com.arcclaw.core.observability.AgentOtelContext.class);
                com.arcclaw.core.audit.AuditHashUtil hashUtil = mock(com.arcclaw.core.audit.AuditHashUtil.class);
                @SuppressWarnings("unchecked")
                jakarta.enterprise.inject.Instance<Object> observers = mock(jakarta.enterprise.inject.Instance.class);

                setField(loop, "agentStore", agentStore);
                setField(loop, "stateMachineRunner", stateMachineRunner);
                setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
                setField(loop, "otelContext", otelContext);
                setField(loop, "hashUtil", hashUtil);
                setField(loop, "observers", observers);

                com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("foo", "bar", "disp",
                                com.arcclaw.channel.api.ChannelType.TELEGRAM);
                com.arcclaw.channel.api.ChannelMessage msg = new com.arcclaw.channel.api.ChannelMessage.TelegramMessage(
                                user,
                                "h", "foo", java.time.Instant.now());
                com.arcclaw.core.pipeline.MessageContext ctx = new com.arcclaw.core.pipeline.MessageContext("foo", user,
                                msg,
                                mock(com.arcclaw.channel.api.ChannelSender.class));

                com.arcclaw.core.state.AgentCheckpoint checkpoint = mock(com.arcclaw.core.state.AgentCheckpoint.class);
                when(checkpoint.getStatus()).thenReturn(com.arcclaw.core.state.CheckpointStatus.IN_PROGRESS);

                when(agentStore.load(any(), any())).thenReturn(java.util.Optional.of(checkpoint));
                when(agentStore.save(any())).thenThrow(new RuntimeException("SQLITE_BUSY"));

                com.arcclaw.core.agent.result.AgentResult approval = com.arcclaw.core.agent.result.AgentResult
                                .awaitingApproval("mytool", "{}", "s1", java.util.Collections.emptyList());
                when(stateMachineRunner.run(any(), any(), any())).thenReturn(approval);

                com.arcclaw.core.agent.result.AgentResult result = loop.runWithRole("s1", "coord", "goal", engine, 5,
                                ctx);

                assertTrue(result instanceof com.arcclaw.core.agent.result.AgentResult.AwaitingApproval);
        }
}
