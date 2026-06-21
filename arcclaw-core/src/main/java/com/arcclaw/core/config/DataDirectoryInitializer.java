package com.arcclaw.core.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.File;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class DataDirectoryInitializer {

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    // This runs the millisecond Quarkus boots, before any database connection is established
    void onStart(@Observes StartupEvent ev) {
        // Create both: the data root (for SQLite) and the workspaces dir (for agents)
        new File(workspaceRootDir).mkdirs(); // implicitly creates ./data parent too
    }
}
