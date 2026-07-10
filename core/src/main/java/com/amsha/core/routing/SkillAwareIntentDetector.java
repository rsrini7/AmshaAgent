package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.channel.api.IntentDetector;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillRegistry;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * An alternative intent classifier that sits in front of the default
 * {@link IntentDetectorService}.
 * <p>
 * Evaluates user input against the rapidly executable pre-compiled regex
 * trigger patterns
 * stored in the {@link SkillRegistry}. If a match is found, it bypasses the LLM
 * completely and routes directly to the mapped dynamic role ("skill:name").
 */
@ApplicationScoped
@Alternative
@Priority(1)
@Unremovable
public class SkillAwareIntentDetector implements IntentDetector {

    private static final Logger LOG = Logger.getLogger(SkillAwareIntentDetector.class);

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    IntentDetectorService fallbackService;

    @Override
    public String classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return fallbackService.classify(userMessage);
        }

        List<CompiledSkillEntry> snapshot = skillRegistry.currentSnapshot();
        for (CompiledSkillEntry entry : snapshot) {
            if (entry.matches(userMessage)) {
                return skillRole(entry, "trigger pattern", userMessage);
            }
        }

        // Second chance: name-based fuzzy match for skills with zero trigger patterns.
        // e.g. "use remote-browser to navigate ..." should route to skill:remote-browser
        // even when the skill has no regex routes defined.
        // Sort by name length descending so that longer/more specific names match first.
        List<CompiledSkillEntry> sortedByLength = snapshot.stream()
                .sorted((e1, e2) -> Integer.compare(
                        e2.definition().name().length(), e1.definition().name().length()))
                .toList();

        for (CompiledSkillEntry entry : sortedByLength) {
            String name = entry.definition().name();
            if (name != null && !name.isBlank()) {
                String[] segments = name.split("[-_]");
                String regexName = Arrays.stream(segments).map(Pattern::quote).collect(Collectors.joining("[\\s_-]+"));
                Pattern boundaryPattern = Pattern.compile("\\b" + regexName + "\\b", Pattern.CASE_INSENSITIVE);
                if (boundaryPattern.matcher(userMessage).find()) {
                    return skillRole(entry, "skill name", userMessage);
                }
            }
        }

        // No skill match - fallback to expensive LLM classification
        return fallbackService.classify(userMessage);
    }

    private static String skillRole(CompiledSkillEntry entry, String matchType, String userMessage) {
        String role = AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
        LOG.infof("Skill intent matched via %s: role=%s message='%s'", matchType, role, userMessage);
        return role;
    }
}
