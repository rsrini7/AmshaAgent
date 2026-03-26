package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class IngestRoutingProvider implements IntentRoutingProvider {
    private static final Pattern COORDINATOR_FORCE_PATTERN =
                        Pattern.compile(
                            "(?i)^\\s*(ingest|store this|add to (knowledge|rag|memory|kb)|" +
                            "remember this|save this|learn this|index this|" +
                            ".*(and\\s+ingest|then\\s+ingest|and\\s+store|then\\s+store|" +
                            "and\\s+remember|and\\s+save\\s+that|and\\s+add\\s+that))",
                            Pattern.CASE_INSENSITIVE
                        );

    private static final Pattern STORE_CMD =
                        Pattern.compile("(?i)^store\\s+.+", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(COORDINATOR_FORCE_PATTERN, STORE_CMD);
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "If the user message starts with 'ingest', 'store this', 'remember this', " +
            "'add to knowledge', or similar document storage commands -> coordinator only."
        );
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
