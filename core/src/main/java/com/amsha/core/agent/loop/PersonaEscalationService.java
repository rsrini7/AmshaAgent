package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Cross-persona escalation service that detects when a persona hits its
 * capability boundary and automatically routes the task to a more suitable
 * specialist, then merges the result back.
 *
 * <p>Escalation Matrix:
 * <pre>
 *   Source → Target           | Trigger
 *   ───────────────────────────────────────────────────────────
 *   Brahma → Saraswati        | Unknown API/dependency, needs docs
 *   Brahma → Brihaspati       | Architecture/trade-off decision
 *   Brahma → Shiva            | Bug diagnosis (superseded by auto-heal)
 *   Shiva → Saraswati         | Obscure error needing external research
 *   Shiva → Brahma            | Fundamental flaw needing rewrite
 *   Shiva → Ganesha           | Complex logic, recursive debugging (son of Shiva)
 *   Shiva → Kartikeya         | Security vulnerability analysis (son of Shiva)
 *   Saraswati → Brahma        | Research complete, needs implementation
 *   Brihaspati → Brahma       | Architecture decided, needs coding
 *   Brihaspati → Saraswati    | Needs external research/data
 *   Dattatreya → *            | Conflicting outputs need re-specialization
 *   Ganesha → Brahma          | Solution needs implementation
 *   Ganesha → Saraswati       | Problem needs research
 *   Ganesha → Shiva           | Logic reveals bug
 *   Ganesha → Kartikeya       | Security issue found in logic (brother)
 *   Kartikeya → Shiva         | Vulnerability needs fix
 *   Kartikeya → Saraswati     | CVE/security advisory research
 *   Kartikeya → Ganesha       | Complex logic in security (brother)
 *   Lakshmi → Shiva           | Error needs code fix
 *   Lakshmi → Saraswati       | Best practices research
 *   Lakshmi → Vishnu          | Budget/cost coordination (wife→husband)
 *   Durga → Shiva             | Policy violation needs fix
 *   Durga → Saraswati         | Compliance/regulation research
 *   Kali → Shiva              | Cleanup code needed
 *   Kali → Saraswati          | Lifecycle best practices
 *   Parvati → Shiva           | Bug causing frustration
 *   Parvati → Saraswati       | Documentation gap
 * </pre>
 *
 * <p>Mythological Sub-Agent Hierarchy:
 * <pre>
 *   VISHNU'S FAMILY:
 *     - Vishnu (Coordinator) - husband
 *     - Lakshmi (Healer) - wife (wealth/budget management)
 *     - Garuda (Tool) - divine vahana for delegation
 *
 *   SHIVA'S FAMILY:
 *     - Shiva (Debugger) - father/husband
 *     - Parvati (Nurturer) - wife (empathy/tone)
 *     - Ganesha (Optimizer) - son (logic/obstacles)
 *     - Kartikeya (Sentinel) - son (security/defense)
 *     - Durga (Protector) - form of Parvati (policy/guardrails)
 *     - Kali (Purifier) - form of Parvati (cleanup/lifecycle)
 *
 *   SPECIAL ESCALATION PATHS (mythological):
 *     - Shiva → Ganesha (father → son: complex logic)
 *     - Shiva → Kartikeya (father → son: security)
 *     - Ganesha → Kartikeya (brother → brother: security in logic)
 *     - Kartikeya → Ganesha (brother → brother: logic in security)
 *     - Lakshmi → Vishnu (wife → husband: budget coordination)
 *     - Parvati → Shiva (wife → husband: technical debugging)
 * </pre>
 *
 * <p>Flow:
 * <pre>
 *   Source persona → tool call → FAIL / knowledge gap detected
 *     ↓
 *   PersonaEscalationService intercepts
 *     ↓
 *   Determines target persona via escalation matrix
 *     ↓
 *   Isolated sub-session with :escalate: sentinel
 *     ↓
 *   Target persona handles and returns result
 *     ↓
 *   Result merged back into source persona's context
 * </pre>
 *
 * <p>Design rules:
 * <ul>
 *   <li>Enabled by default via amsha.agent.escalation.enabled=true</li>
 *   <li>Max escalation depth: 2 (prevents infinite routing loops)</li>
 *   <li>Isolated sub-sessions with :escalate: sentinel</li>
 *   <li>Opt-out via config: amsha.agent.escalation.enabled=false</li>
 * </ul>
 */
@ApplicationScoped
public class PersonaEscalationService {

    private static final String STR = "\n```\n\n";

    private static final Logger log = Logger.getLogger(PersonaEscalationService.class);

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    Instance<AgentLoop> agentLoop;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GroupConsultationService groupConsultationService;

    @ConfigProperty(name = "amsha.agent.escalation.enabled", defaultValue = "true")
    boolean escalationEnabled;

    @ConfigProperty(name = "amsha.agent.escalation.max-depth", defaultValue = "2")
    int maxEscalationDepth;

    /**
     * Represents the result of an escalation attempt.
     */
    public record EscalationResult(
            String targetPersona, String result, int depthUsed, boolean success, String errorIfFailed) {
        public static EscalationResult success(String target, String result, int depth) {
            return new EscalationResult(target, result, depth, true, null);
        }

        public static EscalationResult failure(String error) {
            return new EscalationResult(null, null, 0, false, error);
        }
    }

    /**
     * Checks if escalation should be attempted for this failure.
     */
    public boolean shouldAttemptEscalation(String agentRole, String toolName, ToolResponse response) {
        if (!escalationEnabled) {
            return false;
        }
        if (response instanceof ToolResponse.ToolFailure failure) {
            return !failure.isInfraFailure();
        }
        return false;
    }

    /**
     * Determines if escalation is needed based on error content patterns.
     * Returns the target persona ID if escalation is warranted, null otherwise.
     *
     * <p>Group-aware escalation: when a role fails, prefer escalating to a
     * sibling within the same group (Loka) before reaching outside. This models
     * the mythological pattern where family members help each other first.
     */
    public String determineEscalationTarget(String sourceRole, String errorOutput, String originalGoal) {
        if (sourceRole == null || errorOutput == null) {
            return null;
        }

        String errorLower = errorOutput.toLowerCase(Locale.ROOT);
        String goalLower = originalGoal != null ? originalGoal.toLowerCase(Locale.ROOT) : "";

        // 1. Role-specific escalation rules (highest priority — preserves existing behavior)
        String target = escalateByRole(sourceRole, errorLower, goalLower);
        if (target != null) return target;

        // 2. New persona escalation patterns (includes group-aware fallback via tryIntraGroupEscalation)
        target = EscalationPatterns.determineNewPersonaEscalation(sourceRole, goalLower);
        if (target != null) return target;

        // 3. Sandbox code errors → Shiva (universal fallback)
        if (matchesSandboxCodeError(errorLower)) {
            return AgentRoleEnum.DEBUGGER.toScopedName();
        }

        return null;
    }

    private String escalateByRole(String sourceRole, String errorLower, String goalLower) {
        if (AgentRoleEnum.CODER.toScopedName().equals(sourceRole)) {
            return escalateFromCoder(errorLower, goalLower);
        }
        if (AgentRoleEnum.DEBUGGER.toScopedName().equals(sourceRole)) {
            return escalateFromDebugger(errorLower, goalLower);
        }
        if (AgentRoleEnum.ARCHITECT.toScopedName().equals(sourceRole)) {
            return escalateFromArchitect(goalLower);
        }
        if (AgentRoleEnum.RESEARCHER.toScopedName().equals(sourceRole)) {
            return escalateFromResearcher(goalLower);
        }
        return null;
    }

    private String escalateFromCoder(String errorLower, String goalLower) {
        if (matchesAny(errorLower, goalLower, EscalationPatterns.SANDBOX_FAILURE_PATTERNS)) {
            return AgentRoleEnum.DEBUGGER.toScopedName();
        }
        if (matchesAny(errorLower, goalLower, EscalationPatterns.UNKNOWN_API_PATTERNS)) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        }
        if (matchesAny(errorLower, goalLower, EscalationPatterns.ARCHITECTURE_PATTERNS)) {
            return AgentRoleEnum.ARCHITECT.toScopedName();
        }
        return null;
    }

    private String escalateFromDebugger(String errorLower, String goalLower) {
        if (matchesAny(errorLower, goalLower, EscalationPatterns.RESEARCH_NEEDED_PATTERNS)) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        }
        if (matchesAny(errorLower, goalLower, EscalationPatterns.REWRITE_NEEDED_PATTERNS)) {
            return AgentRoleEnum.CODER.toScopedName();
        }
        if (EscalationPatterns.containsAny(goalLower, "recursive", "complex logic", "algorithm", "obstacle")) {
            return AgentRoleEnum.OPTIMIZER.toScopedName();
        }
        if (EscalationPatterns.containsAny(goalLower, "security", "vulnerability", "injection", "malicious")) {
            return AgentRoleEnum.SENTINEL.toScopedName();
        }
        return null;
    }

    private String escalateFromArchitect(String goalLower) {
        if (EscalationPatterns.containsAny(goalLower, "implement", "write code", "generate code", "create the")) {
            return AgentRoleEnum.CODER.toScopedName();
        }
        if (EscalationPatterns.containsAny(goalLower, "research", "find information", "look up", "documentation")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        }
        return null;
    }

    private String escalateFromResearcher(String goalLower) {
        if (EscalationPatterns.containsAny(goalLower, "implement", "write code", "execute", "run")) {
            return AgentRoleEnum.CODER.toScopedName();
        }
        return null;
    }

    /**
     * Returns true if the error output indicates a sandbox code error that Shiva can fix.
     * Covers compilation errors, missing methods, syntax errors, and runtime exceptions.
     */
    private static boolean matchesSandboxCodeError(String errorLower) {
        return errorLower.contains("can't find")
                || errorLower.contains("cannot find")
                || errorLower.contains("compilation error")
                || errorLower.contains("compile error")
                || errorLower.contains("syntax error")
                || errorLower.contains("classnotfound")
                || errorLower.contains("nosuchmethod")
                || errorLower.contains("nullpointerexception")
                || errorLower.contains("arrayindexoutofbounds")
                || errorLower.contains("classcastexception")
                || errorLower.contains("illegalargument")
                || (errorLower.contains("error:") && errorLower.contains(".java"))
                || errorLower.contains("exception in thread");
    }

    /**
     * Attempts escalation by delegating to a more suitable persona.
     */
    public EscalationResult attemptEscalation(
            String outerSessionId,
            String userId,
            String chatId,
            String sourceRole,
            String targetRole,
            String errorOutput,
            String originalGoal,
            String originalCode,
            String language,
            String channelType,
            String channelPlatformId) {
        return attemptEscalation(
                outerSessionId,
                userId,
                chatId,
                sourceRole,
                targetRole,
                errorOutput,
                originalGoal,
                originalCode,
                language,
                channelType,
                channelPlatformId,
                1);
    }

    EscalationResult attemptEscalation(
            String outerSessionId,
            String userId,
            String chatId,
            String sourceRole,
            String targetRole,
            String errorOutput,
            String originalGoal,
            String originalCode,
            String language,
            String channelType,
            String channelPlatformId,
            int depth) {
        return attemptEscalation(
                outerSessionId,
                userId,
                chatId,
                sourceRole,
                targetRole,
                errorOutput,
                originalGoal,
                originalCode,
                language,
                channelType,
                channelPlatformId,
                depth,
                new ArrayList<>());
    }

    EscalationResult attemptEscalation(
            String outerSessionId,
            String userId,
            String chatId,
            String sourceRole,
            String targetRole,
            String errorOutput,
            String originalGoal,
            String originalCode,
            String language,
            String channelType,
            String channelPlatformId,
            int depth,
            List<String> escalationChain) {

        if (depth > maxEscalationDepth) {
            log.warnf(
                    "[PersonaEscalation] Max depth %d reached for session %s, aborting",
                    maxEscalationDepth, outerSessionId);
            return EscalationResult.failure("Max escalation depth reached");
        }

        // Circuit breaker: detect A→B→A→B ping-pong
        String pair = sourceRole + "->" + targetRole;
        escalationChain.add(pair);
        if (isPingPong(escalationChain)) {
            log.warnf(
                    "[PersonaEscalation] Ping-pong detected: %s — aborting escalation for session %s",
                    escalationChain, outerSessionId);
            return EscalationResult.failure("Escalation ping-pong detected: " + escalationChain);
        }

        AgentRoleEnum targetEnum;
        try {
            targetEnum = AgentRoleEnum.fromString(targetRole);
        } catch (IllegalArgumentException _) {
            log.warnf("[PersonaEscalation] Unknown target persona '%s' for session %s", targetRole, outerSessionId);
            return EscalationResult.failure("Unknown target persona: " + targetRole);
        }

        String innerSessionId = outerSessionId + AgentSentinels.ESCALATION_SESSION_SENTINEL + sourceRole + ":"
                + targetRole + ":" + System.currentTimeMillis();

        String escalationGoal =
                buildEscalationGoal(sourceRole, targetRole, errorOutput, originalGoal, originalCode, language);

        log.infof(
                "[PersonaEscalation] Escalating %s → %s (depth=%d) session=%s",
                sourceRole, targetRole, depth, innerSessionId);

        AgentResult result;
        try {
            int maxIterations = targetEnum.definition().maxIterations();
            result = WorkspaceContext.run(
                    innerSessionId,
                    userId,
                    chatId,
                    AutonomyLevel.FULL,
                    targetEnum.toScopedName(),
                    channelType,
                    channelPlatformId,
                    () -> agentLoop
                            .get()
                            .run(
                                    innerSessionId,
                                    targetEnum.toScopedName(),
                                    escalationGoal,
                                    reasoningEngine,
                                    maxIterations));
        } catch (Exception e) {
            log.errorf("[PersonaEscalation] Delegation to %s failed: %s", targetRole, LogUtil.rootMessage(e));
            return EscalationResult.failure("Escalation delegation failed: " + e.getMessage());
        }

        if (result instanceof AgentResult.Finished f && f.answer() != null) {
            log.infof(
                    "[PersonaEscalation] Escalation to %s succeeded (depth=%d) for session %s",
                    targetRole, depth, outerSessionId);

            // Check if the target also needs escalation
            if (f.answer() != null && f.answer().contains("I cannot") && depth < maxEscalationDepth) {
                String secondaryTarget = determineEscalationTarget(targetRole, f.answer(), escalationGoal);
                if (secondaryTarget != null && !secondaryTarget.equals(sourceRole)) {
                    log.infof("[PersonaEscalation] %s needs further escalation to %s", targetRole, secondaryTarget);
                    return attemptEscalation(
                            outerSessionId,
                            userId,
                            chatId,
                            targetRole,
                            secondaryTarget,
                            f.answer(),
                            escalationGoal,
                            originalCode,
                            language,
                            channelType,
                            channelPlatformId,
                            depth + 1,
                            escalationChain);
                }
            }

            return EscalationResult.success(targetRole, f.answer(), depth);
        }

        String errorMsg = formatAgentResultError(result);
        return EscalationResult.failure(errorMsg);
    }

    String buildEscalationGoal(
            String sourceRole,
            String targetRole,
            String errorOutput,
            String originalGoal,
            String originalCode,
            String language) {
        StringBuilder goal = new StringBuilder();

        String sourceDisplay = personaDisplayName(sourceRole);
        String targetDisplay = personaDisplayName(targetRole);

        goal.append("ESCALATION FROM ")
                .append(sourceDisplay)
                .append(": ")
                .append(targetDisplay)
                .append(", the previous agent could not handle this task.\n\n")
                .append("## ORIGINAL GOAL:\n```\n")
                .append(originalGoal != null && !originalGoal.isBlank() ? originalGoal : "N/A")
                .append(STR);

        if (errorOutput != null && !errorOutput.isBlank()) {
            goal.append("## ERROR/OUTPUT FROM ")
                    .append(sourceDisplay)
                    .append(":\n```\n")
                    .append(truncate(errorOutput, 4000))
                    .append(STR);
        }

        if (originalCode != null && !originalCode.isBlank()) {
            goal.append("## RELEVANT CODE:\n```")
                    .append(language != null ? language : "text")
                    .append("\n")
                    .append(originalCode)
                    .append(STR);
        }

        goal.append("## YOUR TASK:\n")
                .append("You are the ")
                .append(targetDisplay)
                .append(". ")
                .append(sourceDisplay)
                .append(" encountered a problem and escalated to you.\n")
                .append("1. Analyze the situation\n")
                .append("2. Provide a complete solution within your capabilities\n")
                .append("3. If you also cannot handle this, state clearly what you need\n\n")
                .append("IMPORTANT: Provide your best answer. Do not delegate further unless absolutely necessary.");

        return goal.toString();
    }

    private String personaDisplayName(String role) {
        return switch (role) {
            case "coder" -> AgentRoleEnum.CODER.definition().displayName();
            case "debugger" -> AgentRoleEnum.DEBUGGER.definition().displayName();
            case "researcher" -> AgentRoleEnum.RESEARCHER.definition().displayName();
            case "architect" -> AgentRoleEnum.ARCHITECT.definition().displayName();
            case "coordinator" -> AgentRoleEnum.COORDINATOR.definition().displayName();
            case "synthesizer" -> AgentRoleEnum.SYNTHESIZER.definition().displayName();
            case "optimizer" -> AgentRoleEnum.OPTIMIZER.definition().displayName();
            case "sentinel" -> AgentRoleEnum.SENTINEL.definition().displayName();
            case "healer" -> AgentRoleEnum.HEALER.definition().displayName();
            case "protector" -> AgentRoleEnum.PROTECTOR.definition().displayName();
            case "purifier" -> AgentRoleEnum.PURIFIER.definition().displayName();
            case "nurturer" -> AgentRoleEnum.NURTURER.definition().displayName();
            default -> role;
        };
    }

    private boolean matchesAny(String errorLower, String goalLower, Set<String> patterns) {
        for (String pattern : patterns) {
            if (errorLower.contains(pattern) || goalLower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... [truncated]";
    }

    /**
     * Detects A→B→A→B ping-pong patterns in the escalation chain.
     * Returns true if the last 4 entries form a repeating pair.
     */
    private boolean isPingPong(List<String> chain) {
        if (chain.size() < 4) return false;
        String a = chain.get(chain.size() - 4);
        String b = chain.get(chain.size() - 3);
        String c = chain.get(chain.size() - 2);
        String d = chain.get(chain.size() - 1);
        return a.equals(c) && b.equals(d);
    }

    String formatAgentResultError(AgentResult result) {
        return switch (result) {
            case AgentResult.Failed f -> f.reason();
            case AgentResult.LoopExceeded l -> "Agent exceeded max iterations (" + l.maxIterations() + ")";
            case AgentResult.AwaitingApproval a -> "Agent requires human approval for tool: " + a.toolName();
            case AgentResult.AwaitingRetry r -> "Agent hit rate limit, retry after " + (r.retryAfterMs() / 1000) + "s";
            default -> "Unknown agent result: " + result.getClass().getSimpleName();
        };
    }

    // ── Group Consultation ────────────────────────────────────────────

    /**
     * Performs group consultation triage for a task arriving at a group lead.
     * Returns triage result indicating whether the lead should self-handle
     * or consult family members.
     *
     * @param leadRoleId The lead's role ID
     * @param goal       The task description
     * @return Triage result with recommendation
     */
    public GroupConsultationService.TriageResult triageGroupConsultation(String leadRoleId, String goal) {
        return groupConsultationService.triage(leadRoleId, goal);
    }

    /**
     * Checks if a role is a group lead who can consult family members.
     */
    public boolean isGroupLead(String roleId) {
        return groupConsultationService.isGroupLead(roleId);
    }
}
