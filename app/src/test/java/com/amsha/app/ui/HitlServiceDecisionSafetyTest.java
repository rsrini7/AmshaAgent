package com.amsha.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.CheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Plain-JUnit tests for {@link HitlService}'s approval-decision safety logic: the idempotent-replay
 * classification, the contradictory-decision and unsafe-state guards, requester authorization, checkpoint
 * lookup, and tenant scoping.
 *
 * <p>This is the part of the service that decides whether a second approval of the same checkpoint is a benign
 * replay or a conflict, so the assertions are deliberately about <em>which</em> exception or delegation comes
 * out, not merely that a call happened. A real {@link ObjectMapper} is used rather than a mock, because the
 * batch-decision JSON is real input and a mocked mapper would only prove that the test stubbed itself.
 *
 * <p>Sibling classes cover other concerns: {@code HitlServiceAbstractionTest} and
 * {@code HitlServicePerToolApprovalTest} cover delegation from {@code resolve}, and {@code HitlServiceTest} is
 * a {@code @QuarkusTest} covering the persistence-backed tenant scoping. The explicit-tool and bulk <em>deny</em>
 * arms of {@code executeResolveDecision} are deliberately left to those two delegation classes rather than
 * duplicated here — this class adds the approve-side single-pending arm and the safety guards around them.
 */
class HitlServiceDecisionSafetyTest {

    private static final String SESSION = "session-1";
    private static final String USER = "user-1";
    private static final String REASON = "because";

    @Mock
    CheckpointStorePort checkpointStorePort;

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    private HitlService hitlService;

    /** Real mapper: the batch-decision paths parse genuine JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        hitlService = new HitlService();
        hitlService.checkpointStorePort = checkpointStorePort;
        hitlService.objectMapper = objectMapper;
        hitlService.checkpointApprovalService = checkpointApprovalService;
        hitlService.otelContext = otelContext;
        hitlService.heartbeatTracker = heartbeatTracker;
        lenient().when(heartbeatTracker.getOwner(anyString())).thenReturn(Optional.of(USER));
    }

    // ---------- fixtures ----------

    private static CheckpointDto dto(Long id, String sessionId, CheckpointStatus status) {
        return dto(id, sessionId, status, null, null);
    }

    private static CheckpointDto dto(
            Long id, String sessionId, CheckpointStatus status, String pendingToolName, String failReason) {
        Instant now = Instant.now();
        return new CheckpointDto(
                id,
                sessionId,
                "default",
                status == null ? null : status.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                failReason,
                pendingToolName,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now);
    }

    private static CheckpointDto withBatch(CheckpointDto base, String batchJson) {
        return base.withBatchToolDecisions(batchJson);
    }

    private void foundById(CheckpointDto cp) {
        when(checkpointStorePort.findById(cp.id())).thenReturn(Optional.of(cp));
    }

    /** Resolves {@code taskId} as {@code USER}, with no OpenTelemetry span in play. */
    private void resolve(String taskId, String toolName, boolean approved) {
        hitlService.resolve(taskId, toolName, approved, REASON, USER);
    }

    private static String batch(String tool, String decision) {
        return "{\"" + tool + "\":\"" + decision + "\"}";
    }

    // ---------- checkpoint lookup ----------

    @Nested
    @DisplayName("checkpoint lookup")
    class Lookup {

        @Test
        @DisplayName("a blank approval id is rejected before any store access")
        void blankTaskIdIsRejected() {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> resolve("   ", null, true));

            assertTrue(error.getMessage().contains("must not be blank"), error.getMessage());
            verifyNoInteractions(checkpointStorePort);
        }

        @Test
        @DisplayName("an unknown numeric id falls through to the awaiting-approval index")
        void unknownNumericIdFallsThroughToTheAwaitingIndex() {
            CheckpointDto cp = dto(7L, SESSION, CheckpointStatus.AWAITING_APPROVAL);
            when(checkpointStorePort.findById(7L)).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApproval("7")).thenReturn(Optional.of(cp));

            resolve("7", null, true);

            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("a session id that is not numeric skips the primary-key lookup entirely")
        void nonNumericIdSkipsThePrimaryKeyLookup() {
            CheckpointDto cp = dto(7L, SESSION, CheckpointStatus.AWAITING_APPROVAL);
            when(checkpointStorePort.findLatestAwaitingApproval(SESSION)).thenReturn(Optional.of(cp));

            resolve(SESSION, null, true);

            verify(checkpointStorePort, never()).findById(any());
            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("the lookup chain ends at the containing-session and latest-by-session fallbacks")
        void lookupFallsThroughToTheContainingAndLatestFallbacks() {
            CheckpointDto containing = dto(9L, SESSION, CheckpointStatus.AWAITING_APPROVAL);
            when(checkpointStorePort.findById(9L)).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApproval("9")).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApprovalBySessionContaining("9"))
                    .thenReturn(Optional.of(containing));

            resolve("9", null, true);
            verify(checkpointApprovalService).approveAll(SESSION, REASON);

            // ... and when even that misses, the plain latest-by-session lookup is the last resort.
            CheckpointDto latest = dto(11L, SESSION, CheckpointStatus.AWAITING_APPROVAL);
            when(checkpointStorePort.findById(11L)).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApproval("11")).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApprovalBySessionContaining("11"))
                    .thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestBySession("11")).thenReturn(Optional.of(latest));

            resolve("11", null, true);
            verify(checkpointApprovalService, times(2)).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("an approval id that matches nothing anywhere is rejected")
        void missingApprovalIsRejected() {
            when(checkpointStorePort.findById(13L)).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApproval("13")).thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestAwaitingApprovalBySessionContaining("13"))
                    .thenReturn(Optional.empty());
            when(checkpointStorePort.findLatestBySession("13")).thenReturn(Optional.empty());

            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> resolve("13", null, true));

            assertTrue(error.getMessage().contains("No pending approval found"), error.getMessage());
        }
    }

    // ---------- requester authorization ----------

    @Nested
    @DisplayName("requester authorization")
    class Authorization {

        private CheckpointDto awaitingIn(String sessionId) {
            CheckpointDto cp = dto(1L, sessionId, CheckpointStatus.AWAITING_APPROVAL);
            foundById(cp);
            return cp;
        }

        @Test
        @DisplayName("cron approvals must go back through the originating channel")
        void cronSessionsCannotBeResolvedThroughTheUi() {
            awaitingIn("cron-42");

            SecurityException error = assertThrows(SecurityException.class, () -> resolve("1", null, true));

            assertTrue(error.getMessage().contains("originating channel"), error.getMessage());
        }

        @Test
        @DisplayName("a missing, blank or anonymous requester is rejected")
        void anonymousOrMissingRequesterIsRejected() {
            awaitingIn(SESSION);

            for (String requester : new String[] {null, "   ", "anonymous"}) {
                SecurityException error = assertThrows(
                        SecurityException.class, () -> hitlService.resolve("1", null, true, REASON, requester));
                assertTrue(error.getMessage().contains("identity is required"), error.getMessage());
            }
        }

        @Test
        @DisplayName("the internal requester bypasses the ownership check")
        void internalRequesterBypassesTheOwnershipCheck() {
            awaitingIn(SESSION);
            // The stored owner is somebody else entirely: only the internal bypass lets this through.
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.of("someone-else"));

            hitlService.resolve("1", null, true, REASON, "INTERNAL");

            verify(heartbeatTracker, never()).tryBindOwner(anyString(), anyString());
            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("a checkpoint owned by someone else is rejected")
        void aDifferentOwnerIsRejected() {
            awaitingIn(SESSION);
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.of("someone-else"));

            SecurityException error = assertThrows(SecurityException.class, () -> resolve("1", null, true));

            assertTrue(error.getMessage().contains("does not belong"), error.getMessage());
        }

        @Test
        @DisplayName("an unowned CLI session is claimed by the requester")
        void cliSessionWithoutAnOwnerIsClaimedByTheRequester() {
            awaitingIn("cli-abc");
            when(heartbeatTracker.getOwner("cli-abc")).thenReturn(Optional.empty());

            resolve("1", null, true);

            verify(heartbeatTracker).recordHeartbeat("cli-abc");
            verify(heartbeatTracker).tryBindOwner("cli-abc", USER);
            verify(checkpointApprovalService).approveAll("cli-abc", REASON);
        }

        @Test
        @DisplayName("an unowned session is bound to the requester when the tracker allows it")
        void anUnclaimedSessionIsBoundToTheRequester() {
            awaitingIn(SESSION);
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.empty());
            when(heartbeatTracker.tryBindOwner(SESSION, USER)).thenReturn(true);

            resolve("1", null, true);

            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("a session the tracker refuses to bind is re-claimed while an approval is still pending")
        void anUnclaimedSessionWithAPendingApprovalIsClaimed() {
            awaitingIn(SESSION);
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.empty());
            when(heartbeatTracker.tryBindOwner(SESSION, USER)).thenReturn(false);
            when(checkpointStorePort.findLatestAwaitingApproval(SESSION))
                    .thenReturn(Optional.of(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL)));

            resolve("1", null, true);

            verify(heartbeatTracker).recordHeartbeat(SESSION);
            verify(heartbeatTracker, times(2)).tryBindOwner(SESSION, USER);
            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("a session with nothing pending cannot be claimed and is rejected")
        void anInactiveSessionIsRejected() {
            awaitingIn(SESSION);
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.empty());
            when(heartbeatTracker.tryBindOwner(SESSION, USER)).thenReturn(false);
            when(checkpointStorePort.findLatestAwaitingApproval(SESSION)).thenReturn(Optional.empty());

            SecurityException error = assertThrows(SecurityException.class, () -> resolve("1", null, true));

            assertTrue(error.getMessage().contains("no longer active"), error.getMessage());
        }
    }

    // ---------- resolve: span, resume and the resolution race ----------

    @Nested
    @DisplayName("resolve wiring")
    class ResolveWiring {

        private Span span;

        @BeforeEach
        void withSpan() {
            span = mock(Span.class);
            when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        }

        @Test
        @DisplayName("a successful resolve annotates the span, resumes the checkpoint and ends the span")
        void aSuccessfulResolveMarksTheSpanAndTriggersResume() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            AgentCheckpoint resolved =
                    CheckpointRecord.fromDto(dto(1L, SESSION, CheckpointStatus.APPROVED, "read_file", null));
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenReturn(resolved);

            resolve("1", "read_file", true);

            verify(span).setAttribute(AgentOTelConstants.ATTR_HITL_TASK_ID, "1");
            verify(span).setAttribute(AgentOTelConstants.ATTR_HITL_TOOL_NAME, "read_file");
            verify(span).setAttribute(AgentOTelConstants.ATTR_HITL_APPROVED, true);
            verify(span).setStatus(StatusCode.OK);
            verify(checkpointApprovalService).triggerResume(resolved);
            verify(span).end();
        }

        @Test
        @DisplayName("a batch approval with no tool name annotates the span as applying to all tools")
        void aBulkResolveNamesAllToolsOnTheSpan() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null);
            foundById(cp);

            resolve("1", null, true);

            verify(span).setAttribute(AgentOTelConstants.ATTR_HITL_TOOL_NAME, "all");
            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("a resume is skipped when the decision produced no checkpoint")
        void noResumeWhenTheDecisionReturnsNothing() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenReturn(null);

            resolve("1", "read_file", true);

            verify(checkpointApprovalService, never()).triggerResume(any());
            verify(span).setStatus(StatusCode.OK);
        }

        @Test
        @DisplayName("a race that a later checkpoint proves was replayed is reported as success")
        void aReplayAfterARaceIsReportedAsSuccess() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenThrow(new IllegalStateException("checkpoint already resolved by another request"));
            when(checkpointStorePort.findLatestBySession(SESSION))
                    .thenReturn(Optional.of(dto(1L, SESSION, CheckpointStatus.APPROVED, "read_file", null)));

            assertDoesNotThrow(() -> resolve("1", "read_file", true));

            verify(span).setStatus(StatusCode.OK);
            verify(span, never()).recordException(any(Throwable.class));
            verify(span).end();
        }

        @Test
        @DisplayName("a race that the latest checkpoint does not corroborate is still an error")
        void anUncorroboratedRaceIsRecordedAsAnError() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenThrow(new IllegalStateException("No pending checkpoint for session"));
            when(checkpointStorePort.findLatestBySession(SESSION))
                    .thenReturn(Optional.of(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null)));

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolve("1", "read_file", true));

            assertTrue(error.getMessage().contains("No pending checkpoint"), error.getMessage());
            verify(span).setStatus(eq(StatusCode.ERROR), anyString());
            verify(span).recordException(error);
            verify(span).end();
        }

        @Test
        @DisplayName("an ordinary failure is recorded on the span and rethrown unchanged")
        void anOrdinaryFailureIsRecordedOnTheSpan() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            RuntimeException boom = new RuntimeException("downstream exploded");
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenThrow(boom);

            RuntimeException error = assertThrows(RuntimeException.class, () -> resolve("1", "read_file", true));

            assertSame(boom, error);
            verify(span).recordException(boom);
            verify(span).end();
        }

        @Test
        @DisplayName("an exception with no message is never treated as a resolution race")
        void anExceptionWithoutAMessageIsNotARace() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);
            when(checkpointApprovalService.approveTool(SESSION, "read_file", REASON))
                    .thenThrow(new IllegalStateException());

            assertThrows(IllegalStateException.class, () -> resolve("1", "read_file", true));

            verify(span).setStatus(eq(StatusCode.ERROR), isNull());
            verify(checkpointStorePort, never()).findLatestBySession(anyString());
        }
    }

    // ---------- idempotent-replay classification ----------

    @Nested
    @DisplayName("idempotent replay classification")
    class ReplayClassification {

        private void assertUnsafe(CheckpointDto cp, String toolName, boolean approved) {
            foundById(cp);
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolve("1", toolName, approved));
            assertTrue(error.getMessage().contains("cannot be determined safely"), error.getMessage());
            verifyNoInteractions(checkpointApprovalService);
        }

        private void assertContradictory(CheckpointDto cp, String toolName, boolean approved) {
            foundById(cp);
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolve("1", toolName, approved));
            assertTrue(error.getMessage().contains("already resolved"), error.getMessage());
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a checkpoint with no status is unsafe rather than replayable")
        void aCheckpointWithoutAStatusIsUnsafe() {
            assertUnsafe(dto(1L, SESSION, null, "read_file", null), "read_file", true);
        }

        @Test
        @DisplayName("a status outside the known decision set is unsafe")
        void anUnknownStatusIsUnsafe() {
            assertUnsafe(dto(1L, SESSION, CheckpointStatus.NOT_STARTED, "read_file", null), "read_file", true);
            assertUnsafe(dto(1L, SESSION, CheckpointStatus.ORPHANED, "read_file", null), "read_file", true);
        }

        @Test
        @DisplayName("a denied checkpoint replays a denial and contradicts an approval")
        void aDeniedCheckpointAcceptsOnlyAnotherDenial() {
            CheckpointDto denied = dto(1L, SESSION, CheckpointStatus.DENIED, "read_file", null);
            foundById(denied);

            assertDoesNotThrow(() -> resolve("1", "read_file", false));
            verifyNoInteractions(checkpointApprovalService);

            assertContradictory(denied, "read_file", true);
        }

        @Test
        @DisplayName("every accepted status replays an approval and contradicts a denial")
        void everyAcceptedStatusIsIdempotentForApprove() {
            CheckpointStatus[] accepted = {
                CheckpointStatus.APPROVED,
                CheckpointStatus.RESUMING,
                CheckpointStatus.IN_PROGRESS,
                CheckpointStatus.AWAITING_RETRY,
                CheckpointStatus.COMPLETED,
                CheckpointStatus.LOOP_EXCEEDED,
            };
            for (CheckpointStatus status : accepted) {
                CheckpointDto cp = dto(1L, SESSION, status, "read_file", null);
                foundById(cp);

                assertDoesNotThrow(() -> resolve("1", "read_file", true), "expected a replay for " + status);
                assertContradictory(cp, "read_file", false);
            }
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a fully decided batch replays an approval of all tools")
        void aFullyApprovedBatchIsAnIdempotentApprove() {
            CheckpointDto cp = withBatch(
                    dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null),
                    "{\"a\":\"APPROVED\",\"b\":\"APPROVED\"}");
            foundById(cp);

            assertDoesNotThrow(() -> resolve("1", null, true));
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a batch containing an opposite decision contradicts a bulk approval")
        void aBatchWithAnOppositeDecisionIsContradictory() {
            CheckpointDto cp = withBatch(
                    dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null),
                    "{\"a\":\"APPROVED\",\"b\":\"DENIED\"}");

            assertContradictory(cp, null, true);
        }

        @Test
        @DisplayName("a per-tool replay of the same decision is idempotent")
        void aPerToolReplayOfTheSameDecisionIsIdempotent() {
            CheckpointDto cp =
                    withBatch(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null), batch("a", "APPROVED"));
            foundById(cp);

            assertDoesNotThrow(() -> resolve("1", "a", true));
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a per-tool decision that contradicts the request is rejected")
        void aPerToolOppositeDecisionIsContradictory() {
            CheckpointDto cp =
                    withBatch(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null), batch("a", "APPROVED"));

            assertContradictory(cp, "a", false);
        }

        @Test
        @DisplayName("a tool that is not part of the checkpoint is unsafe")
        void aPerToolDecisionForAMissingToolIsUnsafe() {
            CheckpointDto cp =
                    withBatch(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null), batch("a", "APPROVED"));

            assertUnsafe(cp, "not-in-the-batch", true);
        }

        @Test
        @DisplayName("a still-pending per-tool decision is not a replay, so the decision proceeds")
        void aPendingPerToolDecisionIsNotAReplay() {
            CheckpointDto cp =
                    withBatch(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null), batch("a", "PENDING"));
            foundById(cp);

            resolve("1", "a", true);

            verify(checkpointApprovalService).approveTool(SESSION, "a", REASON);
        }
    }

    // ---------- failed-checkpoint replay ----------

    @Nested
    @DisplayName("failed checkpoint replay")
    class FailedReplay {

        private CheckpointDto failed(String failReason) {
            return dto(1L, SESSION, CheckpointStatus.FAILED, "read_file", failReason);
        }

        @Test
        @DisplayName("a denial-caused failure replays a denial and contradicts an approval")
        void aDenialFailureAcceptsOnlyADenial() {
            CheckpointDto cp = failed("user denied the request");
            foundById(cp);

            assertDoesNotThrow(() -> resolve("1", "read_file", false));
            verifyNoInteractions(checkpointApprovalService);

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolve("1", "read_file", true));
            assertTrue(error.getMessage().contains("already resolved"), error.getMessage());
        }

        @Test
        @DisplayName("a rejection-caused failure is recognised too")
        void aRejectionFailureIsRecognised() {
            CheckpointDto cp = failed("REJECTED by policy");
            foundById(cp);

            assertDoesNotThrow(() -> resolve("1", "read_file", false));
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a failure whose cause does not identify a decision is unsafe")
        void aFailureWithoutADenialCauseIsUnsafe() {
            CheckpointDto cp = failed("tool timed out");
            foundById(cp);

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolve("1", "read_file", true));
            assertTrue(error.getMessage().contains("cannot be determined safely"), error.getMessage());

            // a blank reason is treated the same way
            CheckpointDto blank = failed("   ");
            foundById(blank);
            assertThrows(IllegalStateException.class, () -> resolve("1", "read_file", true));
        }

        @Test
        @DisplayName("a failed batch with a still-pending decision is unsafe")
        void aFailedBatchWithPendingDecisionsIsUnsafe() {
            CheckpointDto cp = withBatch(failed(null), batch("a", "PENDING"));
            foundById(cp);

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> resolve("1", "a", true));
            assertTrue(error.getMessage().contains("cannot be determined safely"), error.getMessage());
        }

        @Test
        @DisplayName("a failed batch whose decisions disagree with each other is unsafe")
        void aContradictoryBatchIsUnsafe() {
            CheckpointDto cp = withBatch(failed(null), "{\"a\":\"APPROVED\",\"b\":\"DENIED\"}");
            foundById(cp);

            assertThrows(IllegalStateException.class, () -> resolve("1", "a", true));
        }

        @Test
        @DisplayName("a denial reason that conflicts with the batch decision is unsafe")
        void aDenialReasonConflictingWithTheBatchDecisionIsUnsafe() {
            CheckpointDto cp = withBatch(failed("denied by the operator"), batch("a", "APPROVED"));
            foundById(cp);

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> resolve("1", "a", false));
            assertTrue(error.getMessage().contains("conflicts with batch decision"), error.getMessage());
        }

        @Test
        @DisplayName("a failed batch whose decision matches the request is a replay")
        void aFailedBatchMatchingTheDecisionIsAReplay() {
            CheckpointDto cp = withBatch(failed("denied by the operator"), batch("a", "DENIED"));
            foundById(cp);

            assertDoesNotThrow(() -> resolve("1", "a", false));
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("a failed batch whose decision contradicts the request is rejected")
        void aFailedBatchContradictingTheDecisionIsContradictory() {
            CheckpointDto cp = withBatch(failed(null), batch("a", "APPROVED"));
            foundById(cp);

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> resolve("1", "a", false));
            assertTrue(error.getMessage().contains("already resolved"), error.getMessage());
        }
    }

    // ---------- malformed batch state ----------

    @Nested
    @DisplayName("malformed batch decision state")
    class MalformedBatch {

        private void assertInvalidBatch(String batchJson) {
            CheckpointDto cp = withBatch(dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null), batchJson);
            foundById(cp);

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> resolve("1", "a", true));
            assertTrue(error.getMessage().contains("cannot be determined safely"), error.getMessage());
            verifyNoInteractions(checkpointApprovalService);
        }

        @Test
        @DisplayName("unparseable batch JSON is unsafe rather than silently empty")
        void unparseableBatchJsonIsUnsafe() {
            assertInvalidBatch("{not json at all");
        }

        @Test
        @DisplayName("an empty batch decision map is unsafe")
        void anEmptyBatchMapIsUnsafe() {
            assertInvalidBatch("{}");
        }

        @Test
        @DisplayName("a batch with a blank key or a null decision is unsafe")
        void aBatchWithABlankKeyOrNullValueIsUnsafe() {
            assertInvalidBatch("{\"\":\"APPROVED\"}");
            assertInvalidBatch("{\"a\":null}");
        }

        @Test
        @DisplayName("a batch decision that is not a known enum value is unsafe")
        void anUnknownBatchDecisionIsUnsafe() {
            assertInvalidBatch("{\"a\":\"MAYBE\"}");
        }
    }

    // ---------- tenant scoping and counting ----------

    @Nested
    @DisplayName("tenant scoping and counting")
    class Scoping {

        @Test
        @DisplayName("a blank user id yields no pending approvals and a zero count")
        void blankUsersSeeNothing() {
            assertTrue(hitlService.getPendingApprovals(null, 10, 0).isEmpty());
            assertTrue(hitlService.getPendingApprovals("  ", 10, 0).isEmpty());
            assertEquals(0L, hitlService.countPendingApprovals(null));
            assertEquals(0L, hitlService.countPendingApprovals("  "));
            verifyNoInteractions(checkpointStorePort);
        }

        @Test
        @DisplayName("a user with no visible sessions gets an empty page and a zero count")
        void noVisibleSessionsMeansNothing() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of());
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            when(checkpointStorePort.findByStatus(anyString())).thenReturn(List.of());

            assertTrue(hitlService.getPendingApprovals(USER, 10, 0).isEmpty());
            assertEquals(0L, hitlService.countPendingApprovals(USER));
            verify(checkpointStorePort, never()).findByStatusAndSessionIds(anyString(), anyList(), anyInt(), anyInt());
            verify(checkpointStorePort, never()).countByStatusAndSessionIds(anyString(), anyList());
        }

        @Test
        @DisplayName("the count is scoped to the sessions the user can see")
        void theCountIsScopedToVisibleSessions() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of(SESSION));
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            when(checkpointStorePort.findByStatus(anyString())).thenReturn(List.of());
            when(checkpointStorePort.countByStatusAndSessionIds(
                            CheckpointStatus.AWAITING_APPROVAL.name(), List.of(SESSION)))
                    .thenReturn(3L);

            assertEquals(3L, hitlService.countPendingApprovals(USER));
        }

        @Test
        @DisplayName("cron and session-less checkpoints never become visible")
        void cronAndSessionlessCheckpointsAreSkipped() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of());
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            when(checkpointStorePort.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                    .thenReturn(List.of(
                            dto(1L, "cron-7", CheckpointStatus.AWAITING_APPROVAL, "t", null),
                            dto(2L, null, CheckpointStatus.AWAITING_APPROVAL, "t", null)));

            assertTrue(hitlService.getPendingApprovals(USER, 10, 0).isEmpty());
            verify(checkpointStorePort, never()).findByStatusAndSessionIds(anyString(), anyList(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("a checkpoint owned by the requester is visible through its root session")
        void aCheckpointOwnedByTheRequesterIsVisible() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of());
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            CheckpointDto cp = dto(5L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            when(checkpointStorePort.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                    .thenReturn(List.of(cp));
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.of(USER));
            when(checkpointStorePort.findByStatusAndSessionIds(
                            eq(CheckpointStatus.AWAITING_APPROVAL.name()), anyList(), anyInt(), anyInt()))
                    .thenReturn(List.of(cp));

            assertEquals(1, hitlService.getPendingApprovals(USER, 10, 0).size());
        }

        @Test
        @DisplayName("a session with no owner record is visible to any requester")
        void anUnownedCheckpointIsVisible() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of());
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            CheckpointDto cp = dto(5L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            when(checkpointStorePort.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                    .thenReturn(List.of(cp));
            when(heartbeatTracker.getOwner(SESSION)).thenReturn(Optional.empty());
            when(checkpointStorePort.findByStatusAndSessionIds(
                            eq(CheckpointStatus.AWAITING_APPROVAL.name()), anyList(), anyInt(), anyInt()))
                    .thenReturn(List.of(cp));

            assertEquals(1, hitlService.getPendingApprovals(USER, 10, 0).size());
        }

        @Test
        @DisplayName("a pending checkpoint is reachable through the session tree of a visible session")
        void pendingApprovalsAreFoundThroughTheSessionTree() {
            when(heartbeatTracker.getSessionIdsByOwner(USER)).thenReturn(List.of(SESSION));
            when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
            when(checkpointStorePort.findByStatus(anyString())).thenReturn(List.of());
            CheckpointDto child = dto(6L, "session-1::child", CheckpointStatus.AWAITING_APPROVAL, "t", null);
            when(checkpointStorePort.findActiveBySessionTree(SESSION)).thenReturn(List.of(child));
            when(checkpointStorePort.findByStatusAndSessionIds(
                            eq(CheckpointStatus.AWAITING_APPROVAL.name()), anyList(), anyInt(), anyInt()))
                    .thenReturn(List.of(child));

            assertEquals(1, hitlService.getPendingApprovals(USER, 10, 0).size());
        }
    }

    // ---------- tool targeting ----------

    @Nested
    @DisplayName("tool targeting")
    class ToolTargeting {

        @Test
        @DisplayName("approving the only pending tool targets that tool rather than the whole batch")
        void approvingTheOnlyPendingToolTargetsThatTool() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);

            resolve("1", null, true);

            verify(checkpointApprovalService).approveTool(SESSION, "read_file", REASON);
            verify(checkpointApprovalService, never()).approveAll(anyString(), anyString());
        }

        @Test
        @DisplayName("denying the only pending tool targets that tool rather than the whole batch")
        void denyingTheOnlyPendingToolTargetsThatTool() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);

            resolve("1", null, false);

            verify(checkpointApprovalService).denyTool(SESSION, "read_file", REASON);
            verify(checkpointApprovalService, never()).denyAll(anyString(), anyString());
        }

        @Test
        @DisplayName("with nothing identifiable as pending the decision falls back to the whole checkpoint")
        void withNothingPendingTheDecisionAppliesToEverything() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, null, null);
            foundById(cp);

            resolve("1", null, true);

            verify(checkpointApprovalService).approveAll(SESSION, REASON);
        }

        @Test
        @DisplayName("an explicit 'all' tool name is never treated as a tool called 'all'")
        void anExplicitAllToolNameIsBulk() {
            CheckpointDto cp = dto(1L, SESSION, CheckpointStatus.AWAITING_APPROVAL, "read_file", null);
            foundById(cp);

            resolve("1", "all", true);

            // "all" resolves to the pending set, not to a tool literally named "all".
            verify(checkpointApprovalService).approveTool(SESSION, "read_file", REASON);
            verify(checkpointApprovalService, never()).approveTool(SESSION, "all", REASON);
        }
    }
}
