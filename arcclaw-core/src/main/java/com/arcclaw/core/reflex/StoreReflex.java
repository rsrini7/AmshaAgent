package com.arcclaw.core.reflex;

import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.pipeline.MessageContext;
import org.jboss.logging.Logger;

/**
 * Handles the "store" and "remember" commands.
 * Implements code-side parsing for KEY=VALUE pairs to prevent LLM confusion
 * when values contain equal signs (e.g. JWT tokens).
 */
@ApplicationScoped
@ReflexPriority(500)
public class StoreReflex implements AgentReflex {

    private static final Logger log = Logger.getLogger(StoreReflex.class);

    // Matches commands like "store FOO=bar" or "remember my name is Alice"
    private static final Pattern STORE_REMEMBER_PATTERN =
            Pattern.compile("^(store|remember)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        return STORE_REMEMBER_PATTERN.matcher(input).find();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        var matcher = STORE_REMEMBER_PATTERN.matcher(input);
        if (!matcher.find()) {
            return new ReflexResult(Optional.empty(), input);
        }

        String content = matcher.group(2).trim();
        
        // Pre-parse KEY=VALUE pairs to ensure accurate label extraction
        int idx = content.indexOf('=');
        if (idx > 0) {
            String label = content.substring(0, idx).trim();
            String value = content.substring(idx + 1).trim();
            
            log.debugf("StoreReflex: pre-parsed label='%s' for session=%s", label, sessionId);
            
            // Re-format the input for the agent to be hyper-explicit,
            // preventing the LLM from trying to parse the equal sign itself.
            String structuredGoal = String.format("Store the following information with label \"%s\":\n\n%s", 
                label, value);
            
            return new ReflexResult(Optional.empty(), structuredGoal);
        }

        //  2 — Alias "remember X" to a structured storage goal
        return new ReflexResult(Optional.empty(), "Remember this information: " + content);
    }
}
