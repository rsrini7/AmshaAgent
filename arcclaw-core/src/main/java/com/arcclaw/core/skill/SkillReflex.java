package com.arcclaw.core.skill;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.core.reflex.ReflexPriority;
import com.arcclaw.core.reflex.ReflexResult;

/**
 * Handles the {@code /skills} reflex.
 * <p>
 * Lists all currently loaded skills mapped into the {@link SkillRegistry}.
 */
@ApplicationScoped
@ReflexPriority(500)
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
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        List<CompiledSkillEntry> activeSkills = registry.getAll();
        if (activeSkills.isEmpty()) {
            return ReflexResult.respond("No skills are currently loaded.", context);
        }

        String list = activeSkills.stream()
            .map(e -> String.format("- **%s**: %s (Routes: %d)",
                e.definition().name(),
                e.definition().description().isBlank() ? "No description" : e.definition().description(),
                e.triggerPatterns().length))
            .collect(Collectors.joining("\n"));

        return ReflexResult.respond("### Active Skills\n\n" + list, context);
    }
}
