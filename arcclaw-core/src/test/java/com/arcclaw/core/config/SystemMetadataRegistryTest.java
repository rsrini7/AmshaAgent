package com.arcclaw.core.config;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SystemMetadataRegistryTest {

    private SystemMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SystemMetadataRegistry();
        registry.appName = "arcclaw-test";
        registry.appVersion = "1.2.3";
        registry.init();
    }

    @Test
    void testResolveEnvironment_development_returnsDev() {
        assertEquals("dev", registry.resolveEnvironment(LaunchMode.DEVELOPMENT));
    }

    @Test
    void testResolveEnvironment_test_returnsTest() {
        assertEquals("test", registry.resolveEnvironment(LaunchMode.TEST));
    }

    @Test
    void testResolveEnvironment_normal_returnsProd() {
        assertEquals("prod", registry.resolveEnvironment(LaunchMode.NORMAL));
    }

    @Test
    void testToInfo_populatesAllFields() {
        SystemMetadataRegistry.SystemInfo info = registry.toInfo();
        
        assertEquals("arcclaw-test", info.name());
        assertEquals("1.2.3", info.version());
        assertNotNull(info.environment());
        assertNotNull(info.startedAt());
    }

    @Test
    void testStartedAt_isSetAtStartup_notNull() {
        assertNotNull(registry.getStartedAt());
    }
    
    @Test
    void testGetters() {
        assertEquals("arcclaw-test", registry.getAppName());
        assertEquals("1.2.3", registry.getAppVersion());
    }
}
