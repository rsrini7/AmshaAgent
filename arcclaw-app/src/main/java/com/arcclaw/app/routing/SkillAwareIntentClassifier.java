package com.arcclaw.app.routing;

import java.util.Optional;

import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

/**
 * An alternative intent classifier that sits in front of the default {@link IntentClassifierService}.
 * <p>
 * Evaluates user input against the rapidly executable pre-compiled regex trigger patterns
 * stored in the {@link SkillRegistry}. If a match is found, it bypasses the LLM
 * completely and routes directly to the mapped dynamic role ("skill:name").
 */
@ApplicationScoped
@Alternative
@jakarta.annotation.Priority(1)
public class SkillAwareIntentClassifier implements IntentClassifier {

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    IntentClassifierService fallbackService;

    @Override
    public String classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return fallbackService.classify(userMessage);
        }

        Optional<CompiledSkillEntry> match = skillRegistry.findMatch(userMessage);
        if (match.isPresent()) {
            // Return dynamic role name syntax
            return "skill:" + match.get().definition().name();
        }

        // No skill trigger match — fallback to expensive LLM classification
        return fallbackService.classify(userMessage);
    }
}
