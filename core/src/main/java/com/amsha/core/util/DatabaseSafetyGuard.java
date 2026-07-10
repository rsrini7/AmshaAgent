package com.amsha.core.util;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Quarkus;

/**
 * Monitors connection exceptions to detect critical database file/directory loss at runtime.
 * If detected, prints a user-friendly instruction to restart and initiates a graceful shutdown.
 */
public class DatabaseSafetyGuard {

    private static final Logger log = Logger.getLogger(DatabaseSafetyGuard.class);
    private static volatile boolean exiting = false;

    /**
     * Checks if the given throwable is caused by the database file or its parent directory being missing.
     * If so, logs a user-friendly crash message and initiates Quarkus shutdown.
     *
     * @param t the throwable to analyze
     * @return true if a database missing failure was detected and shutdown was initiated
     */
    public static boolean checkDatabaseFailure(Throwable t) {
        if (exiting) {
            return true;
        }

        String msg = t.getMessage() != null ? t.getMessage() : "";
        Throwable cause = t.getCause();
        while (cause != null) {
            msg += " | " + (cause.getMessage() != null ? cause.getMessage() : "");
            cause = cause.getCause();
        }

        // Catch typical SQLite or Filesystem backend errors:
        // - SQLite: "path to '...': '...' does not exist" or similar.
        // - Filesystem: NoSuchFileException, FileNotFoundException, or directory write/read/lucene failures.
        boolean isSqliteFailure =
                msg.contains("does not exist") && (msg.contains("amsha-meta.db") || msg.contains("sqlite"));
        boolean isFsFailure = (msg.contains("NoSuchFileException")
                        || msg.contains("FileNotFoundException")
                        || msg.contains("does not exist")
                        || msg.contains("Failed to write")
                        || msg.contains("Failed to read"))
                && (msg.contains("data/fs")
                        || msg.contains("lucene-index")
                        || msg.contains("memory-index")
                        || msg.contains("persistence-fs"));

        if (isSqliteFailure || isFsFailure) {
            triggerShutdown();
            return true;
        }

        return false;
    }

    private static synchronized void triggerShutdown() {
        if (exiting) {
            return;
        }
        exiting = true;

        System.err.println("\n================================================================================\n"
                + "🔥 CRITICAL DATABASE DIRECTORY OR FILE IS MISSING!\n"
                + "The database folder has been deleted or is inaccessible while the server was running.\n"
                + "The application cannot recover from this state and is exiting immediately.\n"
                + "👉 Please verify your data directory and RESTART the application.\n"
                + "================================================================================\n");

        log.error("Critical database folder missing. Initiating graceful shutdown...");

        // Run exit in a virtual thread so we do not block the scheduled task runner / active worker.
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(500); // Give MDC/stdout buffers a small window to flush
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Quarkus.asyncExit(1);
        });
    }
}
