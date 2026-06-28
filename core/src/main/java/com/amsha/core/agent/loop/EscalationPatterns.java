package com.amsha.core.agent.loop;

import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.role.AgentGroup;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.jboss.logging.Logger;

/**
 * Escalation keyword patterns and new-persona routing logic.
 * <p>
 * Group-aware escalation: when a member fails, prefer escalating to a
 * sibling within the same group before reaching outside. This models
 * the mythological pattern where family members help each other first.
 */
@SuppressWarnings("java:S1192")
final class EscalationPatterns {

    private static final Logger log = Logger.getLogger(EscalationPatterns.class);

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
        String result =
                switch (sourceRole) {
                    case String s
                    when AgentRoleEnum.OPTIMIZER.toScopedName().equals(s) -> escalateFromOptimizer(goalLower);
                    case String s
                    when AgentRoleEnum.SENTINEL.toScopedName().equals(s) -> escalateFromSentinel(goalLower);
                    case String s when AgentRoleEnum.HEALER.toScopedName().equals(s) -> escalateFromHealer(goalLower);
                    case String s
                    when AgentRoleEnum.PROTECTOR.toScopedName().equals(s) -> escalateFromProtector(goalLower);
                    case String s
                    when AgentRoleEnum.PURIFIER.toScopedName().equals(s) -> escalateFromPurifier(goalLower);
                    case String s
                    when AgentRoleEnum.NURTURER.toScopedName().equals(s) -> escalateFromNurturer(goalLower);
                    default -> null;
                };
        if (result == null) {
            result = tryIntraGroupEscalation(sourceRole, goalLower);
        }
        return result;
    }

    // ── Kailasa Family (Shiva's realm) ────────────────────────────────

    private static String escalateFromOptimizer(String goal) {
        if (containsAny(goal, "implement", "write code", "generate code", "create the"))
            return AgentRoleEnum.CODER.toScopedName();
        if (containsAny(goal, "research", "find information", "look up", "documentation"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        return null;
    }

    private static String escalateFromSentinel(String goal) {
        if (containsAny(goal, "fix", "patch", "remediate", "implement")) return AgentRoleEnum.DEBUGGER.toScopedName();
        if (containsAny(goal, "research", "vulnerability", "cve", "security advisory"))
            return AgentRoleEnum.RESEARCHER.toScopedName();
        return null;
    }

    private static String escalateFromHealer(String goal) {
        // Lakshmi in Vaikunta — limited family, escalate to lead first
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
        return null;
    }

    // ── Group-Aware Escalation Helper ──────────────────────────────────

    /**
     * Attempts to find an escalation target within the same group (Loka).
     * Models the mythological pattern where family members help each other first
     * before reaching outside the realm.
     *
     * @param sourceRole The role that needs to escalate
     * @param goal       The task goal (lowercase)
     * @return A sibling role ID if a match is found, null otherwise
     */
    static String tryIntraGroupEscalation(String sourceRole, String goal) {
        Optional<AgentGroup> groupOpt = AgentGroupRegistry.ALL_GROUPS.stream()
                .filter(g -> g.contains(sourceRole))
                .findFirst();

        if (groupOpt.isEmpty()) return null;

        AgentGroup group = groupOpt.get();

        // If the source is not the lead, prefer escalating to the lead first
        if (!group.isLead(sourceRole)) {
            // Check if the lead can handle this
            if (canRoleHandleGoal(group.leadRoleId(), goal)) {
                try {
                    String target = AgentRoleEnum.fromString(group.leadRoleId()).toScopedName();
                    log.debugf("[ESCALATION] Group-aware routing: %s → %s (intra-group lead)", sourceRole, target);
                    return target;
                } catch (IllegalArgumentException e) {
                    log.warnf(
                            "[ESCALATION] Unknown role ID '%s' in group '%s' — skipping intra-group lead",
                            group.leadRoleId(), group.groupId());
                }
            }
        }

        // Try siblings in the same group
        for (String sibling : group.consultableMembers()) {
            if (sibling.equals(sourceRole)) continue;
            if (canRoleHandleGoal(sibling, goal)) {
                try {
                    String target = AgentRoleEnum.fromString(sibling).toScopedName();
                    log.debugf("[ESCALATION] Group-aware routing: %s → %s (intra-group sibling)", sourceRole, target);
                    return target;
                } catch (IllegalArgumentException e) {
                    log.warnf(
                            "[ESCALATION] Unknown role ID '%s' in group '%s' — skipping intra-group sibling",
                            sibling, group.groupId());
                }
            }
        }

        return null;
    }

    /**
     * Quick check if a role's domain matches the goal keywords.
     */
    private static boolean canRoleHandleGoal(String roleId, String goal) {
        return switch (roleId) {
            case String s
            when AgentRoleEnum.DEBUGGER_ID.equals(s) ->
                containsAny(
                        goal,
                        "fix bug",
                        "debug",
                        "error",
                        "exception",
                        "stack trace",
                        "crash",
                        "broken code",
                        "patch bug",
                        "bug",
                        "regression");
            case String s
            when AgentRoleEnum.OPTIMIZER_ID.equals(s) ->
                containsAny(goal, "complex logic", "recursion", "recursive", "algorithm", "obstacle", "workaround");
            case String s
            when AgentRoleEnum.SENTINEL_ID.equals(s) ->
                containsAny(
                        goal,
                        "security",
                        "vulnerability",
                        "injection",
                        "malicious",
                        "attack",
                        "exploit",
                        "validation",
                        "sanitiz");
            case String s
            when AgentRoleEnum.PROTECTOR_ID.equals(s) ->
                containsAny(goal, "policy", "guardrail", "compliance", "enforce", "audit", "rule");
            case String s
            when AgentRoleEnum.PURIFIER_ID.equals(s) ->
                containsAny(goal, "cleanup", "lifecycle", "resource", "garbage", "memory leak", "shutdown");
            case String s
            when AgentRoleEnum.NURTURER_ID.equals(s) ->
                containsAny(goal, "tone", "empathy", "sentiment", "user experience", "communication");
            case String s
            when AgentRoleEnum.HEALER_ID.equals(s) ->
                containsAny(
                        goal,
                        "error recovery",
                        "self-healing",
                        "heal",
                        "health",
                        "budget",
                        "cost",
                        "optimization",
                        "resilience",
                        "graceful degradation");
            case String s
            when AgentRoleEnum.CODER_ID.equals(s) ->
                containsAny(goal, "implement", "write code", "generate", "create", "build", "feature", "new code");
            case String s
            when AgentRoleEnum.RESEARCHER_ID.equals(s) ->
                containsAny(goal, "research", "documentation", "api docs", "look up", "find information");
            case String s
            when AgentRoleEnum.ARCHITECT_ID.equals(s) ->
                containsAny(goal, "architecture", "design pattern", "trade-off", "best practice");
            default -> false;
        };
    }

    static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }
}
