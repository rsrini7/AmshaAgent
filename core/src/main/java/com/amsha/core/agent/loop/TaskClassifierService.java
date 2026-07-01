package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.role.AgentGroup;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.jboss.logging.Logger;

/**
 * Classifies tasks into domain categories and determines which agent roles
 * are needed. Used by the coordinator to form dynamic groups when a task
 * doesn't fit neatly into a single static Loka.
 *
 * <h3>Domain Categories:</h3>
 * <pre>
 *   DEBUGGING    → debugger, optimizer
 *   SECURITY     → sentinel, debugger
 *   CODING       → coder
 *   RESEARCH     → researcher
 *   ARCHITECTURE → architect
 *   POLICY       → protector
 *   CLEANUP      → purifier
 *   HEALING      → healer
 *   EMPATHY      → nurturer
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   TaskClassifierService classifier = ...;
 *   var domains = classifier.classify("Migrate from MySQL to PostgreSQL with zero downtime");
 *   // → [CODING, ARCHITECTURE, POLICY]
 *   var roles = classifier.rolesForDomains(domains);
 *   // → [coder, architect, protector]
 * }</pre>
 */
@ApplicationScoped
public class TaskClassifierService {

    private static final Logger log = Logger.getLogger(TaskClassifierService.class);

    /**
     * Domain categories that a task can be classified into.
     */
    public enum TaskDomain {
        DEBUGGING,
        SECURITY,
        CODING,
        RESEARCH,
        ARCHITECTURE,
        POLICY,
        CLEANUP,
        HEALING,
        EMPATHY
    }

    private static final Map<TaskDomain, Set<String>> DOMAIN_KEYWORDS = Map.ofEntries(
            Map.entry(
                    TaskDomain.DEBUGGING,
                    Set.of(
                            "debug",
                            "fix bug",
                            "error",
                            "exception",
                            "stack trace",
                            "crash",
                            "bug",
                            "broken",
                            "null pointer",
                            "npe",
                            "regression",
                            "fault",
                            "defect")),
            Map.entry(
                    TaskDomain.SECURITY,
                    Set.of(
                            "security",
                            "vulnerability",
                            "injection",
                            "xss",
                            "ssrf",
                            "malicious",
                            "attack",
                            "exploit",
                            "cve",
                            "authentication",
                            "authorization",
                            "permission",
                            "access control",
                            "sanitiz",
                            "validation")),
            Map.entry(
                    TaskDomain.CODING,
                    Set.of(
                            "implement",
                            "write code",
                            "create",
                            "build",
                            "feature",
                            "generate",
                            "develop",
                            "new code",
                            "migration",
                            "migrate",
                            "refactor")),
            Map.entry(
                    TaskDomain.RESEARCH,
                    Set.of(
                            "research",
                            "documentation",
                            "api docs",
                            "look up",
                            "find information",
                            "reference",
                            "specification",
                            "library",
                            "dependency",
                            "version")),
            Map.entry(
                    TaskDomain.ARCHITECTURE,
                    Set.of(
                            "architecture",
                            "design pattern",
                            "trade-off",
                            "tradeoff",
                            "best practice",
                            "structure",
                            "scalability",
                            "performance",
                            "microservice",
                            "monolith")),
            Map.entry(
                    TaskDomain.POLICY,
                    Set.of(
                            "policy",
                            "guardrail",
                            "compliance",
                            "audit",
                            "regulation",
                            "constraint",
                            "governance",
                            "enforce",
                            "rule",
                            "standard")),
            Map.entry(
                    TaskDomain.CLEANUP,
                    Set.of(
                            "cleanup",
                            "lifecycle",
                            "garbage",
                            "memory leak",
                            "expire",
                            "purge",
                            "shutdown",
                            "terminate",
                            "destroy",
                            "resource management")),
            Map.entry(
                    TaskDomain.HEALING,
                    Set.of(
                            "recovery",
                            "self-heal",
                            "budget",
                            "cost",
                            "token",
                            "retry",
                            "circuit breaker",
                            "fallback",
                            "resilience",
                            "health",
                            "monitoring")),
            Map.entry(
                    TaskDomain.EMPATHY,
                    Set.of(
                            "tone",
                            "empathy",
                            "sentiment",
                            "user experience",
                            "frustration",
                            "communication",
                            "customer",
                            "end user",
                            "notification")));

    private static final Map<TaskDomain, String> DOMAIN_PRIMARY_ROLE = Map.ofEntries(
            Map.entry(TaskDomain.DEBUGGING, AgentRoleEnum.DEBUGGER_ID),
            Map.entry(TaskDomain.SECURITY, AgentRoleEnum.SENTINEL_ID),
            Map.entry(TaskDomain.CODING, AgentRoleEnum.CODER_ID),
            Map.entry(TaskDomain.RESEARCH, AgentRoleEnum.RESEARCHER_ID),
            Map.entry(TaskDomain.ARCHITECTURE, AgentRoleEnum.ARCHITECT_ID),
            Map.entry(TaskDomain.POLICY, AgentRoleEnum.PROTECTOR_ID),
            Map.entry(TaskDomain.CLEANUP, AgentRoleEnum.PURIFIER_ID),
            Map.entry(TaskDomain.HEALING, AgentRoleEnum.HEALER_ID),
            Map.entry(TaskDomain.EMPATHY, AgentRoleEnum.NURTURER_ID));

    public List<TaskDomain> classify(String goal) {
        if (goal == null || goal.isBlank()) {
            return List.of();
        }

        String goalLower = goal.toLowerCase(Locale.ROOT);

        record MatchedDomain(TaskDomain domain, int firstIndex) {}
        List<MatchedDomain> matches = new ArrayList<>();

        for (TaskDomain domain : TaskDomain.values()) {
            Set<String> keywords = DOMAIN_KEYWORDS.get(domain);
            if (keywords != null) {
                int minIndex = Integer.MAX_VALUE;
                for (String keyword : keywords) {
                    int index = goalLower.indexOf(keyword);
                    if (index >= 0 && index < minIndex) {
                        minIndex = index;
                    }
                }
                if (minIndex != Integer.MAX_VALUE) {
                    matches.add(new MatchedDomain(domain, minIndex));
                }
            }
        }

        // Sort matches by their first appearance index in the goal text
        List<MatchedDomain> sortedMatches = new ArrayList<>(matches);
        sortedMatches.sort((a, b) -> Integer.compare(a.firstIndex(), b.firstIndex()));

        List<TaskDomain> domains =
                sortedMatches.stream().map(MatchedDomain::domain).toList();
        log.debugf("[CLASSIFIER] Task classified into domains: %s", domains);
        return domains;
    }

    /**
     * Returns the primary role IDs for the given domains.
     *
     * @param domains The task domains
     * @return Ordered list of role IDs (deduplicated)
     */
    public List<String> rolesForDomains(List<TaskDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }

        List<String> roles = new ArrayList<>();
        for (TaskDomain domain : domains) {
            String role = DOMAIN_PRIMARY_ROLE.get(domain);
            if (role != null && !roles.contains(role)) {
                roles.add(role);
            }
        }
        return roles;
    }

    /**
     * Determines which role should lead a dynamic group for the given domains.
     * The lead is chosen based on the primary domain of the task.
     *
     * @param domains The task domains
     * @return The lead role ID, or null if no domains
     */
    public String determineLead(List<TaskDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return null;
        }

        // First domain is the primary one (most relevant keyword match)
        return DOMAIN_PRIMARY_ROLE.get(domains.get(0));
    }

    /**
     * Forms a dynamic group for the given task. The group includes all roles
     * needed for the task's domains, with the primary domain's role as lead.
     *
     * @param goal The task description
     * @return A dynamic AgentGroup, or null if the task is single-domain
     */
    public AgentGroup formDynamicGroup(String goal) {
        List<TaskDomain> domains = classify(goal);
        List<String> roles = rolesForDomains(domains);
        String lead = determineLead(domains);

        if (roles.size() <= 1 || lead == null) {
            // Single-domain — no group needed
            return null;
        }

        String groupId = "dynamic-"
                + String.join(
                        "-",
                        domains.stream()
                                .map(d -> d.name().toLowerCase(Locale.ROOT))
                                .toList());

        String domainDesc = String.join(
                ", ",
                domains.stream().map(d -> d.name().toLowerCase(Locale.ROOT)).toList());

        return new AgentGroup(
                groupId,
                "Dynamic Group — " + domainDesc,
                lead,
                roles,
                "Task spanning: " + domainDesc,
                "Lead triages and delegates to relevant members based on their expertise.");
    }
}
