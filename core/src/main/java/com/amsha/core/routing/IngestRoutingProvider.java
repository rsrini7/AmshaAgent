package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.util.FuzzyMatcher;

@ApplicationScoped
public class IngestRoutingProvider implements IntentRoutingProvider {
    // NEGATION SAFETY: This pattern is intentionally specific to
    // time-delay signals (after X min, in X hours, remind me, etc.)
    // and does NOT match negations like "do not schedule" or
    // "cancel my reminder". If the pattern is ever broadened,
    // add a shouldSkip() guard via IntentRoutingProvider.shouldSkip()
    // default method to handle negations.
    private static final Pattern COORDINATOR_FORCE_PATTERN = Pattern.compile(
            "(?i)^\\s*(ingest|store this|add to (knowledge|rag|memory|kb)|"
                    + "remember this|save this|learn this|index this|"
                    + ".*(and\\s+ingest|then\\s+ingest|and\\s+store|then\\s+store|"
                    + "and\\s+remember|and\\s+save\\s+that|and\\s+add\\s+that))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STORE_CMD = Pattern.compile("(?i)^store\\s+.+", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(COORDINATOR_FORCE_PATTERN, STORE_CMD);
    }

    @Override
    public boolean matchesFuzzy(String userMessage) {
        return FuzzyMatcher.containsFuzzy(userMessage, "store")
                || FuzzyMatcher.containsFuzzy(userMessage, "save")
                || FuzzyMatcher.containsFuzzy(userMessage, "remember")
                || FuzzyMatcher.containsFuzzy(userMessage, "ingest");
    }

    @Override
    public List<String> promptDirectives() {
        return List.of("If the user message starts with 'ingest', 'store this', 'remember this', "
                + "'add to knowledge', or similar document storage commands -> coordinator only.");
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    @Override
    public int priority() {
        return 30;
    }
}
