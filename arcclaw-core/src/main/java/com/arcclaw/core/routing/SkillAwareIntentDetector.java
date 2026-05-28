package com.arcclaw.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillRegistry;

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
                // Return dynamic role name syntax
                return AgentRoleEnum.SKILL_PREFIX + entry.definition().name();
            }
        }

        // No skill trigger match - fallback to expensive LLM classification
        return fallbackService.classify(userMessage);
    }
}
