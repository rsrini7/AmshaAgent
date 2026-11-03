package com.amsha.core.tool.attempt;

import java.util.Objects;

import com.amsha.persistence.api.ActionLockScope;

/**
 * Result of resolving trusted target identity for a tool invocation per Proposal §4.2 and §12.2.
 *
 * @param scope the resolved lock scope (TARGET_RESOURCE or SESSION_MUTATIONS)
 * @param targetFingerprint the target fingerprint digest formatted as {@code target:sha256:v1:<hex>}
 *                          (must be null for SESSION_MUTATIONS, and non-null for TARGET_RESOURCE)
 * @param resourceType the resolved canonical resource type (null for SESSION_MUTATIONS)
 * @param resourceIdentity the resolved canonical resource identity (null for SESSION_MUTATIONS)
 */
public record ToolTargetResolution(
        ActionLockScope scope, String targetFingerprint, String resourceType, String resourceIdentity) {

    public ToolTargetResolution {
        Objects.requireNonNull(scope, "scope must not be null");
        if (scope == ActionLockScope.TARGET_RESOURCE) {
            Objects.requireNonNull(targetFingerprint, "targetFingerprint must not be null for TARGET_RESOURCE");
            if (targetFingerprint.isBlank()) {
                throw new IllegalArgumentException("targetFingerprint must not be blank for TARGET_RESOURCE");
            }
            if (!targetFingerprint.startsWith(ToolTargetResolver.TARGET_PREFIX)) {
                throw new IllegalArgumentException("targetFingerprint must start with "
                        + ToolTargetResolver.TARGET_PREFIX + ": " + targetFingerprint);
            }
            String targetDigest = targetFingerprint.substring(ToolTargetResolver.TARGET_PREFIX.length());
            if (!CanonicalJsonUtil.isLowercaseHex64(targetDigest)) {
                throw new IllegalArgumentException(
                        "targetFingerprint must contain a 64-character lowercase hex digest: " + targetFingerprint);
            }
            Objects.requireNonNull(resourceType, "resourceType must not be null for TARGET_RESOURCE");
            if (resourceType.isBlank()) {
                throw new IllegalArgumentException("resourceType must not be blank for TARGET_RESOURCE");
            }
            Objects.requireNonNull(resourceIdentity, "resourceIdentity must not be null for TARGET_RESOURCE");
            if (resourceIdentity.isBlank()) {
                throw new IllegalArgumentException("resourceIdentity must not be blank for TARGET_RESOURCE");
            }
        } else if (scope == ActionLockScope.SESSION_MUTATIONS || scope == ActionLockScope.EXACT_REQUEST) {
            if (targetFingerprint != null) {
                throw new IllegalArgumentException("targetFingerprint must be null for " + scope);
            }
            if (resourceType != null) {
                throw new IllegalArgumentException("resourceType must be null for " + scope);
            }
            if (resourceIdentity != null) {
                throw new IllegalArgumentException("resourceIdentity must be null for " + scope);
            }
        }
    }

    public static ToolTargetResolution targetResource(
            String targetFingerprint, String resourceType, String resourceIdentity) {
        return new ToolTargetResolution(
                ActionLockScope.TARGET_RESOURCE, targetFingerprint, resourceType, resourceIdentity);
    }

    public static ToolTargetResolution sessionMutations() {
        return new ToolTargetResolution(ActionLockScope.SESSION_MUTATIONS, null, null, null);
    }

    public static ToolTargetResolution exactRequest() {
        return new ToolTargetResolution(ActionLockScope.EXACT_REQUEST, null, null, null);
    }

    public boolean isTargetResource() {
        return scope == ActionLockScope.TARGET_RESOURCE;
    }
}
