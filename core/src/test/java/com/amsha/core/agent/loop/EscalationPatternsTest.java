package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
