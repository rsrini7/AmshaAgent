package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

import org.junit.jupiter.api.Test;

class TelegramMessageMapperTest {

    private final TelegramMessageMapper mapper = new TelegramMessageMapper();

    @Test
    void testMapValidMessage() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        ChannelMessage msg = mapper.map("hello world", user, "c1");

        assertNotNull(msg);
        assertTrue(msg instanceof ChannelMessage.TelegramMessage);
        assertEquals("hello world", msg.text());
        assertEquals("c1", msg.correlationId());
        assertEquals(user, msg.sender());
    }

    @Test
    void testMapNullOrBlank() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        assertNull(mapper.map(null, user, "c1"));
        assertNull(mapper.map("   ", user, "c1"));
    }
}
