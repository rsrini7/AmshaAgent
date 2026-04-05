package com.arcclaw.core.persistence;

import java.sql.Connection;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

@QuarkusTest
@TestProfile(FtsConnectionInterceptorVecFallbackTest.MissingBinaryProfile.class)
class FtsConnectionInterceptorVecFallbackTest {

    public static class MissingBinaryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Force a non-existent path to guarantee the fallback pathway is tested
            return Collections.singletonMap("arcclaw.rag.vec-extension-path", "/tmp/non_existent_vec0");
        }
    }

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
        Assertions.assertFalse(enabled);
        
        // We can't strictly assert false because the user might have the lib,
        // but we assert the interceptor is injected and alive.
        Assertions.assertNotNull(ftsInterceptor);
    }
}
