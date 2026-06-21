package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@code ChannelCommand.parseCronCommand} extraction.
 * Verifies edge cases around {@code /cron delete <id>} positional parsing.
 */
class ChannelCommandParseCronTest {

    @Test
    void parseCronDeleteWithId() {
        var result = ChannelCommand.parse("/cron delete abc-123");
        assertTrue(
                result instanceof ChannelCommand.DeleteCron,
                "Expected DeleteCron but got " + result.getClass().getSimpleName());
        var deleteCron = (ChannelCommand.DeleteCron) result;
        assertTrue(deleteCron.cronId().isPresent(), "Expected targetId to be present");
        assertEquals("abc-123", deleteCron.cronId().get());
    }

    @Test
    void parseCronDeleteWithoutId() {
        var result = ChannelCommand.parse("/cron delete");
        assertTrue(
                result instanceof ChannelCommand.DeleteCron,
                "Expected DeleteCron but got " + result.getClass().getSimpleName());
        var deleteCron = (ChannelCommand.DeleteCron) result;
        assertTrue(deleteCron.cronId().isEmpty(), "Expected targetId to be empty");
    }

    @Test
    void parseCronDeleteWithBlankId() {
        var result = ChannelCommand.parse("/cron delete   ");
        assertTrue(
                result instanceof ChannelCommand.DeleteCron,
                "Expected DeleteCron but got " + result.getClass().getSimpleName());
        var deleteCron = (ChannelCommand.DeleteCron) result;
        assertTrue(deleteCron.cronId().isEmpty(), "Expected targetId to be empty for blank input");
    }

    @Test
    void parseCronWithoutDeleteShowsTasks() {
        var result = ChannelCommand.parse("/cron");
        assertTrue(
                result instanceof ChannelCommand.CronTasks,
                "Expected CronTasks but got " + result.getClass().getSimpleName());
    }

    @Test
    void parseCronWithRandomArgShowsTasks() {
        var result = ChannelCommand.parse("/cron list");
        assertTrue(
                result instanceof ChannelCommand.CronTasks,
                "Expected CronTasks but got " + result.getClass().getSimpleName());
    }

    @Test
    void parseCronDeleteWithUuid() {
        var uuid = "550e8400-e29b-41d4-a716-446655440000";
        var result = ChannelCommand.parse("/cron delete " + uuid);
        assertTrue(
                result instanceof ChannelCommand.DeleteCron,
                "Expected DeleteCron but got " + result.getClass().getSimpleName());
        var deleteCron = (ChannelCommand.DeleteCron) result;
        assertTrue(deleteCron.cronId().isPresent(), "Expected targetId to be present");
        assertEquals(uuid, deleteCron.cronId().get());
    }
}
