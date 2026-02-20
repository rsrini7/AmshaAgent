package com.arcclaw.core.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;

@ApplicationScoped
public class DataDirectoryInitializer {

    @ConfigProperty(name = "arcclaw.data.dir", defaultValue = "./data")
    String dataDir;

    // This runs the millisecond Quarkus boots, BEFORE Hibernate connects to SQLite
    void onStart(@Observes StartupEvent ev) {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs(); // Forces Windows/Linux to create the missing folder!
        }
    }
}