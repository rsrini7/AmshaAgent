package com.arcclaw.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelUser} compact constructor validation.
 */
class ChannelUserTest {

    @Test
    void nullPlatformIdThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> new ChannelUser(null, null, "TestUser", ChannelType.TELEGRAM));
        assertTrue(ex.getMessage().contains("platformId"));
    }

    @Test
    void nullChannelIdThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> new ChannelUser("123", null, "TestUser", ChannelType.TELEGRAM));
        assertTrue(ex.getMessage().contains("channelId"));
    }

    @Test
    void nullChannelThrows() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> new ChannelUser("123", "123", "TestUser", null));
        assertTrue(ex.getMessage().contains("channel"));
    }

    @Test
    void testChannelUserHasChannelId() {
        ChannelUser user = new ChannelUser("platform-123", "channel-456", "TestUser", ChannelType.TELEGRAM);
        assertEquals("platform-123", user.platformId());
        assertEquals("channel-456", user.channelId());
        assertEquals("TestUser", user.displayName());
        assertEquals(ChannelType.TELEGRAM, user.channel());
    }

    @Test
    void nullDisplayNameDefaultsToUnknown() {
        ChannelUser user = new ChannelUser("123", "123", null, ChannelType.TELEGRAM);
        assertEquals("unknown", user.displayName());
    }
}
