package com.arcclaw.core.agent.loop;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import org.jboss.logging.Logger;

/**
 * Enforces the code-generation capability policy.
 *
 * <p>Only roles whose {@link com.arcclaw.core.agent.role.AgentRoleDefinition}
 * declares {@code canGenerateCode = true} are permitted to emit code blocks in
 * their FINISH answers. All other roles receive a polite refusal message that
 * redirects the user to the CODER role.
 *
 * <p><strong>§4.10:</strong> This policy is purely declarative — it reads the
 * role capability flag and never alters tool routing or skill-classification
 * order. It must not be confused with {@code SkillAwareIntentDetector}'s
 * skill-first guard (§4.3).
 *
 * <p>No persistence, no transactions, no CDI state.
 */
@ApplicationScoped
public class CodeGenerationPolicy {

    private static final Logger log = Logger.getLogger(CodeGenerationPolicy.class);

    /**
     * Detects whether the answer is a generated code block (fenced ``` with
     * optional language tag, or an indented code block of 4+ spaces).
     *
     * <p><strong>§9.1:</strong> {@code static final} — never compiled per-call.
     */
    static final Pattern PROSE_CODE_PATTERN =
            Pattern.compile("```[\\w]*\\n[\\s\\S]*?```|^( {4}|\\t).+", Pattern.MULTILINE);

    /**
     * Returns {@code true} if the answer contains a generated code block.
     *
     * <p><strong>Optimization:</strong> For coder roles, we use a fast-path
     * detection for fenced code blocks to ensure metrics and audit logs are
     * consistent. For non-coder roles, we only block if the full
     * {@link #PROSE_CODE_PATTERN} is matched.
     *
     * @param answer    the candidate FINISH answer
     * @param agentRole the canonical agent role
     * @return {@code true} if a fenced or indented code block is detected
     */
    public boolean isGeneratedCodeBlock(String answer, String agentRole) {
        if (answer == null || answer.isBlank()) return false;
        String stripped = answer.trim();

        boolean isCoder = canGenerateCode(agentRole);
        if (isCoder) {
            // Coder: detect code blocks faster for metrics/audit
            if (stripped.startsWith("```")) return true;
            return PROSE_CODE_PATTERN.matcher(stripped).find();
        } else {
            // Non-coder: only block on full prose-announced code patterns
            return PROSE_CODE_PATTERN.matcher(stripped).find();
        }
    }

    /**
     * Returns {@code true} if the given agent role is permitted to emit code
     * in its answers.
     *
     * @param agentRole the canonical agent role string (e.g. "coder",
     *                  "skill:code-reviewer")
     * @return {@code true} if the role's definition declares
     *         {@code canGenerateCode = true}
     */
    public boolean canGenerateCode(String agentRole) {
        try {
            return AgentRoleEnum.fromString(agentRole).definition().canGenerateCode();
        } catch (Exception e) {
            // Unknown/custom roles are treated as non-coders by default
            log.debugf("CodeGenerationPolicy: unknown role '%s' — treating as no-code", agentRole);
            return false;
        }
    }

    /**
     * Builds the refusal message shown to the user when a non-coder role
     * attempts to emit a code block.
     *
     * @param agentRole the canonical role that triggered the violation
     * @return a user-facing refusal string
     */
    public String buildRefusalMessage(String agentRole) {
        return "I'm " + agentRole + ". I don't generate code. Please ask "
                + AgentRoleEnum.CODER.definition().displayName()
                + " (" + AgentRoleEnum.CODER.id() + ") for code generation tasks.";
    }
}
