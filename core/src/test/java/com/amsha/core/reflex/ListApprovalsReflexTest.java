package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListApprovalsReflexTest {

    private ListApprovalsReflex reflex;
    private SessionHeartbeatTracker heartbeatTracker;
    private StatefulAgentStore statefulAgentStore;
    private CronStorePort cronStore;

    @BeforeEach
    void setUp() {
        reflex = new ListApprovalsReflex();
        heartbeatTracker = mock(SessionHeartbeatTracker.class);
        statefulAgentStore = mock(StatefulAgentStore.class);
        cronStore = mock(CronStorePort.class);
        reflex.heartbeatTracker = heartbeatTracker;
        reflex.statefulAgentStore = statefulAgentStore;
        reflex.cronStore = cronStore;
    }

    @Test
    void matchesRecognizesApprovalCommands() {
        assertTrue(reflex.matches("/approvals"));
        assertTrue(reflex.matches("/approval"));
        assertTrue(reflex.matches("/pending approvals"));
        assertTrue(reflex.matches("list approvals"));
        assertTrue(reflex.matches("show approvals"));
        assertTrue(reflex.matches("my approvals"));
        assertFalse(reflex.matches("/crons"));
    }

    @Test
    void executeListsSessionAndCronApprovalsWithCommands() {
        ChannelUser user = new ChannelUser("user-1", "chat-1", "User 1", ChannelType.CLI);
        ChannelMessage msg =
                new ChannelMessage.CliMessage(user, "/approvals", "sess-1", Instant.parse("2026-07-10T03:42:01Z"));
        MessageContext context = new MessageContext("chat-1", user, msg);

        AgentCheckpoint cp = mock(AgentCheckpointRecord.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(cp.getSessionId()).thenReturn("sess-1");
        when(cp.getAgentRole()).thenReturn("coordinator");
        when(cp.getPendingToolName()).thenReturn("github");
        when(cp.getOriginalGoal()).thenReturn("Search GitHub");
        when(cp.getUpdatedAt()).thenReturn(Instant.parse("2026-07-10T03:40:00Z"));
        when(cp.hasBatchToolDecisions()).thenReturn(false);

        AgentCronDto cron = new AgentCronDto(
                "cron-1",
                "cron-session-1",
                "user-1",
                "chat-1",
                "CLI",
                "platform-1",
                "0 9 * * *",
                "Asia/Kolkata",
                true,
                "Daily health check",
                Instant.parse("2026-07-10T03:30:00Z"),
                null,
                "AWAITING_APPROVAL",
                "coordinator",
                null,
                null,
                Instant.parse("2026-07-10T03:29:00Z"),
                Instant.parse("2026-07-10T03:29:00Z"));

        when(heartbeatTracker.activeSessionsByOwner("user-1")).thenReturn(Map.of("sess-1", Instant.now()));
        when(statefulAgentStore.findLatestAwaitingApproval("sess-1")).thenReturn(Optional.of(cp));
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of(cron));

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/approvals", context);

        String output = result.commandResponse().orElseThrow();
        assertTrue(output.contains("Session approvals"));
        assertTrue(output.contains("sess-1"));
        assertTrue(output.contains("/approve <sessionId>"));
        assertTrue(output.contains("Cron approvals"));
        assertTrue(output.contains("cron-1"));
        assertTrue(output.contains("/approve cron-1"));
    }
}
