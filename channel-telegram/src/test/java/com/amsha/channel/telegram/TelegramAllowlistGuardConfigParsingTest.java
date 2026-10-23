package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelegramAllowlistGuardConfigParsingTest {

    @Test
    void numericConfiguredIdMatches() {
        assertTrue(TelegramAllowlistGuard.isConfiguredUserId("123456", 123456L));
    }

    @Test
    void placeholderConfiguredIdIsRejectedWithoutThrowing() {
        assertFalse(TelegramAllowlistGuard.isConfiguredUserId("<telegram-user-ids-comma-seperated>", 123456L));
    }
}
