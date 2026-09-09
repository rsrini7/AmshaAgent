package com.amsha.core.routing;

import java.util.List;
import java.util.Locale;

import com.amsha.llm.api.NetworkProfilePolicy;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Validates connection network profile JSON strings against canonical
 * {@link NetworkProfilePolicy} rules, enum types, and field constraints.
 */
public final class NetworkProfilePolicyValidator {

    private NetworkProfilePolicyValidator() {}

    /**
     * Validates a networkProfileJson string against canonical NetworkProfilePolicy rules and
     * appends descriptive errors to {@code errors}.
     */
    public static void validateNetworkProfileJson(String netJson, List<String> errors) {
        if (netJson == null || netJson.isBlank()) {
            return;
        }
        JsonObject netObj;
        try {
            netObj = new JsonObject(netJson);
        } catch (Exception _) {
            errors.add("networkProfileJson must be valid JSON object");
            return;
        }

        validateFieldTypesAndEnums(netObj, errors);
        if (!NetworkProfilePolicy.isValidJson(netJson)) {
            appendCanonicalPolicyErrors(netObj, errors);
        }
    }

    private static void validateFieldTypesAndEnums(JsonObject netObj, List<String> errors) {
        Object egressModeObj = netObj.getValue("egressMode");
        if (egressModeObj != null) {
            if (!(egressModeObj instanceof String s)) {
                errors.add("networkProfileJson field 'egressMode' must be a string");
            } else if (!s.isBlank()) {
                try {
                    NetworkProfilePolicy.EgressMode.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException _) {
                    errors.add("networkProfileJson field 'egressMode' is invalid: " + s);
                }
            }
        }
        Object allowedHostsObj = netObj.getValue("allowedHosts");
        if (allowedHostsObj != null && !(allowedHostsObj instanceof JsonArray)) {
            errors.add("networkProfileJson field 'allowedHosts' must be a JSON array");
        } else if (allowedHostsObj instanceof JsonArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.getValue(i) instanceof String host) || host.isBlank()) {
                    errors.add("networkProfileJson 'allowedHosts' elements must be non-blank strings");
                }
            }
        }
        Object requireProxyObj = netObj.getValue("requireProxy");
        if (requireProxyObj != null && !(requireProxyObj instanceof Boolean)) {
            errors.add("networkProfileJson field 'requireProxy' must be a boolean");
        }
        Object proxyUrlObj = netObj.getValue("proxyUrl");
        if (proxyUrlObj != null && !(proxyUrlObj instanceof String)) {
            errors.add("networkProfileJson field 'proxyUrl' must be a string");
        } else if (proxyUrlObj instanceof String p && !p.isBlank() && !NetworkProfilePolicy.isAbsoluteHttpUri(p)) {
            errors.add("networkProfileJson field 'proxyUrl' must be a valid HTTP(S) URI: " + p);
        }
        Object noProxyObj = netObj.getValue("noProxyHosts");
        if (noProxyObj != null && !(noProxyObj instanceof JsonArray)) {
            errors.add("networkProfileJson field 'noProxyHosts' must be a JSON array");
        } else if (noProxyObj instanceof JsonArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.getValue(i) instanceof String host) || host.isBlank()) {
                    errors.add("networkProfileJson 'noProxyHosts' elements must be non-blank strings");
                }
            }
        }
        for (String timeoutKey : List.of("connectTimeoutMs", "readTimeoutMs")) {
            Object timeoutObj = netObj.getValue(timeoutKey);
            if (timeoutObj != null && !(timeoutObj instanceof Number)) {
                errors.add("networkProfileJson field '" + timeoutKey + "' must be a number");
            }
        }
        Object tlsTrustModeObj = netObj.getValue("tlsTrustMode");
        if (tlsTrustModeObj != null) {
            if (!(tlsTrustModeObj instanceof String s)) {
                errors.add("networkProfileJson field 'tlsTrustMode' must be a string");
            } else if (!s.isBlank()) {
                try {
                    NetworkProfilePolicy.TlsTrustMode.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException _) {
                    errors.add("networkProfileJson field 'tlsTrustMode' is invalid: " + s);
                }
            }
        }
        Object caVaultRefObj = netObj.getValue("customCaVaultRef");
        if (caVaultRefObj != null && !(caVaultRefObj instanceof String)) {
            errors.add("networkProfileJson field 'customCaVaultRef' must be a string");
        }
    }

    private static void appendCanonicalPolicyErrors(JsonObject netObj, List<String> errors) {
        Object rawMode = netObj.getValue("egressMode");
        String mode = rawMode instanceof String s ? s.toUpperCase(Locale.ROOT) : "ALLOW_ALL";
        if ("ALLOW_LIST".equals(mode)
                && (!(netObj.getValue("allowedHosts") instanceof JsonArray arr) || arr.isEmpty())) {
            errors.add("networkProfileJson requires non-empty 'allowedHosts' when egressMode is ALLOW_LIST");
        }
        Object rawProxy = netObj.getValue("requireProxy");
        boolean requireProxy = Boolean.TRUE.equals(rawProxy);
        if (requireProxy && !NetworkProfilePolicy.isAbsoluteHttpUri(netObj.getValue("proxyUrl"))) {
            errors.add("networkProfileJson requires a valid HTTP(S) proxyUrl when requireProxy is true");
        }
        Object rawTls = netObj.getValue("tlsTrustMode");
        String tls = rawTls instanceof String s ? s.toUpperCase(Locale.ROOT) : "SYSTEM";
        if ("CUSTOM_CA".equals(tls)) {
            Object rawCa = netObj.getValue("customCaVaultRef");
            if (!(rawCa instanceof String caRef) || caRef.isBlank()) {
                errors.add("networkProfileJson requires non-blank 'customCaVaultRef' when tlsTrustMode is CUSTOM_CA");
            }
        }
        for (String timeoutKey : List.of("connectTimeoutMs", "readTimeoutMs")) {
            Object timeoutVal = netObj.getValue(timeoutKey);
            if (timeoutVal instanceof Number num && num.intValue() <= 0) {
                errors.add("networkProfileJson field '" + timeoutKey + "' must be positive: " + num);
            }
        }
    }
}
