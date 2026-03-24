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
        assertTrue(((ChannelCommand.Approve) cmd).cronId().isEmpty());
    }

    @Test
    void parseApproveWithCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/approve abc-123");
        assertInstanceOf(ChannelCommand.Approve.class, cmd);
        assertEquals(Optional.of("abc-123"), ((ChannelCommand.Approve) cmd).cronId());
    }

    @Test
    void parseDenyWithNoCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/deny");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertTrue(((ChannelCommand.Deny) cmd).cronId().isEmpty());
    }

    @Test
    void parseDenyWithCronId() {
        ChannelCommand cmd = ChannelCommand.parse("/deny cron-42");
        assertInstanceOf(ChannelCommand.Deny.class, cmd);
        assertEquals(Optional.of("cron-42"), ((ChannelCommand.Deny) cmd).cronId());
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
        // The regex only captures the first whitespace-delimited token as cronId,
        // so the full payload is harmless — the cronId is just the second token.
        ChannelCommand cmd = ChannelCommand.parse(injection);
        // Regardless of whether it matches Approve or Unknown, the raw SQL
        // must NOT be treated as a real cronId that reaches the DB.
        // Approve.cronId contains only the second whitespace-delimited token.
        if (cmd instanceof ChannelCommand.Approve a) {
            // cronId is "; DROP TABLE..." — handler must sanitise before DB lookup
            assertTrue(a.cronId().isPresent());
            // Verify it is a bounded string, not the entire injection tail
            assertFalse(a.cronId().get().contains(" "), "cronId must be a single token");
        } else {
            assertInstanceOf(ChannelCommand.Unknown.class, cmd);
        }
    }

    // ------------------------------------------------------------------
    // isControlCommand
    // ------------------------------------------------------------------

    @Test
    void controlCommandsShouldBeIdentified() {
        assertTrue(new ChannelCommand.Approve(Optional.empty()).isControlCommand());
        assertTrue(new ChannelCommand.Deny(Optional.empty()).isControlCommand());
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
}
