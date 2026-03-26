package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
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

                StatefulAgentStore agentStore = mock(
                                StatefulAgentStore.class);
                AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
                CheckpointResumeHandler checkpointResumeHandler = mock(CheckpointResumeHandler.class);
                AgentOtelContext otelContext = mock(
                                AgentOtelContext.class);
                AuditHashUtil hashUtil = mock(AuditHashUtil.class);
                @SuppressWarnings("unchecked")
                Instance<Object> observers = mock(Instance.class);

                setField(loop, "agentStore", agentStore);
                setField(loop, "stateMachineRunner", stateMachineRunner);
                setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
                setField(loop, "otelContext", otelContext);
                setField(loop, "hashUtil", hashUtil);
                setField(loop, "observers", observers);

                ChannelUser user = new ChannelUser("foo", "bar", "disp", ChannelType.TELEGRAM);
                ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "h", "foo", Instant.now());
                MessageContext ctx = new MessageContext("foo", user, msg);

                AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
                when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);

                when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));
                when(agentStore.save(any())).thenThrow(new RuntimeException("SQLITE_BUSY"));

                AgentResult approval = AgentResult
                                .awaitingApproval("mytool", "{}", "s1", Collections.emptyList());
                when(stateMachineRunner.run(any(), any(), any())).thenReturn(approval);

                AgentResult result = loop.runWithRole("s1", "coord", "goal", engine, 5,
                                ctx);

                assertTrue(result instanceof AgentResult.AwaitingApproval);
        }
}
