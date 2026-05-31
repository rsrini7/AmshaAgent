package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(DotenvConfigSourceProvider.class);

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        Map<String, String> dotenvProperties = loadDotenv();
        return Collections.singletonList(new DotenvConfigSource(dotenvProperties));
    }

    private Map<String, String> loadDotenv() {
        Map<String, String> properties = new HashMap<>();
        Path envPath = resolveEnvPath();

        if (!Files.exists(envPath)) {
            return properties; // No .env file found, rely on system/environment variables
        }

        try {
            for (String line : Files.readAllLines(envPath)) {
                line = line.trim();

                // 1. Ignore full-line comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // 2. Split by the first '=' character
                int equalSignIndex = line.indexOf('=');
                if (equalSignIndex > 0) {
                    String key = line.substring(0, equalSignIndex).trim();
                    String rawValue = line.substring(equalSignIndex + 1).trim();

                    // 3. Strip inline comments (Quote-Aware)
                    String value = stripInlineComments(rawValue);

                    // 4. Remove surrounding quotes if the user added them
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        if (value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                    }

                    properties.put(key, value);
                }
            }
            log.info("Loaded {} properties from .env file.", properties.size());
        } catch (IOException e) {
            log.warn("Failed to read .env file. Falling back to default environment variables.", e);
        }

        return properties;
    }

    private Path resolveEnvPath() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String command = ProcessHandle.current().info().command().orElse("");
            if (!command.isEmpty()) {
                try {
                    Path binaryDir =
                            Path.of(command).toAbsolutePath().normalize().getParent();
                    if (binaryDir != null && Files.exists(binaryDir)) {
                        return binaryDir.resolve(".env");
                    }
                } catch (Exception e) {
                    // fall through to CWD
                }
            }
        }
        return Paths.get(".env");
    }

    /**
     * Safely strips inline comments starting with '#' unless they are enclosed in quotes.
     */
    private String stripInlineComments(String value) {
        boolean inDoubleQuotes = false;
        boolean inSingleQuotes = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '#' && !inDoubleQuotes && !inSingleQuotes) {
                // We found a true inline comment. Chop the string here and trim trailing spaces.
                return value.substring(0, i).trim();
            }
        }
        return value;
    }

    /**
     * Inner class implementing the actual ConfigSource
     */
    private static class DotenvConfigSource implements ConfigSource {
        private final Map<String, String> properties;

        DotenvConfigSource(Map<String, String> properties) {
            this.properties = properties;
        }

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
            return "DotenvConfigSource";
        }

        @Override
        public int getOrdinal() {
            // CRITICAL: Must be exactly 296!
            // 295 = SmallRye Config's default .env reader (we must beat this)
            // 300 = Actual OS Environment Variables (must remain higher so Docker/CLI can override)
            return 296;
        }

        @Override
        public Set<String> getPropertyNames() {
            return properties.keySet();
        }
    }
}
