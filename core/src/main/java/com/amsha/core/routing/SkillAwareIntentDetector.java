package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.channel.api.IntentDetector;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillRegistry;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Skill-first intent detector.
 *
 * <p>Checks loaded skills before delegating to the LLM-backed detector. Exact
 * trigger-pattern matches win first; if no trigger matches, the skill name is
 * matched fuzzily so prompts like "use algorithmic art" and
 * "use skill:algorithmic-art" still route to the skill role.
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

        Optional<String> skillMatch = findSkillMatch(userMessage);
        if (skillMatch.isPresent()) {
            return skillMatch.get();
        }

        return fallbackService.classify(userMessage);
    }

    /**
     * Checks skill trigger patterns and fuzzy name matches only — no LLM
     * fallback. Use this when the caller already has a default role and
     * only wants to detect skill overrides.
     */
    public Optional<String> findSkillMatch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        List<CompiledSkillEntry> snapshot = skillRegistry.currentSnapshot();

        for (CompiledSkillEntry entry : snapshot) {
            if (entry.matches(userMessage)) {
                return Optional.of(skillRole(entry, "trigger pattern", userMessage));
            }
        }

        for (CompiledSkillEntry entry : snapshot.stream()
                .sorted((e1, e2) -> Integer.compare(
                        e2.definition().name().length(), e1.definition().name().length()))
                .toList()) {
            if (matchesSkillName(userMessage, entry.definition().name())) {
                return Optional.of(skillRole(entry, "skill name", userMessage));
            }
        }

        return Optional.empty();
    }

    private static boolean matchesSkillName(String userMessage, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }

        String[] segments = skillName.split("[-_]");
        String pattern = Arrays.stream(segments).map(Pattern::quote).collect(Collectors.joining("[\\s_-]+"));

        Pattern boundaryPattern = Pattern.compile("\\b" + pattern + "\\b", Pattern.CASE_INSENSITIVE);
        return boundaryPattern.matcher(userMessage).find();
    }

    private static String skillRole(CompiledSkillEntry entry, String matchType, String userMessage) {
        String role = AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
        LOG.infof("Skill intent matched via %s: role=%s message='%s'", matchType, role, userMessage);
        return role;
    }
}
