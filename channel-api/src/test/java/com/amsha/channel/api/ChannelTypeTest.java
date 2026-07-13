package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChannelTypeTest {

    @Test
    void testFromString_validTypes() {
        assertEquals(Optional.<ChannelType>of(ChannelType.TELEGRAM), ChannelType.fromString("TELEGRAM"));
        assertEquals(Optional.<ChannelType>of(ChannelType.DISCORD), ChannelType.fromString("discord"));
        assertEquals(Optional.<ChannelType>of(ChannelType.SLACK), ChannelType.fromString("Slack"));
    }

    @Test
    void testFromString_unknownReturnsEmpty() {
        assertTrue(ChannelType.fromString("custom").isEmpty());
        assertTrue(ChannelType.fromString(null).isEmpty());
        assertTrue(ChannelType.fromString("").isEmpty());
        assertTrue(ChannelType.fromString("  ").isEmpty());
    }

    @Test
    void testFlyweightIdentity() {
        ChannelType a = ChannelType.of("whatsapp");
        ChannelType b = ChannelType.of("WHATSAPP");
        // Flyweight guarantees reference equality for the same normalized id
        assertSame(a, b, "ChannelType.of() must return same instance for same id");
        assertEquals("whatsapp", a.id());
    }

    @Test
    void testFallbackPattern() {
        Optional<ChannelType> maybeChannelType = ChannelType.fromString(null);
        ChannelType ch = maybeChannelType.orElse(ChannelType.TELEGRAM);
        assertEquals(ChannelType.TELEGRAM, ch);
    }

    @Test
    void testNameUppercase() {
        assertEquals("TELEGRAM", ChannelType.TELEGRAM.name());
        assertEquals("CLI", ChannelType.CLI.name());
    }

    @Test
    void testIdentityEquality() {
        // Same reference
        assertSame(ChannelType.TELEGRAM, ChannelType.of("telegram"));
        // Different id
        assertNotSame(ChannelType.TELEGRAM, ChannelType.DISCORD);
    }
}
