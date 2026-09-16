package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.ComplianceViolationException;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoopResultHandlerTest {

    private StatefulAgentStore agentStore;
    private AgentSseService sseService;
    private ChannelPluginRegistry channelRegistry;
    private IntentClassificationService intentClassifier;
    private ChatMemoryWriter writer;

    @BeforeEach
    void setUp() {
        agentStore = mock(StatefulAgentStore.class);
        sseService = mock(AgentSseService.class);
        channelRegistry = mock(ChannelPluginRegistry.class);
        intentClassifier = mock(IntentClassificationService.class);
        writer = mock(ChatMemoryWriter.class);
    }

    private LoopResultHandler handler() {
        return new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);
    }

    private static AgentResult.AwaitingRetry retry(int retryAfterMs) {
        return new AgentResult.AwaitingRetry("s1", retryAfterMs, List.of(), 0, 0);
    }

    /** A tool step as it appears in resumed history. */
    private static ReasoningStep step(String action, String observation, Boolean success) {
        return new ReasoningStep.ToolCallStep(
                "thought", action, Map.of(), observation, 0, 0, success, null, null, null, false, false);
    }

    private static final String DELEGATE = ToolCapability.DELEGATE_TO_AGENT.toolName();

    private static MessageContext contextWith(ChannelUser user) {
        MessageContext context = mock(MessageContext.class);
        when(context.getUser()).thenReturn(user);
        return context;
    }

    private static ChannelUser telegramUser() {
        return new ChannelUser("platform-1", "chan-1", "Alice", ChannelType.TELEGRAM);
    }

    @Test
    void updateStoreForTerminalPersistsFinishedAnswerToChatMemory() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        handler.updateStoreForTerminal(
                "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.finished("answer", List.of()), "goal");

        verify(writer).saveAnswer("s1", "answer");
    }

    @Test
    void updateStoreForTerminalPreservesFailedStatusWhenCheckpointWasFailed() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.FAILED);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler.updateStoreForTerminal(
                "s1",
                "coordinator",
                "u1",
                AutonomyLevel.FULL,
                AgentResult.finished("❌ Tool execution was denied and the task has been cancelled.", List.of()),
                "goal");

        verify(writer).saveAnswer("s1", "❌ Tool execution was denied and the task has been cancelled.");
        verify(agentStore)
                .saveAndFinalize(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), CheckpointStatus.FAILED, "goal");
    }

    @Test
    void updateStoreForTerminalPersistsFailedReasonToChatMemory() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(123L);
        when(agentStore.save("s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), "goal"))
                .thenReturn(cp);

        handler.updateStoreForTerminal(
                "s1",
                "coordinator",
                "u1",
                AutonomyLevel.FULL,
                AgentResult.failed("Something failed", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()),
                "goal");

        verify(writer).saveAnswer("s1", "Something failed");
        verify(agentStore).markFailed(123L, "Something failed");
    }

    // ================= updateStoreForRetry =================

    @Test
    void retryPausesTheCheckpointAndAlwaysPublishesStatus() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler().updateStoreForRetry("s1", "coordinator", retry(5000), null);

        verify(cp).setStatus(CheckpointStatus.AWAITING_RETRY);
        verify(cp).setRetryAfterMs(5000);
        verify(agentStore).save(cp);
        verify(sseService).publish(eq("s1"), any(AgentSseService.AgentStatusEvent.class));
    }

    @Test
    void retryNotifiesTelegramWithTheRetryDelay() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getChannelType()).thenReturn("TELEGRAM");
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser user = telegramUser();
        handler().updateStoreForRetry("s1", "coordinator", retry(5000), contextWith(user));

        ArgumentCaptor<String> notice = ArgumentCaptor.forClass(String.class);
        verify(sender).sendRaw(eq(user), notice.capture());
        assertTrue(
                notice.getValue().contains("5 seconds"),
                "notice should report the delay in whole seconds, was: " + notice.getValue());
    }

    @Test
    void retryDoesNotNotifyNonTelegramChannels() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getChannelType()).thenReturn("SLACK");
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler().updateStoreForRetry("s1", "coordinator", retry(5000), contextWith(telegramUser()));

        verifyNoInteractions(channelRegistry);
        verify(sseService).publish(eq("s1"), any(AgentSseService.AgentStatusEvent.class));
    }

    @Test
    void retryStillPublishesStatusWhenNoCheckpointExists() {
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.empty());

        handler().updateStoreForRetry("s1", "coordinator", retry(1000), null);

        verify(agentStore, never()).save(any());
        verify(sseService).publish(eq("s1"), any(AgentSseService.AgentStatusEvent.class));
    }

    // ================= updateStoreForTerminal: remaining variants =================

    @Test
    void structuredFinishedFinalizesAsCompleted() {
        handler()
                .updateStoreForTerminal(
                        "s1",
                        "coordinator",
                        "u1",
                        AutonomyLevel.FULL,
                        AgentResult.structuredFinished("answer", Map.of("k", "v"), List.of()),
                        "goal");

        verify(writer).saveAnswer("s1", "answer");
        verify(agentStore)
                .saveAndFinalize(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), CheckpointStatus.COMPLETED, "goal");
        verify(sseService).publish(eq("s1"), any(AgentSseService.AgentStatusEvent.class));
    }

    @Test
    void loopExceededScrubsDanglingToolCallsAndMarksFailed() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(9L);
        when(agentStore.save("s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), "goal"))
                .thenReturn(cp);

        handler()
                .updateStoreForTerminal(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.loopExceeded(5, List.of()), "goal");

        // The dangling tool requests from the failed turn must be quarantined.
        verify(writer).scrubUnfulfilledToolCalls("s1");
        verify(agentStore).markFailed(9L, "Max iterations (5) exceeded");
        verify(sseService).publish(eq("s1"), any(AgentSseService.AgentStatusEvent.class));
    }

    @Test
    void loopExceededWithNoSavedCheckpointDoesNotMarkFailed() {
        when(agentStore.save("s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), "goal"))
                .thenReturn(null);

        handler()
                .updateStoreForTerminal(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.loopExceeded(5, List.of()), "goal");

        verify(agentStore, never()).markFailed(anyLong(), anyString());
        verify(writer).scrubUnfulfilledToolCalls("s1");
    }

    @Test
    void failedResultWithNoSavedCheckpointDoesNotMarkFailed() {
        when(agentStore.save("s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), "goal"))
                .thenReturn(null);

        handler()
                .updateStoreForTerminal(
                        "s1",
                        "coordinator",
                        "u1",
                        AutonomyLevel.FULL,
                        AgentResult.failed("boom", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()),
                        "goal");

        verify(agentStore, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void terminalToleratesNullStoreAndSseService() {
        LoopResultHandler bare = new LoopResultHandler(null, null, channelRegistry, intentClassifier, writer);

        assertDoesNotThrow(() -> bare.updateStoreForTerminal(
                "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.finished("answer", List.of()), "goal"));

        verify(writer).saveAnswer("s1", "answer");
    }

    @Test
    void terminalSkipsAnswerPersistenceWhenChatMemoryWriterIsAbsent() {
        LoopResultHandler bare = new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, null);

        assertDoesNotThrow(() -> bare.updateStoreForTerminal(
                "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.finished("answer", List.of()), "goal"));

        verify(agentStore)
                .saveAndFinalize(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), CheckpointStatus.COMPLETED, "goal");
    }

    @Test
    void finishedTreatsANullCheckpointLookupAsCompleted() {
        // load() returning null (not empty) must not NPE and must still finalise as COMPLETED.
        when(agentStore.load("s1", "coordinator")).thenReturn(null);

        handler()
                .updateStoreForTerminal(
                        "s1",
                        "coordinator",
                        "u1",
                        AutonomyLevel.FULL,
                        AgentResult.finished("answer", List.of()),
                        "goal");

        verify(agentStore)
                .saveAndFinalize(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), CheckpointStatus.COMPLETED, "goal");
    }

    // ================= compliance violation / system error =================

    private static ComplianceViolationException complianceViolation() {
        return new ComplianceViolationException(
                "s1", SecurityEventType.TOOL_INVOKED, new IllegalStateException("blocked"));
    }

    @Test
    void complianceViolationMarksTheCheckpointFailedWithDiagnostics() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(7L);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        AgentResult result = handler().handleComplianceViolation("s1", "coordinator", complianceViolation());

        assertInstanceOf(AgentResult.Failed.class, result);
        verify(agentStore)
                .markFailed(
                        eq(7L),
                        startsWith("COMPLIANCE_VIOLATION: "),
                        eq("COMPLIANCE_VIOLATION"),
                        anyString(),
                        anyString());
    }

    @Test
    void complianceViolationIsStillReturnedWhenTheStoreFails() {
        // The loop must halt on a compliance breach even if persistence is unavailable.
        when(agentStore.load("s1", "coordinator")).thenThrow(new IllegalStateException("db down"));

        AgentResult result = assertDoesNotThrow(
                () -> handler().handleComplianceViolation("s1", "coordinator", complianceViolation()));

        assertInstanceOf(AgentResult.Failed.class, result);
    }

    @Test
    void systemErrorMarksTheCheckpointFailedWithDiagnostics() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(8L);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        AgentResult result = handler().handleSystemError("s1", "coordinator", new IllegalStateException("boom"));

        assertInstanceOf(AgentResult.Failed.class, result);
        verify(agentStore)
                .markFailed(eq(8L), startsWith("SYSTEM_ERROR: "), eq("SYSTEM_ERROR"), anyString(), anyString());
    }

    @Test
    void systemErrorIsStillReturnedWhenTheStoreFails() {
        when(agentStore.load("s1", "coordinator")).thenThrow(new IllegalStateException("db down"));

        AgentResult result = assertDoesNotThrow(
                () -> handler().handleSystemError("s1", "coordinator", new IllegalStateException("boom")));

        assertInstanceOf(AgentResult.Failed.class, result);
    }

    // ================= tryStampChannelInfo =================

    @Test
    void stampChannelInfoSkipsWhenContextIsNull() {
        handler().tryStampChannelInfo("s1", "coordinator", null);

        verifyNoInteractions(agentStore);
    }

    @Test
    void stampChannelInfoSkipsWhenContextHasNoUser() {
        MessageContext context = mock(MessageContext.class);

        handler().tryStampChannelInfo("s1", "coordinator", context);

        verifyNoInteractions(agentStore);
    }

    @Test
    void stampChannelInfoWritesChannelAndPlatformOnly() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(11L);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler().tryStampChannelInfo("s1", "coordinator", contextWith(telegramUser()));

        // Only the channel fields are stamped - re-saving the whole checkpoint would clobber
        // the AWAITING_APPROVAL status with a stale loaded one.
        verify(agentStore).stampChannelInfo(11L, "TELEGRAM", "platform-1");
        verify(agentStore, never()).save(any());
    }

    @Test
    void stampChannelInfoSkipsACheckpointWithoutAnId() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(null);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler().tryStampChannelInfo("s1", "coordinator", contextWith(telegramUser()));

        verify(agentStore, never()).stampChannelInfo(anyLong(), anyString(), anyString());
    }

    @Test
    void stampChannelInfoToleratesAMissingCheckpoint() {
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> handler().tryStampChannelInfo("s1", "coordinator", contextWith(telegramUser())));

        verify(agentStore, never()).stampChannelInfo(anyLong(), anyString(), anyString());
    }

    @Test
    void stampChannelInfoSwallowsStoreFailures() {
        when(agentStore.load("s1", "coordinator")).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> handler().tryStampChannelInfo("s1", "coordinator", contextWith(telegramUser())));
    }

    // ================= hasApprovedToolPending =================

    @Test
    void pendingApprovalIsDetected() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        assertTrue(handler().hasApprovedToolPending("s1", "coordinator"));
    }

    @Test
    void resumingStatusCountsAsPending() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.RESUMING);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        assertTrue(handler().hasApprovedToolPending("s1", "coordinator"));
    }

    @Test
    void completedStatusIsNotPending() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        assertFalse(handler().hasApprovedToolPending("s1", "coordinator"));
    }

    @Test
    void missingCheckpointIsNotPending() {
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.empty());

        assertFalse(handler().hasApprovedToolPending("s1", "coordinator"));
    }

    // ================= tryDelegateFinishFastPath =================

    @Test
    void fastPathIsSkippedForEmptyHistory() {
        assertTrue(handler().tryDelegateFinishFastPath("s1", List.of(), null).isEmpty());
    }

    @Test
    void fastPathIsSkippedForAResumeSignal() {
        MessageContext context = mock(MessageContext.class);
        when(context.isResumeSignal()).thenReturn(true);

        assertTrue(handler()
                .tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, "done", true)), context)
                .isEmpty());
    }

    @Test
    void fastPathIsSkippedWhenTheLastStepIsNotADelegateCall() {
        assertTrue(handler()
                .tryDelegateFinishFastPath("s1", List.of(step("some_other_tool", "done", true)), null)
                .isEmpty());
    }

    @Test
    void fastPathIsSkippedForABlankObservation() {
        assertTrue(handler()
                .tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, "   ", true)), null)
                .isEmpty());
    }

    @Test
    void fastPathIsSkippedWhenTheGoalIsNotAResumeCommand() {
        MessageContext context = mock(MessageContext.class);
        when(context.getGoal()).thenReturn("build me a website");
        when(intentClassifier.isResumeCommand("build me a website")).thenReturn(false);

        assertTrue(handler()
                .tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, "done", true)), context)
                .isEmpty());
    }

    @Test
    void fastPathIsSkippedWhenTheDelegateStepIsMarkedFailed() {
        assertTrue(handler()
                .tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, "done", false)), null)
                .isEmpty());
    }

    @Test
    void fastPathIsSkippedForKnownFailurePrefixesWhenSuccessIsUnknown() {
        for (String prefix : List.of("⏳", "⚠️", "Tool execution failed", "Parameter ", "❌")) {
            assertTrue(
                    handler()
                            .tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, prefix + " details", null)), null)
                            .isEmpty(),
                    "prefix should be treated as a failure: " + prefix);
        }
    }

    @Test
    void fastPathFinishesDirectlyWithTheDelegateObservation() {
        List<ReasoningStep> history = List.of(step(DELEGATE, "Here is your result", true));

        Optional<AgentResult> result = handler().tryDelegateFinishFastPath("s1", history, null);

        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, result.orElseThrow());
        assertEquals("Here is your result", finished.answer());
        // Straight through: the coordinator wrap-up call is skipped entirely.
        assertEquals(1, finished.steps().size());
    }

    @Test
    void fastPathInjectsAnObservationWhenTheDelegateReportedFailurePhrases() {
        List<ReasoningStep> history = List.of(step(DELEGATE, "I was unable to complete the task", true));

        Optional<AgentResult> result = handler().tryDelegateFinishFastPath("s1", history, null);

        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, result.orElseThrow());
        assertEquals("I was unable to complete the task", finished.answer());
        // A synthetic observation is appended so the coordinator can wrap up without restarting.
        assertEquals(2, finished.steps().size());
        assertEquals(
                "Delegate failed - synthesize and finish",
                finished.steps().getLast().thought());
    }

    @Test
    void fastPathOnlyScansTheOpeningWindowForFailurePhrases() {
        // Phrase sits beyond the 150-character window, so this is treated as a normal result.
        String observation = "x".repeat(200) + " i was unable";

        Optional<AgentResult> result =
                handler().tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, observation, true)), null);

        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, result.orElseThrow());
        assertEquals(1, finished.steps().size());
    }

    @Test
    void fastPathDetectsFailurePhrasesInsideTheOpeningWindow() {
        String observation = "I was unable to do it " + "x".repeat(200);

        Optional<AgentResult> result =
                handler().tryDelegateFinishFastPath("s1", List.of(step(DELEGATE, observation, true)), null);

        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, result.orElseThrow());
        assertEquals(2, finished.steps().size());
    }
}
