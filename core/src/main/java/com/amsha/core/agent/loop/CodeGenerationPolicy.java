package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.AgentRoleEnum;

import org.jboss.logging.Logger;

/**
 * Enforces the code-generation capability policy.
 *
 * <p>Only roles whose {@link com.amsha.core.agent.role.AgentRoleDefinition}
 * declares {@code canGenerateCode = true} are permitted to emit code blocks in
 * their FINISH answers. All other roles receive a polite refusal message that
 * redirects the user to the CODER role.
 *
 * <p><strong>§4.10:</strong> This policy is purely declarative - it reads the
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
     * optional language tag).
     *
     * <p><strong>§9.1:</strong> {@code static final} - never compiled per-call.
     */
    static final Pattern PROSE_CODE_PATTERN = Pattern.compile("```[\\w]*\\n[\\s\\S]*?```", Pattern.MULTILINE);

    static final Pattern FENCE_HEADER_PATTERN = Pattern.compile("^```([\\w-]*)\\R");

    /**
     * Keywords and syntax structures that strongly suggest the block is actual source code,
     * not just formatted configuration, structured data, or version numbers.
     */
    static final Pattern SOURCE_CODE_INDICATOR = Pattern.compile(
            "(?m)(?:^\\s*(?:public|private|protected)\\s+(?:class|interface|record|enum|static|final|abstract|void|int|long|double|boolean|String|var)|"
                    + "^\\s*(?:import|package)\\s+[\\w.]+;|"
                    + "^\\s*def\\s+\\w+\\s*\\(.*?\\):|"
                    + "^\\s*class\\s+\\w+\\s*[:{(]|"
                    + "^\\s*(?:async\\s+)?function\\s+\\w+\\s*\\(.*?\\)|"
                    + "^\\s*if\\s+__name__\\s*==\\s*['\"]__main__['\"]:|"
                    + "\\b(?:System\\.out\\.println|console\\.log)\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns {@code true} if the answer contains a generated code block.
     *
     * <p><strong>Refinement:</strong> For non-coder roles, we only block if the
     * code block is large (> 10 lines) OR contains source-code keywords.
     * This allows researchers to use small code blocks for formatting version
     * numbers or simple configuration snippets.
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
            if (stripped.startsWith("```")) return true;
            return PROSE_CODE_PATTERN.matcher(stripped).find();
        } else {
            Matcher m = PROSE_CODE_PATTERN.matcher(stripped);
            while (m.find()) {
                String block = m.group();
                if (looksLikeStructuredDataBlock(block)) {
                    continue;
                }
                // If it's a long block or looks like source code, block it
                if (block.lines().count() > 10
                        || SOURCE_CODE_INDICATOR.matcher(block).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean looksLikeStructuredDataBlock(String block) {
        if (block == null || block.isBlank()) return false;

        Matcher headerMatcher = FENCE_HEADER_PATTERN.matcher(block);
        String language = headerMatcher.find() ? headerMatcher.group(1).toLowerCase(Locale.ROOT) : "";
        String body = block.replaceFirst("^```[\\w-]*\\R", "")
                .replaceFirst("\\R```\\s*$", "")
                .trim();

        if ("json".equals(language)
                || "yaml".equals(language)
                || "yml".equals(language)
                || "text".equals(language)
                || "markdown".equals(language)
                || "md".equals(language)) {
            return !SOURCE_CODE_INDICATOR.matcher(body).find();
        }

        boolean jsonLike = (body.startsWith("{") && body.endsWith("}")) || (body.startsWith("[") && body.endsWith("]"));
        return jsonLike && !SOURCE_CODE_INDICATOR.matcher(body).find();
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
            log.debugf("CodeGenerationPolicy: unknown role '%s' - treating as no-code", agentRole);
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
