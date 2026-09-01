package com.amsha.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom ConfigSourceProvider to natively parse .env files.
 * <p>
 * Quarkus DEV mode executes from within the `amsha-app` directory,
 * skipping the root `.env` file. This provider explicitly searches
 * both the current directory (`.env`) and parent directory (`../.env`),
 * parsing the K-V pairs so they are available for recursive variable expansion
 * in application.yaml (e.g. ${TELEGRAM_BOT_TOKEN}).
 */
public class DotenvConfigSourceProvider implements ConfigSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(DotenvConfigSourceProvider.class);

    private static final AtomicReference<CachedDotenv> CACHE = new AtomicReference<>();

    private record CachedDotenv(Path path, FileTime lastModified, String contentHash, Map<String, String> properties) {}

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        if (isDisabled()) {
            log.debug("DotenvConfigSourceProvider is disabled via amsha.dotenv.enabled=false");
            return Collections.singletonList(new DotenvConfigSource(Collections.emptyMap()));
        }
        Map<String, String> dotenvProperties = loadDotenv();
        return Collections.singletonList(new DotenvConfigSource(dotenvProperties));
    }

    private boolean isDisabled() {
        String sysProp = System.getProperty("amsha.dotenv.enabled");
        if (sysProp != null) {
            return "false".equalsIgnoreCase(sysProp);
        }
        String envVar = System.getenv("AMSHA_DOTENV_ENABLED");
        if (envVar != null) {
            return "false".equalsIgnoreCase(envVar);
        }
        return false;
    }

    private Map<String, String> loadDotenv() {
        Path envPath = resolveEnvPath();

        if (!Files.exists(envPath)) {
            return Collections.emptyMap(); // No .env file found, rely on system/environment variables
        }

        try {
            FileTime currentModified = Files.getLastModifiedTime(envPath);
            String content = Files.readString(envPath, StandardCharsets.UTF_8);
            String contentHash = sha256(content);
            CachedDotenv cached = CACHE.get();
            if (cached != null
                    && cached.path().equals(envPath)
                    && cached.lastModified().equals(currentModified)
                    && cached.contentHash().equals(contentHash)) {
                return cached.properties();
            }

            Map<String, String> properties = new HashMap<>();
            for (String line : content.lines().toList()) {
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
            Map<String, String> immutableProperties = Map.copyOf(properties);
            CACHE.set(new CachedDotenv(envPath, currentModified, contentHash, immutableProperties));
            log.debug("Loaded {} properties from .env file.", immutableProperties.size());
            return immutableProperties;
        } catch (IOException e) {
            log.warn("Failed to read .env file. Falling back to default environment variables.", e);
            return Collections.emptyMap();
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static Path resolveEnvPath(Path workingDirectory) {
        Path currentEnv = workingDirectory.toAbsolutePath().normalize().resolve(".env");
        if (Files.exists(currentEnv)) {
            return currentEnv;
        }
        Path parentDirectory = currentEnv.getParent();
        if (parentDirectory != null && parentDirectory.getParent() != null) {
            Path parentEnv = parentDirectory.getParent().resolve(".env");
            if (Files.exists(parentEnv)) {
                return parentEnv;
            }
        }
        return currentEnv;
    }

    private Path resolveEnvPath() {
        Path workingDirectoryEnv = resolveEnvPath(Paths.get("."));
        if (Files.exists(workingDirectoryEnv)) {
            return workingDirectoryEnv;
        }
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String command = ProcessHandle.current().info().command().orElse("");
            if (!command.isEmpty()) {
                try {
                    Path binaryDir =
                            Path.of(command).toAbsolutePath().normalize().getParent();
                    if (binaryDir != null) {
                        Path binaryEnv = binaryDir.resolve(".env");
                        if (Files.exists(binaryEnv)) {
                            return binaryEnv;
                        }
                    }
                } catch (Exception e) {
                    // fall through to CWD
                }
            }
        }
        return workingDirectoryEnv;
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
