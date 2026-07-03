package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.amsha.channel.api.IntentDetector;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillRegistry;

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
                return AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
            }
        }

        // Second chance: name-based match for skills with zero trigger patterns.
        // Uses word boundaries to prevent substring collisions (e.g. "go" won't match "google").
        // Sorted by name length descending so "remote-browser" is checked before "browser".
        List<CompiledSkillEntry> sorted = snapshot.stream()
                .sorted(Comparator.comparingInt(e -> -e.definition().name().length()))
                .toList();
        String lowerInput = userMessage.toLowerCase(Locale.ROOT);
        for (CompiledSkillEntry entry : sorted) {
            String rawName = entry.definition().name();
            if (rawName.isBlank()) continue;
            String name = rawName.toLowerCase(Locale.ROOT);
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            if (p.matcher(lowerInput).find()) {
                return AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
            }
        }

        // No skill match - fallback to expensive LLM classification
        return fallbackService.classify(userMessage);
    }
}
