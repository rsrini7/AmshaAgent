package com.arcclaw.core.security;

import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import org.jboss.logging.Logger;

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

    // Single-word destructive tokens - word boundary anchoring is reliable here
    private static final Pattern DESTRUCTIVE_WORDS = Pattern.compile(
            "(?i)\\b(delete|remove|purge|drop|chmod|truncate|wipe|erase|destroy|archive|rename)\\b"
    );

    // rm -rf variants - word boundary unreliable for multi-word tokens
    // Match: rm-rf, rm -rf, rm  -rf, rm\t-rf, rmrf (no separator)
    private static final Pattern RM_RF_PATTERN = Pattern.compile(
            "(?i)\\brm\\s*-?\\s*rf\\b"
    );

    private static final Set<AgentRoleEnum> EXEMPT_ROLES = Set.of(AgentRoleEnum.DEBUGGER);

    /**
     * Returns true if the message contains destructive vocabulary,
     * regardless of role. Used by IntentClassificationHandler to
     * short-circuit before agent dispatch.
     */
    public boolean isDestructive(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return DESTRUCTIVE_WORDS.matcher(userMessage).find()
                || RM_RF_PATTERN.matcher(userMessage).find();
    }

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
        } catch (IllegalArgumentException e) {
            // Fallback for unknown roles - cannot safely determine capability
            return role;
        }

        // Rule: if the role has ANY tool capabilities, it must pass the destructive check.
        if (!EXEMPT_ROLES.contains(resolvedRole)
            && !isSandboxOnlyRole(resolvedRole)
                && isDestructive(userMessage)) {
            Log.warnf("Destructive vocabulary detected in message routed to '%s' (%s). " +
                    "Overriding to 'coordinator' with no-tool mode.",
                    role, resolvedRole.toScopedName());
            return AgentRoleEnum.COORDINATOR.id();
        }
        return role;
    }

    /**
     * Returns true if the role's primary mission is writing code (CODER), or if its
     * ONLY capability is SANDBOX_EXEC. These roles are exempt
     * from the destructive vocabulary filter because code creation often requires
     * using words that would otherwise be flagged.
     */
    boolean isSandboxOnlyRole(AgentRoleEnum role) {
        if (role == AgentRoleEnum.CODER) return true;
        
        Set<ToolCapability> caps = role.definition().capabilities();
        return caps.isEmpty() ||
            (caps.size() == 1 && caps.contains(ToolCapability.SANDBOX_EXEC));
    }
}
