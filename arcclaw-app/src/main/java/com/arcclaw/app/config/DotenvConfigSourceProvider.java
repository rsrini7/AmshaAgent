package com.arcclaw.app.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

/**
 * Custom ConfigSourceProvider to natively parse .env files.
 * <p>
 * Quarkus DEV mode executes from within the `arcclaw-app` directory,
 * skipping the root `.env` file. This provider explicitly searches
 * both the current directory (`.env`) and parent directory (`../.env`),
 * parsing the K-V pairs so they are available for recursive variable expansion
 * in application.yaml (e.g. ${TELEGRAM_BOT_TOKEN}).
 */
public class DotenvConfigSourceProvider implements ConfigSourceProvider {

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        Map<String, String> properties = new HashMap<>();
        try {
            // Check current dir first, fallback to parent dir (multi-module root execution)
            Path envPath = Path.of(".env");
            if (!Files.exists(envPath)) {
                envPath = Path.of("../.env");
            }
            if (Files.exists(envPath)) {
                Files.readAllLines(envPath).forEach(line -> {
                    String clean = line.trim();
                    if (!clean.isEmpty() && !clean.startsWith("#") && clean.contains("=")) {
                        String[] parts = clean.split("=", 2);
                        String key = parts[0].trim();
                        String val = parts[1].trim();
                        // Remove surrounding quotes if any
                        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        } else if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        }
                        properties.put(key, val);
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[ArcClaw] Could not parse .env file: " + e.getMessage());
        }

        return List.of(new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return properties;
            }

            @Override
            public String getValue(String propertyName) {
                return properties.get(propertyName);
            }

            @Override
            public String getName() {
                return "ArcClaw-DotEnv";
            }

            @Override
            public Set<String> getPropertyNames() {
                return properties.keySet();
            }

            @Override
            public int getOrdinal() {
                // Higher than application.yaml (250) but lower than System Properties (400)
                // Default .env ordinal in SmallRye is 295. We use 296 to explicitly beat it.
                return 296;
            }
        });
    }
}
