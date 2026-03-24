package com.arcclaw.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChannelTypeTest {

    @Test
    void testFromString_validTypes() {
        assertEquals(Optional.of(ChannelType.TELEGRAM), ChannelType.fromString("TELEGRAM"));
        assertEquals(Optional.of(ChannelType.DISCORD), ChannelType.fromString("discord"));
        assertEquals(Optional.of(ChannelType.SLACK), ChannelType.fromString("Slack"));
    }

    @Test
    void testFromString_invalidReturnsEmpty() {
        // Now any string is valid as a custom channel type
        assertTrue(ChannelType.fromString("custom").isPresent());
        assertEquals(new ChannelType("custom"), ChannelType.fromString("CUSTOM").get());
        
        assertTrue(ChannelType.fromString(null).isEmpty());
        assertTrue(ChannelType.fromString("").isEmpty());
        assertTrue(ChannelType.fromString("  ").isEmpty());
    }

    @Test
    void testCustomChannelTypeCreatable() {
        ChannelType custom = new ChannelType("whatsapp");
        assertEquals("whatsapp", custom.id());
        
        ChannelType alsoCustom = ChannelType.fromString("WHATSAPP").get();
        assertEquals(custom, alsoCustom);
    }

    @Test
    void testFallbackPattern() {
        // This simulates how we use it in the app for legacy rows
        ChannelType ch = ChannelType.fromString(null).orElse(ChannelType.TELEGRAM);
        assertEquals(ChannelType.TELEGRAM, ch);
    }
}
