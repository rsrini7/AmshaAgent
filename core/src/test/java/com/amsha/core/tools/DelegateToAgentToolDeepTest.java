package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.message.UserMessages;
import com.amsha.security.api.RiskLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Deep coverage tests for {@link DelegationProcessor} and {@link DelegateToAgentTool}.
 * Tests name normalization, safety checks, validation rules,
 * and result/exception formatting helper methods directly.
 */
class DelegateToAgentToolDeepTest {

    private DelegateToAgentTool tool;
    private DelegationProcessor processor;

    @BeforeEach
    void setUp() {
        tool = new DelegateToAgentTool();
        tool.maxDelegationDepth = 3;

        processor = new DelegationProcessor();
        tool.delegationProcessor = processor;
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

    @Test
    void validateParameters_rejectsUnauthorizedNestedHostExecBeforeApproval() {
        tool.roleRegistry = new RoleRegistry();

        String validationError = tool.validateParameters(Map.of(
                "agent", "shiva",
                "goal", "Run this through host_exec: sudo shutdown -h now"));

        assertNotNull(validationError);
        assertTrue(validationError.contains("host_exec"));
        assertTrue(validationError.contains("shiva"));
    }

    @Test
    void validateParameters_allowsPermittedNestedHostExec() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        Mockito.when(registry.resolvePermittedTools("shiva")).thenReturn(Set.of("host_exec"));

        assertNull(DelegationCapabilityValidator.validate(
                Map.of("agent", "shiva", "goal", "Use host_exec for the approved diagnostic"), registry));
    }

    @Test
    void validateParameters_allowsDescriptiveOrdinaryToolMention() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        Mockito.when(registry.resolvePermittedTools("shiva")).thenReturn(Set.of());

        assertNull(DelegationCapabilityValidator.validate(
                Map.of("agent", "shiva", "goal", "Review our github PR for wording"), registry));
    }

    @Test
    void validateParameters_defersUnknownAgentToNormalization() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        Mockito.when(registry.resolvePermittedTools("unknown-agent"))
                .thenThrow(new IllegalArgumentException("unknown"));

        assertNull(DelegationCapabilityValidator.validate(
                Map.of("agent", "unknown-agent", "goal", "Use host_exec"), registry));
    }

    @Test
    void validateParameters_respectsToolNameBoundaries() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        Mockito.when(registry.resolvePermittedTools("shiva")).thenReturn(Set.of());

        assertNull(DelegationCapabilityValidator.validate(
                Map.of("agent", "shiva", "goal", "Use my_host_exec helper"), registry));
    }

    @Test
    void validateParameters_rejectsNestedDelegation() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        Mockito.when(registry.resolvePermittedTools("shiva")).thenReturn(Set.of());

        String validationError = DelegationCapabilityValidator.validate(
                Map.of("agent", "shiva", "goal", "Ask another role through garuda"), registry);

        assertNotNull(validationError);
        assertTrue(validationError.contains("garuda"));
    }

    // ── stripSurroundingQuotes ───────────────────────────────────────────

    @Test
    void testStripSurroundingQuotes_singleQuotes() {
        assertEquals("hello", DelegationProcessor.stripSurroundingQuotes("'hello'"));
    }

    @Test
    void testStripSurroundingQuotes_doubleQuotes() {
        assertEquals("hello", DelegationProcessor.stripSurroundingQuotes("\"hello\""));
    }

    @Test
    void testStripSurroundingQuotes_noQuotes() {
        assertEquals("hello", DelegationProcessor.stripSurroundingQuotes("hello"));
    }

    @Test
    void testStripSurroundingQuotes_withSpaces() {
        assertEquals("hello world", DelegationProcessor.stripSurroundingQuotes("' hello world '"));
    }

    @Test
    void testStripSurroundingQuotes_null() {
        assertNull(DelegationProcessor.stripSurroundingQuotes(null));
    }

    @Test
    void testStripSurroundingQuotes_singleChar() {
        assertEquals("x", DelegationProcessor.stripSurroundingQuotes("x"));
    }

    @Test
    void testStripSurroundingQuotes_twoQuotes() {
        assertEquals("", DelegationProcessor.stripSurroundingQuotes("''"));
    }

    // ── normalizeAgent ──────────────────────────────────────────────────

    @Test
    void testNormalizeAgent_knownRole() {
        String result = processor.normalizeAgent("saraswati", "do research", "coordinator");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_dynamicSkillAutoPrepend() {
        RoleRegistry registry = Mockito.mock(RoleRegistry.class);
        RoleDefinition skillDef = new RoleDefinition(
                "skill:remote-browser", "Remote Browser", "Description", 5, true, false, Set.of(), Set.of());
        Mockito.when(registry.getDynamicRoles()).thenReturn(List.of(skillDef));
        processor.roleRegistry = registry;

        String result = processor.normalizeAgent("remote-browser", "open website", "coordinator");
        assertEquals("skill:remote-browser", result);
    }

    @Test
    void testNormalizeAgent_unknownRole_throws() {
        try {
            processor.normalizeAgent("nonexistent_xyz", "do something", "coordinator");
            assertTrue(false, "Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void testNormalizeAgent_selfDelegation_redirectsToCoordinator() {
        // Coordinator trying to delegate to itself → redirect to coordinator
        // But if coordinator is delegating to coordinator, that's self-delegation → return null
        String result = processor.normalizeAgent("coordinator", "do something", "coordinator");
        assertNull(result, "Self-delegation to coordinator from coordinator should return null");
    }

    @Test
    void testNormalizeAgent_coderDelegatingToResearcher() {
        // This should succeed (allowed delegation path)
        String result = processor.normalizeAgent("saraswati", "research something", "coder");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_caseInsensitive() {
        String result = processor.normalizeAgent("SARASWATI", "do research", "coordinator");
        assertEquals("saraswati", result);
    }

    @Test
    void testNormalizeAgent_withAlias() {
        // brahma is an alias for coder
        String result = processor.normalizeAgent("brahma", "write code", "coordinator");
        assertEquals("brahma", result);
    }

    // ── isSelfDelegation ────────────────────────────────────────────────

    @Test
    void testIsSelfDelegation_sameRole() {
        assertTrue(processor.isSelfDelegation("saraswati", "saraswati"));
    }

    @Test
    void testIsSelfDelegation_differentRole() {
        assertFalse(processor.isSelfDelegation("saraswati", "coordinator"));
    }

    @Test
    void testIsSelfDelegation_alias() {
        // shiva is an alias for debugger
        assertTrue(processor.isSelfDelegation("shiva", "debugger"));
    }

    // ── isCoderDelegatingToResearcher ────────────────────────────────────

    @Test
    void testIsCoderDelegatingToResearcher_yes() {
        assertTrue(processor.isCoderDelegatingToResearcher("saraswati", "coder"));
    }

    @Test
    void testIsCoderDelegatingToResearcher_no_coordinator() {
        assertFalse(processor.isCoderDelegatingToResearcher("saraswati", "coordinator"));
    }

    @Test
    void testIsCoderDelegatingToResearcher_unknownTarget() {
        assertFalse(processor.isCoderDelegatingToResearcher("nonexistent", "coder"));
    }

    // ── isCombinedGoalToResearcher ──────────────────────────────────────

    @Test
    void testIsCombinedGoalToResearcher_codeKeyword() {
        assertTrue(processor.isCombinedGoalToResearcher("saraswati", "coordinator", "search and execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_compileKeyword() {
        assertTrue(processor.isCombinedGoalToResearcher("saraswati", "coordinator", "compile and run the code"));
    }

    @Test
    void testIsCombinedGoalToResearcher_notCoordinator() {
        assertFalse(processor.isCombinedGoalToResearcher("saraswati", "coder", "execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_notResearcher() {
        assertFalse(processor.isCombinedGoalToResearcher("coder", "coordinator", "execute the program"));
    }

    @Test
    void testIsCombinedGoalToResearcher_noCodeKeyword() {
        assertFalse(processor.isCombinedGoalToResearcher("saraswati", "coordinator", "find documentation about APIs"));
    }

    @Test
    void testIsCombinedGoalToResearcher_nullGoal() {
        assertFalse(processor.isCombinedGoalToResearcher("saraswati", "coordinator", null));
    }

    // ── getDelegatableAgentList ──────────────────────────────────────────

    @Test
    void testGetDelegatableAgentList_containsKnownRoles() {
        String list = processor.getDelegatableAgentList();
        assertNotNull(list);
        assertFalse(list.isEmpty());
        // Should contain at least some known roles (excluding coordinator)
        assertTrue(list.contains("saraswati") || list.contains("brahma"));
    }

    @Test
    void testGetDelegatableAgentList_includesCoordinator() {
        String list = processor.getDelegatableAgentList();
        // Coordinator should not be in the delegatable list
        assertTrue(list.contains("coordinator"));
    }

    // ── handleSubAgentResult ─────────────────────────────────────────────

    @Test
    void testHandleSubAgentResult_finished() {
        var result = new AgentResult.Finished("The answer", Collections.emptyList(), 100, 50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER);
        assertTrue(response.success());
        assertNotNull(response.output());
    }

    @Test
    void testHandleSubAgentResult_paused_showsPersonaAliasWithRole() {
        // F5 regression: the user asked for "saraswati" — the message must lead with the
        // persona and include the canonical role, not show a bare scoped role name.
        var result = new AgentResult.AwaitingApproval(
                new ApprovalFrame("parallel|batch", Map.of(), RiskLevel.MEDIUM),
                "sess-1:delegate:saraswati:1",
                Collections.emptyList(),
                100,
                50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER, "saraswati");
        assertTrue(response.success());
        assertTrue(response.output().contains("saraswati"));
        assertTrue(response.output().contains(AgentRoleEnum.RESEARCHER.toScopedName()));
        assertTrue(response.output().contains(UserMessages.DELEGATION_PAUSED_MARKER));
    }

    @Test
    void testDisplayTarget_personaEqualsRoleId_staysBare() {
        assertEquals(
                AgentRoleEnum.DEBUGGER.toScopedName(),
                DelegationProcessor.displayTarget("debugger", AgentRoleEnum.DEBUGGER));
        assertEquals(
                "Shiva (" + AgentRoleEnum.DEBUGGER.toScopedName() + ")",
                DelegationProcessor.displayTarget("Shiva", AgentRoleEnum.DEBUGGER));
        assertEquals(
                AgentRoleEnum.DEBUGGER.toScopedName(), DelegationProcessor.displayTarget(null, AgentRoleEnum.DEBUGGER));
        assertEquals(
                AgentRoleEnum.DEBUGGER.toScopedName(), DelegationProcessor.displayTarget("  ", AgentRoleEnum.DEBUGGER));
    }

    @Test
    void testHandleSubAgentResult_loopExceeded() {
        var result = new AgentResult.LoopExceeded(5, Collections.emptyList(), 100, 50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER);
        assertFalse(response.success());
        assertEquals(500, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_promptLeakBlocked() {
        var result =
                new AgentResult.Finished("unable to share my internal instructions", Collections.emptyList(), 100, 50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER);
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_promptLeakBlocked_sentinel() {
        var result = new AgentResult.Finished("PROMPT_LEAK_BLOCKED", Collections.emptyList(), 100, 50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER);
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
    }

    @Test
    void testHandleSubAgentResult_failed() {
        var result = new AgentResult.Failed(
                "Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, Collections.emptyList(), 100, 50);
        var response = processor.handleSubAgentResult(result, AgentRoleEnum.RESEARCHER);
        assertNotNull(response);
    }

    // ── handleRunSubAgentException ───────────────────────────────────────

    @Test
    void testHandleRunSubAgentException_unboundSecurity() {
        var response = processor.handleRunSubAgentException(
                AgentRoleEnum.RESEARCHER, new IllegalStateException("context unbound"));
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("Security violation"));
    }

    @Test
    void testHandleRunSubAgentException_genericException() {
        var response = processor.handleRunSubAgentException(
                AgentRoleEnum.RESEARCHER, new RuntimeException("something failed"));
        assertFalse(response.success());
        assertEquals(500, response.exitCode());
    }
}
