package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.role.AgentGroup;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.jboss.logging.Logger;

/**
 * Service that enables group leads to consult their family members before
 * responding. Implements the "family huddle" pattern where a lead agent
 * triages a task, determines which members are needed, and synthesizes
 * their perspectives into a unified recommendation.
 *
 * <p>This service does NOT spawn sub-agent sessions — it provides the
 * triage logic and prompt guidance that the lead agent uses to decide
 * whether to delegate to family members via garuda.
 *
 * <h3>Flow:</h3>
 * <pre>
 *   Task arrives at group lead (e.g., Shiva)
 *     ↓
 *   GroupConsultationService.triage() analyzes the task
 *     ↓
 *   Returns TriageResult with:
 *     - canSelfHandle: true if the lead can handle alone
 *     - recommendedMembers: list of family members to consult
 *     - consultationPrompt: assembled prompt for the huddle
 *     ↓
 *   Lead decides: self-handle OR delegate to recommended members
 * </pre>
 */
@ApplicationScoped
public class GroupConsultationService {

    private static final Logger log = Logger.getLogger(GroupConsultationService.class);

    @Inject
    AgentGroupRegistry groupRegistry;

    @Inject
    TaskClassifierService taskClassifier;

    /**
     * Result of triaging a task within a group.
     *
     * @param canSelfHandle      Whether the lead can handle this alone
     * @param recommendedMembers Family members to consult (empty if self-handle)
     * @param reason             Why this triage decision was made
     * @param consultationPrompt Assembled prompt for the family huddle (null if self-handle)
     */
    public record TriageResult(
            boolean canSelfHandle, List<String> recommendedMembers, String reason, String consultationPrompt) {

        public static TriageResult selfHandle(String reason) {
            return new TriageResult(true, List.of(), reason, null);
        }

        public static TriageResult consult(List<String> members, String reason, String prompt) {
            return new TriageResult(false, List.copyOf(members), reason, prompt);
        }
    }

    /**
     * Triages a task to determine if the lead should self-handle or consult family.
     *
     * @param leadRoleId The lead's role ID (e.g., "debugger" for Shiva)
     * @param goal       The task description
     * @return TriageResult with recommendation
     */
    public TriageResult triage(String leadRoleId, String goal) {
        if (leadRoleId == null || goal == null) {
            return TriageResult.selfHandle("Missing lead role or goal — handling directly");
        }

        return groupRegistry
                .findByLead(leadRoleId)
                .map(group -> triageWithinGroup(group, goal))
                .orElse(TriageResult.selfHandle("Role '" + leadRoleId + "' is not a group lead — handling directly"));
    }

    /**
     * Triages a task with dynamic group fallback. If the static group says
     * "self-handle" but the task actually spans multiple domains, forms a
     * dynamic group using the TaskClassifierService.
     *
     * @param leadRoleId The lead's role ID
     * @param goal       The task description
     * @return TriageResult — either from static group or dynamic group
     */
    public TriageResult triageWithDynamicFallback(String leadRoleId, String goal) {
        TriageResult staticResult = triage(leadRoleId, goal);

        // If static group found members, use that
        if (!staticResult.canSelfHandle()) {
            return staticResult;
        }

        // Try dynamic group formation
        var domains = taskClassifier.classify(goal);
        if (domains.size() <= 1) {
            return staticResult; // Single domain — self-handle is correct
        }

        var dynamicGroup = taskClassifier.formDynamicGroup(goal);
        if (dynamicGroup == null) {
            return staticResult;
        }

        // Dynamic group formed — triage within it
        log.infof("[GROUP-CONSULT] Dynamic group formed: %s for domains %s", dynamicGroup.groupId(), domains);
        return triageWithinGroup(dynamicGroup, goal);
    }

    /**
     * Checks if a role is a group lead.
     */
    public boolean isGroupLead(String roleId) {
        if (groupRegistry == null) {
            log.warnf("[GroupConsultation] groupRegistry is null — isGroupLead returning false for %s", roleId);
            return false;
        }
        return groupRegistry.findByLead(roleId).isPresent();
    }

    /**
     * Gets the group for a given role, if any.
     */
    public Optional<AgentGroup> getGroup(String roleId) {
        if (groupRegistry == null) {
            log.warnf("[GroupConsultation] groupRegistry is null — getGroup returning empty for %s", roleId);
            return Optional.empty();
        }
        return groupRegistry.findByMember(roleId);
    }

    /**
     * Builds a consultation prompt for a family huddle.
     *
     * @param group  The group
     * @param goal   The original task
     * @param members The specific members to consult
     * @return Assembled prompt
     */
    public String buildConsultationPrompt(AgentGroup group, String goal, List<String> members) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== %s FAMILY HUDDLE ===\n", group.groupId().toUpperCase(Locale.ROOT)));
        sb.append(String.format("Task: %s\n\n", goal));
        sb.append("The following family members have been consulted:\n\n");

        for (String member : members) {
            String displayName = member;
            try {
                AgentRoleEnum role = AgentRoleEnum.fromString(member);
                if (role != null && role.definition() != null) {
                    displayName = role.definition().displayName();
                }
            } catch (Exception e) {
                log.warnf("Unknown or invalid roleId when building huddle prompt: %s", member);
            }
            sb.append(String.format("- %s (%s): Analyze from your expertise perspective.\n", displayName, member));
        }

        sb.append("\nEach member should provide their analysis, then the lead will synthesize.\n");
        sb.append("If members disagree, the lead should resolve the conflict.\n");
        return sb.toString();
    }

    // ── Private ────────────────────────────────────────────────────────────

    private TriageResult triageWithinGroup(AgentGroup group, String goal) {
        String goalLower = goal.toLowerCase(Locale.ROOT);

        // Determine which family members are needed FIRST
        List<String> needed = determineNeededMembers(group, goalLower);

        if (needed.isEmpty()) {
            // No specific member matches — check if lead can handle
            if (isWithinLeadExpertise(group.leadRoleId(), goalLower)) {
                return TriageResult.selfHandle(String.format(
                        "Task is within %s's core expertise — no family consultation needed", group.leadRoleId()));
            }
            return TriageResult.selfHandle(
                    "No specific family member identified — lead will handle with best judgment");
        }

        String prompt = buildConsultationPrompt(group, goal, needed);
        log.debugf(
                "[GROUP-CONSULT] %s triage: consulting %s for goal='%s'", group.groupId(), needed, truncate(goal, 80));

        return TriageResult.consult(
                needed,
                String.format(
                        "Task spans %s expertise — consulting %s",
                        needed.size() > 1 ? "multiple" : "a family member's", needed),
                prompt);
    }

    /**
     * Determines which family members are needed based on keyword analysis.
     */
    private List<String> determineNeededMembers(AgentGroup group, String goalLower) {
        List<String> needed = new ArrayList<>();

        for (String memberId : group.consultableMembers()) {
            if (isNeededForMember(memberId, goalLower)) {
                needed.add(memberId);
            }
        }

        return needed;
    }

    /**
     * Checks if a specific member's expertise is needed for the goal.
     */
    private boolean isNeededForMember(String memberId, String goalLower) {
        return switch (memberId) {
            case String s
            when AgentRoleEnum.OPTIMIZER_ID.equals(s) ->
                // Ganesha: complex logic, recursion, algorithms, obstacle removal
                containsAny(
                        goalLower,
                        "complex logic",
                        "recursion",
                        "recursive",
                        "algorithm",
                        "obstacle",
                        "workaround",
                        "multi-step",
                        "nested",
                        "graph",
                        "tree traversal",
                        "dynamic programming",
                        "optimization problem");

            case String s
            when AgentRoleEnum.SENTINEL_ID.equals(s) ->
                // Kartikeya: security, validation, attack patterns
                containsAny(
                        goalLower,
                        "security",
                        "vulnerability",
                        "injection",
                        "xss",
                        "ssrf",
                        "malicious",
                        "sanitiz",
                        "validation",
                        "attack",
                        "exploit",
                        "cve",
                        "authentication",
                        "authorization",
                        "permission",
                        "access control");

            case String s
            when AgentRoleEnum.NURTURER_ID.equals(s) ->
                // Parvati: empathy, tone, user-facing communication
                containsAny(
                        goalLower,
                        "tone",
                        "empathy",
                        "sentiment",
                        "user experience",
                        "frustration",
                        "communication",
                        "message",
                        "notification",
                        "user-facing",
                        "customer",
                        "end user");

            case String s
            when AgentRoleEnum.PROTECTOR_ID.equals(s) ->
                // Durga: policy, guardrails, compliance
                containsAny(
                        goalLower,
                        "policy",
                        "guardrail",
                        "compliance",
                        "audit",
                        "regulation",
                        "constraint",
                        "boundary",
                        "enforce",
                        "rule",
                        "governance",
                        "restriction");

            case String s
            when AgentRoleEnum.PURIFIER_ID.equals(s) ->
                // Kali: cleanup, lifecycle, resource management
                containsAny(
                        goalLower,
                        "cleanup",
                        "lifecycle",
                        "resource management",
                        "garbage collect",
                        "memory leak",
                        "session cleanup",
                        "expire",
                        "graceful shutdown",
                        "terminate",
                        "destroy",
                        "purge");

            case String s
            when AgentRoleEnum.RESEARCHER_ID.equals(s) ->
                // Saraswati: research, documentation, external info
                containsAny(
                        goalLower,
                        "research",
                        "documentation",
                        "api docs",
                        "reference",
                        "specification",
                        "standard",
                        "library",
                        "dependency",
                        "version",
                        "changelog",
                        "migration guide");

            case String s
            when AgentRoleEnum.ARCHITECT_ID.equals(s) ->
                // Brihaspati: architecture, design patterns, trade-offs
                containsAny(
                        goalLower,
                        "architecture",
                        "design pattern",
                        "trade-off",
                        "tradeoff",
                        "best practice",
                        "structure",
                        "refactor",
                        "microservice",
                        "monolith",
                        "scalability",
                        "performance");

            case String s
            when AgentRoleEnum.HEALER_ID.equals(s) ->
                // Lakshmi: error recovery, self-healing, budget
                containsAny(
                        goalLower,
                        "recovery",
                        "self-heal",
                        "budget",
                        "cost",
                        "token",
                        "retry",
                        "circuit breaker",
                        "fallback",
                        "resilience",
                        "health check",
                        "monitoring");

            default -> false;
        };
    }

    /**
     * Checks if the goal falls within the lead's core expertise (no consultation needed).
     */
    private boolean isWithinLeadExpertise(String leadRoleId, String goalLower) {
        return switch (leadRoleId) {
            case String s
            when AgentRoleEnum.DEBUGGER_ID.equals(s) ->
                // Shiva's core: debugging, stack traces, error analysis, code fixes
                containsAny(
                        goalLower,
                        "stack trace",
                        "exception",
                        "error log",
                        "debug",
                        "fix bug",
                        "null pointer",
                        "npe",
                        "runtime error",
                        "compile error",
                        "compilation error",
                        "broken code",
                        "regression",
                        "crash");

            case String s
            when AgentRoleEnum.CODER_ID.equals(s) ->
                // Brahma's core: writing new code, features, implementations
                containsAny(
                        goalLower,
                        "write code",
                        "implement",
                        "create",
                        "build",
                        "new feature",
                        "generate",
                        "develop",
                        "code from scratch");

            case String s
            when AgentRoleEnum.COORDINATOR_ID.equals(s) ->
                // Vishnu's core: scheduling, orchestration, session management
                containsAny(goalLower, "schedule", "cron", "orchestrat", "session", "coordinate", "delegat", "manage");

            default -> false;
        };
    }

    private static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
