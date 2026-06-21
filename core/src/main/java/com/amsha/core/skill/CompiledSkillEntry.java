package com.amsha.core.skill;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.RoleDefinition;

/**
 * Immutable runtime entry for a loaded skill inside the {@link SkillRegistry}.
 * <p>
 * Contains the parsed definition alongside pre-compiled regex patterns to ensure
 * fast evaluation during the message routing loop.
 *
 * @param definition The parsed skill metadata and prompt string.
 * @param triggerPatterns Immutable list of pre-compiled regex objects corresponding to the definition patterns.
 * @param roleDefinition The derived RoleDefinition that grants tool capabilities.
 * @param source The origin of this skill ("classpath" or "database").
 */
public record CompiledSkillEntry(
        SkillDefinition definition, List<Pattern> triggerPatterns, RoleDefinition roleDefinition, String source) {
    /**
     * Compact constructor enforcing null safety.
     */
    public CompiledSkillEntry {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(triggerPatterns, "triggerPatterns must not be null");
        Objects.requireNonNull(roleDefinition, "roleDefinition must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }

    /**
     * Checks if the user's input matches any of the skill's pre-compiled regular expressions.
     *
     * @param input The raw user input string.
     * @return true if there is at least one match, otherwise false.
     */
    public boolean matches(String input) {
        if (input == null || input.isBlank()) return false;
        for (Pattern p : triggerPatterns) {
            if (p.matcher(input).find()) return true;
        }
        return false;
    }
}
