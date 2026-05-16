package com.arcclaw.core.security;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.config.PolicyConfig;
import org.jboss.logging.Logger;

/**
 * Filters agent routing based on destructive vocabulary.
 * <p>
 * If a message routed to a tool-capable role (non-empty capabilities)
 * contains destructive keywords, this filter overrides the role to
 * 'coordinator' (which typically triggers verification or safe fallback).
 * <p>
 * Patterns and exemptions are loaded from {@code arcclaw.policy.destructive-vocabulary}
 * config at startup.
 */
@ApplicationScoped
public class DestructiveVocabularyFilter {

    private static final Logger Log = Logger.getLogger(DestructiveVocabularyFilter.class);

    @Inject PolicyConfig policyConfig;

    private List<Pattern> compiledPatterns;
    private Set<AgentRoleEnum> exemptRoles;
    private Set<AgentRoleEnum> sandboxOnlyRoles;
    private String overrideRole;

    @PostConstruct
    void init() {
        var vocab = policyConfig.destructiveVocabulary();
        this.compiledPatterns = vocab.patterns().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
        this.exemptRoles = toRoleSet(vocab.exemptRoles());
        this.sandboxOnlyRoles = toRoleSet(vocab.sandboxOnlyRoles());
        this.overrideRole = vocab.overrideRole();
    }

    private Set<AgentRoleEnum> toRoleSet(List<String> roleIds) {
        return roleIds.stream()
                .map(id -> {
                    try { return AgentRoleEnum.fromString(id); }
                    catch (IllegalArgumentException e) {
                        Log.warnf("Unknown role '%s' in policy config — ignoring", id);
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns true if the message contains destructive vocabulary,
     * regardless of role. Used by IntentClassificationHandler to
     * short-circuit before agent dispatch.
     */
    public boolean isDestructive(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        for (Pattern p : compiledPatterns) {
            if (p.matcher(userMessage).find()) return true;
        }
        return false;
    }

    /**
     * Applies the filter logic.
     *
     * @param role         The role name (e.g., "researcher").
     * @param userMessage  The raw user message.
     * @return The filtered role (either the original or the configured override).
     */
    public String filter(String role, String userMessage) {
        AgentRoleEnum resolvedRole;
        try {
            resolvedRole = AgentRoleEnum.fromString(role);
        } catch (IllegalArgumentException e) {
            return role;
        }

        if (!exemptRoles.contains(resolvedRole)
            && !sandboxOnlyRoles.contains(resolvedRole)
                && isDestructive(userMessage)) {
            Log.warnf("Destructive vocabulary detected in message routed to '%s' (%s). " +
                    "Overriding to '%s' with no-tool mode.",
                    role, resolvedRole.toScopedName(), overrideRole);
            return overrideRole;
        }
        return role;
    }
}
