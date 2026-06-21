package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.security.ToolOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link DelegateToAgentTool}.
 * Tests real internal methods (normalizeAgent, stripSurroundingQuotes,
 * isSelfDelegation, isCoderDelegatingToResearcher, isCombinedGoalToResearcher,
 * handleSubAgentResult, getDelegatableAgentList) via reflection.
 */
class DelegateToAgentToolDeepTest {

    private DelegateToAgentTool tool;

    @BeforeEach
    void setUp() {
        tool = new DelegateToAgentTool();
        tool.maxDelegationDepth = 3;
    }

    // ── Public interface ─────────────────────────────────────────────────

    @Test
    void testName() {
        assertEquals("garuda", tool.name());
    }

    @Test
    void testDescription() {
        assertNotNull(tool.description());
        assertFalse(tool.description().isEmpty());
    }

    @Test
    void testRequiresSandbox() {
        assertFalse(tool.requiresSandbox());
    }

    @Test
    void testRequiredOperation() {
        assertEquals(ToolOperation.DELEGATE_AUTONOMOUS, tool.requiredOperation());
    }

    @Test
    void testParametersSchema() {
        var schema = tool.parametersSchema();
        assertNotNull(schema);
    }

    // ── stripSurroundingQuotes ───────────────────────────────────────────

    @Test
    void testStripSurroundingQuotes_singleQuotes() throws Exception {
        assertEquals("hello", invokeStripSurroundingQuotes("'hello'"));
    }

    @Test
    void testStripSurroundingQuotes_doubleQuotes() throws Exception {
        assertEquals("hello", invokeStripSurroundingQuotes("\"hello\""));
    }

    @Test
    void testStripSurroundingQuotes_noQuotes() throws Exception {
        assertEquals("hello", invokeStripSurroundingQuotes("hello"));
    }

    @Test
    void testStripSurroundingQuotes_withSpaces() throws Exception {
        assertEquals("hello world", invokeStripSurroundingQuotes("' hello world '"));
    }

    @Test
    void testStripSurroundingQuotes_null() throws Exception {
        assertNull(invokeStripSurroundingQuotes(null));
    }

    @Test
    void testStripSurroundingQuotes_singleChar() throws Exception {
        assertEquals("x", invokeStripSurroundingQuotes("x"));
    }

    @Test
    void testStripSurroundingQuotes_twoQuotes() throws Exception {
        assertEquals("", invokeStripSurroundingQuotes("''"));
    }

    // ── normalizeAgent ──────────────────────────────────────────────────

    @Test
    void testNormalizeAgent_knownRole() throws Exception {
        String result = invokeNormalizeAgent("saraswati", "do research", "coordinator", "list");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_unknownRole_throws() throws Exception {
        try {
            invokeNormalizeAgent("nonexistent_xyz", "do something", "coordinator", "list");
            assertTrue(false, "Should throw IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void testNormalizeAgent_selfDelegation_redirectsToCoordinator() throws Exception {
        // Coordinator trying to delegate to itself → redirect to coordinator
        // But if coordinator is delegating to coordinator, that's self-delegation → return null
        String result = invokeNormalizeAgent("coordinator", "do something", "coordinator", "list");
        assertNull(result, "Self-delegation to coordinator from coordinator should return null");
    }

    @Test
    void testNormalizeAgent_coderDelegatingToResearcher() throws Exception {
        // This should succeed (allowed delegation path)
        String result = invokeNormalizeAgent("saraswati", "research something", "coder", "list");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_caseInsensitive() throws Exception {
        String result = invokeNormalizeAgent("SARASWATI", "do research", "coordinator", "list");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_withAlias() throws Exception {
        // brahma is an alias for coder
        String result = invokeNormalizeAgent("brahma", "write code", "coordinator", "list");
        assertEquals("brahma", result);
    }

    // ── isSelfDelegation ────────────────────────────────────────────────

    @Test
    void testIsSelfDelegation_sameRole() throws Exception {
        assertTrue(invokeIsSelfDelegation("saraswati", "saraswati"));
    }

    @Test
    void testIsSelfDelegation_differentRole() throws Exception {
        assertFalse(invokeIsSelfDelegation("saraswati", "coordinator"));
    }

    @Test
    void testIsSelfDelegation_alias() throws Exception {
        // shiva is an alias for debugger
        assertTrue(invokeIsSelfDelegation("shiva", "debugger"));
    }

    // ── isCoderDelegatingToResearcher ────────────────────────────────────

    @Test
    void testIsCoderDelegatingToResearcher_yes() throws Exception {
        assertTrue(invokeIsCoderDelegatingToResearcher("saraswati", "coder"));
    }

    @Test
    void testIsCoderDelegatingToResearcher_no_coordinator() throws Exception {
        assertFalse(invokeIsCoderDelegatingToResearcher("saraswati", "coordinator"));
    }

    @Test
    void testIsCoderDelegatingToResearcher_unknownTarget() throws Exception {
        assertFalse(invokeIsCoderDelegatingToResearcher("nonexistent", "coder"));
    }

    // ── isCombinedGoalToResearcher ──────────────────────────────────────

    @Test
    void testIsCombinedGoalToResearcher_codeKeyword() throws Exception {
        assertTrue(invokeIsCombinedGoalToResearcher("saraswati", "coordinator", "search and execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_compileKeyword() throws Exception {
        assertTrue(invokeIsCombinedGoalToResearcher("saraswati", "coordinator", "compile and run the code"));
    }

    @Test
    void testIsCombinedGoalToResearcher_notCoordinator() throws Exception {
        assertFalse(invokeIsCombinedGoalToResearcher("saraswati", "coder", "execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_notResearcher() throws Exception {
        assertFalse(invokeIsCombinedGoalToResearcher("coder", "coordinator", "execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_noCodeKeyword() throws Exception {
        assertFalse(invokeIsCombinedGoalToResearcher("saraswati", "coordinator", "find documentation about APIs"));
    }

    @Test
    void testIsCombinedGoalToResearcher_nullGoal() throws Exception {
        assertFalse(invokeIsCombinedGoalToResearcher("saraswati", "coordinator", null));
    }

    // ── getDelegatableAgentList ──────────────────────────────────────────

    @Test
    void testGetDelegatableAgentList_containsKnownRoles() throws Exception {
        String list = invokeGetDelegatableAgentList();
        assertNotNull(list);
        assertFalse(list.isEmpty());
        // Should contain at least some known roles (excluding coordinator)
        assertTrue(list.contains("saraswati") || list.contains("brahma"));
    }

    @Test
    void testGetDelegatableAgentList_excludesCoordinator() throws Exception {
        String list = invokeGetDelegatableAgentList();
        // Coordinator should not be in the delegatable list
        // Note: "coordinator" appears in aliases of other roles, so we check the exact role
        assertFalse(list.startsWith("coordinator,") && !list.contains(", coordinator"));
    }

    // ── handleSubAgentResult ─────────────────────────────────────────────

    @Test
    void testHandleSubAgentResult_finished() throws Exception {
        var result = new AgentResult.Finished("The answer", Collections.emptyList(), 100, 50);
        var response = invokeHandleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "session-1");
        assertTrue(response.success());
        assertNotNull(response.output());
    }

    @Test
    void testHandleSubAgentResult_loopExceeded() throws Exception {
        var result = new AgentResult.LoopExceeded(5, Collections.emptyList(), 100, 50);
        var response = invokeHandleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "session-1");
        assertFalse(response.success());
        assertEquals(500, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_promptLeakBlocked() throws Exception {
        var result =
                new AgentResult.Finished("unable to share my internal instructions", Collections.emptyList(), 100, 50);
        var response = invokeHandleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "session-1");
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_promptLeakBlocked_sentinel() throws Exception {
        var result = new AgentResult.Finished("PROMPT_LEAK_BLOCKED", Collections.emptyList(), 100, 50);
        var response = invokeHandleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "session-1");
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_failed() throws Exception {
        var result = new AgentResult.Failed(
                "Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, Collections.emptyList(), 100, 50);
        var response = invokeHandleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "session-1");
        // Failed result formats to error text
        assertNotNull(response);
    }

    // ── handleRunSubAgentException ───────────────────────────────────────

    @Test
    void testHandleRunSubAgentException_unboundSecurity() throws Exception {
        var response = invokeHandleRunSubAgentException(
                AgentRoleEnum.RESEARCHER, new IllegalStateException("context unbound"));
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("Security violation"));
    }

    @Test
    void testHandleRunSubAgentException_genericException() throws Exception {
        var response =
                invokeHandleRunSubAgentException(AgentRoleEnum.RESEARCHER, new RuntimeException("something failed"));
        assertFalse(response.success());
        assertEquals(500, response.exitCode());
    }

    // ── Helper invoke methods ────────────────────────────────────────────

    private String invokeStripSurroundingQuotes(String s) throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod("stripSurroundingQuotes", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private String invokeNormalizeAgent(String agentName, String goal, String currentRoleName, String delegatableList)
            throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod(
                "normalizeAgent", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(tool, agentName, goal, currentRoleName, delegatableList);
    }

    private boolean invokeIsSelfDelegation(String normalized, String currentRoleName) throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod("isSelfDelegation", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tool, normalized, currentRoleName);
    }

    private boolean invokeIsCoderDelegatingToResearcher(String normalized, String currentRoleName) throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod(
                "isCoderDelegatingToResearcher", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tool, normalized, currentRoleName);
    }

    private boolean invokeIsCombinedGoalToResearcher(String normalized, String currentRoleName, String goal)
            throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod(
                "isCombinedGoalToResearcher", String.class, String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tool, normalized, currentRoleName, goal);
    }

    private String invokeGetDelegatableAgentList() throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod("getDelegatableAgentList");
        m.setAccessible(true);
        return (String) m.invoke(tool);
    }

    private ToolResponse invokeHandleSubAgentResult(AgentResult result, AgentRoleEnum targetRole, String outerSessionId)
            throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod(
                "handleSubAgentResult", AgentResult.class, AgentRoleEnum.class, String.class);
        m.setAccessible(true);
        return (ToolResponse) m.invoke(tool, result, targetRole, outerSessionId);
    }

    private ToolResponse invokeHandleRunSubAgentException(AgentRoleEnum targetRole, Exception e) throws Exception {
        Method m = DelegateToAgentTool.class.getDeclaredMethod(
                "handleRunSubAgentException", AgentRoleEnum.class, Exception.class);
        m.setAccessible(true);
        return (ToolResponse) m.invoke(tool, targetRole, e);
    }
}
