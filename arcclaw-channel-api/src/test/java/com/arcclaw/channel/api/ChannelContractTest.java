package com.arcclaw.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import org.junit.jupiter.api.Test;

/**
 * SR-4: Test coverage for arcclaw-channel-api.
 * Focuses on state transitions and HITL contract integrity.
 */
public class ChannelContractTest {

    @Test
    void testApprovalDecisionEventIntegrity() {
        ApprovalDecisionEvent event = new ApprovalDecisionEvent(
            "sess-1",
            "telegram",
            "plat-1",
            "tool-1",
            ApprovalDecision.APPROVE,
            ApprovalOutcome.APPROVED
        );

        assertEquals(ApprovalOutcome.APPROVED, event.outcome());
        assertEquals("sess-1", event.sessionId());
        assertEquals(ApprovalDecision.APPROVE, event.decision());
    }

    @Test
    void testChannelMessageContract() {
        ChannelUser user = new ChannelUser("u1", "chat-1", "user1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(
            user,
            "Hello",
            "chat-1",
            Instant.now()
        );

        assertEquals(ChannelType.TELEGRAM, msg.channelType());
        assertEquals("Hello", msg.text());
        assertEquals("chat-1", msg.correlationId());
    }
}
