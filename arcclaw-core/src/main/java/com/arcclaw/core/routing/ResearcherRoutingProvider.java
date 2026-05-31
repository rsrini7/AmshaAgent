package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.util.FuzzyMatcher;

/**
 * Fast-path routing for research and information retrieval requests.
 * Priority 15 - runs after delegation (5) and scheduling (10)
 * but before coder (20), ingest (30), and default (1000).
 *
 * Handles: GitHub repo/gist fetch, describe, lookup, research queries.
 */
@ApplicationScoped
public class ResearcherRoutingProvider implements IntentRoutingProvider {

    // Matches: fetch X, describe X, lookup X, research X, browse X, navigate to X
    //          github/gist/repo references, owner/repo format, and direct URL targets.
    private static final Pattern RESEARCHER_PATTERN = Pattern.compile("(?i)(" +
            // Action verbs for research/fetch/browse
            "^\\s*(fetch|describe|lookup|look\\s+up|research|search|find\\s+info|get\\s+info|"
            + "get\\s+details|show\\s+info|what\\s+is|tell\\s+me\\s+about|"
            + "summarize|summary\\s+of|overview\\s+of|"
            + "browse|navigate|visit|open|scrape|check\\s+website|fetch\\s+page)"
            + "|"
            +
            // GitHub-specific patterns
            "\\bgithub\\b.*\\b(repo|gist|repository|desc|view|list)\\b"
            + "|"
            +
            // owner/repo format (e.g. rsrini7/mermaid2gif)
            "\\b[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+\\b"
            + "|"
            +
            // URL patterns (starts with or contains http)
            "\\bhttps?://\\S+"
            + "|"
            +
            // gh CLI patterns
            "\\bgh\\s+(repo|gist)\\b"
            + ")");

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(RESEARCHER_PATTERN);
    }

    @Override
    public boolean matchesFuzzy(String userMessage) {
        return FuzzyMatcher.containsFuzzy(userMessage, "fetch")
                || FuzzyMatcher.containsFuzzy(userMessage, "search")
                || FuzzyMatcher.containsFuzzy(userMessage, "browse")
                || FuzzyMatcher.containsFuzzy(userMessage, "navigate");
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
                "If the user wants to FETCH, DESCRIBE, LOOKUP, or VIEW existing GitHub "
                        + "repos, gists, or any online resource - route to 'researcher'. "
                        + "Patterns like 'owner/repo', 'fetch X desc', 'describe repo' "
                        + "are always researcher, never coder.",
                "If the user message contains a GitHub owner/repo path (e.g. 'rsrini7/mermaid2gif') "
                        + "and asks to view or describe it - route to 'researcher'.");
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.RESEARCHER.toScopedName());
    }

    @Override
    public boolean shouldSkip(String userMessage) {
        if (userMessage == null) return true;
        String lower = userMessage.toLowerCase(Locale.ROOT);
        boolean hasUrl = lower.contains("http://") || lower.contains("https://");
        boolean hasGithubRef = lower.contains("github") || lower.contains("/");
        boolean hasBrowseVerbs = lower.contains("browse")
                || lower.contains("navigate")
                || lower.contains("visit")
                || lower.contains("scrape")
                || lower.contains("check website");

        return !hasUrl && !hasGithubRef && !hasBrowseVerbs;
    }

    @Override
    public int priority() {
        return 15;
    }
}
