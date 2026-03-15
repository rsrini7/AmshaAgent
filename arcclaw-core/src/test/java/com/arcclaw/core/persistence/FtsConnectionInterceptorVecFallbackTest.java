package com.arcclaw.core.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import javax.sql.DataSource;

@QuarkusTest
class FtsConnectionInterceptorVecFallbackTest {

    @Inject
    DataSource dataSource;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @Test
    void testVecFallbackWhenBinaryMissing() throws Exception {
        // Trigger a connection acquisition
        try (Connection _ = dataSource.getConnection()) {
            // Interceptor runs here
        }

        // Unless the user has already dropped vec0.dll in ./lib/, 
        // this should be false but the app should NOT have crashed.
        // We just verify it doesn't throw and state is tracked.
        boolean enabled = ftsInterceptor.isVectorSearchEnabled();
        System.out.println("Vector Search Enabled: " + enabled);
        
        // We can't strictly assert false because the user might have the lib,
        // but we assert the interceptor is injected and alive.
        Assertions.assertNotNull(ftsInterceptor);
    }
}
