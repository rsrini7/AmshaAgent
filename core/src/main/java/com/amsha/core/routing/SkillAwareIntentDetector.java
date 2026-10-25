package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    private static final Pattern SKILL_WORD_PATTERN = Pattern.compile("(?i)\\bskills?\\b");
    private static final Pattern NAME_TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int FUZZY_MIN_NAME_LENGTH = 10;
    private static final int FUZZY_MAX_DISTANCE = 1;

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
                return Optional.of(skillRole(entry, "trigger pattern"));
            }
        }

        List<CompiledSkillEntry> byNameLength = snapshot.stream()
                .sorted((e1, e2) -> Integer.compare(
                        e2.definition().name().length(), e1.definition().name().length()))
                .toList();

        for (CompiledSkillEntry entry : byNameLength) {
            if (matchesSkillName(userMessage, entry.definition().name())) {
                return Optional.of(skillRole(entry, "skill name"));
            }
        }

        List<CompiledSkillEntry> fuzzyMatches = byNameLength.stream()
                .filter(entry ->
                        matchesFuzzySkillName(userMessage, entry.definition().name()))
                .toList();
        if (fuzzyMatches.size() == 1) {
            return Optional.of(skillRole(fuzzyMatches.getFirst(), "fuzzy skill name"));
        }
        if (fuzzyMatches.size() > 1) {
            LOG.warnf(
                    "Ambiguous fuzzy skill match among %s; falling back instead of guessing",
                    fuzzyMatches.stream()
                            .map(entry -> entry.definition().name())
                            .toList());
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

    /**
     * Tolerates a single typo in a skill name when the user explicitly says
     * "skill" (for example "capture-knowledge-not skill" for
     * "capture-knowledge-note"). Matching stays opt-in via the word "skill" so
     * ordinary prose is not routed to a skill by accident.
     */
    private static boolean matchesFuzzySkillName(String userMessage, String skillName) {
        if (skillName == null || skillName.length() < FUZZY_MIN_NAME_LENGTH) {
            return false;
        }
        if (!SKILL_WORD_PATTERN.matcher(userMessage).find()) {
            return false;
        }

        List<String> tokens = Arrays.stream(NAME_TOKEN_SPLIT.split(userMessage.toLowerCase(Locale.ROOT)))
                .filter(token -> !token.isBlank())
                .toList();
        int skillIndex = tokens.indexOf("skill");
        if (skillIndex < 0) {
            skillIndex = tokens.indexOf("skills");
        }
        if (skillIndex < 0) {
            return false;
        }

        String normalizedName = skillName.toLowerCase(Locale.ROOT).replace('_', '-');
        int segmentCount = normalizedName.split("-").length;
        int[] candidateStarts = {skillIndex - segmentCount, skillIndex + 1};
        for (int start : candidateStarts) {
            if (start < 0 || start + segmentCount > tokens.size()) {
                continue;
            }
            String candidate = String.join("-", tokens.subList(start, start + segmentCount));
            if (Math.abs(candidate.length() - normalizedName.length()) <= FUZZY_MAX_DISTANCE
                    && editDistance(candidate, normalizedName) <= FUZZY_MAX_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1), previous[column - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String skillRole(CompiledSkillEntry entry, String matchType) {
        String role = AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
        LOG.infof("Skill intent matched via %s: role=%s", matchType, role);
        return role;
    }
}
