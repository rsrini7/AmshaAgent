package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

class PhoenixExporterConfigTest {

    @Test
    void disabledReturnsNullProvider() throws Exception {
        var config = new PhoenixExporterConfig(false, "http://localhost:6006/v1/traces");
        assertNull(getProvider(config));
    }

    @Test
    void enabledCreatesProvider() throws Exception {
        var config = new PhoenixExporterConfig(true, "http://localhost:6006/v1/traces");
        assertNotNull(getProvider(config));
    }

    @Test
    void getTracerProviderReturnsProvider() throws Exception {
        var config = new PhoenixExporterConfig(true, "http://localhost:6006/v1/traces");
        assertNotNull(config.getTracerProvider());
    }

    @Test
    void getTracerProviderReturnsNullWhenDisabled() throws Exception {
        var config = new PhoenixExporterConfig(false, "http://localhost:6006/v1/traces");
        assertNull(config.getTracerProvider());
    }

    private static SdkTracerProvider getProvider(PhoenixExporterConfig config) throws Exception {
        Field f = PhoenixExporterConfig.class.getDeclaredField("phoenixTracerProvider");
        f.setAccessible(true);
        return (SdkTracerProvider) f.get(config);
    }
}
