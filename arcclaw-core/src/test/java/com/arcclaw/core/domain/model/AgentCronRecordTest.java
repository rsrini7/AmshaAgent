package com.arcclaw.core.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.arcclaw.core.state.CronStatus;
import com.arcclaw.persistence.api.AgentCronDto;

import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link AgentCronRecord}.
 * Tests all getters/setters, fromDto, and toDisplayString with all branches.
 */
class AgentCronRecordTest {

    // -----------------------------------------------------------------------
    // fromDto
    // -----------------------------------------------------------------------

    @Test
    void fromDto_mapsAllFields() {
        Instant now = Instant.now();
        AgentCronDto dto = new AgentCronDto(
                "cron-1",
                "sess-1",
                "user-1",
                "chat-1",
                "TELEGRAM",
                "tg-123",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                now,
                now,
                "ACTIVE",
                "coder",
                null,
                "del-1",
                now,
                now);

        AgentCronRecord r = AgentCronRecord.fromDto(dto);

        assertEquals("cron-1", r.getId());
        assertEquals("sess-1", r.getSessionId());
        assertEquals("user-1", r.getUserId());
        assertEquals("chat-1", r.getChatId());
        assertEquals("TELEGRAM", r.getChannelType());
        assertEquals("tg-123", r.getChannelPlatformId());
        assertEquals("0 * * * *", r.getCronExpression());
        assertEquals("UTC", r.getTimezone());
        assertFalse(r.isRunOnce());
        assertEquals("do something", r.getGoal());
        assertEquals(now, r.getNextFireTime());
        assertEquals(now, r.getLastFireTime());
        assertEquals(CronStatus.ACTIVE, r.getStatus());
        assertEquals("coder", r.getExecutionRole());
        assertNull(r.getErrorReason());
        assertEquals("del-1", r.getLastDeliveryId());
        assertEquals(now, r.getCreatedAt());
        assertEquals(now, r.getUpdatedAt());
    }

    @Test
    void fromDto_nullStatus_mapsToNull() {
        AgentCronDto dto = new AgentCronDto(
                "cron-2",
                "sess",
                "user",
                "chat",
                "CLI",
                "cli-id",
                "0 9 * * *",
                "US/Eastern",
                true,
                "greet",
                null,
                null,
                null,
                "researcher",
                "err",
                null,
                null,
                null);

        AgentCronRecord r = AgentCronRecord.fromDto(dto);
        assertNull(r.getStatus());
        assertTrue(r.isRunOnce());
        assertEquals("US/Eastern", r.getTimezone());
        assertEquals("err", r.getErrorReason());
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    @Test
    void setAndGetId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setId("abc");
        assertEquals("abc", r.getId());
    }

    @Test
    void setAndGetSessionId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setSessionId("sess-1");
        assertEquals("sess-1", r.getSessionId());
    }

    @Test
    void setAndGetUserId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setUserId("user-1");
        assertEquals("user-1", r.getUserId());
    }

    @Test
    void setAndGetChatId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setChatId("chat-1");
        assertEquals("chat-1", r.getChatId());
    }

    @Test
    void setAndGetChannelType() {
        AgentCronRecord r = new AgentCronRecord();
        r.setChannelType("TELEGRAM");
        assertEquals("TELEGRAM", r.getChannelType());
    }

    @Test
    void setAndGetChannelPlatformId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setChannelPlatformId("tg-789");
        assertEquals("tg-789", r.getChannelPlatformId());
    }

    @Test
    void setAndGetCronExpression() {
        AgentCronRecord r = new AgentCronRecord();
        r.setCronExpression("*/5 * * * *");
        assertEquals("*/5 * * * *", r.getCronExpression());
    }

    @Test
    void setAndGetTimezone() {
        AgentCronRecord r = new AgentCronRecord();
        r.setTimezone("Asia/Tokyo");
        assertEquals("Asia/Tokyo", r.getTimezone());
    }

    @Test
    void setAndGetRunOnce() {
        AgentCronRecord r = new AgentCronRecord();
        r.setRunOnce(true);
        assertTrue(r.isRunOnce());
        r.setRunOnce(false);
        assertFalse(r.isRunOnce());
    }

    @Test
    void setAndGetGoal() {
        AgentCronRecord r = new AgentCronRecord();
        r.setGoal("remind me to drink water");
        assertEquals("remind me to drink water", r.getGoal());
    }

    @Test
    void setAndGetNextFireTime() {
        AgentCronRecord r = new AgentCronRecord();
        Instant t = Instant.now();
        r.setNextFireTime(t);
        assertEquals(t, r.getNextFireTime());
    }

    @Test
    void setAndGetLastFireTime() {
        AgentCronRecord r = new AgentCronRecord();
        Instant t = Instant.now();
        r.setLastFireTime(t);
        assertEquals(t, r.getLastFireTime());
    }

    @Test
    void setAndGetStatus() {
        AgentCronRecord r = new AgentCronRecord();
        r.setStatus(CronStatus.RUNNING);
        assertEquals(CronStatus.RUNNING, r.getStatus());
    }

    @Test
    void setAndGetExecutionRole() {
        AgentCronRecord r = new AgentCronRecord();
        r.setExecutionRole("brahma");
        assertEquals("brahma", r.getExecutionRole());
    }

    @Test
    void setAndGetErrorReason() {
        AgentCronRecord r = new AgentCronRecord();
        r.setErrorReason("timeout");
        assertEquals("timeout", r.getErrorReason());
    }

    @Test
    void setAndGetLastDeliveryId() {
        AgentCronRecord r = new AgentCronRecord();
        r.setLastDeliveryId("del-42");
        assertEquals("del-42", r.getLastDeliveryId());
    }

    @Test
    void setAndGetCreatedAt() {
        AgentCronRecord r = new AgentCronRecord();
        Instant t = Instant.now();
        r.setCreatedAt(t);
        assertEquals(t, r.getCreatedAt());
    }

    @Test
    void setAndGetUpdatedAt() {
        AgentCronRecord r = new AgentCronRecord();
        Instant t = Instant.now();
        r.setUpdatedAt(t);
        assertEquals(t, r.getUpdatedAt());
    }

    // -----------------------------------------------------------------------
    // toDisplayString — all branches
    // -----------------------------------------------------------------------

    @Test
    void toDisplayString_activeStatus_showsNextFireTime() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "CRON send text: Good morning!");
        String display = r.toDisplayString();
        assertTrue(display.contains("cron-1"), "Should contain ID");
        assertTrue(display.contains("0 9 * * *"), "Should contain cron expression");
        assertTrue(display.contains("UTC"), "Should contain timezone");
        assertTrue(display.contains("ACTIVE"), "Should contain status");
        assertTrue(display.contains("Good morning!"), "Should show stripped goal");
        assertFalse(display.contains("N/A"), "Should NOT contain N/A for active status");
    }

    @Test
    void toDisplayString_completedStatus_showsNA() {
        AgentCronRecord r = buildRecord(CronStatus.COMPLETED, "0 9 * * *", "UTC", "some goal");
        String display = r.toDisplayString();
        assertTrue(display.contains("N/A"), "COMPLETED next fire should be N/A");
    }

    @Test
    void toDisplayString_failedStatus_showsNA() {
        AgentCronRecord r = buildRecord(CronStatus.FAILED, "0 9 * * *", "UTC", "some goal");
        String display = r.toDisplayString();
        assertTrue(display.contains("N/A"), "FAILED next fire should be N/A");
    }

    @Test
    void toDisplayString_skippedStatus_showsNA() {
        AgentCronRecord r = buildRecord(CronStatus.SKIPPED, "0 9 * * *", "UTC", "some goal");
        String display = r.toDisplayString();
        assertTrue(display.contains("N/A"), "SKIPPED next fire should be N/A");
    }

    @Test
    void toDisplayString_nullNextFire_showsNA() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "goal");
        r.setNextFireTime(null);
        String display = r.toDisplayString();
        assertTrue(display.contains("N/A"), "null next fire should be N/A");
    }

    @Test
    void toDisplayString_nullGoal_showsNA() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", null);
        String display = r.toDisplayString();
        assertTrue(display.contains("N/A"), "null goal should show N/A");
    }

    @Test
    void toDisplayString_stripsCronPrefix() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "CRON send text: Hello world");
        String display = r.toDisplayString();
        assertTrue(display.contains("Hello world"), "Should strip 'CRON ' prefix");
        assertFalse(display.contains("CRON send text:"), "Should not contain the full prefix");
    }

    @Test
    void toDisplayString_stripsOutputFollowingMessage() {
        AgentCronRecord r =
                buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "output the following message: Hi there");
        String display = r.toDisplayString();
        assertTrue(display.contains("Hi there"), "Should strip 'output the following message:'");
    }

    @Test
    void toDisplayString_stripsOutputTheMessage() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "output the message: Hey!");
        String display = r.toDisplayString();
        assertTrue(display.contains("Hey!"), "Should strip 'output the message:'");
    }

    @Test
    void toDisplayString_stripsSayPrefix() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "say hello to everyone");
        String display = r.toDisplayString();
        assertTrue(display.contains("hello to everyone"), "Should strip 'say '");
    }

    @Test
    void toDisplayString_stripsTellPrefix() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "tell user about the meeting");
        String display = r.toDisplayString();
        assertTrue(display.contains("user about the meeting"), "Should strip 'tell '");
    }

    @Test
    void toDisplayString_stripsSendMessagePrefix() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "send message: Meeting at 3pm");
        String display = r.toDisplayString();
        assertTrue(display.contains("Meeting at 3pm"), "Should strip 'send message:'");
    }

    @Test
    void toDisplayString_stripsSendTextPrefix() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "send text: Don't forget");
        String display = r.toDisplayString();
        assertTrue(display.contains("Don't forget"), "Should strip 'send text:'");
    }

    @Test
    void toDisplayString_caseInsensitivePrefixMatch() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "SAY something important");
        String display = r.toDisplayString();
        assertTrue(display.contains("something important"), "Should strip 'SAY ' case-insensitively");
    }

    @Test
    void toDisplayString_noMatchingPrefix_showsGoal() {
        AgentCronRecord r = buildRecord(CronStatus.ACTIVE, "0 9 * * *", "UTC", "analyze the server logs");
        String display = r.toDisplayString();
        assertTrue(display.contains("analyze the server logs"), "Should show goal as-is");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AgentCronRecord buildRecord(CronStatus status, String cron, String tz, String goal) {
        AgentCronRecord r = new AgentCronRecord();
        r.setId("cron-1");
        r.setCronExpression(cron);
        r.setTimezone(tz);
        r.setStatus(status);
        r.setGoal(goal);
        r.setNextFireTime(Instant.now().plusSeconds(3600));
        return r;
    }
}
