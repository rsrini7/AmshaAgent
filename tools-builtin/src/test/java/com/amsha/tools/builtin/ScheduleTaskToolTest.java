package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.event.Event;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S1192")
class ScheduleTaskToolTest {

    /**
     * Raw user goals deliberately free of any temporal expression, so that
     * {@code CronTimeResolver.resolveTargetTimeFromNow} returns null and the tool is forced
     * down its own next-fire-time resolution branches instead of the fresh-resolution shortcut.
     */
    private static final String NO_TEMPORAL_GOAL = "send the deployment status report";

    private static final String STATIC_REPORT_GOAL = "Send the weekly report";

    private final CronStorePort cronStore = mock(CronStorePort.class);
    private final AuditService auditService = mock(AuditService.class);

    @SuppressWarnings("unchecked")
    private final Event<CronTaskScheduled> cronScheduledEvent = mock(Event.class);

    private ScheduleTaskTool createTool() {
        ScheduleTaskTool tool = new ScheduleTaskTool();
        try {
            var repoField = ScheduleTaskTool.class.getDeclaredField("cronStore");
            repoField.setAccessible(true);
            repoField.set(tool, cronStore);

            var auditField = ScheduleTaskTool.class.getDeclaredField("auditService");
            auditField.setAccessible(true);
            auditField.set(tool, auditService);

            var eventField = ScheduleTaskTool.class.getDeclaredField("cronScheduledEvent");
            eventField.setAccessible(true);
            eventField.set(tool, cronScheduledEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
        return tool;
    }

    private static ToolExecutionContext context(
            String rawUserGoal, String suggestedCron, String suggestedNextFireTime, String suggestedCronSource) {
        return new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource);
    }

    @Test
    void testValidCronSchedulesSuccessfully() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send weekly status report to the team via Telegram"));

        assertTrue(result.success(), "Valid cron should schedule successfully: " + result.output());
        assertTrue(result.output().contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE));
        assertTrue(result.output().contains("ID:"));
        assertTrue(result.output().contains("Next fire:"));

        verify(cronStore).save(any(AgentCronDto.class));
        verify(auditService)
                .record(
                        eq("test-session-id"),
                        eq("test-user"),
                        eq("coordinator"),
                        eq(SecurityEventType.CRONCREATED),
                        anyString());
    }

    @Test
    void testInvalidCronExpressionReturnsFailure() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                null,
                null,
                null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send status report"));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Missing required parameter: cronExpression"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testNakedPronounGoalRejected() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Deploy this to production"));

        assertFalse(result.success(), "Naked pronoun 'this' should be rejected");
        assertEquals(422, result.exitCode());
        assertTrue(result.output().contains("unresolved references"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testMissingUserIdReturnsFailure403() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send report"));

        assertFalse(result.success());
        assertEquals(403, result.exitCode());
        assertTrue(result.output().contains("tenant context not bound"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testScheduleAutonomousOperationDeclared() {
        ScheduleTaskTool tool = createTool();
        assertEquals(ToolOperation.SCHEDULE_AUTONOMOUS, tool.requiredOperation());
    }

    @Test
    void testRunOnceTruePassedThrough() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "30 14 15 3 *",
                null,
                "UTC");

        tool.execute(ctx, Map.of("goal", "Send one-time reminder to check deployment status", "runOnce", true));

        verify(cronStore).save(argThat(dto -> {
            AgentCronDto e = (AgentCronDto) dto;
            return e.runOnce();
        }));
    }

    /**
     * Regression test for the 2026-09-02 incident: "share the result after 1 min" was persisted
     * with a fire time ~24h later (cron 39 12 * * *, created 12:41:26Z, next fire 12:39:00Z the
     * next day). The LLM resolved the relative intent into an absolute cron using a time
     * snapshot captured before a ~150s inference, so the target minute had already elapsed by
     * the time this tool ran and the run-once cron silently rolled over to its next occurrence.
     */
    @Test
    void testStaleRunOnceCronIsRebasedToCurrentTime() throws Exception {
        ScheduleTaskTool tool = createTool();

        // A cron pointing at a minute that has already elapsed from the tool's point of view,
        // exactly as a slow inference hands it over.
        ZonedDateTime elapsed = ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(3);
        String staleCron = elapsed.getMinute() + " " + elapsed.getHour() + " * * *";

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "schedule to compute factorial of 10 and share the result after 1 min",
                staleCron,
                null,
                "UTC");

        Instant before = Instant.now();
        ToolResponse result = tool.execute(
                ctx, Map.of("goal", "Compute the factorial of 10 with Python and share the result", "runOnce", true));

        assertTrue(result.success(), "Stale run-once target should be rebased, not rejected: " + result.output());

        Instant[] captured = new Instant[1];
        verify(cronStore).save(argThat(dto -> {
            captured[0] = ((AgentCronDto) dto).nextFireTime();
            return true;
        }));

        // Intent was "after 1 min": the fire time must be soon, never a day out.
        long delaySeconds = Duration.between(before, captured[0]).toSeconds();
        assertTrue(
                delaySeconds >= 55 && delaySeconds <= 5 * 60,
                "Expected a near-term delay but scheduled " + delaySeconds + "s out (" + captured[0] + ")");
    }

    /**
     * A resolved fire time in the past must fail loudly instead of being persisted, where the
     * scheduler would either fire it immediately or let a run-once cron roll over to a
     * much later occurrence.
     */
    @Test
    void testFireTimeInThePastIsRejected() throws Exception {
        ScheduleTaskTool tool = createTool();

        // No temporal expression in the raw goal, so only the stale suggested instant remains.
        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "send the deployment status report",
                null,
                Instant.now().minusSeconds(120).toString(),
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send the deployment status report", "runOnce", true));

        assertFalse(result.success(), "An already-elapsed fire time must not be persisted: " + result.output());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("already elapsed"), "Unexpected message: " + result.output());

        verify(cronStore, never()).save(any());
    }

    @Test
    void omittedExecutionRoleDefaultsToResearcher() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "remind me in 1 minute to list gists",
                null,
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "List gists", "runOnce", true));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "researcher".equals(((AgentCronDto) dto).executionRole())));
    }

    @Test
    void testRunOnceWithoutCronSynthesizesCronExpression() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "remind me in 5 minutes to check server logs",
                null,
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Check server logs for error spikes", "runOnce", true));

        assertTrue(result.success(), "Schedule should succeed: " + result.output());
        verify(cronStore).save(argThat(dto -> {
            AgentCronDto cronDto = (AgentCronDto) dto;
            return cronDto.runOnce()
                    && cronDto.cronExpression() != null
                    && !cronDto.cronExpression().isBlank()
                    && cronDto.cronExpression().split("\\s+").length == 5;
        }));
    }

    // ---------------------------------------------------------------------------------------
    // Tool-surface metadata (name/description/sandbox/schema)
    // ---------------------------------------------------------------------------------------

    @Test
    void toolSurfaceExposesStableNameDescriptionAndSandboxFlag() {
        ScheduleTaskTool tool = createTool();

        assertEquals(ScheduleTaskTool.TOOL_NAME, tool.name());
        assertTrue(
                tool.description().contains("Schedule a future autonomous task"),
                "Unexpected description: " + tool.description());
        assertTrue(tool.description().contains("runOnce"), "Description must document runOnce");
        assertFalse(tool.requiresSandbox(), "Scheduling only persists a row; it never needs a sandbox");
    }

    @Test
    void parametersSchemaDeclaresEveryAcceptedParameter() {
        ScheduleTaskTool tool = createTool();

        JsonObjectSchema schema = tool.parametersSchema();

        assertEquals(List.of("goal"), schema.required(), "Only 'goal' is mandatory; the rest are inferred");
        assertTrue(
                schema.properties()
                        .keySet()
                        .containsAll(List.of("goal", "cronExpression", "timezone", "runOnce", "executionRole")),
                "Schema is missing parameters the tool reads: "
                        + schema.properties().keySet());
    }

    /**
     * A cron alone is not enough - the goal is the one genuinely required field. Note this is
     * checked *after* the cron/schedule preconditions, so a cron must be supplied for the
     * request to reach the goal guard.
     */
    @Test
    void missingGoalWithValidCronIsRejected() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", null, null, null);

        ToolResponse result = tool.execute(ctx, Map.of("cronExpression", "0 9 * * 1"));

        assertFalse(result.success(), result.output());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("goal is required"), result.output());
        verify(cronStore, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // Parameter parsing / normalization
    // ---------------------------------------------------------------------------------------

    /** 'runOnce' may arrive as a JSON boolean or as the string "true"/"false". */
    @Test
    void runOnceSuppliedAsStringIsParsed() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("remind me in 1 minute to list gists", null, null, null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", "List gists", "runOnce", "true"));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> ((AgentCronDto) dto).runOnce()));
    }

    @Test
    void blankExecutionRoleFallsBackToDefaultRole() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("remind me in 1 minute to list gists", null, null, null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", "List gists", "runOnce", true, "executionRole", " "));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "researcher".equals(((AgentCronDto) dto).executionRole())));
    }

    @Test
    void unknownExecutionRoleFallsBackToDefaultRole() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("remind me in 1 minute list gists", null, null, null);

        ToolResponse result =
                tool.execute(ctx, Map.of("goal", "List gists", "runOnce", true, "executionRole", "code-writer"));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "researcher".equals(((AgentCronDto) dto).executionRole())));
    }

    /**
     * A cron supplied directly in params (rather than via {@code ctx.suggestedCron()}) must be
     * honoured verbatim - this is the non-blank arm of {@code resolveCronOrDefault}.
     */
    @Test
    void cronSuppliedInParamsIsUsedVerbatim() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", "0 0 1 1 *", null, null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", STATIC_REPORT_GOAL, "cronExpression", "0 9 * * 1"));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "0 9 * * 1".equals(((AgentCronDto) dto).cronExpression())));
    }

    // ---------------------------------------------------------------------------------------
    // Timezone normalization (FIX 1: blank / "null" / invalid must never propagate)
    // ---------------------------------------------------------------------------------------

    @Test
    void blankTimezoneFallsBackToUtc() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", null, null, null);

        ToolResponse result =
                tool.execute(ctx, Map.of("goal", STATIC_REPORT_GOAL, "cronExpression", "0 9 * * 1", "timezone", ""));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "UTC".equals(((AgentCronDto) dto).timezone())));
    }

    /** Some models emit the literal four-character string "null" instead of omitting the field. */
    @Test
    void literalNullTimezoneFallsBackToUtc() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", null, null, null);

        ToolResponse result = tool.execute(
                ctx, Map.of("goal", STATIC_REPORT_GOAL, "cronExpression", "0 9 * * 1", "timezone", "null"));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "UTC".equals(((AgentCronDto) dto).timezone())));
    }

    @Test
    void validIanaTimezoneIsHonoured() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", null, null, null);

        ToolResponse result = tool.execute(
                ctx, Map.of("goal", STATIC_REPORT_GOAL, "cronExpression", "0 9 * * 1", "timezone", "Asia/Kolkata"));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "Asia/Kolkata".equals(((AgentCronDto) dto).timezone())));
    }

    /** An unrecognized ID must warn and fall back to UTC, never surface as a terminal 400. */
    @Test
    void unrecognizedTimezoneFallsBackToUtc() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context("raw goal", null, null, null);

        ToolResponse result = tool.execute(
                ctx, Map.of("goal", STATIC_REPORT_GOAL, "cronExpression", "0 9 * * 1", "timezone", "Not/AZone"));

        assertTrue(result.success(), "Invalid timezone must be normalized, not rejected: " + result.output());
        verify(cronStore).save(argThat(dto -> "UTC".equals(((AgentCronDto) dto).timezone())));
    }

    // ---------------------------------------------------------------------------------------
    // Next-fire-time resolution failure modes
    // ---------------------------------------------------------------------------------------

    /** Recurring task + unparseable cron -> computeNextFromCron returns null -> hard 400. */
    @Test
    void invalidCronWithNoSuggestedFireTimeIsRejected() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, null, null);

        ToolResponse result =
                tool.execute(ctx, Map.of("goal", "Send the deployment status report", "cronExpression", "not-a-cron"));

        assertFalse(result.success(), result.output());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Could not determine next fire time"), result.output());
        verify(cronStore, never()).save(any());
    }

    /**
     * runOnce + an unparseable suggested instant + a valid cron: the Instant.parse failure must
     * fall through to the cron computation rather than aborting.
     */
    @Test
    void unparseableSuggestedFireTimeFallsThroughToCron() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, "not-an-instant", null);

        Instant before = Instant.now();
        ToolResponse result = tool.execute(
                ctx,
                Map.of("goal", "Send the deployment status report", "cronExpression", "*/5 * * * *", "runOnce", true));

        assertTrue(result.success(), result.output());

        Instant[] captured = new Instant[1];
        verify(cronStore).save(argThat(dto -> {
            captured[0] = ((AgentCronDto) dto).nextFireTime();
            return true;
        }));
        assertTrue(captured[0].isAfter(before), "Fire time must be resolved from the cron, not the bad instant");
    }

    /** runOnce + unparseable suggested instant + invalid cron -> nothing resolvable -> 400. */
    @Test
    void unparseableSuggestedFireTimeWithInvalidCronIsRejected() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, "not-an-instant", null);

        ToolResponse result = tool.execute(
                ctx,
                Map.of("goal", "Send the deployment status report", "cronExpression", "not-a-cron", "runOnce", true));

        assertFalse(result.success(), result.output());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Could not determine next fire time"), result.output());
        verify(cronStore, never()).save(any());
    }

    /** runOnce + unparseable suggested instant + no cron at all -> 400 (no fallback possible). */
    @Test
    void unparseableSuggestedFireTimeWithoutCronIsRejected() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, "not-an-instant", null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send the deployment status report", "runOnce", true));

        assertFalse(result.success(), result.output());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Could not determine next fire time"), result.output());
        verify(cronStore, never()).save(any());
    }

    /**
     * runOnce with neither a cron nor a suggested instant: the tool falls back to a short
     * now+10s delay so the one-shot still fires instead of being rejected.
     */
    @Test
    void runOnceWithoutCronOrSuggestedFireTimeUsesFallbackDelay() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, null, null);

        Instant before = Instant.now();
        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send the deployment status report", "runOnce", true));

        assertTrue(result.success(), result.output());

        Instant[] captured = new Instant[1];
        verify(cronStore).save(argThat(dto -> {
            captured[0] = ((AgentCronDto) dto).nextFireTime();
            return true;
        }));

        long delaySeconds = Duration.between(before, captured[0]).toSeconds();
        assertTrue(
                delaySeconds >= 5 && delaySeconds <= 60,
                "Expected the ~10s fallback delay but got " + delaySeconds + "s (" + captured[0] + ")");
    }

    /** A supplied cron plus runOnce=true collapses the day/month fields to wildcards. */
    @Test
    void runOnceCollapsesCronToWildcardDayAndMonth() throws Exception {
        ScheduleTaskTool tool = createTool();
        ToolExecutionContext ctx = context(NO_TEMPORAL_GOAL, null, null, null);

        ToolResponse result = tool.execute(
                ctx,
                Map.of("goal", "Send the deployment status report", "cronExpression", "30 14 15 3 *", "runOnce", true));

        assertTrue(result.success(), result.output());
        verify(cronStore).save(argThat(dto -> "30 14 * * *".equals(((AgentCronDto) dto).cronExpression())));
    }
}
