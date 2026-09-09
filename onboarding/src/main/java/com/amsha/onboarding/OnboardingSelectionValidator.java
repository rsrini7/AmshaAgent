package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.routing.ModelProfileValidator;
import com.amsha.core.routing.ProfileValidationResult;
import com.amsha.llm.api.ConnectionValidationRequest;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ProviderValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Validates onboarding connection and profile selections against provider adapters and
 * model profile draft topologies. Extracted from {@link OnboardingSelectionScrubber} to keep
 * class sizes within PMD limits and decouple validation from secret scrubbing.
 */
@ApplicationScoped
public class OnboardingSelectionValidator {

    private static final Logger log = Logger.getLogger(OnboardingSelectionValidator.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    LlmProviderOnboardingAdapter providerAdapter;

    @Inject
    ModelProfileValidator profileValidator;

    /**
     * Tiered provider validation for the connections step. Extracts connection selections from the
     * payload and validates each through the provider adapter and profile draft topology.
     * Returns a safe error string on failure, or null when all selections are valid.
     */
    public String validateConnections(List<String> selections) {
        if (selections == null || selections.isEmpty()) {
            return null;
        }
        if (providerAdapter == null) {
            return "LlmProviderOnboardingAdapter is unavailable";
        }
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> allConnections = new ArrayList<>();
        List<Map<String, Object>> allProfiles = new ArrayList<>();

        // Pass 1: Parse and validate individual connection structures & adapter eligibility
        for (String sel : selections) {
            parseAndCollectSelection(sel, allConnections, allProfiles, errors);
        }

        // Pass 2: Validate topology across all collected connections & profiles
        if ((!allProfiles.isEmpty() || !allConnections.isEmpty()) && profileValidator != null) {
            ProfileValidationResult profileRes =
                    profileValidator.validateDraftTopology(allConnections, allProfiles, Set.of());
            if (!profileRes.valid()) {
                errors.addAll(profileRes.errors());
            }
        }

        return errors.isEmpty() ? null : String.join("; ", errors);
    }

    @SuppressWarnings("unchecked")
    private void parseAndCollectSelection(
            String sel,
            List<Map<String, Object>> allConnections,
            List<Map<String, Object>> allProfiles,
            List<String> errors) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(sel, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse onboarding selection payload", e);
            errors.add("Invalid selection payload: malformed JSON");
            return;
        }
        if (payload == null) {
            errors.add("Selection payload must not be null");
            return;
        }
        List<Map<String, Object>> conns = extractConnectionList(payload.get("connections"), errors);
        allConnections.addAll(conns);

        List<Map<String, Object>> profs = extractProfileList(payload.get("profiles"), errors);
        allProfiles.addAll(profs);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractConnectionList(Object rawConnections, List<String> errors) {
        if (rawConnections == null) {
            return List.of();
        }
        if (!(rawConnections instanceof List<?> list)) {
            errors.add("connections must be a list");
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("connection entry must be a JSON object");
                continue;
            }
            Map<String, Object> conn = (Map<String, Object>) map;
            validateSingleConnection(conn, errors);
            result.add(conn);
        }
        return result;
    }

    private void validateSingleConnection(Map<String, Object> conn, List<String> errors) {
        String provider = String.valueOf(conn.getOrDefault("provider", ""));
        String model = String.valueOf(conn.getOrDefault("modelId", ""));
        String baseUrl = String.valueOf(
                conn.getOrDefault("customBaseUrl", conn.getOrDefault("baseUrl", conn.getOrDefault("targetUrl", ""))));
        if ("generic-openai".equalsIgnoreCase(provider) && baseUrl.isBlank()) {
            errors.add("Provider generic-openai requires a targetUrl or baseUrl");
        }
        boolean hasKey = conn.containsKey("apiKeyVaultRef")
                || (conn.get("apiKey") != null
                        && !String.valueOf(conn.get("apiKey")).isBlank());
        List<String> allowedHosts = List.of();
        Object egress = conn.get("allowedEgressHosts");
        if (egress instanceof List<?> egressList) {
            allowedHosts = egressList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        ProviderValidationResult result = providerAdapter.validate(
                new ConnectionValidationRequest(provider, model, baseUrl, hasKey, allowedHosts));
        if (!result.eligible()) {
            errors.addAll(result.errors());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractProfileList(Object rawProfiles, List<String> errors) {
        if (rawProfiles == null) {
            return List.of();
        }
        if (!(rawProfiles instanceof List<?> list)) {
            errors.add("profiles must be a list");
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("profile entry must be a JSON object");
                continue;
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }
}
