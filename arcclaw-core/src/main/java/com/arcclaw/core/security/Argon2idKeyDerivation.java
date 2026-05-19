package com.arcclaw.core.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jakarta.enterprise.context.ApplicationScoped;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Handles key derivation using the Argon2id algorithm (winner of the Password
 * Hashing Competition).
 * <p>
 * Configuration (Hardened for robustness vs performance):
 * <ul>
 * <li>Mode: Argon2id (resistant to both GPU and side-channel attacks)</li>
 * <li>Memory: 64 MB (65536 KB)</li>
 * <li>Iterations: 3</li>
 * <li>Parallelism: 1 (single-threaded derivation to avoid resource
 * contention)</li>
 * </ul>
 */
@ApplicationScoped
public class Argon2idKeyDerivation {

    // Argon2id Constants
    private static final int MEMORY_COST_KB = 65536; // 64 MB
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int KEY_LENGTH_BYTES = 32; // AES-256

    /**
     * A system-wide "pepper" secret injected from configuration.
     * This acts as a master secret component. If lost, all derived keys are
     * unrecoverable.
     */
    @ConfigProperty(name = "arcclaw.vault.pepper")
    char[] pepperChars;

    /**
     * Derives a symmetric encryption key for a specific session.
     * <p>
     * Logic: K_session = Argon2id(pass=pepper, salt=sessionId)
     *
     * @param sessionId The workspace session ID (acts as the salt).
     * @return A {@link VaultKey} containing the derived 32-byte AES key.
     */
    public VaultKey deriveSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty during key derivation");
        }
        if (pepperChars == null || pepperChars.length == 0) {
            throw new IllegalStateException("Pepper not configured - check arcclaw.vault.pepper property");
        }

        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pepperChars));
        byte[] passwordBytes = new byte[bb.remaining()];
        bb.get(passwordBytes);
        byte[] saltBytes = sessionId.getBytes(StandardCharsets.UTF_8);

        try {
            Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(MEMORY_COST_KB)
                    .withIterations(ITERATIONS)
                    .withParallelism(PARALLELISM)
                    .withSalt(saltBytes)
                    .build();

            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);

            byte[] resultKey = new byte[KEY_LENGTH_BYTES];
            generator.generateBytes(passwordBytes, resultKey);

            return new VaultKey(resultKey);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (bb.hasArray()) {
                Arrays.fill(bb.array(), (byte) 0);
            }
        }
    }

    // test-only — used by Argon2idKeyDerivationTest.setUp() to inject pepper without CDI
    void setPepperChars(char[] pepperChars) {
        this.pepperChars = pepperChars;
    }
}
