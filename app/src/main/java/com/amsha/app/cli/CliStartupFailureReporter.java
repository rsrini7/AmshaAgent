package com.amsha.app.cli;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.SecretRedactor;

/** Renders actionable startup diagnostics for users running the plain CLI binary. */
public final class CliStartupFailureReporter {

    private static final String PROFILE_PROPERTY = "quarkus.profile";
    private static final String PROFILE_ENV = "QUARKUS_PROFILE";
    private static final String LOG_FILE_ENABLED_PROPERTY = "quarkus.log.file.enabled";
    private static final String LOG_FILE_ENABLED_ENV = "QUARKUS_LOG_FILE_ENABLED";
    private static final String LOG_FILE_PATH_PROPERTY = "quarkus.log.file.path";
    private static final String LOG_FILE_PATH_ENV = "QUARKUS_LOG_FILE_PATH";
    private static final String PROFILE_CLI = "cli";
    private static final String PROFILE_DEV = "dev";
    private static final String PROFILE_FILESYSTEM = "filesystem";
    private static final String PROFILE_POSTGRESQL = "postgresql";
    private static final String PROFILE_PROD = "prod";
    private static final String PROFILE_STAGING = "staging";
    private static final String PROFILE_TEST = "test";
    private static final String LOG_FILE_CLI = "amsha-cli-sqlite.log";
    private static final String LOG_FILE_DEV = "amsha-dev-sqlite.log";
    private static final String LOG_FILE_FILESYSTEM = "amsha-filesystem-fs.log";
    private static final String LOG_FILE_POSTGRESQL = "amsha-postgresql-postgres.log";
    private static final String LOG_FILE_PROD = "amsha-prod-sqlite.log";
    private static final String LOG_FILE_STAGING = "amsha-staging-sqlite.log";
    private static final String LOG_FILE_TEST = "amsha-test-sqlite.log";
    private static final String DEFAULT_PROFILE = PROFILE_PROD;
    private static final String DEFAULT_LOG_FILE = LOG_FILE_PROD;
    private static final String SECURITY_DIAGNOSTIC_PREFIX = "[SECURITY]";
    private static final String FATAL_DIAGNOSTIC_PREFIX = "FATAL:";

    private static final Map<String, String> PROFILE_LOG_FILES = Map.of(
            PROFILE_CLI, LOG_FILE_CLI,
            PROFILE_DEV, LOG_FILE_DEV,
            PROFILE_FILESYSTEM, LOG_FILE_FILESYSTEM,
            PROFILE_POSTGRESQL, LOG_FILE_POSTGRESQL,
            PROFILE_PROD, LOG_FILE_PROD,
            PROFILE_STAGING, LOG_FILE_STAGING,
            PROFILE_TEST, LOG_FILE_TEST);

    private CliStartupFailureReporter() {}

    public static void report(String stage, Throwable failure, PrintStream errorOutput) {
        errorOutput.printf("[Amsha Agent] Could not complete %s.%n", stage);
        errorOutput.printf("Reason: %s%n", safeReason(failure));
        printLogHint(errorOutput);
    }

    public static void reportNotice(String notice, PrintStream errorOutput) {
        errorOutput.printf("[Amsha Agent] %s%n", SecretRedactor.redact(notice));
        printLogHint(errorOutput);
    }

    private static void printLogHint(PrintStream errorOutput) {
        Path logFile = configuredLogFile();
        if (logFile != null) {
            errorOutput.printf("Diagnostic log path (when Quarkus logging has started): %s%n", logFile);
            errorOutput.println(
                    "For failures after logging starts, review this file for more detail and retry the command.");
            return;
        }

        errorOutput.println("No file log is enabled for the active profile.");
        errorOutput.println("For a detailed log, set QUARKUS_LOG_FILE_ENABLED=true and optionally:");
        errorOutput.println("QUARKUS_LOG_FILE_PATH=./amsha-startup.log");
    }

    static Path configuredLogFile() {
        String profile = firstConfiguredValue(PROFILE_PROPERTY, PROFILE_ENV);
        if (profile == null || profile.isBlank()) {
            profile = DEFAULT_PROFILE;
        }

        String enabled = firstConfiguredValue(LOG_FILE_ENABLED_PROPERTY, LOG_FILE_ENABLED_ENV);
        String configuredPath = firstConfiguredValue(LOG_FILE_PATH_PROPERTY, LOG_FILE_PATH_ENV);
        String defaultPath = PROFILE_LOG_FILES.get(profile.toLowerCase(Locale.ROOT));

        boolean fileLoggingEnabled = enabled == null ? defaultPath != null : Boolean.parseBoolean(enabled);
        if (!fileLoggingEnabled) {
            return null;
        }

        String logPath = configuredPath == null || configuredPath.isBlank()
                ? defaultPath == null ? DEFAULT_LOG_FILE : defaultPath
                : configuredPath;
        try {
            return Path.of(logPath).toAbsolutePath().normalize();
        } catch (InvalidPathException _) {
            return null;
        }
    }

    private static String firstConfiguredValue(String propertyName, String environmentName) {
        String value = System.getProperty(propertyName);
        if (value != null) {
            return value;
        }
        value = System.getProperty(environmentName);
        return value == null ? System.getenv(environmentName) : value;
    }

    private static String safeReason(Throwable failure) {
        if (failure == null) {
            return "No exception details were provided.";
        }
        String diagnostic = startupDiagnosticMessage(failure);
        if (diagnostic == null) {
            diagnostic = LogUtil.rootMessage(new IllegalStateException(failure));
        }
        return SecretRedactor.redact(diagnostic);
    }

    private static String startupDiagnosticMessage(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && (message.startsWith(SECURITY_DIAGNOSTIC_PREFIX)
                            || message.startsWith(FATAL_DIAGNOSTIC_PREFIX))) {
                return message;
            }
        }
        return null;
    }
}
