package com.amsha.app.security;

/** Identifies a stored vault key that cannot be unlocked with the configured pepper. */
public final class VaultUnlockFailureException extends IllegalStateException {
    public static final String DIAGNOSTIC = "[SECURITY] Stored audit HMAC key cannot be decrypted. "
            + "AMSHA_VAULT_PEPPER must match the value used to create this data. "
            + "Restore the original pepper to keep existing data. "
            + "If the pepper is unrecoverable and SQLite data is disposable, the interactive native CLI offers "
            + "BACKUP (move the directory) or DELETE (permanently erase it); stop other Amsha processes first. "
            + "PostgreSQL and filesystem installs need backend-specific recovery. "
            + "Do not discard the backup.";

    private final String persistenceBackend;
    private final String dataDirectory;

    public VaultUnlockFailureException(String persistenceBackend, String dataDirectory, SecurityException cause) {
        super(DIAGNOSTIC, cause);
        this.persistenceBackend = persistenceBackend;
        this.dataDirectory = dataDirectory;
    }

    public String persistenceBackend() {
        return persistenceBackend;
    }

    public String dataDirectory() {
        return dataDirectory;
    }
}
