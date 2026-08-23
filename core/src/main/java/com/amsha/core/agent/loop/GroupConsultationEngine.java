package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.amsha.core.agent.role.AgentGroup;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownEvent;

/**
 * Engine for parallel multi-agent group consultation. When a group lead
 * receives a task with mode=group_consult, this engine spawns parallel
 * sub-agent sessions for each family member, collects their analyses,
 * and returns a structured result for the lead to synthesize.
 *
 * <h3>Flow:</h3>
 * <pre>
 *   GroupConsultationEngine.consultGroup():
 *     1. Triage → determine which members are needed
 *     2. Spawn parallel sub-agents for each member
 *     3. Collect results (with timeout)
 *     4. Return GroupConsultationResult with all member analyses
 * </pre>
 *
 * <h3>Cost Control:</h3>
 * <ul>
 *   <li>Max concurrent members: configurable (default 4)</li>
 *   <li>Per-member timeout: configurable (default 60s)</li>
 *   <li>Total budget: estimated from member count × max iterations</li>
 * </ul>
 */
@ApplicationScoped
public class GroupConsultationEngine {

    private static final Logger log = Logger.getLogger(GroupConsultationEngine.class);

    @Inject
    public AgentGroupRegistry groupRegistry;

    /**
     * Proxy-safe registry availability check. Callers holding an {@code @ApplicationScoped}
     * client proxy must NOT read the {@code groupRegistry} field directly — field access on a
     * proxy reads the proxy's own (always null) field, not the contextual instance.
     */
    public boolean hasGroupRegistry() {
        return groupRegistry != null;
    }

    /** Proxy-safe lookup of a group by id (delegates to the real registry instance). */
    public Optional<AgentGroup> findGroupById(String groupId) {
        return groupRegistry.findById(groupId);
    }

    @Inject
    GroupConsultationService consultationService;

    @ConfigProperty(name = "amsha.group.consult.max-concurrent", defaultValue = "4")
    int maxConcurrent;

    @ConfigProperty(name = "amsha.group.consult.timeout-seconds", defaultValue = "60")
    int timeoutSeconds;

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    void shutdown(@Observes ShutdownEvent ev) {
        log.info("[GROUP-ENGINE] Shutting down virtualThreadExecutor...");
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Result of a group consultation.
     *
     * @param groupId           The group that was consulted
     * @param memberResults     Map of member role ID → their analysis result
     * @param synthesizedAnswer Combined answer from all members (null if not yet synthesized)
     * @param membersConsulted  Number of members that were actually consulted
     * @param membersFailed     Number of members that failed or timed out
     */
    public record GroupConsultationResult(
            String groupId,
            Map<String, String> memberResults,
            String synthesizedAnswer,
            int membersConsulted,
            int membersFailed) {

        /**
         * Returns true if at least one member provided a useful result.
         */
        public boolean hasUsefulResults() {
            return membersConsulted > 0;
        }

        /**
         * Formats the result as a prompt for the lead to synthesize.
         */
        public String toSynthesisPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== GROUP CONSULTATION RESULTS ===\n\n");
            sb.append(String.format(
                    "Group: %s | Consulted: %d | Failed: %d\n\n", groupId, membersConsulted, membersFailed));

            for (Map.Entry<String, String> entry : memberResults.entrySet()) {
                sb.append(String.format("--- %s ---\n%s\n\n", entry.getKey(), entry.getValue()));
            }

            sb.append("=== SYNTHESIS INSTRUCTION ===\n");
            sb.append("You have received analyses from your family members above.\n");
            sb.append("Synthesize their findings into a unified response.\n");
            sb.append("If members disagree, note the conflict and provide your judgment.\n");
            sb.append("Do NOT repeat individual analyses — provide a single coherent answer.\n");

            return sb.toString();
        }
    }

    /**
     * Runs a parallel group consultation. Each member receives a sub-task
     * derived from the original goal, scoped to their expertise.
     *
     * @param groupId    The group to consult
     * @param goal       The original task
     * @param subTasks   Map of member role ID → their specific sub-task
     * @param agentRunner Function that runs a sub-agent and returns the result as a string
     * @return GroupConsultationResult with all member analyses
     */
    public GroupConsultationResult consultGroup(
            String groupId, String goal, Map<String, String> subTasks, SubAgentRunner agentRunner) {

        if (subTasks == null || subTasks.isEmpty()) {
            return new GroupConsultationResult(groupId, Map.of(), null, 0, 0);
        }

        // Limit concurrency
        int effectiveConcurrency = Math.min(subTasks.size(), maxConcurrent);
        log.infof(
                "[GROUP-ENGINE] Starting consultation: group=%s members=%d concurrency=%d",
                groupId, subTasks.size(), effectiveConcurrency);

        // Spawn parallel sub-agents
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : subTasks.entrySet()) {
            String memberId = entry.getKey();
            String memberGoal = entry.getValue();

            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    semaphore.acquire();
                                    try {
                                        return runMemberAgent(memberId, memberGoal, agentRunner);
                                    } finally {
                                        semaphore.release();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException("Sub-agent execution interrupted", e);
                                } catch (Exception e) {
                                    if (e instanceof RuntimeException re) throw re;
                                    throw new RuntimeException(e);
                                }
                            },
                            virtualThreadExecutor)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS);
            futures.put(memberId, future);
        }

        // Collect results with timeout
        Map<String, String> results = new LinkedHashMap<>();
        int consulted = 0;
        int failed = 0;

        for (Map.Entry<String, CompletableFuture<String>> entry : futures.entrySet()) {
            String memberId = entry.getKey();
            try {
                String result = entry.getValue().get();
                if (result != null && !result.isBlank()) {
                    results.put(memberId, result);
                    consulted++;
                    log.debugf("[GROUP-ENGINE] Member %s completed: %d chars", memberId, result.length());
                } else {
                    results.put(memberId, "[No output from " + memberId + "]");
                    failed++;
                }
            } catch (Exception e) {
                Throwable cause = e.getCause();
                String msg = cause != null ? cause.getMessage() : e.getMessage();
                results.put(memberId, "[" + memberId + " failed: " + msg + "]");
                failed++;
                log.warnf("[GROUP-ENGINE] Member %s failed: %s", memberId, msg);
            }
        }

        log.infof("[GROUP-ENGINE] Consultation complete: group=%s consulted=%d failed=%d", groupId, consulted, failed);

        return new GroupConsultationResult(groupId, results, null, consulted, failed);
    }

    /**
     * Builds sub-tasks for each member based on the group's domain and the original goal.
     * Each member receives a task scoped to their expertise.
     *
     * @return Map of memberId → subGoal. Empty map (via {@code Map.of()}) means the lead
     *         can self-handle — callers should check {@code isEmpty()} to decide
     *         whether to skip parallel consultation.
     */
    public Map<String, String> buildSubTasks(AgentGroup group, String goal) {
        Map<String, String> subTasks = new LinkedHashMap<>();
        GroupConsultationService.TriageResult triage = consultationService.triage(group.leadRoleId(), goal);

        if (triage.canSelfHandle()) {
            return Map.of();
        }

        for (String memberId : triage.recommendedMembers()) {
            String subGoal = buildMemberSubGoal(memberId, goal);
            subTasks.put(memberId, subGoal);
        }

        return subTasks;
    }

    // ── Private ────────────────────────────────────────────────────────────

    private String runMemberAgent(String memberId, String goal, SubAgentRunner runner) throws Exception {
        return runner.run(memberId, goal);
    }

    private String buildMemberSubGoal(String memberId, String originalGoal) {
        String expertise = getMemberExpertiseHint(memberId);
        return String.format(
                "You have been consulted as part of a family huddle. "
                        + "Analyze the following task from your domain expertise (%s). "
                        + "Be specific, actionable, and concise. "
                        + "If you identify issues outside your domain, note them briefly.\n\n"
                        + "TASK: %s",
                expertise, originalGoal);
    }

    private String getMemberExpertiseHint(String memberId) {
        return switch (memberId) {
            case String s
            when AgentRoleEnum.OPTIMIZER_ID.equals(s) -> "complex logic, recursion, algorithms, obstacle removal";
            case String s
            when AgentRoleEnum.SENTINEL_ID.equals(s) -> "security vulnerabilities, input validation, attack defense";
            case String s
            when AgentRoleEnum.NURTURER_ID.equals(s) -> "user empathy, tone analysis, sentiment management";
            case String s when AgentRoleEnum.PROTECTOR_ID.equals(s) -> "policy enforcement, guardrails, compliance";
            case String s
            when AgentRoleEnum.PURIFIER_ID.equals(s) -> "cleanup, lifecycle management, resource termination";
            case String s when AgentRoleEnum.RESEARCHER_ID.equals(s) -> "research, documentation, external knowledge";
            case String s
            when AgentRoleEnum.ARCHITECT_ID.equals(s) -> "system architecture, design patterns, trade-offs";
            case String s when AgentRoleEnum.HEALER_ID.equals(s) -> "error recovery, self-healing, budget optimization";
            default -> "general analysis";
        };
    }

    /**
     * Functional interface for running a sub-agent. Delegates to the actual
     * AgentLoop execution in production, or a mock in tests.
     */
    @FunctionalInterface
    public interface SubAgentRunner {
        String run(String memberId, String goal) throws Exception;
    }
}
