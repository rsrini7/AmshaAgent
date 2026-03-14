package com.arcclaw.core.skill;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.arcclaw.core.reflex.AgentReflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Handles the {@code /skills} command.
 * <p>
 * Lists all currently loaded skills mapped into the {@link SkillRegistry}.
 */
@ApplicationScoped
public class SkillReflex implements AgentReflex {

    private static final Pattern SKILLS_CMD = Pattern.compile("^\\s*/skills\\s*$", Pattern.CASE_INSENSITIVE);

    @Inject
    SkillRegistry registry;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        return SKILLS_CMD.matcher(input).matches();
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        List<CompiledSkillEntry> activeSkills = registry.getAll();
        if (activeSkills.isEmpty()) {
            return "No skills are currently loaded.";
        }

        String list = activeSkills.stream()
            .map(e -> String.format("- **%s**: %s (Routes: %d)",
                e.definition().name(),
                e.definition().description().isBlank() ? "No description" : e.definition().description(),
                e.triggerPatterns().length))
            .collect(Collectors.joining("\n"));

        return "### Active Skills\n\n" + list;
    }
}
