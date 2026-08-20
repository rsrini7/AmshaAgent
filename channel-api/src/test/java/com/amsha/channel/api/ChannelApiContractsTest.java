package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;

import org.junit.jupiter.api.Test;

class ChannelApiContractsTest {

    @Test
    void testChannelUser() {
        ChannelUser user = new ChannelUser("p1", "c1", "Alice", ChannelType.CLI);
        assertEquals("p1", user.platformId());
        assertEquals("c1", user.channelId());
        assertEquals("Alice", user.displayName());
        assertEquals(ChannelType.CLI, user.channel());

        // Default displayName
        ChannelUser defaultUser = new ChannelUser("p2", "c2", null, ChannelType.TELEGRAM);
        assertEquals("unknown", defaultUser.displayName());

        // Validation
        assertThrows(IllegalArgumentException.class, () -> new ChannelUser(null, "c1", "a", ChannelType.CLI));
        assertThrows(IllegalArgumentException.class, () -> new ChannelUser("p1", null, "a", ChannelType.CLI));
        assertThrows(IllegalArgumentException.class, () -> new ChannelUser("p1", "c1", "a", null));
    }

    @Test
    void testChannelMessages() {
        Instant now = Instant.now();
        ChannelUser user = new ChannelUser("p1", "c1", "Alice", ChannelType.TELEGRAM);

        ChannelMessage.TelegramMessage tg = new ChannelMessage.TelegramMessage(user, "tg text", "chat123", now);
        assertEquals(user, tg.sender());
        assertEquals("tg text", tg.text());
        assertEquals("chat123", tg.correlationId());
        assertEquals(ChannelType.TELEGRAM, tg.channelType());
        assertEquals(now, tg.receivedAt());

        ChannelMessage.DiscordMessage dc = new ChannelMessage.DiscordMessage(user, "dc text", "guild1", "chan1", now);
        assertEquals("chan1", dc.correlationId());
        assertEquals(ChannelType.DISCORD, dc.channelType());

        ChannelMessage.CliMessage cli = new ChannelMessage.CliMessage(user, "cli text", "sess1", now);
        assertEquals("sess1", cli.correlationId());
        assertEquals(ChannelType.CLI, cli.channelType());
    }

    @Test
    void testApprovalResult() {
        ApprovalResult result = new ApprovalResult(ApprovalOutcome.APPROVED, "sess1", "host_exec", 0, List.of());
        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        assertEquals("sess1", result.sessionId());
        assertEquals("host_exec", result.toolName());

        ApprovalResult already = ApprovalResult.alreadyDecided("sess2", "web_fetch");
        assertEquals(ApprovalOutcome.ALREADY_DECIDED, already.outcome());
        assertEquals("sess2", already.sessionId());
        assertEquals("web_fetch", already.toolName());
    }

    @Test
    void testApprovalPrompt() {
        ApprovalPrompt prompt = new ApprovalPrompt("sandbox_exec", "{\"code\": \"print(1)\"}", "sess1");
        assertEquals("sandbox_exec", prompt.toolName());
        assertEquals("{\"code\": \"print(1)\"}", prompt.paramsJson());
        assertEquals("sess1", prompt.sessionId());
        assertTrue(prompt.fallbackText().contains("sandbox_exec"));
    }
}
