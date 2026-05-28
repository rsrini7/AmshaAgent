package com.arcclaw.core.agent.loop;

import java.util.Set;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Escalation keyword patterns and new-persona routing logic.
 */
final class EscalationPatterns {

    private EscalationPatterns() {}

    static final Set<String> UNKNOWN_API_PATTERNS = Set.of(
            "does not exist",
            "cannot find symbol",
            "cannot be resolved",
            "cannot be applied",
            "undefined reference",
            "no such method",
            "no such class",
            "package does not exist",
            "import cannot be resolved",
            "cannot resolve",
            "cannot find",
            "unknown",
            "not found",
            "no documentation",
            "no such file",
            "class not found",
            "module not found",
            "dependency not found");

    static final Set<String> SANDBOX_FAILURE_PATTERNS = Set.of(
            "can't find",
            "cannot find main",
            "no main method",
            "compilation error",
            "compile error",
            "syntax error",
            "runtime error",
            "exception in thread",
            "classnotfoundexception",
            "nosuchmethoderror",
            "noclassdeffounderror",
            "error: could not find",
            "error: main method",
            "build failed",
            "exit code",
            "non-zero exit",
            "segmentation fault",
            "core dumped");

    static final Set<String> ARCHITECTURE_PATTERNS = Set.of(
            "architecture",
            "design pattern",
            "trade-off",
            "tradeoff",
            "which approach",
            "which pattern",
            "best practice",
            "how should i structure",
            "how to design",
            "how to architect",
            "should i use",
            "should we use",
            "what is the best way");

    static final Set<String> RESEARCH_NEEDED_PATTERNS = Set.of(
            "unknown error",
            "cannot determine",
            "unable to diagnose",
            "insufficient information",
            "need more context",
            "this appears to be a known issue",
            "check the documentation",
            "refer to",
            "see the documentation",
            "look up");

    static final Set<String> REWRITE_NEEDED_PATTERNS = Set.of(
            "fundamental flaw",
            "design flaw",
            "architecture issue",
            "needs to be rewritten",
            "needs to be redesigned",
            "structural problem",
            "cannot be fixed with a simple",
            "requires a complete rewrite",
            "core issue");

    /**
     * Determines escalation targets for new personas (Ganesha, Kartikeya, Lakshmi, Durga, Kali, Parvati).
     */
    static String determineNewPersonaEscalation(String sourceRole, String goalLower) {
        if (sourceRole == null) return null;
        return switch (sourceRole) {
            case String s when AgentRoleEnum.OPTIMIZER.toScopedName().equals(s) -> escalateFromOptimizer(goalLower);
            case String s when AgentRoleEnum.SENTINEL.toScopedName().equals(s) -> escalateFromSentinel(goalLower);
            case String s when AgentRoleEnum.HEALER.toScopedName().equals(s) -> escalateFromHealer(goalLower);
            case String s when AgentRoleEnum.PROTECTOR.toScopedName().equals(s) -> escalateFromProtector(goalLower);
            case String s when AgentRoleEnum.PURIFIER.toScopedName().equals(s) -> escalateFromPurifier(goalLower);
            case String s when AgentRoleEnum.NURTURER.toScopedName().equals(s) -> escalateFromNurturer(goalLower);
            default -> null;
        };
    }

    private static String escalateFromOptimizer(String goal) {
        if (containsAny(goal, "implement", "write code", "generate code", "create the"))
            return AgentRoleEnum.CODER.toScopedName();
        if (containsAny(goal, "research", "find information", "look up", "documentation"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        if (containsAny(goal, "fix bug", "debug", "error", "exception")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "security", "vulnerability", "injection", "malicious"))
            return AgentRoleEnum.SENTINEL.toScopedName();
        return null;
    }

    private static String escalateFromSentinel(String goal) {
        if (containsAny(goal, "fix", "patch", "remediate", "implement")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "vulnerability", "cve", "security advisory"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        if (containsAny(goal, "algorithm", "recursive", "complex logic", "obstacle"))
            return AgentRoleEnum.OPTIMIZER.toScopedName();
        return null;
    }

    private static String escalateFromHealer(String goal) {
        if (containsAny(goal, "fix", "patch", "implement", "code change")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "documentation", "best practice"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        if (containsAny(goal, "budget", "cost", "allocation", "governance"))
            return AgentRoleEnum.COORDINATOR.toScopedName();
        return null;
    }

    private static String escalateFromProtector(String goal) {
        if (containsAny(goal, "fix", "patch", "implement", "enforce")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "policy", "compliance", "regulation"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        return null;
    }

    private static String escalateFromPurifier(String goal) {
        if (containsAny(goal, "fix", "patch", "implement", "code change")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "documentation", "best practice"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        return null;
    }

    private static String escalateFromNurturer(String goal) {
        if (containsAny(goal, "fix", "bug", "error", "broken")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "documentation", "information"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        if (containsAny(goal, "debug", "stack trace", "exception", "crash"))
            return AgentRoleEnum.DEBUGGER.toScopedName();
        return null;
    }

    static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }
}
