package com.amsha.core.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link AgentCronRecord}.
 * All methods called for real -- no mocking needed for a domain model.
 */
class AgentCronRecordDeepTest {

    // ── fromDto ──────────────────────────────────────────────────────────

    @Nested
    class FromDtoTests {

        @Test
        void fromDto_mapsAllFields() {
            Instant now = Instant.now();
            AgentCronDto dto = new AgentCronDto(
                    "id-1",
                    "session-1",
                    "user-1",
                    "chat-1",
                    "telegram",
                    "tg-123",
                    "0 9 * * *",
                    "Asia/Kolkata",
                    false,
                    "do something",
                    now,
                    now,
                    "ACTIVE",
                    "coder",
                    null,
                    "delivery-1",
                    now,
                    now);

            AgentCronRecord record = AgentCronRecord.fromDto(dto);

            assertEquals("id-1", record.getId());
            assertEquals("session-1", record.getSessionId());
            assertEquals("user-1", record.getUserId());
            assertEquals("chat-1", record.getChatId());
            assertEquals("telegram", record.getChannelType());
            assertEquals("tg-123", record.getChannelPlatformId());
            assertEquals("0 9 * * *", record.getCronExpression());
            assertEquals("Asia/Kolkata", record.getTimezone());
            assertFalse(record.isRunOnce());
            assertEquals("do something", record.getGoal());
            assertEquals(now, record.getNextFireTime());
            assertEquals(now, record.getLastFireTime());
            assertEquals(CronStatus.ACTIVE, record.getStatus());
            assertEquals("coder", record.getExecutionRole());
            assertNull(record.getErrorReason());
            assertEquals("delivery-1", record.getLastDeliveryId());
            assertEquals(now, record.getCreatedAt());
            assertEquals(now, record.getUpdatedAt());
        }

        @Test
        void fromDto_nullStatus_mapsToNull() {
            AgentCronDto dto = new AgentCronDto(
                    "id-1",
                    "session-1",
                    "user-1",
                    "chat-1",
                    "telegram",
                    "tg-123",
                    "0 9 * * *",
                    "Asia/Kolkata",
                    false,
                    "goal",
                    null,
                    null,
                    null,
                    "coder",
                    null,
                    null,
                    null,
                    null);

            AgentCronRecord record = AgentCronRecord.fromDto(dto);

            assertNull(record.getStatus());
        }

        @Test
        void fromDto_runOnce_mapsCorrectly() {
            AgentCronDto dto = new AgentCronDto(
                    "id-1",
                    "session-1",
                    "user-1",
                    "chat-1",
                    "telegram",
                    "tg-123",
                    null,
                    "UTC",
                    true,
                    "one-shot goal",
                    null,
                    null,
                    "COMPLETED",
                    "coder",
                    "error msg",
                    null,
                    null,
                    null);

            AgentCronRecord record = AgentCronRecord.fromDto(dto);

            assertTrue(record.isRunOnce());
            assertEquals(CronStatus.COMPLETED, record.getStatus());
            assertEquals("error msg", record.getErrorReason());
        }
    }

    // ── Getters and Setters ──────────────────────────────────────────────

    @Nested
    class GetterSetterTests {

        @Test
        void id_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("test-id");
            assertEquals("test-id", r.getId());
        }

        @Test
        void sessionId_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setSessionId("session-1");
            assertEquals("session-1", r.getSessionId());
        }

        @Test
        void userId_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setUserId("user-1");
            assertEquals("user-1", r.getUserId());
        }

        @Test
        void chatId_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setChatId("chat-1");
            assertEquals("chat-1", r.getChatId());
        }

        @Test
        void channelType_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setChannelType("telegram");
            assertEquals("telegram", r.getChannelType());
        }

        @Test
        void channelPlatformId_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setChannelPlatformId("tg-123");
            assertEquals("tg-123", r.getChannelPlatformId());
        }

        @Test
        void cronExpression_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setCronExpression("0 9 * * *");
            assertEquals("0 9 * * *", r.getCronExpression());
        }

        @Test
        void timezone_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setTimezone("UTC");
            assertEquals("UTC", r.getTimezone());
        }

        @Test
        void runOnce_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setRunOnce(true);
            assertTrue(r.isRunOnce());
            r.setRunOnce(false);
            assertFalse(r.isRunOnce());
        }

        @Test
        void goal_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setGoal("do something");
            assertEquals("do something", r.getGoal());
        }

        @Test
        void nextFireTime_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            r.setNextFireTime(now);
            assertEquals(now, r.getNextFireTime());
        }

        @Test
        void lastFireTime_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            r.setLastFireTime(now);
            assertEquals(now, r.getLastFireTime());
        }

        @Test
        void status_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setStatus(CronStatus.FAILED);
            assertEquals(CronStatus.FAILED, r.getStatus());
        }

        @Test
        void executionRole_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setExecutionRole("coder");
            assertEquals("coder", r.getExecutionRole());
        }

        @Test
        void errorReason_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setErrorReason("something broke");
            assertEquals("something broke", r.getErrorReason());
        }

        @Test
        void lastDeliveryId_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            r.setLastDeliveryId("delivery-1");
            assertEquals("delivery-1", r.getLastDeliveryId());
        }

        @Test
        void createdAt_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            r.setCreatedAt(now);
            assertEquals(now, r.getCreatedAt());
        }

        @Test
        void updatedAt_getterSetter() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            r.setUpdatedAt(now);
            assertEquals(now, r.getUpdatedAt());
        }
    }

    // ── Wither methods ───────────────────────────────────────────────────

    @Nested
    class WitherTests {

        @Test
        void withId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            AgentCron result = r.withId("id-1");
            assertSame(r, result);
            assertEquals("id-1", r.getId());
        }

        @Test
        void withSessionId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withSessionId("s1"));
            assertEquals("s1", r.getSessionId());
        }

        @Test
        void withUserId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withUserId("u1"));
            assertEquals("u1", r.getUserId());
        }

        @Test
        void withChatId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withChatId("c1"));
            assertEquals("c1", r.getChatId());
        }

        @Test
        void withChannelType_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withChannelType("telegram"));
            assertEquals("telegram", r.getChannelType());
        }

        @Test
        void withChannelPlatformId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withChannelPlatformId("tg-1"));
            assertEquals("tg-1", r.getChannelPlatformId());
        }

        @Test
        void withCronExpression_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withCronExpression("0 9 * * *"));
            assertEquals("0 9 * * *", r.getCronExpression());
        }

        @Test
        void withTimezone_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withTimezone("UTC"));
            assertEquals("UTC", r.getTimezone());
        }

        @Test
        void withRunOnce_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withRunOnce(true));
            assertTrue(r.isRunOnce());
        }

        @Test
        void withGoal_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withGoal("test goal"));
            assertEquals("test goal", r.getGoal());
        }

        @Test
        void withNextFireTime_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            assertSame(r, r.withNextFireTime(now));
            assertEquals(now, r.getNextFireTime());
        }

        @Test
        void withLastFireTime_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            assertSame(r, r.withLastFireTime(now));
            assertEquals(now, r.getLastFireTime());
        }

        @Test
        void withStatus_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withStatus(CronStatus.ACTIVE));
            assertEquals(CronStatus.ACTIVE, r.getStatus());
        }

        @Test
        void withExecutionRole_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withExecutionRole("coder"));
            assertEquals("coder", r.getExecutionRole());
        }

        @Test
        void withErrorReason_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withErrorReason("err"));
            assertEquals("err", r.getErrorReason());
        }

        @Test
        void withLastDeliveryId_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            assertSame(r, r.withLastDeliveryId("d1"));
            assertEquals("d1", r.getLastDeliveryId());
        }

        @Test
        void withCreatedAt_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            assertSame(r, r.withCreatedAt(now));
            assertEquals(now, r.getCreatedAt());
        }

        @Test
        void withUpdatedAt_returnsSelf() {
            AgentCronRecord r = new AgentCronRecord();
            Instant now = Instant.now();
            assertSame(r, r.withUpdatedAt(now));
            assertEquals(now, r.getUpdatedAt());
        }
    }

    // ── toDisplayString ──────────────────────────────────────────────────

    @Nested
    class ToDisplayStringTests {

        @Test
        void toDisplayString_activeStatus_showsNextFireTime() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("Asia/Kolkata");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("do something");
            Instant next = Instant.parse("2025-06-01T09:00:00Z");
            r.setNextFireTime(next);

            String display = r.toDisplayString();

            assertTrue(display.contains("id-1"));
            assertTrue(display.contains("0 9 * * *"));
            assertTrue(display.contains("Asia/Kolkata"));
            assertTrue(display.contains("ACTIVE"));
            assertTrue(display.contains("do something"));
            assertFalse(display.contains("N/A"));
        }

        @Test
        void toDisplayString_completedStatus_showsNAForNext() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.COMPLETED);
            r.setGoal("task done");

            String display = r.toDisplayString();

            assertTrue(display.contains("N/A"));
        }

        @Test
        void toDisplayString_failedStatus_showsNAForNext() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.FAILED);
            r.setGoal("failed task");

            String display = r.toDisplayString();

            assertTrue(display.contains("N/A"));
        }

        @Test
        void toDisplayString_skippedStatus_showsNAForNext() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.SKIPPED);
            r.setGoal("skipped");

            String display = r.toDisplayString();

            assertTrue(display.contains("N/A"));
        }

        @Test
        void toDisplayString_nullNextFireTime_showsNA() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("something");
            r.setNextFireTime(null);

            String display = r.toDisplayString();

            assertTrue(display.contains("N/A"));
        }

        @Test
        void toDisplayString_nullGoal_showsNA() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal(null);

            String display = r.toDisplayString();

            assertTrue(display.contains("N/A"));
        }

        @Test
        void toDisplayString_stripsCronPrefix() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("CRON send text: hello world");

            String display = r.toDisplayString();

            // "CRON " prefix should be stripped
            assertFalse(display.contains("CRON send text:"));
            assertTrue(display.contains("send text: hello world") || display.contains("hello world"));
        }

        @Test
        void toDisplayString_outputTheFollowingMessage_stripsPrefix() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("output the following message: Good morning!");

            String display = r.toDisplayString();

            assertTrue(display.contains("Good morning!"));
            assertFalse(display.contains("output the following message:"));
        }

        @Test
        void toDisplayString_outputTheMessage_stripsPrefix() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("output the message: Alert!");

            String display = r.toDisplayString();

            assertTrue(display.contains("Alert!"));
            assertFalse(display.contains("output the message:"));
        }

        @Test
        void toDisplayString_sayPrefix_stripsIt() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("say hello to everyone");

            String display = r.toDisplayString();

            assertTrue(display.contains("hello to everyone"));
            assertFalse(display.contains("say hello"));
        }

        @Test
        void toDisplayString_tellPrefix_stripsIt() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("tell the team about the update");

            String display = r.toDisplayString();

            assertTrue(display.contains("the team about the update"));
        }

        @Test
        void toDisplayString_sendMessagePrefix_stripsIt() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("send message: Meeting in 5 minutes");

            String display = r.toDisplayString();

            assertTrue(display.contains("Meeting in 5 minutes"));
            assertFalse(display.contains("send message:"));
        }

        @Test
        void toDisplayString_sendTextPrefix_stripsIt() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("send text: Daily reminder");

            String display = r.toDisplayString();

            assertTrue(display.contains("Daily reminder"));
            assertFalse(display.contains("send text:"));
        }

        @Test
        void toDisplayString_plainGoal_noStripping() {
            AgentCronRecord r = new AgentCronRecord();
            r.setId("id-1");
            r.setCronExpression("0 9 * * *");
            r.setTimezone("UTC");
            r.setStatus(CronStatus.ACTIVE);
            r.setGoal("Check the latest weather forecast");

            String display = r.toDisplayString();

            assertTrue(display.contains("Check the latest weather forecast"));
        }
    }
}
