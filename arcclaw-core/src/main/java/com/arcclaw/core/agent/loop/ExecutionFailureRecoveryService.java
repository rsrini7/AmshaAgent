package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Auto-heal service that redirects sandbox_exec failures to Shiva (debugger)
 * for self-correction before returning the error to the user.
 *
 * <p>Flow:
 * <pre>
 *   Brahma (coder) → sandbox_exec → FAIL
 *     ↓
 *   ExecutionFailureRecoveryService intercepts
 *     ↓
 *   Auto-delegate to Shiva (debugger) with:
 *     - Original code + language
 *     - Full error output
 *     ↓
 *   Shiva analyzes, fixes, returns corrected code
 *     ↓
 *   RecoveryService extracts fixed code from Shiva's response
 *     ↓
 *   Returns fixed code for re-execution
 * </pre>
 *
 * <p>Design rules:
 * <ul>
 *   <li>Only activates for sandbox_exec failures under coder role</li>
 *   <li>Max attempts configurable (default 2)</li>
 *   <li>Isolated sub-session with :heal: sentinel</li>
 *   <li>Shiva returns code only; Brahma remains the executor</li>
 *   <li>Opt-out via config: arcclaw.agent.auto-heal.enabled=false</li>
 * </ul>
 */
@ApplicationScoped
public class ExecutionFailureRecoveryService {

    private static final Logger log = Logger.getLogger(ExecutionFailureRecoveryService.class);

    // Code block extraction patterns: ```lang\n...\n``` or ```\n...\n```
    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:\\w+)?\\s*\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private static final Pattern SINGLE_LINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    Instance<AgentLoop> agentLoop;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.agent.auto-heal.enabled", defaultValue = "true")
    boolean autoHealEnabled;

    @ConfigProperty(name = "arcclaw.agent.auto-heal.max-attempts", defaultValue = "2")
    int maxAutoHealAttempts;

    /**
     * Represents a recovery result with the fixed code and attempt count.
     */
    public record RecoveryResult(String fixedCode, int attemptsUsed, boolean success, String errorIfFailed) {
        public static RecoveryResult success(String fixedCode, int attemptsUsed) {
            return new RecoveryResult(fixedCode, attemptsUsed, true, null);
        }

        public static RecoveryResult failure(String error) {
            return new RecoveryResult(null, 0, false, error);
        }
    }

    // Filesystem/environment errors that Shiva cannot fix (cross-platform: Linux, macOS, Windows)
    private static final Pattern NON_CODE_ERRORS = Pattern.compile(
            // Linux / POSIX
            "File name too long|ENAMETOOLONG|" + "Permission denied|EACCES|Operation not permitted|EPERM|"
                    + "No space left on device|ENOSPC|Read-only file system|EROFS|"
                    + "Connection refused|ECONNREFUSED|"
                    + "Cannot allocate memory|ENOMEM|Too many open files|EMFILE|ENFILE|"
                    + "No such file or directory|ENOENT|"
                    +
                    // macOS specific
                    "Operation not supported|"
                    +
                    // Windows
                    "The filename or extension is too long|"
                    + "Access is denied|Access denied|"
                    + "There is not enough space on the disk|"
                    + "The system cannot find the path specified|"
                    + "The file is being used by another process|"
                    + "The directory is not empty",
            Pattern.CASE_INSENSITIVE);

    /**
     * Checks if auto-heal should be attempted for this failure.
     */
    public boolean shouldAttemptRecovery(String agentRole, String toolName, ToolResponse response) {
        if (!autoHealEnabled) {
            return false;
        }
        if (!ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) {
            return false;
        }
        if (response instanceof ToolResponse.ToolFailure failure) {
            if (failure.isInfraFailure()) return false;
            // Skip non-code errors — Shiva can't fix filesystem/environment issues
            String output = failure.output();
            if (output != null && NON_CODE_ERRORS.matcher(output).find()) {
                log.infof("[AutoHeal] Skipping recovery for non-code error: %s", output);
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Attempts to recover from a sandbox execution failure by delegating to Shiva (debugger).
     *
     * @param outerSessionId the parent session ID
     * @param userId the user ID
     * @param chatId the chat ID
     * @param originalCode the code that failed
     * @param language the programming language
     * @param errorOutput the error output from sandbox
     * @param channelType optional channel type
     * @param channelPlatformId optional channel platform ID
     * @return RecoveryResult with fixed code or failure info
     */
    public RecoveryResult attemptRecovery(
            String outerSessionId,
            String userId,
            String chatId,
            String originalCode,
            String language,
            String errorOutput,
            String channelType,
            String channelPlatformId) {
        return attemptRecovery(
                outerSessionId,
                userId,
                chatId,
                originalCode,
                language,
                errorOutput,
                channelType,
                channelPlatformId,
                null);
    }

    /**
     * Attempts to recover from a sandbox execution failure by delegating to Shiva (debugger).
     * Accepts the goal context to provide Shiva with more information.
     */
    public RecoveryResult attemptRecovery(
            String outerSessionId,
            String userId,
            String chatId,
            String originalCode,
            String language,
            String errorOutput,
            String channelType,
            String channelPlatformId,
            String originalGoal) {
        return attemptRecovery(
                outerSessionId,
                userId,
                chatId,
                originalCode,
                language,
                errorOutput,
                channelType,
                channelPlatformId,
                originalGoal,
                maxAutoHealAttempts);
    }

    RecoveryResult attemptRecovery(
            String outerSessionId,
            String userId,
            String chatId,
            String originalCode,
            String language,
            String errorOutput,
            String channelType,
            String channelPlatformId,
            String originalGoal,
            int maxAttempts) {
        log.infof(
                "[AutoHeal] Starting recovery for session %s, language=%s, maxAttempts=%d",
                outerSessionId, language, maxAttempts);

        String previousCode = originalCode;
        String accumulatedErrors = errorOutput;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RecoveryResult result = singleHealAttempt(
                    outerSessionId,
                    userId,
                    chatId,
                    previousCode,
                    language,
                    accumulatedErrors,
                    channelType,
                    channelPlatformId,
                    attempt);

            if (result.success()) {
                log.infof("[AutoHeal] Recovery succeeded on attempt %d for session %s", attempt, outerSessionId);
                return result;
            }

            accumulatedErrors +=
                    "\n\n--- Previous fix attempt " + attempt + " also failed ---\n" + result.errorIfFailed();
            previousCode = extractCodeFromResponse(result.errorIfFailed());
        }

        log.warnf("[AutoHeal] All %d recovery attempts failed for session %s", maxAttempts, outerSessionId);
        return RecoveryResult.failure(accumulatedErrors);
    }

    private RecoveryResult singleHealAttempt(
            String outerSessionId,
            String userId,
            String chatId,
            String currentCode,
            String language,
            String errorOutput,
            String channelType,
            String channelPlatformId,
            int attempt) {
        String innerSessionId = outerSessionId + AgentSentinels.HEAL_SESSION_SENTINEL + "shiva:"
                + System.currentTimeMillis() + ":a" + attempt;

        String goal = buildHealGoal(currentCode, language, errorOutput, attempt);

        log.infof("[AutoHeal] Delegating to Shiva (attempt %d) session=%s", attempt, innerSessionId);

        AgentResult result;
        try {
            int maxIterations = AgentRoleEnum.DEBUGGER.definition().maxIterations();
            result = WorkspaceContext.run(
                    innerSessionId,
                    userId,
                    chatId,
                    AutonomyLevel.FULL,
                    AgentRoleEnum.DEBUGGER.toScopedName(),
                    channelType,
                    channelPlatformId,
                    () -> agentLoop
                            .get()
                            .run(
                                    innerSessionId,
                                    AgentRoleEnum.DEBUGGER.toScopedName(),
                                    goal,
                                    reasoningEngine,
                                    maxIterations));
        } catch (Exception e) {
            log.errorf("[AutoHeal] Shiva delegation failed: %s", LogUtil.rootMessage(e));
            return RecoveryResult.failure("Debug delegation failed: " + e.getMessage());
        }

        if (result instanceof AgentResult.Finished f && f.answer() != null) {
            String fixedCode = extractCodeFromResponse(f.answer());
            if (fixedCode != null && !fixedCode.isBlank()) {
                return RecoveryResult.success(fixedCode, attempt);
            }
            return RecoveryResult.failure("Shiva responded but no code was found in the response: " + f.answer());
        }

        String errorMsg = formatAgentResultError(result);
        return RecoveryResult.failure(errorMsg);
    }

    String buildHealGoal(String currentCode, String language, String errorOutput, int attempt) {
        StringBuilder goal = new StringBuilder();
        goal.append("AUTO-HEAL MODE (attempt ")
                .append(attempt)
                .append("): ")
                .append("The following ")
                .append(language)
                .append(" code failed to execute.\n\n")
                .append("## ERROR OUTPUT:\n```\n")
                .append(truncate(errorOutput, 4000))
                .append("\n```\n\n")
                .append("## ORIGINAL CODE:\n```")
                .append(language)
                .append("\n")
                .append(currentCode)
                .append("\n```\n\n")
                .append("## YOUR TASK:\n")
                .append("1. Analyze the error and identify the root cause\n")
                .append("2. Fix the code to resolve the error\n")
                .append("3. Return ONLY the corrected complete code in a single code block\n")
                .append("4. Do NOT include explanations, markdown text, or multiple code blocks\n")
                .append("5. The code block MUST be complete and ready to execute\n\n")
                .append("CRITICAL: Your entire response must be exactly one code block containing the fixed ")
                .append(language)
                .append(" code. Nothing else.");
        return goal.toString();
    }

    /**
     * Extracts code from an LLM response. Handles:
     * - Markdown fenced code blocks (```lang\n...\n```)
     * - Single backtick inline code
     * - Raw code if no markdown found
     */
    public String extractCodeFromResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // Try fenced code blocks first
        Matcher blockMatcher = CODE_BLOCK_PATTERN.matcher(response);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }

        // Try single backtick
        Matcher inlineMatcher = SINGLE_LINE_CODE_PATTERN.matcher(response);
        if (inlineMatcher.find()) {
            return inlineMatcher.group(1).trim();
        }

        // If response looks like it might be code itself (no conversational text),
        // return it as-is only if it's reasonably short and doesn't contain typical
        // conversational phrases
        String trimmed = response.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.contains("here is")
                && !lower.contains("here's")
                && !lower.contains("i've")
                && !lower.contains("i have")
                && !lower.contains("the fix")
                && !lower.contains("the issue")
                && !lower.contains("the error")
                && !lower.contains("the problem")
                && trimmed.length() < 5000) {
            return trimmed;
        }

        return null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... [truncated]";
    }

    String formatAgentResultError(AgentResult result) {
        return switch (result) {
            case AgentResult.Failed f -> f.reason();
            case AgentResult.LoopExceeded l -> "Shiva exceeded max iterations (" + l.maxIterations() + ")";
            case AgentResult.AwaitingApproval a -> "Shiva requires human approval for tool: " + a.toolName();
            case AgentResult.AwaitingRetry r -> "Shiva hit rate limit, retry after " + (r.retryAfterMs() / 1000) + "s";
            default -> "Unknown Shiva result: " + result.getClass().getSimpleName();
        };
    }
}
