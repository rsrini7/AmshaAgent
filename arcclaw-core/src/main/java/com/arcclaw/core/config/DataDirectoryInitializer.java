package com.arcclaw.core.config;

import java.io.File;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DataDirectoryInitializer {

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    // This runs the millisecond Quarkus boots, BEFORE Hibernate connects to SQLite
    void onStart(@Observes StartupEvent ev) {
        // Create both: the data root (for SQLite) and the workspaces dir (for agents)
        new File(workspaceRootDir).mkdirs();  // implicitly creates ./data parent too
    }
}
