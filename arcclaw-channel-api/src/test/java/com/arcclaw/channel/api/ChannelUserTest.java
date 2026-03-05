package com.arcclaw.channel.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChannelUser} compact constructor validation.
 */
class ChannelUserTest {

    @Test
    void nullPlatformIdThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelUser(null, "TestUser", ChannelType.TELEGRAM)
        );
        assertTrue(ex.getMessage().contains("platformId"));
    }

    @Test
    void nullChannelThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelUser("123", "TestUser", null)
        );
        assertTrue(ex.getMessage().contains("channel"));
    }

    @Test
    void nullDisplayNameDefaultsToUnknown() {
        ChannelUser user = new ChannelUser("123", null, ChannelType.TELEGRAM);
        assertEquals("unknown", user.displayName());
    }
}
