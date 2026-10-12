package com.amsha.onboarding;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;

import io.vertx.core.http.HttpServerRequest;

final class OnboardingResourceHelper {

    private static final int COOKIE_MAX_AGE_SECONDS = 30 * 24 * 3600;

    private OnboardingResourceHelper() {}

    static NewCookie buildSkipCookie(HttpServerRequest request, String rawKey) {
        return new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                .value(rawKey)
                .path("/")
                .maxAge(COOKIE_MAX_AGE_SECONDS)
                .httpOnly(true)
                .secure(isSecureCookieRequired(request))
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    static boolean isSecureCookieRequired(HttpServerRequest request) {
        if (request == null || request.remoteAddress() == null) {
            return false;
        }
        String host = request.remoteAddress().host();
        return !(HttpConstants.isLoopbackHost(host) && "http".equalsIgnoreCase(request.scheme()));
    }

    static boolean isLoopbackRequest(HttpServerRequest request) {
        if (request == null || request.remoteAddress() == null) {
            return false;
        }
        return HttpConstants.isLoopbackHost(request.remoteAddress().host());
    }

    static Locale resolveLocale(HttpHeaders httpHeaders) {
        if (httpHeaders != null
                && httpHeaders.getAcceptableLanguages() != null
                && !httpHeaders.getAcceptableLanguages().isEmpty()) {
            return httpHeaders.getAcceptableLanguages().get(0);
        }
        return Locale.ENGLISH;
    }

    /**
     * Verifies internal principal role or matches internal API key from authorization headers.
     * Direct header inspection intentionally supplements the SecurityContext role check to allow
     * loopback or initial setup calls prior to full security filter context initialization without
     * duplicating header parsing logic across resource endpoints.
     */
    static boolean isInternal(SecurityContext securityContext, HttpServerRequest request, String internalApiKey) {
        if (securityContext != null && securityContext.isUserInRole(AccessRoles.INTERNAL)) {
            return true;
        }
        String auth = request != null ? request.getHeader(HttpProtocol.HEADER_AUTHORIZATION) : null;
        if (auth == null && request != null) {
            auth = request.getHeader(HttpConstants.X_AMSHA_API_KEY);
        }
        if (auth != null && auth.startsWith(HttpProtocol.AUTH_SCHEME_BEARER)) {
            auth = auth.substring(HttpProtocol.AUTH_SCHEME_BEARER.length());
        }
        if (auth == null || auth.isBlank() || internalApiKey == null || internalApiKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                auth.getBytes(StandardCharsets.UTF_8), internalApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private static final Logger log = Logger.getLogger(OnboardingResourceHelper.class);

    private record ConnectionSearchResult(int payloadIndex, JsonNode rootNode, ObjectNode connectionNode) {}

    private static ConnectionSearchResult findConnectionNode(
            List<String> selections, int connectionIndex, ObjectMapper objectMapper) {
        if (selections == null || selections.isEmpty()) {
            return null;
        }
        for (int i = 0; i < selections.size(); i++) {
            String s = selections.get(i);
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node.has("connections")) {
                    JsonNode conns = node.get("connections");
                    if (conns.isArray() && !conns.isEmpty()) {
                        int idx = connectionIndex == -1 ? 0 : connectionIndex;
                        if (idx >= 0 && idx < conns.size() && conns.get(idx) instanceof ObjectNode obj) {
                            return new ConnectionSearchResult(i, node, obj);
                        }
                    }
                }
            } catch (Exception e) {
                log.debugf("Could not parse selection payload as JSON: %s", LogUtil.rootMessage(e));
            }
        }
        return null;
    }

    static List<String> injectNetworkProfile(
            List<String> selections, int connectionIndex, String networkProfileJson, ObjectMapper objectMapper) {
        ConnectionSearchResult result = findConnectionNode(selections, connectionIndex, objectMapper);
        if (result == null) {
            return null;
        }
        try {
            result.connectionNode().put("networkProfileJson", networkProfileJson);
            String updated = objectMapper.writeValueAsString(result.rootNode());
            List<String> newSelections = new ArrayList<>(selections);
            newSelections.set(result.payloadIndex(), updated);
            return newSelections;
        } catch (Exception e) {
            log.warnf("Failed to serialize updated network profile selection: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    static String extractNetworkProfile(List<String> selections, int connectionIndex, ObjectMapper objectMapper) {
        ConnectionSearchResult result = findConnectionNode(selections, connectionIndex, objectMapper);
        if (result == null) {
            return null;
        }
        JsonNode profileNode = result.connectionNode().path("networkProfileJson");
        if (profileNode.isTextual()) {
            return profileNode.asText();
        }
        return null;
    }

    /**
     * Returns the staged connection's explicit {@code id} at the given index, or null when the
     * staged entry carries none (wizard-staged connections are identified by provider/model only).
     * Used to correlate a draft-scoped edit with its committed profile-store row.
     */
    static String extractConnectionId(List<String> selections, int connectionIndex, ObjectMapper objectMapper) {
        ConnectionSearchResult result = findConnectionNode(selections, connectionIndex, objectMapper);
        if (result == null) {
            return null;
        }
        JsonNode idNode = result.connectionNode().path("id");
        if (idNode.isTextual() && !idNode.asText().isBlank()) {
            return idNode.asText();
        }
        return null;
    }

    static OnboardingDraftDto buildImportedReviewDraft(OnboardingDraftDto draft, Map<String, Object> data) {
        return buildImportedReviewDraft(draft, data, new ObjectMapper(), null);
    }

    static OnboardingDraftDto buildImportedReviewDraft(
            OnboardingDraftDto draft, Map<String, Object> data, ObjectMapper objectMapper) {
        return buildImportedReviewDraft(draft, data, objectMapper, null);
    }

    @SuppressWarnings("unchecked")
    static OnboardingDraftDto buildImportedReviewDraft(
            OnboardingDraftDto draft,
            Map<String, Object> data,
            ObjectMapper objectMapper,
            OnboardingSelectionScrubber scrubber) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        Map<String, Object> staged = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String k = entry.getKey();
                if (OnboardingSelectionScrubber.isSecretKey(k)) {
                    continue;
                }
                staged.put(k, entry.getValue());
            }
        }

        List<Map<String, Object>> conns = normalizeImportedConnections(data);
        staged.put("connections", conns);

        if (!staged.containsKey("concernRoutes")) {
            staged.put("concernRoutes", deriveConcernRoutes(data, conns));
        }

        String serializedTopology;
        try {
            serializedTopology = mapper.writeValueAsString(staged);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize imported topology payload", e);
        }

        List<String> selections = List.of(serializedTopology);
        if (scrubber != null) {
            selections = scrubber.processAndScrubSecrets(draft.ownerId(), selections);
        } else {
            selections = stripPlaintextSecretsFallback(selections, mapper);
        }

        List<String> completed = new ArrayList<>(draft.completedStepIds());
        for (String step : List.of("welcome", "connections", "routes")) {
            if (!completed.contains(step)) completed.add(step);
        }
        if (data != null && data.containsKey("mcp") && !completed.contains("mcp")) {
            completed.add("mcp");
        }

        return new OnboardingDraftDto(
                draft.draftId(),
                draft.ownerId(),
                draft.purpose(),
                draft.planFingerprint(),
                "review",
                completed,
                selections,
                null,
                draft.pendingApprovalIds(),
                OnboardingDraftState.DRAFT,
                draft.revision() + 1,
                draft.expiresAt(),
                draft.createdAt(),
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stripPlaintextSecretsFallback(List<String> selections, ObjectMapper mapper) {
        List<String> stripped = new ArrayList<>();
        for (String sel : selections) {
            try {
                Map<String, Object> map = mapper.readValue(sel, Map.class);
                stripSecretsFromMap(map);
                stripped.add(mapper.writeValueAsString(map));
            } catch (Exception _) {
                stripped.add(sel);
            }
        }
        return stripped;
    }

    @SuppressWarnings("unchecked")
    private static void stripSecretsFromMap(Map<String, Object> map) {
        if (map == null) return;
        map.entrySet().removeIf(e -> OnboardingSelectionScrubber.isSecretKey(e.getKey()));
        for (Object val : map.values()) {
            if (val instanceof Map<?, ?> subMap) {
                stripSecretsFromMap((Map<String, Object>) subMap);
            } else if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        stripSecretsFromMap((Map<String, Object>) itemMap);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeImportedConnections(Map<String, Object> data) {
        List<Map<String, Object>> conns = new ArrayList<>();
        if (data == null || !(data.get("connections") instanceof List<?> rawConns)) {
            return conns;
        }
        List<?> rawProfiles = data.get("profiles") instanceof List<?> list ? list : List.of();
        for (Object item : rawConns) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) rawMap);
                if (!c.containsKey("provider") && c.containsKey("providerAdapterId")) {
                    c.put("provider", c.get("providerAdapterId"));
                }
                if (!c.containsKey("modelId")) {
                    Object connId = c.get("id");
                    for (Object pObj : rawProfiles) {
                        if (pObj instanceof Map<?, ?> pMap && Objects.equals(connId, pMap.get("connectionId"))) {
                            c.put("modelId", pMap.get("modelId"));
                            break;
                        }
                    }
                }
                if (!c.containsKey("baseUrl") && c.containsKey("customBaseUrl")) {
                    c.put("baseUrl", c.get("customBaseUrl"));
                }
                conns.add(c);
            }
        }
        return conns;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Integer>> deriveConcernRoutes(
            Map<String, Object> data, List<Map<String, Object>> conns) {
        Map<String, List<Integer>> concernRoutes = new LinkedHashMap<>();
        if (data == null || !(data.get("routes") instanceof List<?> rawRoutes)) {
            if (!conns.isEmpty()) concernRoutes.put("agentLoop", List.of(0));
            return concernRoutes;
        }
        List<?> rawProfiles = data.get("profiles") instanceof List<?> list ? list : List.of();
        for (Object rObj : rawRoutes) {
            if (!(rObj instanceof Map<?, ?> rMap)) {
                continue;
            }
            Object rawKind = rMap.get("targetKind");
            String targetKind = rawKind != null ? rawKind.toString() : "";
            String concern =
                    switch (targetKind) {
                        case "DEFAULT", "AGENT_LOOP" -> "agentLoop";
                        case "COMPACTION" -> "compaction";
                        case "CLASSIFIER" -> "classifier";
                        case "EMBEDDING" -> "embedding";
                        default -> null;
                    };
            if (concern != null && !concernRoutes.containsKey(concern)) {
                int connIdx = findConnectionIndexForRoute(rMap, rawProfiles, conns);
                concernRoutes.put(concern, List.of(connIdx));
            }
        }
        if (!concernRoutes.containsKey("agentLoop") && !conns.isEmpty()) {
            concernRoutes.put("agentLoop", List.of(0));
        }
        return concernRoutes;
    }

    private static int findConnectionIndexForRoute(
            Map<?, ?> rMap, List<?> rawProfiles, List<Map<String, Object>> conns) {
        if (rMap.get("orderedProfileIds") instanceof List<?> ordered && !ordered.isEmpty()) {
            Object firstProfId = ordered.get(0);
            for (Object pObj : rawProfiles) {
                if (pObj instanceof Map<?, ?> pMap && Objects.equals(firstProfId, pMap.get("id"))) {
                    Object connId = pMap.get("connectionId");
                    for (int i = 0; i < conns.size(); i++) {
                        if (Objects.equals(connId, conns.get(i).get("id"))) {
                            return i;
                        }
                    }
                    break;
                }
            }
        }
        return 0;
    }
}
