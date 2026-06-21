package com.amsha.app;

import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import com.amsha.core.channel.ChannelPluginRegistry;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ModuleBoundaryTest {

    @Inject
    ChannelPluginRegistry registry;

    @Test
    void testTelegramClassesNotOnAppClasspath() {
        // These should fail because the package no longer exists in amsha-app scope
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.amsha.app.channel.telegram.TelegramChannelPlugin"));
        assertThrows(
                ClassNotFoundException.class, () -> Class.forName("com.amsha.app.security.TelegramAllowlistGuard"));
    }
}
