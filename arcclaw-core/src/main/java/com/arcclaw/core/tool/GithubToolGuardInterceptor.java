package com.arcclaw.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.ToolInvocationGuard;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.common.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.jboss.logging.Logger;

@ApplicationScoped
@InterceptorPriority(100)
public class GithubToolGuardInterceptor implements ToolGuardInterceptor {

    private static final Logger log = Logger.getLogger(GithubToolGuardInterceptor.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    StatefulAgentStore agentStore;

    private static final Pattern GITHUB_TRIGGER_PATTERN = Pattern.compile(
            "(?i)\\b(repo|repository|github|gh|fetch|gist|gists|clone|" +
                    "pull\\s+request|pr|get|show|describe|description|desc|" +
                    "star|fork|issue|commit|branch|tag|readme|release)\\b");

    @Override
    public Set<String> handledToolNames() {
        return Set.of(GithubTool.NAME);
    }

    @Override
    public Optional<ToolInvocationGuard.GuardDecision> evaluate(
            String sessionId, String agentRole, AutonomyLevel level, String toolName,
            Map<String, Object> params, List<ReasoningStep> history,
            String goal, List<CompiledSkillEntry> skillSnapshot) {

        // 1.1 CROSS-LOOP DEDUP (GitHub only)
        try {
            List<ChatMessage> memory = chatMemoryStore.getMessages(sessionId);
            if (memory != null) {
                String currentArgsJson = objectMapper.writeValueAsString(params);
                String matchPrefix = currentArgsJson.substring(0, Math.min(80, currentArgsJson.length()));
                for (ChatMessage msg : memory) {
                    if (msg instanceof AiMessage ai && ai.text() != null && ai.text().contains(matchPrefix)) {
                        log.debugf("[DEDUP-GUARD] Cross-loop github match for session %s. Allowing reuse.", sessionId);
                        return Optional.of(new ToolInvocationGuard.GuardDecision.Allow());
                    }
                }
            }
        } catch (Exception e) {
            log.warnf("Cross-loop dedup failed for github in session %s: %s", sessionId, LogUtil.rootMessage(e));
        }

        // 2. GITHUB INTENT GUARD
        boolean isPreApproved = agentStore.load(sessionId, agentRole)
                .filter(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL
                        && toolName.equals(cp.getPendingToolName()))
                .isPresent();

        boolean isSentinelGoal = AgentSentinels.GOAL_TOOL_APPROVED.equals(goal)
                || AgentSentinels.GOAL_RESUME_APPROVED.equals(goal);

        boolean hasRecentGithubContext = history.stream()
                .anyMatch(s -> GithubTool.NAME.equals(s.action())
                        && s.observation() != null
                        && !s.observation().isBlank());

        if (!hasRecentGithubContext) {
            try {
                List<ChatMessage> memory = chatMemoryStore.getMessages(sessionId);
                if (memory != null) {
                    hasRecentGithubContext = memory.stream().anyMatch(msg ->
                            msg instanceof ToolExecutionResultMessage term
                                    && GithubTool.NAME.equals(term.toolName())
                            || msg instanceof AiMessage ai
                                    && ai.text() != null
                                    && GITHUB_TRIGGER_PATTERN.matcher(ai.text()).find());
                }
            } catch (Exception e) {
                log.debugf("Cross-turn github context check failed for session %s: %s",
                        sessionId, LogUtil.rootMessage(e));
            }
        }

        if (!isPreApproved
                && !isSentinelGoal
                && !hasRecentGithubContext
                && !GITHUB_TRIGGER_PATTERN.matcher(goal).find()) {
            String guardObs = "GitHub tool blocked: the goal contains no GitHub repository or resource URL. " +
                    "If the goal contains a Java class reference (e.g. 'com.example.OrderService'), " +
                    "that is a class name, NOT a GitHub path. " +
                    "Analyze the stack trace or code snippet from the goal text directly in plain text. " +
                    "FINISH with your analysis now - do NOT output JSON and do NOT call any other tools.";
            log.warnf("[TOOL-INTENT-GUARD] github blocked session=%s goal='%s'",
                    sessionId, goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);
            return Optional.of(new ToolInvocationGuard.GuardDecision.Blocked(guardObs));
        }

        return Optional.empty(); // Pass through
    }
}
