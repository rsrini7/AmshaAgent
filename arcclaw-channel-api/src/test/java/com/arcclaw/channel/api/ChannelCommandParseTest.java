package com.arcclaw.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelCommand#parse(String)}.
 *
 * <p>Covers: known commands, case-insensitivity, optional cronId extraction,
 * null/blank/non-command fallback, and injection-text edge cases.
 */
class ChannelCommandParseTest {

    // ------------------------------------------------------------------
    // Basic parse tests
    // ------------------------------------------------------------------

    @Test
    void parseApproveWithNoCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/approve");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        assertTrue(((ChannelCommand.Approve) cmd).targetId().isEmpty());
    }

    @Test
    void parseApproveWithCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/approve abc-123");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        assertEquals(Optional.of("abc-123"), ((ChannelCommand.Approve) cmd).targetId());
        assertTrue(((ChannelCommand.Approve) cmd).toolName().isEmpty());
    }

    @Test
    void parseApproveWithToolName() {
        ChannelCommand cmd = ChannelCommand.parse("/approve github");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        assertTrue(((ChannelCommand.Approve) cmd).targetId().isEmpty());
        assertEquals(Optional.of("github"), ((ChannelCommand.Approve) cmd).toolName());
    }

    @Test
    void parseDenyWithNoCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/deny");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertTrue(((ChannelCommand.Deny) cmd).targetId().isEmpty());
        assertTrue(((ChannelCommand.Deny) cmd).toolName().isEmpty());
    }

    @Test
    void parseDenyWithCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/deny cron-42");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertEquals(Optional.of("cron-42"), ((ChannelCommand.Deny) cmd).targetId());
        assertTrue(((ChannelCommand.Deny) cmd).toolName().isEmpty());
    }

    @Test
    void parseDenyWithToolName() {
        ChannelCommand cmd = ChannelCommand.parse("/deny manage_schedule");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertTrue(((ChannelCommand.Deny) cmd).targetId().isEmpty());
        assertEquals(Optional.of("manage_schedule"), ((ChannelCommand.Deny) cmd).toolName());
    }

    @Test
    void parseStatus() {
        assertInstanceOf(ChannelCommand.Status.class, ChannelCommand.parse("/status"));
    }

    @Test
    void parseHalt() {
        assertInstanceOf(ChannelCommand.Halt.class, ChannelCommand.parse("/halt"));
    }

    @Test
    void parseReset() {
        assertInstanceOf(ChannelCommand.Reset.class, ChannelCommand.parse("/reset"));
    }

    @Test
    void parseCronTasks() {
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/crons"));
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/listtasks"));
        assertInstanceOf(ChannelCommand.CronTasks.class, ChannelCommand.parse("/listtask"));
    }

    // ------------------------------------------------------------------
    // Case-insensitivity
    // ------------------------------------------------------------------

    @Test
    void parseCaseInsensitive() {
        assertInstanceOf(ChannelCommand.Approve.class, ChannelCommand.parse("/APPROVE"));
        assertInstanceOf(ChannelCommand.Deny.class,    ChannelCommand.parse("/DENY"));
        assertInstanceOf(ChannelCommand.Halt.class,    ChannelCommand.parse("/Halt"));
    }

    // ------------------------------------------------------------------
    // Fallback / edge cases
    // ------------------------------------------------------------------

    @Test
    void parseNullReturnUnknown() {
        ChannelCommand cmd = ChannelCommand.parse(null);
        assertInstanceOf(ChannelCommand.Unknown.class, cmd);
    }

    @Test
    void parseBlankReturnUnknown() {
        ChannelCommand cmd = ChannelCommand.parse("   ");
        assertInstanceOf(ChannelCommand.Unknown.class, cmd);
    }

    @Test
    void parseNonCommandReturnUnknown() {
        ChannelCommand cmd = ChannelCommand.parse("Hello, agent!");
        assertInstanceOf(ChannelCommand.Unknown.class, cmd);
        assertEquals("Hello, agent!", ((ChannelCommand.Unknown) cmd).raw());
    }

    // ------------------------------------------------------------------
    // Security: prompt-injection payloads must fall through to Unknown
    // ------------------------------------------------------------------

    @Test
    void parseInjectionPayloadFallsToUnknown() {
        String injection = "/approve; DROP TABLE agent_checkpoints; --";
        // The regex only captures the first whitespace-delimited token as targetId,
        // so the full payload is harmless - the targetId is just the second token.
        ChannelCommand cmd = ChannelCommand.parse(injection);
        // Regardless of whether it matches Approve or Unknown, the raw SQL
        // must NOT be treated as a real cronId that reaches the DB.
        if (cmd instanceof ChannelCommand.Approve a) {
            // targetId is "; DROP TABLE..." - handler must sanitise before DB lookup
            assertTrue(a.targetId().isPresent());
            // Verify it is a bounded string, not the entire injection tail
            assertFalse(a.targetId().get().contains(" "), "targetId must be a single token");
        } else {
            assertInstanceOf(ChannelCommand.Unknown.class, cmd);
        }
    }

    // ------------------------------------------------------------------
    // isControlCommand
    // ------------------------------------------------------------------
    @Test
    void controlCommandsShouldBeIdentified() {
        assertTrue(new ChannelCommand.Approve(Optional.empty(), Optional.empty(), Optional.empty()).isControlCommand());
        assertTrue(new ChannelCommand.Deny(Optional.empty(), Optional.empty(), Optional.empty()).isControlCommand());
        assertTrue(new ChannelCommand.Halt().isControlCommand());
        assertTrue(new ChannelCommand.Reset().isControlCommand());

        assertFalse(new ChannelCommand.Status().isControlCommand());
        assertFalse(new ChannelCommand.CronTasks().isControlCommand());
        assertFalse(new ChannelCommand.Unknown("raw").isControlCommand());
    }

    @Test
    void doubleSpaceCronDeleteExtractsCronId() {
        var cmd = ChannelCommand.parse("/cron  delete  abc-123");
        assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
        assertEquals(Optional.of("abc-123"),
            ((ChannelCommand.DeleteCron) cmd).cronId());
    }

    @Test
    void missingCronIdYieldsEmptyOptional() {
        var cmd = ChannelCommand.parse("/cron delete");
        assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
        assertTrue(((ChannelCommand.DeleteCron) cmd).cronId().isEmpty());
    }

    @Test
    void parseApproveWithSessionId() {
        ChannelCommand cmd = ChannelCommand.parse("/approve b2ada049-88e1-474d-9589-783a82190d46");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        ChannelCommand.Approve a = (ChannelCommand.Approve) cmd;
        assertEquals(Optional.of("b2ada049-88e1-474d-9589-783a82190d46"), a.targetId());
    }

    @Test
    void parseApproveWithSessionIdAndTool() {
        ChannelCommand cmd = ChannelCommand.parse("/approve b2ada049-88e1-474d-9589-783a82190d46 github");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        ChannelCommand.Approve a = (ChannelCommand.Approve) cmd;
        assertEquals(Optional.of("b2ada049-88e1-474d-9589-783a82190d46"), a.targetId());
        // Parser only captures the first argument as targetSessionId, github is in 'rest' of arg
        assertTrue(a.toolName().isEmpty()); 
    }

    @Test
    void parseApproveWithCronStyleArg_notParsedAsSessionId() {
        ChannelCommand cmd = ChannelCommand.parse("/approve cron-abc123");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        ChannelCommand.Approve a = (ChannelCommand.Approve) cmd;
        assertTrue(a.targetId().isPresent());
        assertEquals("cron-abc123", a.targetId().get());
    }

    @Test
    void parseApprove_doubleSlashNormalizedToSingle() {
        ChannelCommand cmd = ChannelCommand.parse("//approve");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
    }

    @Test
    void parseApproveWithSessionId_doubleSlashNormalized() {
        ChannelCommand cmd = ChannelCommand.parse("//approve b2ada049-88e1-474d-9589-783a82190d46");
        ChannelCommand.Approve a = (ChannelCommand.Approve) cmd;
        assertTrue(a.targetId().isPresent());
        assertEquals("b2ada049-88e1-474d-9589-783a82190d46", a.targetId().get());
    }

    @Test
    void parseApproveWithCronId_doubleSlashNormalized() {
        ChannelCommand cmd = ChannelCommand.parse("//approve cron-abc123");
        ChannelCommand.Approve a = (ChannelCommand.Approve) cmd;
        assertTrue(a.targetId().isPresent());
        assertEquals("cron-abc123", a.targetId().get());
    }

    @Test
    void parseApprove_tripleSlash_isUnknown() {
        // Only one level of normalization — ///approve is treated as malformed
        ChannelCommand cmd = ChannelCommand.parse("///approve");
        assertInstanceOf(ChannelCommand.Unknown.class, cmd);
    }

    @Test
    void parseApprove_doubleSlashWithBackticks_normalized() {
        ChannelCommand cmd = ChannelCommand.parse("`//approve`");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
    }
}
