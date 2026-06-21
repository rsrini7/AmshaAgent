package com.arcclaw.app;

import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import com.arcclaw.core.channel.ChannelPluginRegistry;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ModuleBoundaryTest {

    @Inject
    ChannelPluginRegistry registry;

    @Test
    void testTelegramClassesNotOnAppClasspath() {
        // These should fail because the package no longer exists in arcclaw-app scope
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.arcclaw.app.channel.telegram.TelegramChannelPlugin"));
        assertThrows(
                ClassNotFoundException.class, () -> Class.forName("com.arcclaw.app.security.TelegramAllowlistGuard"));
    }
}
