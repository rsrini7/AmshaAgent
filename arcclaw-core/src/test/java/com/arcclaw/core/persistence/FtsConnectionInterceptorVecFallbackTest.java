package com.arcclaw.core.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain JUnit test — no Quarkus container needed.
 * Verifies that FtsConnectionInterceptor gracefully handles a missing vec0 binary
 * without crashing the application.
 */
class FtsConnectionInterceptorVecFallbackTest {

    @TempDir
    Path tempDir;

    @Test
    void testVecFallbackWhenBinaryMissing() throws Exception {
        // Create a real SQLite connection (no Quarkus DataSource needed)
        String dbPath = tempDir.resolve("test-fallback.db").toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Manually create the interceptor and set config fields via reflection
            FtsConnectionInterceptor interceptor = new FtsConnectionInterceptor();

            // Set config properties via reflection (they're package-private fields)
            var dataDirField = FtsConnectionInterceptor.class.getDeclaredField("dataDir");
            dataDirField.setAccessible(true);
            dataDirField.set(interceptor, tempDir.toAbsolutePath().toString());

            var vecPathField = FtsConnectionInterceptor.class.getDeclaredField("vecExtensionPath");
            vecPathField.setAccessible(true);
            vecPathField.set(interceptor, "/tmp/non_existent_vec0_binary");

            // Exercise: the interceptor should NOT throw even with a missing binary
            interceptor.onConnectionAcquire(conn);

            // Verify: vector search should be disabled (binary missing), app didn't crash
            Assertions.assertFalse(interceptor.isVectorSearchEnabled(),
                    "Vector search should be disabled when vec0 binary is missing");

            // Verify the interceptor is still functional
            Assertions.assertNotNull(interceptor);
        }
    }
}
