package com.arcclaw.core.agent.loop;

import java.util.Set;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Escalation keyword patterns and new-persona routing logic.
 * Extracted from PersonaEscalationService to keep file length within checkstyle limits.
 */
final class EscalationPatterns {

    private EscalationPatterns() {
    }

    static final Set<String> UNKNOWN_API_PATTERNS = Set.of(
            "does not exist", "cannot find symbol", "cannot be resolved",
            "cannot be applied", "undefined reference", "no such method",
            "no such class", "package does not exist", "import cannot be resolved",
            "cannot resolve", "cannot find", "unknown", "not found",
            "no documentation", "no such file", "class not found",
            "module not found", "dependency not found"
    );

    static final Set<String> ARCHITECTURE_PATTERNS = Set.of(
            "architecture", "design pattern", "trade-off", "tradeoff",
            "which approach", "which pattern", "best practice",
            "how should i structure", "how to design", "how to architect",
            "should i use", "should we use", "what is the best way"
    );

    static final Set<String> RESEARCH_NEEDED_PATTERNS = Set.of(
            "unknown error", "cannot determine", "unable to diagnose",
            "insufficient information", "need more context",
            "this appears to be a known issue", "check the documentation",
            "refer to", "see the documentation", "look up"
    );

    static final Set<String> REWRITE_NEEDED_PATTERNS = Set.of(
            "fundamental flaw", "design flaw", "architecture issue",
            "needs to be rewritten", "needs to be redesigned",
            "structural problem", "cannot be fixed with a simple",
            "requires a complete rewrite", "core issue"
    );

    /**
     * Determines escalation targets for new personas (Ganesha, Kartikeya, Lakshmi, Durga, Kali, Parvati).
     */
    static String determineNewPersonaEscalation(String sourceRole, String goalLower) {
        // Ganesha (Optimizer) escalations
        if (AgentRoleEnum.OPTIMIZER.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "implement", "write code", "generate code", "create the")) {
                return AgentRoleEnum.CODER.toScopedName();
            }
            if (containsAny(goalLower, "research", "find information", "look up", "documentation")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
            if (containsAny(goalLower, "fix bug", "debug", "error", "exception")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            // Kartikeya (brother) - security issue found in logic
            if (containsAny(goalLower, "security", "vulnerability", "injection", "malicious")) {
                return AgentRoleEnum.SENTINEL.toScopedName();
            }
        }

        // Kartikeya (Sentinel) escalations
        if (AgentRoleEnum.SENTINEL.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "fix", "patch", "remediate", "implement")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            if (containsAny(goalLower, "research", "vulnerability", "cve", "security advisory")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
            // Ganesha (brother) - complex logic in security analysis
            if (containsAny(goalLower, "algorithm", "recursive", "complex logic", "obstacle")) {
                return AgentRoleEnum.OPTIMIZER.toScopedName();
            }
        }

        // Lakshmi (Healer) escalations
        if (AgentRoleEnum.HEALER.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "fix", "patch", "implement", "code change")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            if (containsAny(goalLower, "research", "documentation", "best practice")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
            // Vishnu (husband) - budget/cost coordination
            if (containsAny(goalLower, "budget", "cost", "allocation", "governance")) {
                return AgentRoleEnum.COORDINATOR.toScopedName();
            }
        }

        // Durga (Protector) escalations
        if (AgentRoleEnum.PROTECTOR.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "fix", "patch", "implement", "enforce")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            if (containsAny(goalLower, "research", "policy", "compliance", "regulation")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
        }

        // Kali (Purifier) escalations
        if (AgentRoleEnum.PURIFIER.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "fix", "patch", "implement", "code change")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            if (containsAny(goalLower, "research", "documentation", "best practice")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
        }

        // Parvati (Nurturer) escalations
        if (AgentRoleEnum.NURTURER.toScopedName().equals(sourceRole)) {
            if (containsAny(goalLower, "fix", "bug", "error", "broken")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
            if (containsAny(goalLower, "research", "documentation", "information")) {
                return AgentRoleEnum.RESEARCHER.toScopedName();
            }
            // Shiva (husband) - technical issues requiring debugging
            if (containsAny(goalLower, "debug", "stack trace", "exception", "crash")) {
                return AgentRoleEnum.DEBUGGER.toScopedName();
            }
        }

        return null;
    }

    static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }
}
