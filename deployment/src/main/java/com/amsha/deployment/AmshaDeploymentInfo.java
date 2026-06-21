package com.amsha.deployment;

/**
 * Amsha Agent Deployment Module - Quarkus build-time processing.
 * <p>
 * Contains {@code @BuildStep} processors for registering the
 * {@code @WorkspaceScoped} custom CDI scope and related build-time artifacts.
 * This module is NOT included in the runtime fat-jar.
 */
public final class AmshaDeploymentInfo {

    public static final String MODULE = "amsha-deployment";

    private AmshaDeploymentInfo() {
        // utility class
    }
}
