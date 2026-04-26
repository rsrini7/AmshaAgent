package com.arcclaw.deployment;

/**
 * ArcClaw Deployment Module - Quarkus build-time processing.
 * <p>
 * Contains {@code @BuildStep} processors for registering the
 * {@code @WorkspaceScoped} custom CDI scope and related build-time artifacts.
 * This module is NOT included in the runtime fat-jar.
 */
public final class ArcClawDeploymentInfo {

    public static final String MODULE = "arcclaw-deployment";

    private ArcClawDeploymentInfo() {
        // utility class
    }
}
