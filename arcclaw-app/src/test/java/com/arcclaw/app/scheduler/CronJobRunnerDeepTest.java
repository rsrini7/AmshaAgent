package com.arcclaw.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link CronJobRunner}.
 * Tests real internal methods (isPureAnnouncement, isTimeCheck,
 * stripAnnouncementPrefix, unwrapToolCallGoal, resolveRole) via reflection.
 */
class CronJobRunnerDeepTest {

    private CronJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new CronJobRunner();
        runner.cronMaxIterations = 5;
    }

    // ── isPureAnnouncement ───────────────────────────────────────────────

    @Test
    void testIsPureAnnouncement_printHello() throws Exception {
        assertTrue(invokeIsPureAnnouncement("print hello world"));
    }

    @Test
    void testIsPureAnnouncement_sayMessage() throws Exception {
        assertTrue(invokeIsPureAnnouncement("say good morning"));
    }

    @Test
    void testIsPureAnnouncement_tellUser() throws Exception {
        assertTrue(invokeIsPureAnnouncement("tell the user it is done"));
    }

    @Test
    void testIsPureAnnouncement_displayMessage() throws Exception {
        assertTrue(invokeIsPureAnnouncement("display the status"));
    }

    @Test
    void testIsPureAnnouncement_sendMessage() throws Exception {
        assertTrue(invokeIsPureAnnouncement("send message: hello"));
    }

    @Test
    void testIsPureAnnouncement_sendText() throws Exception {
        assertTrue(invokeIsPureAnnouncement("send text: alert"));
    }

    @Test
    void testIsPureAnnouncement_outputMessage() throws Exception {
        assertTrue(invokeIsPureAnnouncement("output message: test"));
    }

    @Test
    void testIsPureAnnouncement_null_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement(null));
    }

    @Test
    void testIsPureAnnouncement_blank_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("  "));
    }

    @Test
    void testIsPureAnnouncement_withActionVerb_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("fetch the latest data from github"));
    }

    @Test
    void testIsPureAnnouncement_withSearchVerb_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("search for Python tutorials"));
    }

    @Test
    void testIsPureAnnouncement_withRunVerb_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("run the test suite"));
    }

    @Test
    void testIsPureAnnouncement_withExecuteVerb_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("execute the migration script"));
    }

    @Test
    void testIsPureAnnouncement_timeCheck_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("what time is it"));
    }

    @Test
    void testIsPureAnnouncement_fetchVerb_returnsFalse() throws Exception {
        assertFalse(invokeIsPureAnnouncement("fetch data from github"));
    }

    @Test
    void testIsPureAnnouncement_longMessage_returnsFalse() throws Exception {
        String longMsg = "a".repeat(200);
        assertFalse(invokeIsPureAnnouncement(longMsg));
    }

    @Test
    void testIsPureAnnouncement_shortNoAction_returnsTrue() throws Exception {
        assertTrue(invokeIsPureAnnouncement("hello there"));
    }

    // ── isTimeCheck ─────────────────────────────────────────────────────

    @Test
    void testIsTimeCheck_currentTime() throws Exception {
        assertTrue(invokeIsTimeCheck("what is the current time"));
    }

    @Test
    void testIsTimeCheck_currentUtcTime() throws Exception {
        assertTrue(invokeIsTimeCheck("tell me the current utc time"));
    }

    @Test
    void testIsTimeCheck_currentSystemTime() throws Exception {
        assertTrue(invokeIsTimeCheck("check the current system time"));
    }

    @Test
    void testIsTimeCheck_whatTimeIsIt() throws Exception {
        assertTrue(invokeIsTimeCheck("what time is it"));
    }

    @Test
    void testIsTimeCheck_tellMeTheTime() throws Exception {
        assertTrue(invokeIsTimeCheck("tell me the time"));
    }

    @Test
    void testIsTimeCheck_specificCheck() throws Exception {
        assertTrue(invokeIsTimeCheck("check: what is the current time?"));
    }

    @Test
    void testIsTimeCheck_null_returnsFalse() throws Exception {
        assertFalse(invokeIsTimeCheck(null));
    }

    @Test
    void testIsTimeCheck_blank_returnsFalse() throws Exception {
        assertFalse(invokeIsTimeCheck("  "));
    }

    @Test
    void testIsTimeCheck_unrelated_returnsFalse() throws Exception {
        assertFalse(invokeIsTimeCheck("fetch data from github"));
    }

    // ── stripAnnouncementPrefix ──────────────────────────────────────────

    @Test
    void testStripAnnouncementPrefix_print() throws Exception {
        String result = invokeStripAnnouncementPrefix("print hello world");
        assertEquals("hello world", result);
    }

    @Test
    void testStripAnnouncementPrefix_say() throws Exception {
        String result = invokeStripAnnouncementPrefix("say the task is complete");
        assertEquals("the task is complete", result);
    }

    @Test
    void testStripAnnouncementPrefix_tellToConsole() throws Exception {
        String result = invokeStripAnnouncementPrefix("tell to the console: all done");
        assertEquals("all done", result);
    }

    @Test
    void testStripAnnouncementPrefix_outputToUser() throws Exception {
        String result = invokeStripAnnouncementPrefix("output to user: status report");
        assertEquals("status report", result);
    }

    @Test
    void testStripAnnouncementPrefix_sendMessageToChat() throws Exception {
        String result = invokeStripAnnouncementPrefix("send message to chat: hello");
        assertEquals("hello", result);
    }

    @Test
    void testStripAnnouncementPrefix_singleQuotes() throws Exception {
        String result = invokeStripAnnouncementPrefix("print 'hello world'");
        assertEquals("hello world", result);
    }

    @Test
    void testStripAnnouncementPrefix_doubleQuotes() throws Exception {
        String result = invokeStripAnnouncementPrefix("say \"good morning everyone\"");
        assertEquals("good morning everyone", result);
    }

    @Test
    void testStripAnnouncementPrefix_null() throws Exception {
        String result = invokeStripAnnouncementPrefix(null);
        assertEquals(null, result);
    }

    @Test
    void testStripAnnouncementPrefix_suffixToConsole() throws Exception {
        String result = invokeStripAnnouncementPrefix("hello to the console");
        assertEquals("hello", result);
    }

    @Test
    void testStripAnnouncementPrefix_suffixToChat() throws Exception {
        String result = invokeStripAnnouncementPrefix("status update in the chat");
        assertEquals("status update", result);
    }

    // ── unwrapToolCallGoal ──────────────────────────────────────────────

    @Test
    void testUnwrapToolCallGoal_garudaWrapper() throws Exception {
        String goal = "garuda(agent=\"saraswati\", goal=\"github repo view arcclaw\")";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals("github repo view arcclaw", result);
    }

    @Test
    void testUnwrapToolCallGoal_garudaWithEscapedQuotes() throws Exception {
        String goal = "garuda(agent=\"coder\", goal=\"write a \\\"hello world\\\" program\")";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals("write a \"hello world\" program", result);
    }

    @Test
    void testUnwrapToolCallGoal_garudaNoGoal() throws Exception {
        String goal = "garuda(agent=\"saraswati\")";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals(goal, result);
    }

    @Test
    void testUnwrapToolCallGoal_jsonGoal() throws Exception {
        String goal = "{\"goal\": \"search for Python docs\", \"agent\": \"saraswati\"}";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals("search for Python docs", result);
    }

    @Test
    void testUnwrapToolCallGoal_jsonNoGoal() throws Exception {
        String goal = "{\"agent\": \"saraswati\"}";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals(goal, result);
    }

    @Test
    void testUnwrapToolCallGoal_invalidJson() throws Exception {
        String goal = "{invalid json}";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals(goal, result);
    }

    @Test
    void testUnwrapToolCallGoal_null() throws Exception {
        String result = invokeUnwrapToolCallGoal(null);
        assertEquals(null, result);
    }

    @Test
    void testUnwrapToolCallGoal_blank() throws Exception {
        String result = invokeUnwrapToolCallGoal("  ");
        assertEquals("  ", result);
    }

    @Test
    void testUnwrapToolCallGoal_plainGoal() throws Exception {
        String goal = "just a plain goal";
        String result = invokeUnwrapToolCallGoal(goal);
        assertEquals(goal, result);
    }

    @Test
    void testUnwrapToolCallGoal_garudaNoClosingQuote() throws Exception {
        String goal = "garuda(agent=\"saraswati\", goal=\"unclosed quote";
        String result = invokeUnwrapToolCallGoal(goal);
        // Should return original since no closing quote found
        assertEquals(goal, result);
    }

    // ── resolveRole ─────────────────────────────────────────────────────

    @Test
    void testResolveRole_explicitRoleWins() throws Exception {
        String result = invokeResolveRole("fetch data from github", "coder");
        assertEquals("coder", result);
    }

    @Test
    void testResolveRole_explicitRoleBlank_fallsThrough() throws Exception {
        String result = invokeResolveRole("fetch data from github", "  ");
        // Should fall through to keyword matching
        assertNotNull(result);
    }

    @Test
    void testResolveRole_outputPrefix_coordinator() throws Exception {
        String result = invokeResolveRole("output the message hello", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_sayPrefix_coordinator() throws Exception {
        String result = invokeResolveRole("say hello", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_sendPrefix_coordinator() throws Exception {
        String result = invokeResolveRole("send a notification", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_tellPrefix_coordinator() throws Exception {
        String result = invokeResolveRole("tell the user something", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_garudaPrefix_coordinator() throws Exception {
        String result = invokeResolveRole("garuda(agent=\"saraswati\")", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_githubPrefix_researcher() throws Exception {
        String result = invokeResolveRole("github repo view arcclaw", null);
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), result);
    }

    @Test
    void testResolveRole_browserPrefix_researcher() throws Exception {
        String result = invokeResolveRole("browse https://example.com", null);
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), result);
    }

    @Test
    void testResolveRole_searchPrefix_researcher() throws Exception {
        String result = invokeResolveRole("search for Python docs", null);
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), result);
    }

    @Test
    void testResolveRole_googlePrefix_researcher() throws Exception {
        String result = invokeResolveRole("google something", null);
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), result);
    }

    @Test
    void testResolveRole_coderKeywords() throws Exception {
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("write code for a calculator", null));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("write python script", null));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("fix bug in module", null));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("refactor the service", null));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("run the test", null));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), invokeResolveRole("execute the script", null));
    }

    @Test
    void testResolveRole_researcherKeywords() throws Exception {
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), invokeResolveRole("researcher task: find info", null));
        assertEquals(AgentRoleEnum.RESEARCHER.toScopedName(), invokeResolveRole("fetch the documentation", null));
    }

    @Test
    void testResolveRole_coordinatorHints() throws Exception {
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), invokeResolveRole("coordinator task: plan", null));
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), invokeResolveRole("meta-schedule the tasks", null));
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), invokeResolveRole("schedule next steps", null));
    }

    @Test
    void testResolveRole_default_coordinator() throws Exception {
        String result = invokeResolveRole("do something unspecified", null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    @Test
    void testResolveRole_nullGoal() throws Exception {
        String result = invokeResolveRole(null, null);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), result);
    }

    // ── Helper invoke methods ────────────────────────────────────────────

    private boolean invokeIsPureAnnouncement(String goal) throws Exception {
        Method m = CronJobRunner.class.getDeclaredMethod("isPureAnnouncement", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(runner, goal);
    }

    private boolean invokeIsTimeCheck(String normalized) throws Exception {
        Method m = CronJobRunner.class.getDeclaredMethod("isTimeCheck", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(runner, normalized);
    }

    private String invokeStripAnnouncementPrefix(String goal) throws Exception {
        Method m = CronJobRunner.class.getDeclaredMethod("stripAnnouncementPrefix", String.class);
        m.setAccessible(true);
        return (String) m.invoke(runner, goal);
    }

    private String invokeUnwrapToolCallGoal(String goal) throws Exception {
        Method m = CronJobRunner.class.getDeclaredMethod("unwrapToolCallGoal", String.class);
        m.setAccessible(true);
        return (String) m.invoke(runner, goal);
    }

    private String invokeResolveRole(String goal, String explicitRole) throws Exception {
        Method m = CronJobRunner.class.getDeclaredMethod("resolveRole", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(runner, goal, explicitRole);
    }
}
