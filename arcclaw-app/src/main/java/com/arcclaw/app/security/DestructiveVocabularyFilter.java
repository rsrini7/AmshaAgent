package com.arcclaw.app.security;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filters agent routing based on destructive vocabulary.
 * <p>
 * If a message routed to a tool-capable role (non-empty capabilities)
 * contains destructive keywords, this filter overrides the role to
 * 'coordinator' (which typically triggers verification or safe fallback).
 */
@ApplicationScoped
public class DestructiveVocabularyFilter {

    private static final Logger Log = Logger.getLogger(DestructiveVocabularyFilter.class);

    // Single-word destructive tokens — word boundary anchoring is reliable here
    private static final Pattern DESTRUCTIVE_WORDS = Pattern.compile(
            "(?i)\\b(delete|purge|drop|chmod|truncate|wipe|erase|destroy|archive|rename)\\b"
    );

    // rm -rf variants — word boundary unreliable for multi-word tokens
    // Match: rm-rf, rm -rf, rm  -rf, rm\t-rf, rmrf (no separator)
    private static final Pattern RM_RF_PATTERN = Pattern.compile(
            "(?i)\\brm\\s*-?\\s*rf\\b"
    );

    private static final Set<AgentRoleEnum> EXEMPT_ROLES = Set.of(AgentRoleEnum.DEBUGGER);

    /**
     * Applies the filter logic.
     *
     * @param role         The role name (e.g., "researcher").
     * @param userMessage  The raw user message.
     * @return The filtered role (either the original or "coordinator").
     */
    public String filter(String role, String userMessage) {
        AgentRoleEnum resolvedRole;
        try {
            resolvedRole = AgentRoleEnum.fromString(role);
        } catch (IllegalArgumentException _) {
            // Fallback for unknown roles — cannot safely determine capability
            return role;
        }

        // Rule: if the role has ANY tool capabilities, it must pass the destructive check.
        if (!EXEMPT_ROLES.contains(resolvedRole)
            && !resolvedRole.definition().capabilities().isEmpty() &&
                (DESTRUCTIVE_WORDS.matcher(userMessage).find()
                        || RM_RF_PATTERN.matcher(userMessage).find())) {
            Log.warnf("Destructive vocabulary detected in message routed to '%s' (%s). " +
                    "Overriding to 'coordinator' with no-tool mode.",
                    role, resolvedRole.toScopedName());
            return "coordinator";
        }
        return role;
    }
}
