package com.arcclaw.core.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;

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