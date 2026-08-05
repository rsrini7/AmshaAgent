package com.amsha.app.mcp;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;

import com.amsha.core.scope.WorkspaceContext;

/** Resolves the MCP REST owner shared by browser and channel execution paths. */
final class McpRestUserIdentity {
    static final String PLATFORM_ID_HEADER = "X-AMSHA-PLATFORM-ID";
    private static final int MAX_PLATFORM_ID_LENGTH = 128;

    private McpRestUserIdentity() {}

    static String resolve(SecurityContext securityContext, HttpHeaders httpHeaders, boolean webAuthRequired) {
        String principalName = principalName(securityContext);
        if (!isAnonymousPrincipal(principalName)) {
            return principalName;
        }
        if (!webAuthRequired && httpHeaders != null) {
            String platformId = httpHeaders.getHeaderString(PLATFORM_ID_HEADER);
            if (isSafePlatformId(platformId)) {
                return platformId;
            }
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    private static String principalName(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return null;
        }
        return securityContext.getUserPrincipal().getName();
    }

    private static boolean isAnonymousPrincipal(String principalName) {
        return isBlank(principalName) || "anonymous".equalsIgnoreCase(principalName.trim());
    }

    private static boolean isSafePlatformId(String platformId) {
        if (isBlank(platformId)
                || platformId.length() > MAX_PLATFORM_ID_LENGTH
                || !platformId.equals(platformId.trim())
                || "anonymous".equalsIgnoreCase(platformId)
                || "unknown".equalsIgnoreCase(platformId)) {
            return false;
        }
        return platformId.chars().noneMatch(Character::isISOControl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
