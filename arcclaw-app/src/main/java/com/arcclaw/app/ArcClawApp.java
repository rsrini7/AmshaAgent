package com.arcclaw.app;

/**
 * ArcClaw Application Module — Quarkus entry point.
 * <p>
 * This module assembles the runtime ({@code arcclaw-core}) into
 * a deployable Quarkus application (fast-jar or native image).
 */
public final class ArcClawApp {

    public static final String MODULE = "arcclaw-app";

    private ArcClawApp() {
        // utility class
    }
}
