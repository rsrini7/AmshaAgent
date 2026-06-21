package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalOutcome;

import org.junit.jupiter.api.Test;

/**
 * SR-4: Test coverage for amsha-channel-api.
 * Focuses on sealed interface dispatch contracts and HITL contract integrity.
 */
public class ChannelContractTest {

    @Test
    void testApprovalDecisionEventIntegrity() {
        ApprovalDecisionEvent event = new ApprovalDecisionEvent(
                "sess-1", "telegram", "plat-1", "tool-1", ApprovalDecision.APPROVE, ApprovalOutcome.APPROVED);
        assertEquals(ApprovalOutcome.APPROVED, event.outcome());
        assertEquals("sess-1", event.sessionId());
        assertEquals(ApprovalDecision.APPROVE, event.decision());
    }

    // ── ChannelMessage sealed interface dispatch ──

    @Test
    void testTelegramMessageDispatch() {
        ChannelUser user = new ChannelUser("u1", "chat-1", "user1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "Hello", "chat-1", Instant.now());
        assertEquals(ChannelType.TELEGRAM, msg.channelType());
        assertEquals("chat-1", msg.correlationId());
        assertEquals("Hello", msg.text());
        assertInstanceOf(ChannelMessage.class, msg);
        assertInstanceOf(ChannelMessage.TelegramMessage.class, msg);
    }

    @Test
    void testDiscordMessageDispatch() {
        ChannelUser user = new ChannelUser("u2", "guild-1", "user2", ChannelType.DISCORD);
        ChannelMessage msg = new ChannelMessage.DiscordMessage(user, "Hi", "guild-1", "chan-1", Instant.now());
        assertEquals(ChannelType.DISCORD, msg.channelType());
        assertEquals("chan-1", msg.correlationId());
    }

    @Test
    void testRestApiMessageDispatch() {
        ChannelUser user = new ChannelUser("u3", "rest-1", "user3", ChannelType.REST_API);
        ChannelMessage msg =
                new ChannelMessage.RestApiMessage(user, "{ \"query\": \"test\" }", "ak-123", Instant.now());
        assertEquals(ChannelType.REST_API, msg.channelType());
        assertEquals("ak-123", msg.correlationId());
    }

    @Test
    void testCliMessageDispatch() {
        ChannelUser user = new ChannelUser("u4", "cli-1", "user4", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "run task", "sess-cli-1", Instant.now());
        assertEquals(ChannelType.CLI, msg.channelType());
        assertEquals("sess-cli-1", msg.correlationId());
    }

    @Test
    void testWebSocketMessageDispatch() {
        ChannelUser user = new ChannelUser("u5", "ws-1", "user5", ChannelType.WEBSOCKET);
        ChannelMessage msg = new ChannelMessage.WebSocketMessage(user, "ping", "ws-sess-1", Instant.now());
        assertEquals(ChannelType.WEBSOCKET, msg.channelType());
        assertEquals("ws-sess-1", msg.correlationId());
    }

    @Test
    void testAllBuiltInMessageTypesExist() {
        // Verify all 5 built-in message types are present as inner records
        assertNotNull(ChannelMessage.TelegramMessage.class);
        assertNotNull(ChannelMessage.DiscordMessage.class);
        assertNotNull(ChannelMessage.RestApiMessage.class);
        assertNotNull(ChannelMessage.CliMessage.class);
        assertNotNull(ChannelMessage.WebSocketMessage.class);
    }

    // ── ChannelCommand sealed interface dispatch ──

    @Test
    void testApproveCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/approve sess-123");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        ChannelCommand.Approve approve = (ChannelCommand.Approve) cmd;
        assertTrue(approve.targetId().isPresent());
        assertEquals("sess-123", approve.targetId().get());
        assertTrue(cmd.isControlCommand());
    }

    @Test
    void testDenyCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/deny");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertTrue(cmd.isControlCommand());
    }

    @Test
    void testStatusCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/status");
        assertInstanceOf(ChannelCommand.Status.class, cmd);
        assertFalse(cmd.isControlCommand());
    }

    @Test
    void testHaltCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/halt");
        assertInstanceOf(ChannelCommand.Halt.class, cmd);
        assertTrue(cmd.isControlCommand());
    }

    @Test
    void testResetCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/reset");
        assertInstanceOf(ChannelCommand.Reset.class, cmd);
        assertTrue(cmd.isControlCommand());
    }

    @Test
    void testHistoryCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/history");
        assertInstanceOf(ChannelCommand.History.class, cmd);
        assertFalse(cmd.isControlCommand());
    }

    @Test
    void testCronTasksCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/crons");
        assertInstanceOf(ChannelCommand.CronTasks.class, cmd);
        assertFalse(cmd.isControlCommand());
    }

    @Test
    void testDeleteCronCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/deletecron cron-123");
        assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
        ChannelCommand.DeleteCron dc = (ChannelCommand.DeleteCron) cmd;
        assertTrue(dc.cronId().isPresent());
        assertEquals("cron-123", dc.cronId().get());
    }

    @Test
    void testDeleteCronViaCronDeleteDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/cron delete cron-456");
        assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
        ChannelCommand.DeleteCron dc = (ChannelCommand.DeleteCron) cmd;
        assertTrue(dc.cronId().isPresent());
        assertEquals("cron-456", dc.cronId().get());
    }

    @Test
    void testStartCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/start");
        assertInstanceOf(ChannelCommand.Start.class, cmd);
        assertFalse(cmd.isControlCommand());
    }

    @Test
    void testStartWithPayloadDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("/start ref-abc");
        assertInstanceOf(ChannelCommand.Start.class, cmd);
        assertEquals(Optional.of("ref-abc"), ((ChannelCommand.Start) cmd).payload());
    }

    @Test
    void testUnknownCommandDispatch() {
        ChannelCommand cmd = ChannelCommand.parse("just some text");
        assertInstanceOf(ChannelCommand.Unknown.class, cmd);
        assertFalse(cmd.isControlCommand());
    }

    @Test
    void testAllBuiltInCommandTypesAreSealedPermits() {
        // Verify all 10 built-in command types are sealed permits of BuiltInChannelCommand
        assertEquals(
                10,
                ChannelCommand.BuiltInChannelCommand.class.getPermittedSubclasses().length,
                "Adding a new built-in command type requires adding it to the permits clause "
                        + "of BuiltInChannelCommand. If you see this failure, add the new record "
                        + "and add a case in CommandDispatchHandler.");
        assertNotNull(ChannelCommand.Approve.class);
        assertNotNull(ChannelCommand.Deny.class);
        assertNotNull(ChannelCommand.Status.class);
        assertNotNull(ChannelCommand.Halt.class);
        assertNotNull(ChannelCommand.Reset.class);
        assertNotNull(ChannelCommand.History.class);
        assertNotNull(ChannelCommand.CronTasks.class);
        assertNotNull(ChannelCommand.DeleteCron.class);
        assertNotNull(ChannelCommand.Start.class);
        assertNotNull(ChannelCommand.Unknown.class);
    }

    @Test
    void testApproveWithToolNameAndReason() {
        ChannelCommand cmd = ChannelCommand.parse("/approve openai_search Reason: looks good");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        ChannelCommand.Approve approve = (ChannelCommand.Approve) cmd;
        assertFalse(approve.targetId().isPresent());
        assertTrue(approve.toolName().isPresent());
        assertEquals("openai_search", approve.toolName().get());
        assertTrue(approve.reason().isPresent());
    }

    @Test
    void testDenyWithTargetAndReason() {
        ChannelCommand cmd = ChannelCommand.parse("/deny sess-abc Not needed");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        ChannelCommand.Deny deny = (ChannelCommand.Deny) cmd;
        assertTrue(deny.targetId().isPresent());
        assertTrue(deny.reason().isPresent());
    }

    @Test
    void testCronTasksAliases() {
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/listtasks"));
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/listtask"));
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/cron"));
    }

    @Test
    void testIsControlCommandExhaustive() {
        assertTrue(ChannelCommand.parse("/approve").isControlCommand());
        assertTrue(ChannelCommand.parse("/deny").isControlCommand());
        assertTrue(ChannelCommand.parse("/halt").isControlCommand());
        assertTrue(ChannelCommand.parse("/reset").isControlCommand());
        assertFalse(ChannelCommand.parse("/status").isControlCommand());
        assertFalse(ChannelCommand.parse("/crons").isControlCommand());
        assertFalse(ChannelCommand.parse("/history").isControlCommand());
        assertFalse(ChannelCommand.parse("/start").isControlCommand());
        assertFalse(ChannelCommand.parse("/deletecron").isControlCommand());
        assertFalse(ChannelCommand.parse("hello").isControlCommand());
    }
}
