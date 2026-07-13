package com.amsha.core.reflex;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Test;

class CronDecisionRouterRunStatusTest {

    @Test
    void directCronApprovalUpdatesAwaitingRunToCompleted() throws Exception {
        CronDecisionRouter router = new CronDecisionRouter();
        router.statefulAgentStore = mock(StatefulAgentStore.class);
        router.cronStore = mock(CronStorePort.class);
        router.cronRunStore = mock(CronRunStorePort.class);
        router.agentLoop = mock(AgentLoop.class);
        router.reasoningEngine = mock(ReasoningEngine.class);
        router.workspaceManager = mock(WorkspaceManager.class);

        String cronId = "e6d3f895-05a3-43b5-b070-eed56a93389b";
        String runId = "0982b5cc-9d81-4d41-a7b5-34296bbb5796";
        String cronSessionId = "cron-" + cronId + "-" + runId;
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId(cronSessionId);
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        cp.setMaxIterations(3);
        AgentCronDto cron = new AgentCronDto(
                cronId,
                null,
                "user-1",
                "chat-1",
                null,
                "cli",
                "chat-1",
                null,
                true,
                "List gists",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                "dlv-1",
                null,
                null);
        CronRunDto run = new CronRunDto(
                runId,
                cronId,
                cronSessionId,
                "AWAITING_APPROVAL",
                Instant.parse("2026-07-10T03:42:01Z"),
                null,
                null,
                0,
                0,
                0,
                BigDecimal.ZERO,
                Instant.parse("2026-07-10T03:42:01Z"));

        when(router.statefulAgentStore.isSessionActive(anyString())).thenReturn(false);
        when(router.statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(cronId))
                .thenReturn(Optional.of(cp));
        when(router.statefulAgentStore.claimForResume(cronSessionId, AgentRoleEnum.COORDINATOR.toScopedName()))
                .thenReturn(true);
        when(router.cronStore.findById(cronId)).thenReturn(Optional.of(cron));
        when(router.cronRunStore.findAll("AWAITING_APPROVAL", 0, 100)).thenReturn(List.of(run));
        when(router.agentLoop.run(anyString(), anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Cron Success", List.of()));

        ChannelUser user = new ChannelUser("user-1", "chat-1", "Srini", ChannelType.CLI);
        MessageContext messageContext = new MessageContext(
                "chat-1", user, new ChannelMessage.CliMessage(user, "/approve " + cronId, "chat-1", Instant.now()));
        CronDecisionRouter.CronContext ctx = new CronDecisionRouter.CronContext(
                cronId,
                "user-1",
                "chat-1",
                "ok",
                messageContext,
                (sid, reason) -> cp,
                () -> "already",
                (sid, role) -> "resume goal",
                (job, reason, result) -> {},
                result -> "",
                () -> "error",
                (job, result) -> {});

        router.execute(ctx);

        verify(router.cronRunStore).save(any(CronRunDto.class));
    }
}
