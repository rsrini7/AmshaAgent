package com.arcclaw.app;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ArcClaw Application Module — Quarkus entry point.
 * <p>
 * This module assembles the runtime ({@code arcclaw-core}) into
 * a deployable Quarkus application (fast-jar or native image).
 */
@QuarkusMain
public class ArcClawApp {

    private static final Logger log = Logger.getLogger(ArcClawApp.class);
    public static final String MODULE = "arcclaw-app";

    public static void main(String... args) {
        try {
            // Intercept startup to safely provision the SQLite host directory.
            // On Windows, SQLite's JDBC driver fails if the parent dir doesn't exist.
            String dataDir = ConfigProvider.getConfig()
                    .getOptionalValue("ARCCLAW_DATA_DIR", String.class)
                    .orElse("./data");

            Path dirPath = Paths.get(dataDir);
            if (!Files.exists(dirPath)) {
                log.infof("Provisioning ArcClaw data directory: %s", dirPath.toAbsolutePath());
                Files.createDirectories(dirPath);
            }
        } catch (Exception e) {
            log.error("Failed to initialize ArcClaw data directory", e);
        }

        Quarkus.run(args);
    }
}
