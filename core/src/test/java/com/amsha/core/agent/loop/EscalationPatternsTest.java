package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link EscalationPatterns#determineNewPersonaEscalation}.
 * Verifies branch parity after extract-method refactor (S3776 complexity reduction).
 */
class EscalationPatternsTest {

    private static final String CODER = AgentRoleEnum.CODER.toScopedName();
    private static final String RESEARCHER = AgentRoleEnum.RESEARCHER.toScopedName();
    private static final String DEBUGGER = AgentRoleEnum.DEBUGGER.toScopedName();
    private static final String SENTINEL = AgentRoleEnum.SENTINEL.toScopedName();
    private static final String OPTIMIZER = AgentRoleEnum.OPTIMIZER.toScopedName();
    private static final String COORDINATOR = AgentRoleEnum.COORDINATOR.toScopedName();

    // --- Optimizer escalations ---
    @Test
    void optimizer_implementEscalatesToCoder() {
        assertEquals(
                CODER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.OPTIMIZER.toScopedName(), "please implement a new feature"));
    }

    @Test
    void optimizer_researchEscalatesToResearcher() {
        assertEquals(
                RESEARCHER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.OPTIMIZER.toScopedName(), "find information about this API"));
    }

    @Test
    void optimizer_debugEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.OPTIMIZER.toScopedName(), "fix bug in the parser"));
    }

    @Test
    void optimizer_securityEscalatesToSentinel() {
        assertEquals(
                SENTINEL,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.OPTIMIZER.toScopedName(), "there is a security vulnerability"));
    }

    // --- Sentinel escalations ---
    @Test
    void sentinel_fixEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.SENTINEL.toScopedName(), "patch the vulnerability"));
    }

    @Test
    void sentinel_researchEscalatesToResearcher() {
        assertEquals(
                RESEARCHER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.SENTINEL.toScopedName(), "research the CVE advisory"));
    }

    @Test
    void sentinel_algorithmEscalatesToOptimizer() {
        assertEquals(
                OPTIMIZER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.SENTINEL.toScopedName(), "complex logic in the encryption algorithm"));
    }

    // --- Healer escalations ---
    @Test
    void healer_fixEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.HEALER.toScopedName(), "implement the fix"));
    }

    @Test
    void healer_budgetEscalatesToCoordinator() {
        assertEquals(
                COORDINATOR,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.HEALER.toScopedName(), "review budget allocation"));
    }

    // --- Protector escalations ---
    @Test
    void protector_enforceEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.PROTECTOR.toScopedName(), "enforce the security policy"));
    }

    // --- Purifier escalations ---
    @Test
    void purifier_codeChangeEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.PURIFIER.toScopedName(), "implement the code change"));
    }

    // --- Nurturer escalations ---
    @Test
    void nurturer_bugEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.NURTURER.toScopedName(), "there is a bug in the error handler"));
    }

    @Test
    void nurturer_crashEscalatesToDebugger() {
        assertEquals(
                DEBUGGER,
                EscalationPatterns.determineNewPersonaEscalation(
                        AgentRoleEnum.NURTURER.toScopedName(), "the stack trace shows a crash"));
    }

    // --- No match ---
    @Test
    void unknownRole_returnsNull() {
        assertNull(EscalationPatterns.determineNewPersonaEscalation("unknown-role", "implement"));
    }

    @Test
    void noMatchingKeywords_returnsNull() {
        assertNull(EscalationPatterns.determineNewPersonaEscalation(
                AgentRoleEnum.OPTIMIZER.toScopedName(), "tell me a joke"));
    }

    @Test
    void nullSourceRole_returnsNull() {
        assertNull(EscalationPatterns.determineNewPersonaEscalation(null, "implement"));
    }

    // ── tryIntraGroupEscalation ────────────────────────────────────

    @Test
    void intraGroup_memberEscalatesToLead_whenLeadCanHandle() {
        // Optimizer is a member of Kailasa, lead is debugger
        // Goal about "fix bug" should route to debugger (lead)
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.OPTIMIZER.toScopedName(), "fix bug in the parser");
        assertEquals(DEBUGGER, result);
    }

    @Test
    void intraGroup_memberEscalatesToSibling_whenLeadCannotHandle() {
        // Sentinel is a member of Kailasa, lead is debugger
        // Goal about "complex logic recursion" should route to optimizer (sibling)
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.SENTINEL.toScopedName(), "complex logic recursion algorithm");
        assertEquals(OPTIMIZER, result);
    }

    @Test
    void intraGroup_leadEscalatesToSibling() {
        // Debugger is the lead of Kailasa
        // Goal about "security vulnerability" should route to sentinel (sibling)
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.DEBUGGER.toScopedName(), "security vulnerability in auth");
        assertEquals(SENTINEL, result);
    }

    @Test
    void intraGroup_noMatchInGroup_returnsNull() {
        // Goal about "tell me a joke" doesn't match any Kailasa member
        String result =
                EscalationPatterns.tryIntraGroupEscalation(AgentRoleEnum.DEBUGGER.toScopedName(), "tell me a joke");
        assertNull(result);
    }

    @Test
    void intraGroup_unknownRoleNotInAnyGroup_returnsNull() {
        // "unknown-role" is not in any built-in group
        String result = EscalationPatterns.tryIntraGroupEscalation("unknown-role", "fix bug");
        assertNull(result);
    }

    @Test
    void intraGroup_brahmaloka_memberEscalatesToLead() {
        // Researcher is a member of Brahmaloka, lead is coder
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.RESEARCHER.toScopedName(), "write code for user service");
        assertEquals(CODER, result);
    }

    @Test
    void intraGroup_brahmaloka_leadEscalatesToResearcher() {
        // Coder is the lead of Brahmaloka
        // Goal about "research API documentation" should route to researcher
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.CODER.toScopedName(), "research API documentation for library");
        assertEquals(RESEARCHER, result);
    }

    @Test
    void intraGroup_vaiKunta_leadEscalatesToHealer() {
        // Coordinator is the lead of Vaikunta
        // Goal about "error recovery" should route to healer
        String result = EscalationPatterns.tryIntraGroupEscalation(
                AgentRoleEnum.COORDINATOR.toScopedName(), "error recovery self-healing");
        assertEquals(AgentRoleEnum.HEALER.toScopedName(), result);
    }

    // ── canRoleHandleGoal ───────────────────────────────────────────

    @Test
    void canRoleHandleGoal_debugger_fixBug() {
        assertTrue(EscalationPatterns.containsAny("fix bug in parser", "fix bug", "debug"));
    }

    @Test
    void canRoleHandleGoal_optimizer_algorithm() {
        assertTrue(
                EscalationPatterns.containsAny("complex logic recursion", "complex logic", "recursion", "algorithm"));
    }

    @Test
    void canRoleHandleGoal_sentinel_security() {
        assertTrue(EscalationPatterns.containsAny("security vulnerability", "security", "vulnerability", "injection"));
    }

    @Test
    void canRoleHandleGoal_noMatch() {
        assertFalse(EscalationPatterns.containsAny("tell me a joke", "fix bug", "debug", "security"));
    }

    // ── determineNewPersonaEscalation with group-aware fallback ────

    @Test
    void groupAwareOptimizer_debugGoal_escalatesToDebugger() {
        // Optimizer is in Kailasa; "fix bug" matches debugger (lead)
        String result = EscalationPatterns.determineNewPersonaEscalation(
                AgentRoleEnum.OPTIMIZER.toScopedName(), "fix bug in parser");
        assertEquals(DEBUGGER, result);
    }

    @Test
    void groupAwareSentinel_optimizeGoal_escalatesToOptimizer() {
        // Sentinel is in Kailasa; "complex logic recursion" matches optimizer (sibling)
        String result = EscalationPatterns.determineNewPersonaEscalation(
                AgentRoleEnum.SENTINEL.toScopedName(), "complex logic recursion");
        assertEquals(OPTIMIZER, result);
    }

    @Test
    void groupAwareResearcher_codeGoal_escalatesToCoder() {
        // Researcher is in Brahmaloka; "implement feature" matches coder (lead)
        String result = EscalationPatterns.determineNewPersonaEscalation(
                AgentRoleEnum.RESEARCHER.toScopedName(), "implement new feature for user service");
        assertEquals(CODER, result);
    }
}
