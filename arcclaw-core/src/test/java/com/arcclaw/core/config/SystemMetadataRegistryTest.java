package com.arcclaw.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemMetadataRegistryTest {

    private SystemMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SystemMetadataRegistry();
        registry.appName      = "arcclaw-test";
        registry.appVersion   = "1.2.3";
        registry.activeProfile = "test";
        registry.init();
    }

    @Test
    void testToInfo_populatesAllFields() {
        SystemMetadataRegistry.SystemInfo info = registry.toInfo();
        
        assertEquals("arcclaw-test", info.name());
        assertEquals("1.2.3", info.version());
        assertNotNull(info.environment());
        assertEquals("test", info.environment());
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
