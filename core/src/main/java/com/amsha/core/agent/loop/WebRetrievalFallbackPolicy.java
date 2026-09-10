package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.tool.api.ToolCapability;

/** Keeps URL retrieval fallback order consistent across normal and resumed execution. */
final class WebRetrievalFallbackPolicy {
    private static final int MAX_DIAGNOSTIC_CHARS = 512;
    private static final String BROWSER_FAILURE_MARKER = "[WEB-RETRIEVAL] browser_navigate failed for ";

    private WebRetrievalFallbackPolicy() {}

    static String browserFailureMarker(String url) {
        return BROWSER_FAILURE_MARKER + url;
    }

    static boolean browserAttemptFailed(List<ReasoningStep> history, String url) {
        if (history == null || url == null || url.isBlank()) {
            return false;
        }
        return history.stream()
                .anyMatch(step ->
                        step.observation() != null && step.observation().contains(BROWSER_FAILURE_MARKER + url));
    }

    static String browserToFetchGoal(String url, String observation) {
        return "The browser_navigate tool failed for "
                + url
                + " ("
                + safeObservation(observation)
                + "). Use web_fetch on this exact URL next. Do not search or invent a different URL yet.";
    }

    static String fetchToBrowserGoal(String url, String observation) {
        return "The web_fetch tool failed for "
                + url
                + " ("
                + safeObservation(observation)
                + "). Use browser_navigate on this exact URL now. Do not invent an alternative URL.";
    }

    static String fetchToSearchGoal(String url, String observation) {
        return "Both browser_navigate and web_fetch failed for "
                + url
                + " ("
                + safeObservation(observation)
                + "). Use serper_web_search or brave_web_search once with a natural-language query to find a working URL. Do not guess or construct URLs.";
    }

    static boolean isBrowser(String toolName) {
        return ToolCapability.BROWSER_NAVIGATE.toolName().equalsIgnoreCase(toolName);
    }

    static boolean isWebFetch(String toolName) {
        return ToolCapability.WEB_FETCH.toolName().equalsIgnoreCase(toolName);
    }

    static String urlFrom(Map<String, Object> params) {
        if (params == null) {
            return "";
        }
        Object value = params.get("url");
        return value instanceof String url ? url.trim() : "";
    }

    private static String safeObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return "no diagnostic was returned";
        }
        String compact = observation.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= MAX_DIAGNOSTIC_CHARS ? compact : compact.substring(0, MAX_DIAGNOSTIC_CHARS) + "…";
    }
}
