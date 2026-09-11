package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Collections;
import java.util.List;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

@SuppressWarnings("java:S1192")
class ReasoningStepParserTest {

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    ReasoningStepParser parser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TextPatternUtils textPatternUtils = new TextPatternUtils();
        ToolCallParameterParser parameterParser = new ToolCallParameterParser(objectMapper, textPatternUtils);
        ParallelToolCallCollapser parallelCollapser = new ParallelToolCallCollapser(objectMapper, parameterParser);
        parser = new ReasoningStepParser(textPatternUtils, parameterParser, parallelCollapser);
    }

    @Test
    void testParse_toolCallResponse() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name(ToolCapability.GITHUB.toolName())
                .arguments("{\"repo\":\"amsha\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(new TokenUsage(100, 50))
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ToolCapability.GITHUB.toolName(), step.action());
        assertEquals("amsha", step.actionInput().get("repo"));
        assertEquals(100, step.inputTokens());
        assertEquals(50, step.outputTokens());
    }

    @Test
    void unrequestedSandboxExec_isConvertedToMarkedFinishStep() throws Exception {
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_shell")
                .name(ToolCapability.SANDBOX_EXEC.toolName())
                .arguments("{\"language\":\"bash\",\"code\":\"echo should-not-run\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("I will run it", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.run(
                "s1",
                "user1",
                "chat1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "Show me the shell command text")
                        .call(() -> parser.parse("s1", "coordinator", response)));

        assertEquals(ReasoningStep.FINISH, step.action());
        assertEquals(Boolean.TRUE, step.actionInput().get(ReasoningStep.ACTION_INPUT_INTERCEPTED_EXECUTION_KEY));
        assertTrue(String.valueOf(step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY))
                .contains("echo should-not-run"));
    }

    @Test
    void testParse_textResponse() {
        // Arrange
        AiMessage aiMessage = AiMessage.aiMessage("Final Answer");
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        assertEquals("Final Answer", step.actionInput().get("answer"));
    }

    @Test
    void testParse_invalidJsonArgsReturnsObserve() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name(ToolCapability.GITHUB.toolName())
                .arguments("invalid json")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.OBSERVE, step.action());
        assertNotNull(step.observation());
        assertTrue(step.observation().contains("invalid JSON"));
    }

    @Test
    void malformedJsonToolArgsReturnsObserveNotFinish() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_invalid")
                .name("sometool")
                .arguments("{ broken json }")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.OBSERVE, step.action());
        assertTrue(step.observation().contains("JSON"));
    }

    @Test
    void testParse_tokenUsagePopulated() {
        // Arrange
        AiMessage aiMessage = AiMessage.aiMessage("OK");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(new TokenUsage(10, 20))
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(10, step.inputTokens());
        assertEquals(20, step.outputTokens());
    }

    @Test
    void testParse_nullTokenUsageDefaultsToZero() {
        // Arrange
        AiMessage aiMessage = AiMessage.aiMessage("OK");
        ChatResponse response =
                ChatResponse.builder().aiMessage(aiMessage).tokenUsage(null).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(0, step.inputTokens());
        assertEquals(0, step.outputTokens());
    }

    @Test
    void testParse_parallelToolCalls_generatesUuidsForOllama() {
        // Arrange: Ollama returns tool calls with NULL id()
        ToolExecutionRequest tool1 = ToolExecutionRequest.builder()
                .id(null) // Ollama style
                .name(ToolCapability.GITHUB.toolName())
                .arguments("{\"repo\":\"amsha\"}")
                .build();
        ToolExecutionRequest tool2 = ToolExecutionRequest.builder()
                .id(null)
                .name("read_file")
                .arguments("{\"path\":\"REAME.md\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Doing two things", List.of(tool1, tool2));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.PARALLEL_SENTINEL_DUNDER, step.action());
        String batchJson = (String) step.actionInput().get("batch");
        assertNotNull(batchJson);
        assertTrue(batchJson.contains("github-")); // UUID fallback
        assertTrue(batchJson.contains("read_file-")); // UUID fallback
    }

    @Test
    void testParse_parallelToolCalls_preservesExistingIds() {
        // Arrange: OpenAI/Anthropic return tool calls with IDs
        ToolExecutionRequest tool1 = ToolExecutionRequest.builder()
                .id("call_abc")
                .name(ToolCapability.GITHUB.toolName())
                .arguments("{\"repo\":\"amsha\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", List.of(tool1));
        // Force requests.size() > 1 for this test path (in parse it handles > 1)
        ToolExecutionRequest tool2 = ToolExecutionRequest.builder()
                .id("call_xyz")
                .name("read")
                .arguments("{}")
                .build();
        aiMessage = AiMessage.aiMessage("Doing context", List.of(tool1, tool2));

        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        String batchJson = (String) step.actionInput().get("batch");
        assertTrue(batchJson.contains("call_abc"));
        assertTrue(batchJson.contains("call_xyz"));
    }

    @Test
    void testParse_selfEvolveReflectParallelCallsCollapseToStatus() {
        ToolExecutionRequest synthesizeReq = ToolExecutionRequest.builder()
                .id("call_synth")
                .name(ToolCapability.SELF_EVOLVE_REFLECT.toolName())
                .arguments("{\"action\":\"synthesize\"}")
                .build();
        ToolExecutionRequest statusReq = ToolExecutionRequest.builder()
                .id("call_status")
                .name(ToolCapability.SELF_EVOLVE_REFLECT.toolName())
                .arguments("{\"action\":\"status\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", List.of(synthesizeReq, statusReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = parser.parse("s1", "coordinator", response);

        assertEquals(ToolCapability.SELF_EVOLVE_REFLECT.toolName(), step.action());
        assertEquals("status", step.actionInput().get("action"));
    }

    @Test
    void testParse_schedulingGoalCollapsesMixedParallelBatchToScheduleTask() throws Exception {
        ToolExecutionRequest scheduleReq = ToolExecutionRequest.builder()
                .id("call_sched")
                .name(ToolCapability.SCHEDULE_TASK.toolName())
                .arguments(
                        "{\"goal\":\"Say Hello Buddy\",\"cronExpression\":\"37 10 * * *\",\"timezone\":\"Asia/Kolkata\",\"runOnce\":true}")
                .build();
        ToolExecutionRequest delegateReq = ToolExecutionRequest.builder()
                .id("call_delegate")
                .name(ToolCapability.DELEGATE_TO_AGENT.toolName())
                .arguments("{\"agent\":\"saraswati\",\"goal\":\"Perform a real-time search\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("I'll schedule it", List.of(scheduleReq, delegateReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coordinator",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "schedule to say Hello Buddy in one min")
                        .call(() -> parser.parse("s1", "coordinator", response)));

        assertEquals(ToolCapability.SCHEDULE_TASK.toolName(), step.action());
        assertEquals("Say Hello Buddy", step.actionInput().get("goal"));
        assertEquals(Boolean.TRUE, step.actionInput().get("runOnce"));
    }

    @Test
    void testParse_recoversMalformedSandboxExecArgs() throws Exception {
        String malformed = "{\"code\": \"public class StringReverser {\n"
                + "    public static String reverseString(String input) {\n"
                + "        return input.chars()\n"
                + "                .mapToObj(c -> (char) c)\n"
                + "                .reduce(\\\"\\\", (s, c) -> c + s, (s1, s2) -> s2 + s1);\n"
                + "    }\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\\\"Original: \\\" + input);\n"
                + "    }\n"
                + "}\", \"language\":\"java\"}";
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_sandbox")
                .name(ToolCapability.SANDBOX_EXEC.toolName())
                .arguments(malformed)
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("execute", List.of(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Bind goal that REQUESTS execution so interception doesn't fire
        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coder",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "execute this code")
                        .call(() -> parser.parse("s1", "coder", response)));

        assertEquals(ToolCapability.SANDBOX_EXEC.toolName(), step.action());
        assertEquals("java", step.actionInput().get("language"));
        String code = (String) step.actionInput().get("code");
        assertNotNull(code);
        assertTrue(code.contains("public class StringReverser"));
        assertTrue(code.contains("System.out.println(\"Original: \" + input);"));
    }

    @Test
    void testParse_recoversTruncatedSandboxExecWithoutLanguage() throws Exception {
        // Truncated payload: missing closing brace and language field entirely
        String truncated =
                "{\"code\": \"public class MyClass { public static void main(String[] a) { System.out.println(\\\"Test\\\");";

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_trunc")
                .name(ToolCapability.SANDBOX_EXEC.toolName())
                .arguments(truncated)
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("execute", List.of(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Bind goal that REQUESTS execution so interception doesn't fire
        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coder",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "execute this code")
                        .call(() -> parser.parse("s1", "coder", response)));

        assertEquals(ToolCapability.SANDBOX_EXEC.toolName(), step.action());
        assertEquals("java", step.actionInput().get("language"), "Should infer java from 'public class'");
        String code = (String) step.actionInput().get("code");
        assertNotNull(code);
        assertTrue(code.contains("public class MyClass"));
    }

    @Test
    void testParse_interceptsUnrequestedExecution() throws Exception {
        // Goal: just write code, do NOT run it
        String goal = "Write a Java class that says hello";

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_unrequested")
                .name(ToolCapability.SANDBOX_EXEC.toolName())
                .arguments("{\"code\":\"public class Hello { }\", \"language\":\"java\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Here is the code", List.of(toolReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coder",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, goal)
                        .call(() -> parser.parse("s1", "coder", response)));

        assertEquals(ReasoningStep.FINISH, step.action(), "Should intercept and convert to FINISH");
        String answer = (String) step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
        assertTrue(answer.contains("public class Hello"), "Should contain the code");
        assertTrue(answer.startsWith("```java"), "Should be wrapped in markdown");
    }

    @Test
    void testParse_nonSchedulingTomorrowQuestionRemainsParallelBatch() throws Exception {
        ToolExecutionRequest scheduleReq = ToolExecutionRequest.builder()
                .id("call_sched")
                .name(ToolCapability.SCHEDULE_TASK.toolName())
                .arguments("{\"goal\":\"bad\",\"cronExpression\":\"37 10 * * *\"}")
                .build();
        ToolExecutionRequest delegateReq = ToolExecutionRequest.builder()
                .id("call_delegate")
                .name(ToolCapability.DELEGATE_TO_AGENT.toolName())
                .arguments("{\"agent\":\"saraswati\",\"goal\":\"weather\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("thinking", List.of(scheduleReq, delegateReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coordinator",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "What are tomorrow's weather forecasts?")
                        .call(() -> parser.parse("s1", "coordinator", response)));

        assertEquals(ReasoningStep.PARALLEL_SENTINEL_DUNDER, step.action());
    }

    @Test
    void testParse_everyDayRequestCollapsesToScheduleTaskWithoutExplicitVerb() throws Exception {
        ToolExecutionRequest scheduleReq = ToolExecutionRequest.builder()
                .id("call_sched")
                .name(ToolCapability.SCHEDULE_TASK.toolName())
                .arguments("{\"goal\":\"send me the news\",\"cronExpression\":\"0 9 * * *\"}")
                .build();
        ToolExecutionRequest delegateReq = ToolExecutionRequest.builder()
                .id("call_delegate")
                .name(ToolCapability.DELEGATE_TO_AGENT.toolName())
                .arguments("{\"agent\":\"saraswati\",\"goal\":\"find the news\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("thinking", List.of(scheduleReq, delegateReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coordinator",
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "every day send me the news")
                        .call(() -> parser.parse("s1", "coordinator", response)));

        assertEquals(ToolCapability.SCHEDULE_TASK.toolName(), step.action());
    }

    @Test
    void testParse_mixedIngestBatchForNonStorageGoalDropsStaleIngest() throws Exception {
        ToolExecutionRequest ingestReq = ToolExecutionRequest.builder()
                .id("call_ingest")
                .name(ToolCapability.INGEST_DOCUMENT.toolName())
                .arguments("{\"text\":\"user_name=Srini\",\"label\":\"user_identity\"}")
                .build();
        ToolExecutionRequest delegateReq = ToolExecutionRequest.builder()
                .id("call_delegate")
                .name(ToolCapability.DELEGATE_TO_AGENT.toolName())
                .arguments("{\"agent\":\"saraswati\",\"goal\":\"Find Spring Boot and Quarkus LTS versions\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("thinking", List.of(ingestReq, delegateReq));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = WorkspaceContext.runWithFullAutonomy(
                "s1",
                "user-1",
                "chat-1",
                "coordinator",
                () -> ScopedValue.where(
                                WorkspaceContext.RAW_USER_GOAL,
                                "do real time search on getting spring boot and quarkus LTS version")
                        .call(() -> parser.parse("s1", "coordinator", response)));

        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), step.action());
        assertEquals("saraswati", step.actionInput().get("agent"));
    }

    @Test
    void testParse_deduplicatesIdenticalParallelToolCalls() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_1")
                .name("serper_web_search")
                .arguments("{\"query\":\"spring boot LTS version\"}")
                .build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_2")
                .name("serper_web_search")
                .arguments("{\"query\":\"spring boot LTS version\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Searching", List.of(req1, req2));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = parser.parse("s1", "researcher", response);

        // Deduplicated from 2 identical calls down to 1, collapsing to single pending step
        assertFalse(step.isParallelBatch());
        assertEquals("serper_web_search", step.action());
        assertEquals("spring boot LTS version", step.actionInput().get("query"));
    }

    @Test
    void testParse_preservesDistinctParallelToolCalls() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_1")
                .name("serper_web_search")
                .arguments("{\"query\":\"spring boot LTS version\"}")
                .build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_2")
                .name("serper_web_search")
                .arguments("{\"query\":\"quarkus LTS version\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Searching", List.of(req1, req2));
        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        ReasoningStep step = parser.parse("s1", "researcher", response);

        // Different queries -> preserved as parallel batch of 2
        assertTrue(step.isParallelBatch());
        assertTrue(step.actionInput().containsKey("batch"));
    }
}
