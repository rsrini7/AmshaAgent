package com.amsha.app.cli;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import com.amsha.app.security.VaultUnlockFailureException;
import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;
import com.amsha.common.util.SecretRedactor;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.security.api.VaultConstants;

/** Offers an explicit local SQLite reset when stored vault material cannot be unlocked. */
public final class CliStartupRecovery {
    private static final String RESTART_GUARD_ENV = "AMSHA_STARTUP_RECOVERY_RESTARTED";
    private static final String IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";
    private static final String CHOICE_BACKUP = "BACKUP";
    private static final String CHOICE_DELETE = "DELETE";
    private static final String BACKUP_SUFFIX = ".backup-";
    private static final String BACKUP_TIME_PATTERN = "yyyyMMdd-HHmmss";
    private static final String CONTROL_CHARACTERS = "\\p{Cntrl}";
    private static final String RESTART_GUARD_VALUE = "true";
    private static final int MAX_BACKUP_NAME_ATTEMPTS = 100;
    private static final int INTERRUPTED_EXIT_CODE = 130;

    private CliStartupRecovery() {}

    /** Prompts only for the typed vault unlock failure and returns the appropriate exit code. */
    public static int offerRecovery(Throwable failure, String[] args, PrintStream errorOutput) {
        VaultUnlockFailureException unlockFailure = findUnlockFailure(failure);
        if (unlockFailure == null) {
            return 1;
        }
        if (RESTART_GUARD_VALUE.equalsIgnoreCase(System.getenv(RESTART_GUARD_ENV))) {
            errorOutput.println("[Amsha Agent] Startup recovery already ran once; no further data changes were made.");
            return 1;
        }
        if (!PersistenceBackend.SQLITE.equalsIgnoreCase(unlockFailure.persistenceBackend())) {
            errorOutput.printf(
                    "[Amsha Agent] Automatic reset is available for SQLite only; current backend is %s.%n"
                            + "Restore the original AMSHA_VAULT_PEPPER or use the recovery procedure for this backend.%n",
                    SecretRedactor.redact(unlockFailure.persistenceBackend()));
            return 1;
        }

        Path dataDirectory = resolveSafeDataDirectory(unlockFailure.dataDirectory(), errorOutput);
        if (dataDirectory == null) {
            return 1;
        }
        Console console = System.console();
        if (console == null) {
            errorOutput.printf(
                    "[Amsha Agent] No reset was performed. Run this command in an interactive terminal to choose "
                            + "BACKUP or DELETE for %s, or restore the original pepper.%n",
                    displayPath(dataDirectory));
            return 1;
        }

        Path backupDirectory;
        try {
            backupDirectory = nextBackupPath(dataDirectory);
        } catch (IOException e) {
            errorOutput.printf(
                    "[Amsha Agent] Cannot prepare a backup path: %s. No data was changed.%n",
                    SecretRedactor.redact(LogUtil.rootMessage(e)));
            return 1;
        }

        printRecoveryOptions(dataDirectory, backupDirectory, errorOutput);
        String enteredChoice = console.readLine("Type BACKUP, DELETE, or press Enter to cancel: ");
        RecoveryChoice choice = RecoveryChoice.parse(enteredChoice);
        if (choice == RecoveryChoice.CANCEL) {
            errorOutput.println("[Amsha Agent] Recovery cancelled. No data was changed.");
            return 1;
        }

        try {
            applyRecovery(choice, dataDirectory, backupDirectory);
        } catch (IOException e) {
            errorOutput.printf(
                    "[Amsha Agent] Recovery did not complete: %s. Verify the data directory before retrying.%n",
                    SecretRedactor.redact(LogUtil.rootMessage(e)));
            return 1;
        }

        if (choice == RecoveryChoice.BACKUP) {
            errorOutput.printf("[Amsha Agent] Existing SQLite data moved to %s.%n", displayPath(backupDirectory));
        } else {
            errorOutput.printf("[Amsha Agent] Files in %s were permanently deleted.%n", displayPath(dataDirectory));
        }
        errorOutput.println("[Amsha Agent] Starting again with a fresh SQLite data directory.");
        return restartNativeProcess(args, errorOutput);
    }

    private static void printRecoveryOptions(Path dataDirectory, Path backupDirectory, PrintStream errorOutput) {
        errorOutput.println();
        errorOutput.println("Stored vault data cannot be decrypted with the configured pepper.");
        errorOutput.printf("Data directory: %s%n", displayPath(dataDirectory));
        errorOutput.println("Choose how to continue with a fresh SQLite store:");
        errorOutput.printf(
                "  %s  Move the current directory to %s and retain it as a backup.%n",
                CHOICE_BACKUP, displayPath(backupDirectory));
        errorOutput.printf("  %s  Permanently delete every file in the current data directory.%n", CHOICE_DELETE);
        errorOutput.println("Every file in this directory is affected. Paths configured outside it are left alone.");
        errorOutput.println("Stop any other Amsha process using this data directory before confirming.");
        errorOutput.printf(
                "For automatic first-boot seeding, verify .env has %s=true and a stable Base64-encoded %s value.%n",
                ConfigKeys.AUTO_SEED_SECRETS_ENV, VaultConstants.AUDIT_HMAC_KEY);
        errorOutput.println("Restore the original AMSHA_VAULT_PEPPER if you need to keep existing data.");
    }

    private static Path resolveSafeDataDirectory(String configuredDirectory, PrintStream errorOutput) {
        Path dataDirectory;
        try {
            dataDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            errorOutput.println("[Amsha Agent] The configured SQLite data directory is invalid. No data was changed.");
            return null;
        }
        if (Files.isSymbolicLink(dataDirectory)
                || !Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)
                || isUnsafeDataDirectory(dataDirectory)) {
            errorOutput.printf(
                    "[Amsha Agent] Reset is disabled for this data directory because it is missing, linked, "
                            + "or contains the current working directory or executable: %s%n",
                    displayPath(dataDirectory));
            return null;
        }
        return dataDirectory;
    }

    private static boolean isUnsafeDataDirectory(Path dataDirectory) {
        Path root = dataDirectory.getRoot();
        if (root == null || dataDirectory.equals(root)) {
            return true;
        }
        Path home =
                Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        if (dataDirectory.equals(home) || home.startsWith(dataDirectory)) {
            return true;
        }
        Path workingDirectory =
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (workingDirectory.startsWith(dataDirectory)) {
            return true;
        }
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            return Path.of(command).toAbsolutePath().normalize().startsWith(dataDirectory);
        } catch (RuntimeException _) {
            return true;
        }
    }

    private static Path nextBackupPath(Path dataDirectory) throws IOException {
        String timestamp =
                DateTimeFormatter.ofPattern(BACKUP_TIME_PATTERN, Locale.ROOT).format(LocalDateTime.now());
        String directoryName = dataDirectory.getFileName().toString();
        for (int attempt = 0; attempt < MAX_BACKUP_NAME_ATTEMPTS; attempt++) {
            String suffix = attempt == 0 ? timestamp : timestamp + "-" + attempt;
            Path candidate = dataDirectory.resolveSibling(directoryName + BACKUP_SUFFIX + suffix);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("No unused backup directory name is available");
    }

    private static void applyRecovery(RecoveryChoice choice, Path dataDirectory, Path backupDirectory)
            throws IOException {
        if (choice == RecoveryChoice.BACKUP) {
            try {
                Files.move(dataDirectory, backupDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(dataDirectory, backupDirectory);
            }
        } else if (choice == RecoveryChoice.DELETE) {
            deleteTree(dataDirectory);
        }
    }

    private static void deleteTree(Path dataDirectory) throws IOException {
        Files.walkFileTree(dataDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static int restartNativeProcess(String[] args, PrintStream errorOutput) {
        if (System.getProperty(IMAGE_CODE_PROPERTY) == null) {
            errorOutput.println("[Amsha Agent] Restart the same command to initialize the fresh data directory.");
            return 1;
        }
        String executable = ProcessHandle.current().info().command().orElse(null);
        if (executable == null || executable.isBlank()) {
            errorOutput.println("[Amsha Agent] Could not locate the executable. Restart the same command manually.");
            return 1;
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(executable);
        Collections.addAll(command, args);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
            processBuilder.environment().put(RESTART_GUARD_ENV, RESTART_GUARD_VALUE);
            return processBuilder.start().waitFor();
        } catch (IOException e) {
            errorOutput.printf(
                    "[Amsha Agent] Could not restart automatically: %s. Restart the same command manually.%n",
                    SecretRedactor.redact(LogUtil.rootMessage(e)));
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorOutput.println("[Amsha Agent] Restart was interrupted. Run the same command again.");
            return INTERRUPTED_EXIT_CODE;
        }
    }

    private static VaultUnlockFailureException findUnlockFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof VaultUnlockFailureException unlockFailure) {
                return unlockFailure;
            }
        }
        return null;
    }

    private static String displayPath(Path path) {
        return SecretRedactor.redact(path.toString()).replaceAll(CONTROL_CHARACTERS, "?");
    }

    private enum RecoveryChoice {
        BACKUP,
        DELETE,
        CANCEL;

        static RecoveryChoice parse(String input) {
            if (input == null) {
                return CANCEL;
            }
            String normalized = input.trim().toUpperCase(Locale.ROOT);
            if (CHOICE_BACKUP.equals(normalized)) {
                return BACKUP;
            }
            if (CHOICE_DELETE.equals(normalized)) {
                return DELETE;
            }
            return CANCEL;
        }
    }
}
