package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.role.AgentGroupRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupConsultationEngineTest {

    private GroupConsultationEngine engine;
    private GroupConsultationService consultationService;
    private AgentGroupRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentGroupRegistry();
        consultationService = new GroupConsultationService();
        consultationService.groupRegistry = registry;
        consultationService.taskClassifier = new TaskClassifierService();

        engine = new GroupConsultationEngine();
        engine.groupRegistry = registry;
        engine.consultationService = consultationService;
        engine.maxConcurrent = 4;
        engine.timeoutSeconds = 30;
    }

    @Test
    void buildSubTasks_pureTask_returnsEmpty() {
        // Debugger can self-handle a pure debugging task
        var group = registry.findById("kailasa").orElseThrow();
        Map<String, String> subTasks = engine.buildSubTasks(group, "Fix NullPointerException in UserService line 42");

        assertNotNull(subTasks);
        assertTrue(subTasks.isEmpty(), "Pure task should return empty map (self-handle)");
    }

    @Test
    void buildSubTasks_multiDomainTask_returnsSubTasks() {
        // Task spanning debug + recursion should require optimizer consultation
        var group = registry.findById("kailasa").orElseThrow();
        Map<String, String> subTasks =
                engine.buildSubTasks(group, "Fix recursive traversal stack overflow and optimize the logic");

        assertNotNull(subTasks);
        assertFalse(subTasks.isEmpty(), "Multi-domain task should produce sub-tasks");
        assertTrue(subTasks.containsKey("optimizer"));
        assertTrue(subTasks.get("optimizer").contains("recursion")
                || subTasks.get("optimizer").contains("optimizer"));
    }

    @Test
    void consultGroup_emptySubTasks_returnsEmptyResult() {
        GroupConsultationEngine.GroupConsultationResult result =
                engine.consultGroup("kailasa", "fix null pointer", Map.of(), (id, goal) -> "should not be called");

        assertEquals("kailasa", result.groupId());
        assertEquals(0, result.membersConsulted());
        assertEquals(0, result.membersFailed());
        assertFalse(result.hasUsefulResults());
        assertTrue(result.memberResults().isEmpty());
    }

    @Test
    void consultGroup_allSucceed_countsCorrectly() {
        Map<String, String> subTasks = new LinkedHashMap<>();
        subTasks.put("optimizer", "Analyze recursion");
        subTasks.put("sentinel", "Check security");

        AtomicInteger callCount = new AtomicInteger(0);
        GroupConsultationEngine.SubAgentRunner runner = (memberId, goal) -> {
            callCount.incrementAndGet();
            return "Analysis for " + memberId + ": all good";
        };

        GroupConsultationEngine.GroupConsultationResult result =
                engine.consultGroup("kailasa", "fix recursive security bug", subTasks, runner);

        assertEquals(2, result.membersConsulted());
        assertEquals(0, result.membersFailed());
        assertTrue(result.hasUsefulResults());
        assertEquals(2, callCount.get());
        assertTrue(result.memberResults().containsKey("optimizer"));
        assertTrue(result.memberResults().containsKey("sentinel"));
    }

    @Test
    void consultGroup_oneRunnerThrows_countsCorrectly() {
        Map<String, String> subTasks = new LinkedHashMap<>();
        subTasks.put("optimizer", "Analyze recursion");
        subTasks.put("sentinel", "Check security");

        GroupConsultationEngine.SubAgentRunner runner = (memberId, goal) -> {
            if ("sentinel".equals(memberId)) {
                throw new RuntimeException("Timeout");
            }
            return "Analysis for " + memberId;
        };

        GroupConsultationEngine.GroupConsultationResult result =
                engine.consultGroup("kailasa", "fix recursive security bug", subTasks, runner);

        assertEquals(1, result.membersConsulted());
        assertEquals(1, result.membersFailed());
        assertTrue(result.hasUsefulResults());
        assertTrue(result.memberResults().get("sentinel").contains("Timeout"));
    }

    @Test
    void consultGroup_runnerThrows_countsCorrectly() {
        Map<String, String> subTasks = new LinkedHashMap<>();
        subTasks.put("optimizer", "Analyze recursion");
        subTasks.put("sentinel", "Check security");

        GroupConsultationEngine.SubAgentRunner runner = (memberId, goal) -> {
            throw new RuntimeException("Provider down");
        };

        GroupConsultationEngine.GroupConsultationResult result =
                engine.consultGroup("kailasa", "fix recursive security bug", subTasks, runner);

        assertEquals(0, result.membersConsulted());
        assertEquals(2, result.membersFailed());
        assertFalse(result.hasUsefulResults());
        assertTrue(result.memberResults().get("optimizer").contains("Provider down"));
    }

    @Test
    void consultGroup_runnerReturnsBlankResult_treatedAsFailure() {
        Map<String, String> subTasks = new LinkedHashMap<>();
        subTasks.put("optimizer", "Analyze recursion");

        GroupConsultationEngine.SubAgentRunner runner = (memberId, goal) -> "  ";

        GroupConsultationEngine.GroupConsultationResult result =
                engine.consultGroup("kailasa", "fix recursive logic", subTasks, runner);

        assertEquals(0, result.membersConsulted());
        assertEquals(1, result.membersFailed());
    }

    @Test
    void groupConsultationResult_toSynthesisPrompt_containsAllMemberResults() {
        Map<String, String> memberResults = new LinkedHashMap<>();
        memberResults.put("optimizer", "The recursion needs memoization.");
        memberResults.put("sentinel", "SQL injection found in query builder.");

        GroupConsultationEngine.GroupConsultationResult result =
                new GroupConsultationEngine.GroupConsultationResult("kailasa", memberResults, null, 2, 0);

        String prompt = result.toSynthesisPrompt();

        assertTrue(prompt.contains("GROUP CONSULTATION RESULTS"));
        assertTrue(prompt.contains("kailasa"));
        assertTrue(prompt.contains("optimizer"));
        assertTrue(prompt.contains("recursion needs memoization"));
        assertTrue(prompt.contains("sentinel"));
        assertTrue(prompt.contains("SQL injection"));
    }

    @Test
    void groupConsultationResult_hasUsefulResults_reflectsConsultedCount() {
        // Zero consulted → no useful results
        GroupConsultationEngine.GroupConsultationResult noResults =
                new GroupConsultationEngine.GroupConsultationResult("kailasa", Map.of(), null, 0, 2);
        assertFalse(noResults.hasUsefulResults());

        // At least one consulted → has useful results
        GroupConsultationEngine.GroupConsultationResult someResults =
                new GroupConsultationEngine.GroupConsultationResult("kailasa", Map.of("optimizer", "ok"), null, 1, 1);
        assertTrue(someResults.hasUsefulResults());
    }
}
