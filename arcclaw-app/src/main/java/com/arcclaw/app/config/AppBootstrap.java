package com.arcclaw.app.config;

/**
 * Bootstraps the application by delegating to specialized orchestrators and initializers.
 */
public class AppBootstrap {

    public static void bootstrap(String[] args) {
        BootstrapOrchestrator.bootstrap(args);
    }
}
